package com.iimoxi.odi_messanger

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Service.START_STICKY
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class MessageService : Service() {
    private lateinit var audioStreamManager: AudioStreamManager
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var messageServer: MessageServer

    companion object {
        var isAppInForeground: Boolean = false // This is updated by appLifecycleObserver
            private set // Keep private set

        const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val SERVICE_CHANNEL_ID = "MessageServiceChannel"
        const val EXTRA_SENDER_IP_UI = "extra_sender_ip_ui"

        // For broadcasting to UI (MainActivity/MainScreen) - TEXT/IMAGE MESSAGES
        const val ACTION_MESSAGE_RECEIVED_FOR_UI = "com.iimoxi.odi_messanger.MESSAGE_RECEIVED_FOR_UI"
        const val EXTRA_SENDER_UI = "extra_sender_ui"
        const val EXTRA_CONTENT_UI = "extra_content_ui"
        const val EXTRA_IS_IMAGE_UI = "extra_is_image_ui"
        const val EXTRA_BITMAP_URI_UI = "extra_bitmap_uri_ui"

        // --- COMMON CALL RELATED EXTRAS AND ACTIONS ---
        const val EXTRA_CALL_ID_SERVICE = "extra_call_id_service"
        const val EXTRA_CALLER_USERNAME_SERVICE = "extra_caller_username_service" // Typically the peer initiating
        const val EXTRA_RESPONDER_USERNAME_SERVICE = "extra_responder_username_service" // Typically this user when responding
        const val EXTRA_CALLER_IP_SERVICE = "extra_caller_ip_service" // Peer's IP
        const val EXTRA_PEER_UDP_PORT_SERVICE = "extra_peer_udp_port_service"
        const val EXTRA_LOCAL_UDP_PORT_SERVICE = "extra_local_udp_port_service"
        const val EXTRA_CALL_STATE = "extra_call_state" // For broadcasting current call state to UI

        // --- ACTIONS FOR MAINACTIVITY (IN-APP UI) INTERACTION WITH SERVICE ---
        const val ACTION_SHOW_CALL_UI = "com.iimoxi.odi_messanger.SHOW_CALL_UI" // To MainScreen for banner/call UI
        const val ACTION_INITIATE_OUTGOING_CALL = "com.iimoxi.odi_messanger.INITIATE_OUTGOING_CALL" // From MainScreen to Service
        const val ACTION_CALL_ACCEPTED_BY_USER = "com.iimoxi.odi_messanger.CALL_ACCEPTED_BY_USER" // From MainScreen banner to Service
        const val ACTION_CALL_REJECTED_BY_USER = "com.iimoxi.odi_messanger.CALL_REJECTED_BY_USER" // From MainScreen banner to Service
        const val ACTION_CALL_ENDED_BY_USER = "com.iimoxi.odi_messanger.CALL_ENDED_BY_USER" // From MainScreen active call UI to Service

        // --- ACTIONS FOR INCOMINGCALLACTIVITY INTERACTION WITH SERVICE ---
        // These are new/modified to clearly distinguish source of action
        const val ACTION_ACCEPT_CALL_FROM_ACTIVITY = "com.iimoxi.odi_messanger.ACCEPT_CALL_FROM_ACTIVITY"
        const val ACTION_REJECT_CALL_FROM_ACTIVITY = "com.iimoxi.odi_messanger.REJECT_CALL_FROM_ACTIVITY"

        // Old constants, check if still used or can be mapped to above
        const val EXTRA_IS_INCOMING_CALL_ACCEPTED = "extra_is_incoming_call_accepted" // Can be part of call state or specific UI update
        const val EXTRA_TARGET_IP_SERVICE = "extra_target_ip_service"
        const val EXTRA_TARGET_USERNAME_SERVICE = "extra_target_username_service"
        const val EXTRA_MY_USERNAME_SERVICE = "extra_my_username_service"
        const val ACTION_END_CALL = "com.iimoxi.odi_messanger.END_CALL" // This is generic, map to ACTION_CALL_ENDED_BY_USER or specific internal end

        // Notification specific
        const val CALL_NOTIFICATION_ID = 2 // You might want dynamic IDs based on callId.hashCode()
        const val CALL_CHANNEL_ID = "call_channel"

        private const val TAG = "MessageService" // Renamed from MessageService.TAG for clarity
    }

    private var activeCallSession: ActiveCallSession? = null
    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            super.onStart(owner)
            isAppInForeground = true
            Log.i(TAG, "App entered FOREGROUND (ProcessLifecycleOwner - onStart)")
        }

        override fun onStop(owner: LifecycleOwner) {
            super.onStop(owner)
            isAppInForeground = false
            Log.i(TAG, "App entered BACKGROUND (ProcessLifecycleOwner - onStop)")
        }
    }

    // ActivityLifecycleCallbacks (as implemented before)
    override fun onCreate() {
        super.onCreate()
        audioStreamManager = AudioStreamManager(applicationContext) // INITIALIZE HERE
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        isAppInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        Log.i(TAG, "Service onCreate. Initial foreground state: $isAppInForeground")

        createServiceNotificationChannel()
        createCallNotificationChannel()

        // This is the original initialization in onCreate
        messageServer = MessageServer(
            context = applicationContext,
            port = Port, // Using your global Port
            onMessageReceived = { messageData -> // <<<< THIS IS THE FIRST LAMBDA
                Log.d(TAG, "Message received in service dispatcher (via onCreate init): $messageData")

                if (handleCallMessage(messageData)) {
                    return@MessageServer // Call message handled
                }

                // Handle non-call messages (Text, Image)
                Log.i(TAG, "Non-call message. App in foreground: $isAppInForeground")
                broadcastMessageToUI(messageData)

                if (!isAppInForeground) {
                    Log.i(TAG, "App is in BACKGROUND. Showing content notification for non-call message.")
                    when (messageData) {
                        is MessageData.Text -> showContentNotification(messageData.senderUsername, messageData.content)
                        is MessageData.Image -> showContentNotification(messageData.senderUsername, "Received an image.")
                        else -> { /* Other non-call message types not handled for notifications here */ }
                    }
                }
            },
            scope = scope,
            onBackgroundNotificationRequired = { sender, contentPreview -> // <<<< THIS IS THE SECOND LAMBDA
                Log.d(TAG, "Background notification hint from MessageServer: $sender - $contentPreview. Current Foreground: $isAppInForeground")
                if (!isAppInForeground && contentPreview != null) {
                    showContentNotification(sender, contentPreview)
                }
            }
        )
        Log.i(TAG, "MessageServer initialized in onCreate.")
    }

    private fun loadUsername(context: Context): String {
        // Replace with your actual SharedPreferences or other storage mechanism
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return prefs.getString("username", "DefaultUser") ?: "DefaultUser"
    }

    private fun sendCallProtocolMessageInternal(
        ipAddress: String,
        port: Int,
        username: String,
        key: String,
        messageType: String,
        callId: String,
        audioUdpPort: Int? = null,
        reason: String? = null // Optional reason for reject/busy
    ) {
        // Use the global sendCallProtocolMessage function for actual sending
        sendCallProtocolMessage(
            ipAddress = ipAddress,
            port = port,
            username = username,
            key = key,
            messageType = messageType,
            callId = callId,
            audioUdpPort = audioUdpPort
            // 'reason' is not part of your global sendCallProtocolMessage's signature yet.
            // You might need to add it or handle it differently if it's purely for logging.
        )
        if (reason != null) {
            Log.i(TAG, "Call protocol message '$messageType' for call '$callId' sent with reason: $reason")
        }
    }

    private fun showIncomingCallNotification(callId: String, callerUsername: String, callerIp: String) {
        val notificationManager = NotificationManagerCompat.from(this)

        // Intent for when the user taps the notification body (opens IncomingCallActivity)
        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId)
            putExtra(IncomingCallActivity.EXTRA_CALLER_USERNAME, callerUsername)
            putExtra(IncomingCallActivity.EXTRA_CALLER_IP, callerIp)
        }
        val fullScreenPendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, callId.hashCode(), fullScreenIntent, fullScreenPendingIntentFlags
        )

        // Intent for "Accept" action
        val acceptIntent = Intent(this, IncomingCallActivity::class.java).apply { // Route through activity to send network response
            action = IncomingCallActivity.ACTION_ACCEPT_CALL
            putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId)
            putExtra(IncomingCallActivity.EXTRA_CALLER_USERNAME, callerUsername)
            putExtra(IncomingCallActivity.EXTRA_CALLER_IP, callerIp)
            // Ensure unique request code for PendingIntent
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            this, (callId + "accept").hashCode(), acceptIntent, fullScreenPendingIntentFlags
        )


        // Intent for "Reject" action
        val rejectIntent = Intent(this, IncomingCallActivity::class.java).apply { // Route through activity
            action = IncomingCallActivity.ACTION_REJECT_CALL
            putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId)
            putExtra(IncomingCallActivity.EXTRA_CALLER_USERNAME, callerUsername)
            putExtra(IncomingCallActivity.EXTRA_CALLER_IP, callerIp)
        }
        val rejectPendingIntent = PendingIntent.getActivity(
            this, (callId + "reject").hashCode(), rejectIntent, fullScreenPendingIntentFlags
        )

        val callerPerson = Person.Builder()
            .setName(callerUsername)
            // .setIcon(IconCompat.createWithResource(this, R.drawable.ic_caller_avatar)) // Optional avatar
            .build()

        val notificationBuilder = androidx . core . app . NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.phone_icon) // You need a call status bar icon (e.g., ic_phone_in_talk or your app icon)
            .setContentTitle("Incoming Call")
            .setContentText("$callerUsername is calling")
            .setPriority(NotificationCompat.PRIORITY_MAX) // For heads-up display
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true) // VERY IMPORTANT for lock screen
            .addAction(NotificationCompat.Action.Builder(
                // Use placeholder icons if you don't have them yet
                // IconCompat.createWithResource(this, R.drawable.ic_call_reject_action), // Need reject icon
                null, // Placeholder for icon
                "Reject",
                rejectPendingIntent).build())
            .addAction(NotificationCompat.Action.Builder(
                // IconCompat.createWithResource(this, R.drawable.ic_call_accept_action), // Need accept icon
                null, // Placeholder for icon
                "Accept",
                acceptPendingIntent).build())
            .setOngoing(true) // Call notifications should be ongoing until dismissed or call ends
            .setAutoCancel(false) // Should be explicitly handled by user action or call end
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true);

        // For Android 12 (API 31) and above, use CallStyle for richer call notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val style = NotificationCompat.CallStyle.forIncomingCall(callerPerson, rejectPendingIntent, acceptPendingIntent)
            notificationBuilder.setStyle(style)
        } else {
            // For older versions, the actions are added above.
            // You might want a different layout or rely on the standard heads-up.
        }


        // Ensure you have POST_NOTIFICATIONS permission (especially for Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("MessageService", "Missing POST_NOTIFICATIONS permission. Cannot show incoming call notification.")
            // Ideally, you'd request this permission earlier.
            // If the app doesn't have it, the full-screen intent might not work as expected.
            return
        }

        try {
            // Use a unique ID for each call notification, but one that can be cleared.
            // Using callId.hashCode() allows you to clear it later if the call is missed or rejected by other means.
            notificationManager.notify(callId.hashCode(), notificationBuilder.build())
            Log.i("MessageService", "Incoming call notification shown for call ID: $callId")
        } catch (e: SecurityException) {
            Log.e("MessageService", "SecurityException showing incoming call notification: ${e.message}")
            // This might happen if USE_FULL_SCREEN_INTENT is revoked on Android 12+
        }
    }

    private fun createCallNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Voice Calls"
            val descriptionText = "Notifications for incoming and ongoing voice calls."
            // IMPORTANCE_HIGH or IMPORTANCE_MAX is crucial for full-screen intents and heads-up.
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CALL_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                // For call channels, you might want to enable vibration, sound, etc.
                // Ensure the sound you pick is appropriate for a ringtone.
                // Example: setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), null)
                enableLights(true)
                lightColor = android.graphics.Color.CYAN
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000) // Example pattern
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.i("MessageService", "Call Notification Channel $CALL_CHANNEL_ID created/updated.")
        }
    }

    private fun clearIncomingCallNotification(notificationId: Int) {
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.cancel(notificationId)
        Log.i("MessageService", "Cleared call notification with ID: $notificationId")
    }

    private fun handleCallMessage(messageData: MessageData): Boolean {
        val currentUsername = loadUsername(applicationContext)

        when (messageData) {
            is MessageData.CallInitiate -> {
                Log.i(TAG, "Incoming CALL_INITIATE from ${messageData.senderUsername} (ID: ${messageData.callId}) with their UDP port: ${messageData.audioUdpPort}")

                if (activeCallSession != null && activeCallSession!!.state != CallState.ENDED && activeCallSession!!.state != CallState.IDLE) {
                    Log.w(TAG, "Already in an active call (${activeCallSession?.callId}, state: ${activeCallSession?.state}). Sending REJECT for new call ${messageData.callId}.")
                    sendCallProtocolMessageInternal(
                        ipAddress = messageData.senderIpAddress,
                        port = Port,
                        username = currentUsername,
                        key = Key,
                        messageType = MessageServer.TYPE_CALL_REJECT,
                        callId = messageData.callId,
                        reason = "busy"
                    )
                    return true // Message handled
                }

                Log.i(TAG, "Setting up new incoming call session for ID: ${messageData.callId}")
                activeCallSession = ActiveCallSession(
                    callId = messageData.callId,
                    peerUsername = messageData.senderUsername,
                    peerIpAddress = messageData.senderIpAddress,
                    state = CallState.INCOMING,
                    peerAudioUdpPort = messageData.audioUdpPort,
                    localAudioUdpPort = null // We don't know our UDP port yet for an incoming call
                )
                // The decision to show UI (banner or activity) is now made based on app foreground state.

                // --- CORE DECISION LOGIC for Incoming Call Presentation ---
                if (isAppInForeground) {
                    Log.i(TAG, "App is IN FOREGROUND. Broadcasting ACTION_SHOW_CALL_UI for in-app banner.")
                    val uiIntent = Intent(ACTION_SHOW_CALL_UI).apply {
                        `package` = packageName // Important for explicit broadcast
                        putExtra(EXTRA_CALL_ID_SERVICE, messageData.callId)
                        putExtra(EXTRA_CALLER_USERNAME_SERVICE, messageData.senderUsername)
                        putExtra(EXTRA_CALLER_IP_SERVICE, messageData.senderIpAddress)
                        putExtra(EXTRA_PEER_UDP_PORT_SERVICE, messageData.audioUdpPort) // Peer's UDP port
                        putExtra(EXTRA_CALL_STATE, CallState.INCOMING.name)
                    }
                    sendBroadcast(uiIntent)
                } else {
                    Log.i(TAG, "App is IN BACKGROUND. Launching IncomingCallActivity directly.")
                    val intent = Intent(this, IncomingCallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        // Pass necessary data for IncomingCallActivity to display and handle the call
                        putExtra(IncomingCallActivity.EXTRA_CALL_ID, messageData.callId)
                        putExtra(IncomingCallActivity.EXTRA_CALLER_USERNAME, messageData.senderUsername)
                        putExtra(IncomingCallActivity.EXTRA_CALLER_IP, messageData.senderIpAddress)
                        // Peer's UDP port is also useful for IncomingCallActivity if it needs to display it or use it.
                        // However, the service will primarily use the activeCallSession.peerAudioUdpPort.
                        putExtra(EXTRA_PEER_UDP_PORT_SERVICE, messageData.audioUdpPort)
                    }
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting IncomingCallActivity: ${e.message}. Falling back to notification.", e)
                        // Fallback: show a notification that opens the activity
                        showIncomingCallNotification( // This notification will have actions to launch IncomingCallActivity
                            callId = messageData.callId,
                            callerUsername = messageData.senderUsername,
                            callerIp = messageData.senderIpAddress
                        )
                    }
                }
                return true // CallInitiate message handled
            }

            is MessageData.CallAccept -> {
                Log.i(TAG, "CALL_ACCEPT received by service for ID ${messageData.callId} from ${messageData.senderUsername} (IP: ${messageData.senderIpAddress}) with their UDP port ${messageData.audioUdpPort}")
                if (activeCallSession?.callId == messageData.callId && activeCallSession?.state == CallState.OUTGOING) {
                    activeCallSession?.state = CallState.ACTIVE
                    activeCallSession?.peerAudioUdpPort = messageData.audioUdpPort // Store the peer's UDP port
                    Log.i(TAG, "Call ${activeCallSession?.callId} is now ACTIVE. My UDP: ${activeCallSession?.localAudioUdpPort}, Peer UDP: ${activeCallSession?.peerAudioUdpPort}, Peer IP: ${activeCallSession?.peerIpAddress}")

                    broadcastCallStateToUI(activeCallSession) // Update UI to active state
                    clearIncomingCallNotification(messageData.callId.hashCode()) // Clear any "calling..." notification if one was shown

                    // Start audio streaming if all conditions met
                    if (activeCallSession?.localAudioUdpPort != null &&
                        activeCallSession?.peerAudioUdpPort != null &&
                        activeCallSession?.peerIpAddress != null) {
                        audioStreamManager.startStreaming(
                            localUdpPort = activeCallSession!!.localAudioUdpPort!!,
                            peerIpAddress = activeCallSession!!.peerIpAddress,
                            peerUdpPort = activeCallSession!!.peerAudioUdpPort!!,
                            scope = scope // Pass the service's scope
                        )
                    } else {
                        Log.e(TAG, "Cannot start audio stream after CALL_ACCEPT, missing information in active session: localUDP=${activeCallSession?.localAudioUdpPort}, peerUDP=${activeCallSession?.peerAudioUdpPort}, peerIP=${activeCallSession?.peerIpAddress}")
                    }
                } else {
                    Log.w(TAG, "Received CallAccept for an unexpected call session. Current: ${activeCallSession?.callId}, Received: ${messageData.callId}, Current State: ${activeCallSession?.state}")
                }
                return true // CallAccept message handled
            }

            is MessageData.CallReject -> {
                Log.i(TAG, "CALL_REJECT received for ID ${messageData.callId} from ${messageData.senderUsername}")
                if (activeCallSession?.callId == messageData.callId) {
                    Log.i(TAG, "Call ${messageData.callId} was REJECTED by ${messageData.senderUsername}")
                    activeCallSession?.state = CallState.REJECTED_BY_PEER
                    audioStreamManager.stopStreaming()
                    broadcastCallStateToUI(activeCallSession)
                    clearIncomingCallNotification(messageData.callId.hashCode()) // Clear any related notification
                    activeCallSession = null // Clear the session
                } else {
                    Log.w(TAG, "Received CallReject for an unknown or already ended call session. Current: ${activeCallSession?.callId}, Received: ${messageData.callId}")
                }
                return true // CallReject message handled
            }

            is MessageData.CallEnd -> {
                Log.i(TAG, "CALL_END received for ID ${messageData.callId} from ${messageData.senderUsername}")
                if (activeCallSession?.callId == messageData.callId) {
                    Log.i(TAG, "Call ${messageData.callId} was ENDED by ${messageData.senderUsername}")
                    activeCallSession?.state = CallState.ENDED
                    audioStreamManager.stopStreaming()
                    broadcastCallStateToUI(activeCallSession) // Notify UI call has ended
                    clearIncomingCallNotification(messageData.callId.hashCode()) // Clear notification
                    activeCallSession = null // Clear the session
                } else {
                    Log.w(TAG, "Received CallEnd for an unknown or already ended call session. Current: ${activeCallSession?.callId}, Received: ${messageData.callId}")
                }
                return true // CallEnd message handled
            }
            // Add cases for other call-related messages if you have them (e.g., CallBusy, CallError)
            else -> return false // Not a call message or not handled by this specific function
        }
    }

    private fun broadcastCallStateToUI(session: ActiveCallSession?) {
        val intent = Intent(ACTION_SHOW_CALL_UI) // Re-use the existing action or make a new one
        intent.setPackage(packageName) // Important for explicit broadcast
        if (session != null) {
            intent.putExtra(EXTRA_CALL_ID_SERVICE, session.callId)
            intent.putExtra(EXTRA_CALLER_USERNAME_SERVICE, session.peerUsername) // This is the peer
            intent.putExtra(EXTRA_CALLER_IP_SERVICE, session.peerIpAddress)
            intent.putExtra("extra_call_state", session.state.name) // Send state as String
        } else {
            // Indicate call has ended or no active call
            intent.putExtra("extra_call_state", CallState.ENDED.name)
        }
        sendBroadcast(intent)
        Log.d("MessageService", "Broadcasted call state to UI: ${session?.state ?: CallState.ENDED}")
    }

    private fun broadcastMessageToUI(messageData: MessageData) {
        val intent = Intent(ACTION_MESSAGE_RECEIVED_FOR_UI)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_SENDER_UI, messageData.senderUsername)
        intent.putExtra(EXTRA_SENDER_IP_UI, messageData.senderIpAddress) // <<< ADD SENDER IP
        when (messageData) {
            is MessageData.Text -> {
                intent.putExtra(EXTRA_IS_IMAGE_UI, false)
                intent.putExtra(EXTRA_CONTENT_UI, messageData.content)
            }
            is MessageData.Image -> {
                intent.putExtra(EXTRA_IS_IMAGE_UI, true)
                val imageUri = saveBitmapToCacheAndGetUri(messageData.bitmap, messageData.senderUsername)
                if (imageUri != null) {
                    intent.putExtra(EXTRA_BITMAP_URI_UI, imageUri.toString())
                    intent.putExtra(EXTRA_CONTENT_UI, "[Image]")
                } else {
                    Log.w("MessageService", "Could not save image to cache for UI broadcast.")
                    return
                }
            }
            else -> {
                Log.w("MessageService", "Unknown message type in broadcastMessageToUI: ${messageData.javaClass.simpleName}")
            }
        }
        sendBroadcast(intent) // Use the regular sendBroadcast
        Log.d("MessageService", "Broadcast sent to UI for sender: ${messageData.senderUsername}")
    }

    private fun saveBitmapToCacheAndGetUri(bitmap: Bitmap, sender: String): Uri? {
        val context: Context = applicationContext
        val imagesFolder = File(context.cacheDir, "images")
        var uri: Uri? = null
        try {
            imagesFolder.mkdirs() // Create if not exists
            val file = File(imagesFolder, "img_${sender}_${System.currentTimeMillis()}.png")
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            stream.flush()
            stream.close()
            uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Log.d("MessageService", "Image saved to cache: $uri")
        } catch (e: IOException) {
            Log.e("MessageService", "Error saving bitmap to cache", e)
        }
        return uri
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service onStartCommand: ${intent?.action}")
        val currentUsername = loadUsername(applicationContext)

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
                Log.i(TAG, "ACTION_START_SERVICE: Launching coroutine to start MessageServer.")
                scope.launch {
                    try {
                        Log.i(TAG, "Coroutine to start MessageServer: STARTED.")
                        if (!::messageServer.isInitialized) {
                            Log.w(TAG, "MessageServer was not initialized. Re-initializing in ACTION_START_SERVICE.")
                            // Re-initialize MessageServer with the same lambdas as in onCreate
                            messageServer = MessageServer(
                                context = applicationContext,
                                port = Port, // Using your global Port
                                onMessageReceived = { messageData -> // <<<< COPIED FROM ONCREATE
                                    Log.d(TAG, "Message received in service dispatcher (via onStartCommand re-init): $messageData")
                                    if (handleCallMessage(messageData)) {
                                        return@MessageServer
                                    }
                                    broadcastMessageToUI(messageData)
                                    if (!isAppInForeground) {
                                        when (messageData) {
                                            is MessageData.Text -> showContentNotification(messageData.senderUsername, messageData.content)
                                            is MessageData.Image -> showContentNotification(messageData.senderUsername, "Received an image.")
                                            else -> {}
                                        }
                                    }
                                },
                                scope = scope,
                                onBackgroundNotificationRequired = { sender, contentPreview -> // <<<< COPIED FROM ONCREATE
                                    Log.d(TAG, "Background notification hint (via onStartCommand re-init): $sender - $contentPreview. Foreground: $isAppInForeground")
                                    if (!isAppInForeground && contentPreview != null) {
                                        showContentNotification(sender, contentPreview)
                                    }
                                }
                            )
                            Log.i(TAG, "MessageServer re-initialized in ACTION_START_SERVICE.")
                        }
                        // else { // Optional: Log if already initialized
                        //    Log.i(TAG, "MessageServer already initialized. Proceeding to start.")
                        // }

                        if (::messageServer.isInitialized) { // Double check before calling start
                            messageServer.start()
                            Log.i(TAG, "Coroutine to start MessageServer: messageServer.start() COMPLETED.")
                        } else {
                            Log.e(TAG, "Failed to initialize messageServer even after attempt in ACTION_START_SERVICE. Cannot start.")
                            stopSelf() // Critical failure
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error trying to start MessageServer in onStartCommand's coroutine", e)
                        stopSelf()
                    }
                }
                Log.i(TAG, "ACTION_START_SERVICE: Coroutine launched. onStartCommand finishing.")
            }
            ACTION_STOP_SERVICE -> {
                Log.i(TAG, "Stopping service and server via ACTION_STOP_SERVICE...")
                activeCallSession?.let {
                    if (it.state == CallState.ACTIVE || it.state == CallState.INCOMING || it.state == CallState.OUTGOING) {
                        Log.i(TAG, "Sending CALL_END for active session ${it.callId} before stopping service.")
                        sendCallProtocolMessageInternal(
                            ipAddress = it.peerIpAddress,
                            port = Port,
                            username = currentUsername,
                            key = Key,
                            messageType = MessageServer.TYPE_CALL_END,
                            callId = it.callId
                        )
                    }
                    clearIncomingCallNotification(it.callId.hashCode())
                }
                audioStreamManager.stopStreaming()
                activeCallSession = null
                broadcastCallStateToUI(null) // Notify UI that call is ended/service is stopping
                stopSelf() // This will trigger onDestroy where messageServer.stop() is called
            }

            // --- HANDLING ACTIONS FROM INCOMINGCALLACTIVITY (Full Screen UI) ---
            ACTION_ACCEPT_CALL_FROM_ACTIVITY -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID_SERVICE)
                val localUdpPortFromActivity = intent.getIntExtra(EXTRA_LOCAL_UDP_PORT_SERVICE, -1)

                Log.i(TAG, "ACTION_ACCEPT_CALL_FROM_ACTIVITY for call ID: $callId, My UDP Port: $localUdpPortFromActivity")

                if (callId != null && activeCallSession?.callId == callId && activeCallSession?.state == CallState.INCOMING) {
                    if (localUdpPortFromActivity == -1) {
                        Log.e(TAG, "No valid UDP port from IncomingCallActivity. Cannot accept call $callId.")
                        sendCallProtocolMessageInternal(
                            ipAddress = activeCallSession!!.peerIpAddress,
                            port = Port,
                            username = currentUsername,
                            key = Key,
                            messageType = MessageServer.TYPE_CALL_REJECT,
                            callId = callId,
                            reason = "internal_error_no_udp_port"
                        )
                        activeCallSession?.state = CallState.ERROR // Or REJECTED_BY_ME
                        broadcastCallStateToUI(activeCallSession)
                        activeCallSession = null
                        return START_STICKY
                    }

                    activeCallSession?.localAudioUdpPort = localUdpPortFromActivity
                    activeCallSession?.state = CallState.ACTIVE

                    Log.i(TAG, "Call $callId accepted via IncomingCallActivity. Sending CALL_ACCEPT. My UDP: ${activeCallSession?.localAudioUdpPort}, Peer UDP: ${activeCallSession?.peerAudioUdpPort}")

                    sendCallProtocolMessageInternal(
                        ipAddress = activeCallSession!!.peerIpAddress,
                        port = Port,
                        username = currentUsername,
                        key = Key,
                        messageType = MessageServer.TYPE_CALL_ACCEPT,
                        callId = callId,
                        audioUdpPort = activeCallSession!!.localAudioUdpPort!!
                    )
                    broadcastCallStateToUI(activeCallSession)
                    clearIncomingCallNotification(callId.hashCode()) // Clear any fallback notification

                    audioStreamManager.startStreaming(
                        localUdpPort = activeCallSession!!.localAudioUdpPort!!,
                        peerIpAddress = activeCallSession!!.peerIpAddress,
                        peerUdpPort = activeCallSession!!.peerAudioUdpPort!!,
                            scope = scope
                    )
                    } else {
                        Log.e(TAG, "Cannot start audio stream after accept from activity: missing info.")
                    }

                    // Optionally, bring MainActivity to the front to show the active call UI
                    // if the device is not locked.
                    val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager?
                    if (keyguardManager != null && !keyguardManager.isKeyguardLocked) {
                        // isAppInForeground check can also be relevant here, though if IncomingCallActivity
                        // was visible, the app might not be "in foreground" in the main process sense.
                        // The primary goal is to show the main app UI if the user is actively using the device.

                        val mainActivityIntent = android.content.Intent(this, MainActivity::class.java).apply {
                            // Explicitly using android.content.Intent to be absolutely sure
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            action = ACTION_SHOW_CALL_UI // Ensure MainActivity can handle this
                            putExtra(EXTRA_CALL_ID_SERVICE, activeCallSession?.callId)
                            putExtra(EXTRA_CALL_STATE, CallState.ACTIVE.name)
                            putExtra(EXTRA_CALLER_USERNAME_SERVICE, activeCallSession?.peerUsername)
                            putExtra(EXTRA_PEER_UDP_PORT_SERVICE, activeCallSession?.peerAudioUdpPort) // Added this, might be useful for UI
                            putExtra(EXTRA_LOCAL_UDP_PORT_SERVICE, activeCallSession?.localAudioUdpPort) // Added this, might be useful for UI
                            // Add other extras MainActivity needs to display the active call
                        }
                        try {
                            startActivity(mainActivityIntent)
                            Log.i(TAG, "Attempting to bring MainActivity to front.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error trying to bring MainActivity to front after accepting call: ${e.message}", e)
                        }
                    }

                } else {
                    Log.w(TAG, "Received ACCEPT_FROM_ACTIVITY for an unknown, mismatched, or invalid state call. Call ID: $callId, Session: ${activeCallSession?.callId}, State: ${activeCallSession?.state}")
                }
            }

            ACTION_REJECT_CALL_FROM_ACTIVITY -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID_SERVICE)
                Log.i(TAG, "ACTION_REJECT_CALL_FROM_ACTIVITY for call ID: $callId")

                if (callId != null && activeCallSession?.callId == callId && activeCallSession?.state == CallState.INCOMING) {
                    Log.i(TAG, "Call REJECTED via IncomingCallActivity action: ID $callId")
                    sendCallProtocolMessageInternal(
                        ipAddress = activeCallSession!!.peerIpAddress,
                        port = Port,
                        username = currentUsername,
                        key = Key,
                        messageType = MessageServer.TYPE_CALL_REJECT,
                        callId = callId
                    )
                    activeCallSession?.state = CallState.REJECTED_BY_ME
                    // audioStreamManager.stopStreaming(); // Should not be streaming yet
                    broadcastCallStateToUI(activeCallSession)
                    clearIncomingCallNotification(callId.hashCode())
                    activeCallSession = null
                } else {
                    Log.w(TAG, "Received REJECT_FROM_ACTIVITY for an unknown, mismatched, or invalid state call. Call ID: $callId, Session: ${activeCallSession?.callId}, State: ${activeCallSession?.state}")
                }
            }

            // --- ACTIONS FROM MAINACTIVITY (IN-APP BANNER/UI) ---
            ACTION_CALL_ACCEPTED_BY_USER -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID_SERVICE)
                val localUdpPortFromUI = intent.getIntExtra(EXTRA_LOCAL_UDP_PORT_SERVICE, -1) // MainScreen provides this

                Log.i(TAG, "ACTION_CALL_ACCEPTED_BY_USER for call ID: $callId, My UDP Port: $localUdpPortFromUI")

                if (callId != null && activeCallSession?.callId == callId && activeCallSession?.state == CallState.INCOMING) {
                    if (localUdpPortFromUI == -1) {
                        Log.e(TAG, "No valid UDP port from MainScreen banner. Cannot accept call $callId.")
                        sendCallProtocolMessageInternal(
                            ipAddress = activeCallSession!!.peerIpAddress,
                            port = Port,
                            username = currentUsername,
                            key = Key,
                            messageType = MessageServer.TYPE_CALL_REJECT,
                            callId = callId,
                            reason = "internal_error_no_udp_port_ui"
                        )
                        activeCallSession?.state = CallState.ERROR
                        broadcastCallStateToUI(activeCallSession)
                        activeCallSession = null
                        return START_STICKY
                    }

                    activeCallSession?.localAudioUdpPort = localUdpPortFromUI
                    activeCallSession?.state = CallState.ACTIVE

                    sendCallProtocolMessageInternal(
                        ipAddress = activeCallSession!!.peerIpAddress,
                        port = Port,
                        username = currentUsername,
                        key = Key,
                        messageType = MessageServer.TYPE_CALL_ACCEPT,
                        callId = callId,
                        audioUdpPort = activeCallSession!!.localAudioUdpPort!!
                    )
                    broadcastCallStateToUI(activeCallSession) // Update MainScreen's UI

                    if (activeCallSession?.localAudioUdpPort != null &&
                        activeCallSession?.peerIpAddress != null &&
                        activeCallSession?.peerAudioUdpPort != null) {
                        audioStreamManager.startStreaming( // NEW
                            localUdpPort = activeCallSession!!.localAudioUdpPort!!,
                            peerIpAddress = activeCallSession!!.peerIpAddress,
                            peerUdpPort = activeCallSession!!.peerAudioUdpPort!!,
                            scope = scope // Pass the service's scope
                        )
                    } else {
                        Log.e(TAG, "Cannot start audio stream after accept from UI: missing info.")
                    }
                } else {
                    Log.w(TAG, "Received ACCEPT_BY_USER for an unknown, mismatched, or invalid state call. Call ID: $callId, Session: ${activeCallSession?.callId}, State: ${activeCallSession?.state}")
                }
            }

            ACTION_CALL_REJECTED_BY_USER -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID_SERVICE)
                Log.i(TAG, "ACTION_CALL_REJECTED_BY_USER for call ID: $callId")

                if (callId != null && activeCallSession?.callId == callId && activeCallSession?.state == CallState.INCOMING) {
                    sendCallProtocolMessageInternal(
                        ipAddress = activeCallSession!!.peerIpAddress,
                        port = Port,
                        username = currentUsername,
                        key = Key,
                        messageType = MessageServer.TYPE_CALL_REJECT,
                        callId = callId
                    )
                    activeCallSession?.state = CallState.REJECTED_BY_ME
                    broadcastCallStateToUI(activeCallSession)
                    activeCallSession = null
                } else {
                    Log.w(TAG, "Received REJECT_BY_USER for an unknown, mismatched, or invalid state call. Call ID: $callId, Session: ${activeCallSession?.callId}, State: ${activeCallSession?.state}")
                }
            }

            ACTION_INITIATE_OUTGOING_CALL -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID_SERVICE) ?: UUID.randomUUID().toString()
                val targetIp = intent.getStringExtra(EXTRA_TARGET_IP_SERVICE)
                val targetUsername = intent.getStringExtra(EXTRA_TARGET_USERNAME_SERVICE)
                val localUdpPort = intent.getIntExtra(EXTRA_LOCAL_UDP_PORT_SERVICE, -1)

                if (targetIp != null && targetUsername != null && localUdpPort != -1) {
                    if (activeCallSession != null && activeCallSession?.state != CallState.ENDED && activeCallSession?.state != CallState.IDLE) {
                        Log.w(TAG, "Cannot initiate new call. Current call session active: ${activeCallSession?.callId}, state: ${activeCallSession?.state}")
                        // Optionally broadcast an error to UI: "Already in call"
                        //broadcastCallStateToUI(ActiveCallSession(callId, targetUsername, targetIp, CallState.ERROR, localUdpPort, null, "Already in call")) // Example
                        return START_STICKY
                    }

                    Log.i(TAG, "Service handling ACTION_INITIATE_OUTGOING_CALL for ID: $callId to $targetUsername ($targetIp) on my UDP port $localUdpPort")
                    activeCallSession = ActiveCallSession(
                        callId = callId,
                        peerUsername = targetUsername,
                        peerIpAddress = targetIp,
                        state = CallState.OUTGOING,
                        localAudioUdpPort = localUdpPort,
                        peerAudioUdpPort = null // We don't know this yet
                    )
                    sendCallProtocolMessageInternal(
                        ipAddress = targetIp,
                        port = Port,
                        username = currentUsername,
                        key = Key,
                        messageType = MessageServer.TYPE_CALL_INITIATE,
                        callId = callId,
                        audioUdpPort = localUdpPort // Send *MY* listening UDP port
                    )
                    broadcastCallStateToUI(activeCallSession) // Update UI to "Calling..."
                } else {
                    Log.e(TAG, "Missing data for initiating outgoing call: callId=$callId, targetIp=$targetIp, targetUsername=$targetUsername, localUdpPort=$localUdpPort")
                    //broadcastCallStateToUI(ActiveCallSession(callId, targetUsername ?: "Unknown", targetIp ?: "Unknown", CallState.ERROR, localUdpPort, null, "Missing call data")) // Example
                }
            }

            ACTION_CALL_ENDED_BY_USER, ACTION_END_CALL -> { // Consolidate ending call from UI or other sources
                val callIdToEnd = intent.getStringExtra(EXTRA_CALL_ID_SERVICE) ?: activeCallSession?.callId

                Log.i(TAG, "Service: ACTION_END_CALL / ACTION_CALL_ENDED_BY_USER received for ID: $callIdToEnd")

                if (activeCallSession != null && activeCallSession!!.callId == callIdToEnd) {
                    Log.i(TAG, "Service: Ending call ${activeCallSession!!.callId}. Current state: ${activeCallSession!!.state}")

                    // Send CALL_END only if the call was somewhat established or actively being managed
                    if (activeCallSession!!.state == CallState.ACTIVE ||
                        activeCallSession!!.state == CallState.OUTGOING ||
                        activeCallSession!!.state == CallState.INCOMING) { // Also send if incoming and user hangs up via notification/activity
                        Log.i(TAG, "Service: Sending TYPE_CALL_END for ${activeCallSession!!.callId} to ${activeCallSession!!.peerIpAddress}")
                        sendCallProtocolMessageInternal(
                            ipAddress = activeCallSession!!.peerIpAddress,
                            port = Port,
                            username = currentUsername,
                            key = Key,
                            messageType = MessageServer.TYPE_CALL_END,
                            callId = activeCallSession!!.callId
                        )
                    }
                    activeCallSession?.state = CallState.ENDED
                    audioStreamManager.stopStreaming()
                    broadcastCallStateToUI(activeCallSession) // Broadcast the "ENDED" state
                    clearIncomingCallNotification(activeCallSession!!.callId.hashCode())
                    activeCallSession = null
                    Log.i(TAG, "Service: activeCallSession set to null after ending call.")
                } else {
                    Log.w(TAG, "Service: ACTION_END_CALL for irrelevant or non-existent session. Requested: $callIdToEnd, Current Session: ${activeCallSession?.callId}")
                }
            }
            // Add other actions if needed
        }
        return START_STICKY // Or START_REDELIVER_INTENT if you want intents redelivered after crash
    }

    private fun createForegroundNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java) // Ensure MainActivity is correct
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("ODI Messenger Service")
            .setContentText("This service is running in the background to listen for incoming messages.")
            .setSmallIcon(R.drawable.sky_bg)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun showContentNotification(sender: String, messageContent: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // open a specific chat screen when the notification is tapped.
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this, sender.hashCode(), intent, pendingIntentFlags
        )

        val notificationBuilder = NotificationCompat.Builder(this, MyApplication.CHANNEL_ID) // Ensure MyApplication.CHANNEL_ID is correct and channel is created
            .setSmallIcon(R.drawable.sky_bg) // Ensure this drawable exists
            .setContentTitle(sender)
            .setContentText(messageContent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)

        with(NotificationManagerCompat.from(this)) {
            val notificationId = sender.hashCode() + MessageServer.NOTIFICATION_ID_OFFSET
            // Add POST_NOTIFICATIONS permission check for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w("MessageService", "Missing POST_NOTIFICATIONS permission. Cannot send notification.")
                return // Or request permission appropriately
            }
            try {
                notify(notificationId, notificationBuilder.build())
                Log.i("MessageService", "Content notification sent for $sender: $messageContent")
            } catch (e: SecurityException) { // Should be rare with the check above
                Log.e("MessageService", "SecurityException for content notification: ${e.message}")
            }

        }
    }

    private fun createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "ODIMessenger Service Channel",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.i("MessageService","Service Notification Channel $SERVICE_CHANNEL_ID created/updated.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Log with a stack trace to see WHO is calling/causing onDestroy
        Log.w("MessageService", "Service onDestroy CALLED", Exception("StackTrace for MessageService.onDestroy"))

        activeCallSession?.let {
            if (it.state == CallState.ACTIVE || it.state == CallState.INCOMING) {
                sendCallProtocolMessage(
                    ipAddress = it.peerIpAddress,
                    port = Port,
                    username = loadUsername(applicationContext) ?: "UnknownUser",
                    key = Key,
                    messageType = MessageServer.TYPE_CALL_END,
                    callId = it.callId
                )
            }
            clearIncomingCallNotification(it.callId.hashCode())
        }
        activeCallSession = null

        if (::messageServer.isInitialized) {
            Log.i("MessageService", "Calling messageServer.stop() from onDestroy.")
            messageServer.stop()
        }
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
        Log.i("MessageService", "Calling scope.cancel() from onDestroy.")
        scope.cancel() // Cancel the service's main scope
        Log.i("MessageService", "MessageServer stopped and scope cancelled.")
    }
}