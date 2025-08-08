package com.iimoxi.odi_messanger // Ensure this matches your package name

import android.Manifest
import androidx.compose.animation.core.copy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
//import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iimoxi.odi_messanger.TopBGColor

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Phone
//import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.test.cancel
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.iimoxi.odi_messanger.MessageService.Companion.ACTION_ACCEPT_CALL_FROM_ACTIVITY
import com.iimoxi.odi_messanger.MessageService.Companion.ACTION_REJECT_CALL_FROM_ACTIVITY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.net.DatagramSocket
import java.net.InetAddress

val AcceptCallColor = Color(0xFF3fc764)
val RejectCallColor = Color(0xFFc73f3f)

class IncomingCallActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_USERNAME = "extra_caller_username"
        const val EXTRA_CALLER_IP = "extra_caller_ip"
        const val ACTION_ACCEPT_CALL = "com.iimoxi.odi_messanger.ACCEPT_CALL"
        const val ACTION_REJECT_CALL = "com.iimoxi.odi_messanger.REJECT_CALL"
    }

    private var callId: String? = null
    private var callerUsername: String? = null
    private var callerIp: String? = null
    private var currentAppUsername: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intentAction = intent.action

        // Make sure activity shows over lock screen and turns screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(KEYGUARD_SERVICE) as KeyguardManager?)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
            )
        }
        callId = intent.getStringExtra(EXTRA_CALL_ID)
        callerUsername = intent.getStringExtra(EXTRA_CALLER_USERNAME)
        callerIp = intent.getStringExtra(EXTRA_CALLER_IP)
        // peerAudioUdpPort = intent.getIntExtra(MessageService.EXTRA_PEER_UDP_PORT_SERVICE, -1) // Get this if needed
        currentAppUsername = loadUsername(applicationContext)

        Log.i("IncomingCallActivity", "onCreate: Action: $intentAction, CallID: $callId, Caller: $callerUsername")

        if (callId == null || callerUsername == null || callerIp == null || currentAppUsername == null) {
            Log.e("IncomingCallActivity", "Missing call data, finishing activity.")
            finishAndRemoveTask()
            return
        }
        when (intentAction) {
            ACTION_ACCEPT_CALL -> {
                Log.i("IncomingCallActivity", "Launched with ACTION_ACCEPT_CALL from notification.")
                val myAudioUdpPort = findFreeUdpPort(tag = "IncomingCallActivity_AcceptNotification")
                if (myAudioUdpPort == -1) {
                    Log.e("IncomingCallActivity", "Failed to find a free UDP port (from notification accept).")
                    Toast.makeText(applicationContext, "Error: Port unavailable.", Toast.LENGTH_LONG).show()
                    // Send reject to service, then finish
                    sendResponseToService(MessageService.ACTION_REJECT_CALL_FROM_ACTIVITY, null)
                } else {
                    Log.i("IncomingCallActivity", "Call ACCEPTED via notification action: ID $callId. My UDP port: $myAudioUdpPort")
                    sendResponseToService(MessageService.ACTION_ACCEPT_CALL_FROM_ACTIVITY, myAudioUdpPort)
                }
                finishAndRemoveTask() // Finish activity as action is handled
                return // Don't proceed to setContent if launched from action
            }
            ACTION_REJECT_CALL -> {
                Log.i("IncomingCallActivity", "Launched with ACTION_REJECT_CALL from notification.")
                sendResponseToService(MessageService.ACTION_REJECT_CALL_FROM_ACTIVITY, null)
                finishAndRemoveTask() // Finish activity as action is handled
                return // Don't proceed to setContent if launched from action
            }
        }
        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                IncomingCallScreen(
                    callerName = callerUsername ?: "Unknown Caller",
                    onAccept = {
                        val myAudioUdpPort = findFreeUdpPort(tag = "IncomingCallActivity_UDP_UI")
                        if (myAudioUdpPort == -1) {
                            // ... (error handling) ...
                            return@IncomingCallScreen
                        }

                        if (ContextCompat.checkSelfPermission(this@IncomingCallActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            Log.i("IncomingCallActivity", "Call ACCEPTED via UI: ID $callId. My UDP port: $myAudioUdpPort")
                            sendResponseToService(MessageService.ACTION_ACCEPT_CALL_FROM_ACTIVITY, myAudioUdpPort)
                            finishAndRemoveTask()
                        } else {
                            // Permission not granted. You might inform the user or just proceed without audio,
                            // relying on the AudioStreamManager logging the error.
                            // For a better UX, you'd request it here, but it's late in the flow.
                            Log.w("IncomingCallActivity", "RECORD_AUDIO permission not granted when accepting call. Audio might not work.")
                            Toast.makeText(applicationContext, "Audio permission needed. Call may not have audio.", Toast.LENGTH_LONG).show()
                            // Still send accept, service will try and fail to start audio if permission truly missing.
                            sendResponseToService(MessageService.ACTION_ACCEPT_CALL_FROM_ACTIVITY, myAudioUdpPort)
                            finishAndRemoveTask()
                        }
                    }
                    onReject = {
                        Log.i("IncomingCallActivity", "Call REJECTED via UI: ID $callId")
                        sendResponseToService(MessageService.ACTION_REJECT_CALL_FROM_ACTIVITY, null)
                        finishAndRemoveTask()
                    }
                )
            }
        }
    }

    private fun sendResponseToService(serviceAction: String, selectedUdpPort: Int?) {
        val serviceIntent = Intent(this, MessageService::class.java).apply {
            action = serviceAction // This should be MessageService.ACTION_ACCEPT_CALL_FROM_ACTIVITY or MessageService.ACTION_REJECT_CALL_FROM_ACTIVITY
            putExtra(MessageService.EXTRA_CALL_ID_SERVICE, callId)
            putExtra(MessageService.EXTRA_CALLER_USERNAME_SERVICE, callerUsername)
            putExtra(MessageService.EXTRA_CALLER_IP_SERVICE, callerIp)
            if (serviceAction == MessageService.ACTION_ACCEPT_CALL_FROM_ACTIVITY && selectedUdpPort != null && selectedUdpPort != -1) {
                putExtra(MessageService.EXTRA_LOCAL_UDP_PORT_SERVICE, selectedUdpPort)
            }
        }
        startService(serviceIntent)
        Log.d("IncomingCallActivity", "Sent action '$serviceAction' to MessageService for call $callId.")
    }

    private fun sendCallResponse(action: String, selectedUdpPort: Int?) {
        val responseType = when (action) {
            ACTION_ACCEPT_CALL -> MessageServer.TYPE_CALL_ACCEPT
            ACTION_REJECT_CALL -> MessageServer.TYPE_CALL_REJECT
            else -> return
        }

        callerIp?.let { ip ->
            callId?.let { cId ->
                currentAppUsername?.let { myUsername ->
                    sendCallProtocolMessage( // This is your global/MainScreen.kt function
                        ipAddress = ip,
                        port = Port,
                        username = myUsername,
                        key = Key,
                        messageType = responseType,
                        callId = cId,
                        audioUdpPort = if (responseType == MessageServer.TYPE_CALL_ACCEPT) selectedUdpPort else null // Use parameter
                    )
                }
            }
        }
        // Also notify the service if it needs to update its state
        val serviceIntent = Intent(this, MessageService::class.java).apply {
            this.action = action
            putExtra(MessageService.EXTRA_CALL_ID_SERVICE, callId)
            putExtra(MessageService.EXTRA_CALLER_USERNAME_SERVICE, callerUsername)
            putExtra(MessageService.EXTRA_RESPONDER_USERNAME_SERVICE, currentAppUsername)
            putExtra(MessageService.EXTRA_CALLER_IP_SERVICE, callerIp)
            // IMPORTANT: Use the 'selectedUdpPort' parameter here
            if (action == ACTION_ACCEPT_CALL && selectedUdpPort != null && selectedUdpPort != -1) { // Check for -1 too
                putExtra(MessageService.EXTRA_LOCAL_UDP_PORT_SERVICE, selectedUdpPort) // Use parameter
                Log.i("IncomingCallActivity", "Sending chosen UDP port $selectedUdpPort to MessageService.")
            }
        }
        startService(serviceIntent)
    }

    /*
    private fun handleAcceptedCall() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isDeviceLocked = keyguardManager.isKeyguardLocked

        if (isDeviceLocked) {
            Log.i("IncomingCallActivity", "Device is locked. Call accepted, but not opening app UI directly.")
            // The service should now manage the call state.
            // You might show a persistent notification indicating an active call.
        } else {
            Log.i("IncomingCallActivity", "Device is unlocked. Opening MainActivity for call.")
            val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = MessageService.ACTION_SHOW_CALL_UI // Custom action for MainActivity
                putExtra(MessageService.EXTRA_CALL_ID_SERVICE, callId)
                putExtra(MessageService.EXTRA_CALLER_USERNAME_SERVICE, callerUsername) // This is the peer
                putExtra(MessageService.EXTRA_IS_INCOMING_CALL_ACCEPTED, true)
                // Add any other necessary data to set up the call UI in MainActivity
            }
            startActivity(mainActivityIntent)
        }
    }
    */

    override fun onDestroy() {
        super.onDestroy()
        Log.d("IncomingCallActivity", "IncomingCallActivity destroyed for call $callId.")
        // Consider if a "missed call" needs to be signaled if neither accept/reject was hit
        // This is complex as it might be destroyed due to accept/reject too.
        // MessageService is likely better placed to determine missed calls via timeout.
    }
}

class AudioStreamManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioStreamManager"
        private const val SAMPLE_RATE = 16000 // Sample rate in Hz
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2 // Factor to ensure buffer is large enough
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private var sendSocket: DatagramSocket? = null
    private var receiveSocket: DatagramSocket? = null

    private var isStreaming = false

    private val minInputBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
    private val minOutputBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)

    fun startStreaming(localUdpPort: Int, peerIpAddress: String, peerUdpPort: Int, scope: CoroutineScope) {
        if (isStreaming) {
            Log.w(TAG, "Streaming is already in progress.")
            return
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted. Cannot start audio streaming.")
            // Consider notifying the user or service about this failure.
            return
        }

        Log.i(TAG, "Starting audio streaming: Local Port: $localUdpPort, Peer: $peerIpAddress:$peerUdpPort")
        Log.i(TAG, "MinInputBufferSize: $minInputBufferSize, MinOutputBufferSize: $minOutputBufferSize")

        if (minInputBufferSize == AudioRecord.ERROR_BAD_VALUE || minInputBufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "AudioRecord: Invalid parameters for getMinBufferSize.")
            return
        }
        if (minOutputBufferSize == AudioTrack.ERROR_BAD_VALUE || minOutputBufferSize == AudioTrack.ERROR) {
            Log.e(TAG, "AudioTrack: Invalid parameters for getMinBufferSize.")
            return
        }

        val recordBufferSize = minInputBufferSize * BUFFER_SIZE_FACTOR
        val trackBufferSize = minOutputBufferSize * BUFFER_SIZE_FACTOR

        try {
            // Initialize AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Optimized for communication
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                recordBufferSize
            )

            // Initialize AudioTrack
            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL, // Use voice call stream type
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT,
                trackBufferSize,
                AudioTrack.MODE_STREAM
            )

            // Initialize Sockets
            sendSocket = DatagramSocket() // OS will assign a free port for sending
            receiveSocket = DatagramSocket(localUdpPort)
            receiveSocket?.soTimeout = 1000 // Timeout for receive to allow job cancellation check

            val peerAddress = InetAddress.getByName(peerIpAddress)

            isStreaming = true

            // Start recording and sending audio
            recordingJob = scope.launch(Dispatchers.IO) {
                Log.d(TAG, "Recording job started on ${Thread.currentThread().name}")
                audioRecord?.startRecording()
                val buffer = ByteArray(recordBufferSize)
                while (isActive && isStreaming && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        try {
                            val packet = DatagramPacket(buffer, bytesRead, peerAddress, peerUdpPort)
                            sendSocket?.send(packet)
                            // Log.v(TAG, "Sent $bytesRead audio bytes to $peerIpAddress:$peerUdpPort")
                        } catch (e: SocketException) {
                            Log.e(TAG, "SocketException while sending audio: ${e.message}. Stopping recording.")
                            break // Exit loop if socket is closed or error occurs
                        } catch (e: IOException) {
                            Log.e(TAG, "IOException while sending audio: ${e.message}")
                            // Potentially continue or break based on error type
                        }
                    } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION || bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "Error reading from AudioRecord: $bytesRead")
                        break
                    }
                }
                Log.d(TAG, "Recording job finishing. isActive: $isActive, isStreaming: $isStreaming")
            }

            // Start receiving and playing audio
            playbackJob = scope.launch(Dispatchers.IO) {
                Log.d(TAG, "Playback job started on ${Thread.currentThread().name}")
                audioTrack?.play()
                val buffer = ByteArray(trackBufferSize) // Use track buffer size for receiving
                while (isActive && isStreaming && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        receiveSocket?.receive(packet) // Blocking call with timeout
                        if (packet.length > 0) {
                            // Log.v(TAG, "Received ${packet.length} audio bytes from ${packet.address}:${packet.port}")
                            audioTrack?.write(packet.data, 0, packet.length)
                        }
                    } catch (e: SocketException) {
                        if (isActive && isStreaming) { // Only log error if we weren't expecting to stop
                            Log.e(TAG, "SocketException while receiving audio (possibly closed): ${e.message}")
                        }
                        break // Exit loop if socket is closed or error occurs
                    } catch (e: java.net.SocketTimeoutException) {
                        // This is expected due to soTimeout, allows checking isActive
                        if (!isActive || !isStreaming) {
                            Log.d(TAG, "Playback job: Socket timeout, and streaming is stopping.")
                            break
                        }
                        // Continue loop if still active
                    }
                    catch (e: IOException) {
                        Log.e(TAG, "IOException while receiving audio: ${e.message}")
                        // Potentially continue or break based on error type
                    }
                }
                Log.d(TAG, "Playback job finishing. isActive: $isActive, isStreaming: $isStreaming")
            }

            Log.i(TAG, "Audio streaming started successfully.")

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during audio setup: ${e.message}", e)
            stopStreamingInternal() // Clean up if setup fails
        }
        catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgumentException during audio setup: ${e.message}", e)
            stopStreamingInternal()
        }
        catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio streaming: ${e.message}", e)
            stopStreamingInternal() // Clean up any partial setup
        }
    }

    fun stopStreaming() {
        if (!isStreaming) {
            Log.w(TAG, "Streaming is not in progress or already stopped.")
            return
        }
        Log.i(TAG, "Stopping audio streaming...")
        stopStreamingInternal()
    }

    private fun stopStreamingInternal() {
        isStreaming = false // Signal loops to stop

        // Cancel coroutines
        recordingJob?.cancel()
        playbackJob?.cancel()
        Log.d(TAG, "Audio jobs cancelled.")

        // Release AudioRecord
        if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                audioRecord?.stop()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException stopping AudioRecord: ${e.message}")
            }
        }
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "AudioRecord released.")

        // Release AudioTrack
        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            try {
                audioTrack?.stop()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException stopping AudioTrack: ${e.message}")
            }
        }
        audioTrack?.release()
        audioTrack = null
        Log.d(TAG, "AudioTrack released.")

        // Close sockets
        sendSocket?.close()
        sendSocket = null
        receiveSocket?.close()
        receiveSocket = null
        Log.d(TAG, "Sockets closed.")

        recordingJob = null
        playbackJob = null

        Log.i(TAG, "Audio streaming stopped completely.")
    }

    fun isStreaming(): Boolean = isStreaming
}

@Composable
fun IncomingCallScreen( // This is what IncomingCallActivity will use
    callerName: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier // Good practice
) {
    // Your full-screen UI implementation (refer to the example in my previous response
    // with a dark background, centered text, and larger accept/reject buttons)
    // For example:
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)) // More opaque for focus
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "Incoming Call From",
                fontSize = 22.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
            Text(
                text = callerName,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(48.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp) // Wider spacing for full screen
            ) {
                Button( // Reject
                    onClick = onReject,
                    shape = RoundedCornerShape(100.dp), // Circular
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    modifier = Modifier.size(80.dp) // Larger buttons
                ) {
                    Icon(Icons.Filled.Call, "Reject Call", tint = Color.White, modifier = Modifier.size(40.dp))
                }
                Button( // Accept
                    onClick = onAccept,
                    shape = RoundedCornerShape(100.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)), // Green
                    modifier = Modifier.size(80.dp)
                ) {
                    Icon(Icons.Filled.Phone, "Accept Call", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
        }
    }
}

@Composable
fun IncomingCallUI(
    callerInfo: String, // e.g., "Username (IP Address)"
    onAcceptCall: () -> Unit,
    onRejectCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp), // Adjust height as needed, or set a fixed one
        color = TopBGColor,
        shape = RoundedCornerShape(
            bottomStart = 12.dp,
            bottomEnd = 12.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = callerInfo,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextColor,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Incoming Call...",
                    fontSize = 14.sp,
                    color = TextColor.copy(alpha = 0.8f)
                )
            }

            Row {
                Button(
                    onClick = onRejectCall,
                    colors = ButtonDefaults.buttonColors(containerColor = RejectCallColor),
                    modifier = Modifier.size(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Call,
                        contentDescription = "Accept Call",
                        tint = TextColor,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = onAcceptCall,
                    colors = ButtonDefaults.buttonColors(containerColor = AcceptCallColor),
                    modifier = Modifier.size(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Call,
                        contentDescription = "Accept Call",
                        tint = TextColor,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun ActiveCallUi(
    callerInfo: String,
    callStatusText: String,
    onEndCallClicked: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp), // Adjust height as needed, or set a fixed one
        color = TopBGColor,
        shape = RoundedCornerShape(
            bottomStart = 12.dp,
            bottomEnd = 12.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp))
            {
                Text(
                    text = callerInfo,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextColor,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = callStatusText,
                    fontSize = 14.sp,
                    color = DisabledTextColor
                )
            }
            Button(
                onClick = onEndCallClicked,
                colors = ButtonDefaults.buttonColors(containerColor = RejectCallColor),
                modifier = Modifier.size(50.dp),
                shape = RoundedCornerShape(25.dp),
                contentPadding = PaddingValues(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Call,
                    contentDescription = "End Call",
                    tint = TextColor,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}