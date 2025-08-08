package com.iimoxi.odi_messanger

// LIST OF FILES:
// MainaAtivity.kt, CallScreen.kt, MainScreen.kt, Settings.kt, StorageData.kt, Network.kt, Media.kt, CryptoUtils.kt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlin.text.isNotBlank
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import android.content.BroadcastReceiver // Add this
import android.content.Context // Add this
import android.content.Intent // Add this
import android.content.IntentFilter // Add this
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Icon // Ensure this is the Material 3 Icon
import androidx.compose.ui.platform.LocalConfiguration
// Call window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
//Call Window
//Animation
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import java.util.UUID

//Animation

val QuinticOutEasing = Easing { fraction ->
    val t = fraction - 1.0f
    (t * t * t * t * t + 1.0f).toFloat()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier, currentUsername: String, onUsernameChange: (String) -> Unit) {
    var showSettingsScreen by remember { mutableStateOf(false) }
    var ipDropdownExpanded by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var isCallGestureActive by remember { mutableStateOf(false) }
    var callIconVisible by remember { mutableStateOf(false) }
    var canInitiateCall by remember { mutableStateOf(false) }
    val animatedMessageIds = remember { mutableStateOf(setOf<String>()) }
    var dragYOffset by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<UiDisplayMessage>() } // Uses UiDisplayMessage
    val listState = rememberLazyListState()
    val serverRunning = true
    // Storage Variables
    val context = LocalContext.current
    val localContext = LocalContext.current // Use this for non-nullable context where appropriate
    val savedIPs = remember { mutableStateListOf<String>() }
    var activeIP by remember { mutableStateOf("127.0.0.1") }
    val savedTargets = remember { mutableStateListOf<UserTarget>() }
    var activeTargetIp by remember { mutableStateOf<String?>(null) }
    var activeTargetDisplayLabel by remember { mutableStateOf("Select Target") } // What to show in TextField
    // Storage Variables

    // Voice Call Variables
    var isCallActive by remember { mutableStateOf(false) } // This can be true for outgoing, incoming accepted, or active
    var currentCallStatusText by remember { mutableStateOf("On a call...") } // "Calling...", "Ringing...", "On call with X"
    var activeCallData by remember { mutableStateOf<ActiveCallSession?>(null) } // Store session details
    var outgoingCallId by remember { mutableStateOf<String?>(null) } // Track ID of call *we* initiated
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val showCallIconThreshold = 50.dp
    val initiateCallThreshold = screenHeight / 4
    val cancelCallThreshold = screenHeight / 6
    // State specifically for the INCOMING call banner UI
    var showIncomingCallBanner by remember { mutableStateOf(false) }
    var incomingCallBannerInfo by remember { mutableStateOf<ActiveCallSession?>(null) }
    // voice Call Variables

    val imeInsets = WindowInsets.ime
    val isKeyboardOpen = imeInsets.getBottom(LocalDensity.current) > 0
    val keyboardController = LocalSoftwareKeyboardController.current

    var imageToPreview by remember { mutableStateOf<Bitmap?>(null) }
    var imageUriForSending by remember { mutableStateOf<Uri?>(null) }
    var bitmapForSending by remember { mutableStateOf<Bitmap?>(null) }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        imageUriForSending = uri // Update the correct state variable
        uri?.let {
            if (Build.VERSION.SDK_INT < 28) {
                @Suppress("DEPRECATION")
                bitmapForSending = android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, it) // Update correct state
            } else {
                val source = ImageDecoder.createSource(context.contentResolver, it)
                bitmapForSending = ImageDecoder.decodeBitmap(source) // Update correct state
            }
        }
    }

    DisposableEffect(Unit) {
        val callIntentFilter = IntentFilter(MessageService.ACTION_SHOW_CALL_UI)
        Log.i("MainScreen", "Registering Call BroadcastReceiver for action: ${MessageService.ACTION_SHOW_CALL_UI}")

        val callStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MessageService.ACTION_SHOW_CALL_UI) {
                    val callId = intent.getStringExtra(MessageService.EXTRA_CALL_ID_SERVICE)
                    val peerUsername = intent.getStringExtra(MessageService.EXTRA_CALLER_USERNAME_SERVICE)
                    val peerIp = intent.getStringExtra(MessageService.EXTRA_CALLER_IP_SERVICE)
                    val callStateStr = intent.getStringExtra("extra_call_state") // This is from the service broadcast
                    val callState = callStateStr?.let { runCatching { CallState.valueOf(it) }.getOrNull() } ?: CallState.IDLE
                    val peerUdpPort = intent.getIntExtra(MessageService.EXTRA_PEER_UDP_PORT_SERVICE, -1)

                    Log.i("MainScreenCallReceiver", "Received ACTION_SHOW_CALL_UI. CallState from Intent: $callState, ID: $callId, Peer: $peerUsername, PeerUDP: $peerUdpPort")

                    if (callId == null || peerUsername == null || peerIp == null) {
                        Log.e("MainScreenCallReceiver", "Essential call data missing from ACTION_SHOW_CALL_UI intent. callId=$callId, peerUsername=$peerUsername, peerIp=$peerIp. Ignoring.")
                        return // Cannot proceed without essential data
                    }

                    // --- Handle showing the Incoming Call Banner ---
                    if (callState == CallState.INCOMING && peerUdpPort != -1) {
                        // Only show banner if this is a fresh INCOMING call signal meant for the banner
                        // and we have the peer's UDP port.
                        if (activeCallData?.callId != callId && outgoingCallId != callId) { // Avoid showing if already in a call or making one
                            showIncomingCallBanner = true
                            incomingCallBannerInfo = ActiveCallSession(
                                callId = callId,
                                peerUsername = peerUsername,
                                peerIpAddress = peerIp,
                                state = CallState.INCOMING,
                                peerAudioUdpPort = peerUdpPort, // Crucial for accepting the call
                                localAudioUdpPort = null // Will be set upon accepting
                            )
                            Log.i("MainScreenCallReceiver", "Populating IncomingCallBanner. showIncomingCallBanner=$showIncomingCallBanner, Info: $incomingCallBannerInfo")
                            // Do not set isCallActive = true here, that's for when the call is truly active.
                            // currentCallStatusText will be handled by the banner itself or later by the when block if needed.
                        } else {
                            Log.w("MainScreenCallReceiver", "Received INCOMING state for call $callId but another call is active or being initiated. Banner not shown.")
                        }
                    } else if (callState == CallState.INCOMING && peerUdpPort == -1) {
                        Log.w("MainScreenCallReceiver", "Received INCOMING state for call $callId but peer UDP port is missing. Cannot show banner properly.")
                    }


                    // --- General Call State Management ---
                    // This handles transitions and ensures UI consistency.
                    when (callState) {
                        CallState.INCOMING -> {
                            // If banner was shown above, great.
                            // This also handles cases where MainScreen opens and a call is already incoming.
                            if (activeCallData?.callId != callId && outgoingCallId != callId) { // If not already in another call
                                if (!showIncomingCallBanner) { // If banner wasn't just set (e.g., screen opened during ringing)
                                    activeCallData = ActiveCallSession(callId, peerUsername, peerIp, CallState.INCOMING, peerAudioUdpPort = peerUdpPort.takeIf { it != -1 })
                                    currentCallStatusText = "Incoming call from $peerUsername..."
                                    Log.i("MainScreenCallReceiver", "Set activeCallData for INCOMING (no banner was shown): $activeCallData")
                                }
                                // else: banner logic above has handled setting incomingCallBannerInfo
                            }
                            // isCallActive remains false until accepted.
                        }

                        CallState.OUTGOING -> {
                            if (outgoingCallId != callId || activeCallData?.state != CallState.OUTGOING) {
                                showIncomingCallBanner = false // Ensure incoming banner is hidden
                                incomingCallBannerInfo = null
                                activeCallData = ActiveCallSession(callId, peerUsername, peerIp, CallState.OUTGOING)
                                outgoingCallId = callId
                                isCallActive = true // For "Calling..." UI
                                currentCallStatusText = "Calling $peerUsername..."
                                Log.i("MainScreenCallReceiver", "Set state to OUTGOING: $activeCallData")
                            }
                        }

                        CallState.ACTIVE -> {
                            showIncomingCallBanner = false // Ensure incoming banner is hidden
                            incomingCallBannerInfo = null
                            // The localAudioUdpPort might need to be passed in the intent if the call was accepted elsewhere (e.g., notification)
                            // For now, assuming it's available or set during the accept flow if accepted via MainScreen.
                            activeCallData = ActiveCallSession(callId, peerUsername, peerIp, CallState.ACTIVE, peerAudioUdpPort = peerUdpPort.takeIf { it != -1 } /*, localAudioUdpPort = ... */)
                            isCallActive = true
                            currentCallStatusText = "On call with $peerUsername"
                            outgoingCallId = null // Clear if it was an outgoing call that got accepted
                            Log.i("MainScreenCallReceiver", "Set state to ACTIVE: $activeCallData")
                        }

                        CallState.ENDED, CallState.IDLE, CallState.REJECTED_BY_ME, CallState.REJECTED_BY_PEER, CallState.MISSED -> {
                            // Check if the event matches the current call being displayed (incoming banner, active, or outgoing)
                            if (incomingCallBannerInfo?.callId == callId || activeCallData?.callId == callId || outgoingCallId == callId) {
                                val previousCallUsername = incomingCallBannerInfo?.peerUsername ?: activeCallData?.peerUsername ?: peerUsername // Get username for status text

                                showIncomingCallBanner = false
                                incomingCallBannerInfo = null
                                isCallActive = false
                                // Keep activeCallData for a moment to show the correct ended status, then clear.
                                // Update its state if the current activeCallData matches the callId
                                if (activeCallData?.callId == callId) {
                                    activeCallData = activeCallData?.copy(state = callState)
                                }


                                currentCallStatusText = when (callState) {
                                    CallState.REJECTED_BY_PEER -> "Call rejected by $previousCallUsername"
                                    CallState.REJECTED_BY_ME -> "Call rejected"
                                    CallState.MISSED -> "Call missed from $previousCallUsername"
                                    else -> "Call ended"
                                }
                                Log.i("MainScreenCallReceiver", "Call ended/terminated. Status: $currentCallStatusText. Call ID: $callId")

                                coroutineScope.launch {
                                    delay(3000) // Show status for 3 seconds
                                    // Only clear if the status hasn't been changed by a new call event
                                    if ((activeCallData?.callId == callId || outgoingCallId == callId || currentCallStatusText.isNotBlank()) && !isCallActive && !showIncomingCallBanner) {
                                        currentCallStatusText = ""
                                    }
                                }
                                // Clear specific call tracking variables
                                if (activeCallData?.callId == callId) activeCallData = null
                                if (outgoingCallId == callId) outgoingCallId = null

                            } else {
                                Log.i("MainScreenCallReceiver", "Received $callState for call $callId, but it doesn't match current UI call.")
                            }
                        }
                        else -> {
                            Log.w("MainScreenCallReceiver", "Unhandled CallState in receiver: $callState for call ID: $callId")
                        }
                    }
                } else {
                    Log.w("MainScreenCallReceiver", "Received broadcast with unknown action: ${intent?.action}")
                }
            }
        }

        // Register the receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(callStateReceiver, callIntentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(callStateReceiver, callIntentFilter)
        }
        Log.i("MainScreen", "CallStateReceiver REGISTERED for ${callIntentFilter.getAction(0)}.")

        onDispose {
            context.unregisterReceiver(callStateReceiver)
            Log.i("MainScreen", "CallStateReceiver UNREGISTERED.")
        }
    }

    LaunchedEffect(Unit) {
        val loadedTargets = UserTargetStorage.loadTargets(localContext) // Use localContext
        if (loadedTargets.isNotEmpty()) {
            savedTargets.clear()
            savedTargets.addAll(loadedTargets.sortedByDescending { it.lastUsed })
            activeTargetIp = savedTargets[0].ipAddress
            activeTargetDisplayLabel = savedTargets[0].username ?: savedTargets[0].ipAddress
        } else {
            val defaultIp = "127.0.0.1"
            UserTargetStorage.addOrUpdateTarget(localContext, defaultIp, "Localhost (Default)") // Use localContext
            savedTargets.add(UserTarget(defaultIp, "Localhost (Default)"))
            activeTargetIp = defaultIp
            activeTargetDisplayLabel = "Localhost (Default)"
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(index = messages.size - 1)
        }
    }

    DisposableEffect(Unit) {
        val intentFilter = IntentFilter(MessageService.ACTION_MESSAGE_RECEIVED_FOR_UI)
        Log.i("MainScreen", "Registering BroadcastReceiver for action: ${MessageService.ACTION_MESSAGE_RECEIVED_FOR_UI}")

        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(
                broadcastContext: Context?,
                intent: Intent?
            ) {
                if (intent?.action == MessageService.ACTION_MESSAGE_RECEIVED_FOR_UI) {
                    val senderUsername = intent.getStringExtra(MessageService.EXTRA_SENDER_UI)
                    val senderIp = intent.getStringExtra(MessageService.EXTRA_SENDER_IP_UI)
                    val isImage = intent.getBooleanExtra(MessageService.EXTRA_IS_IMAGE_UI, false)
                    broadcastContext?.let { ctx -> // <<<----- FIX: Use non-null context
                        if (senderUsername != null && senderIp != null) {
                            Log.d("MainScreenReceiver", "Received message from User: $senderUsername, IP: $senderIp")

                            UserTargetStorage.updateUsernameForIp(ctx, senderIp, senderUsername)
                            val updatedTargets = UserTargetStorage.loadTargets(ctx)
                            savedTargets.clear()
                            savedTargets.addAll(updatedTargets.sortedByDescending { it.lastUsed })

                            savedTargets.find { it.ipAddress == activeTargetIp }?.let { currentActiveTarget ->
                                activeTargetDisplayLabel = currentActiveTarget.username ?: currentActiveTarget.ipAddress
                            }

                            if (isImage) {
                                val imageUriString = intent.getStringExtra(MessageService.EXTRA_BITMAP_URI_UI)
                                Log.d("MainScreenReceiver", "Received IMAGE: Sender=$senderUsername, Uri=$imageUriString")
                                if (imageUriString != null) {
                                    try {
                                        val imageUri = Uri.parse(imageUriString)
                                        ctx.contentResolver?.openInputStream(imageUri)?.use { inputStream -> // <<<----- FIX: Use ctx
                                            val bitmap = BitmapFactory.decodeStream(inputStream)
                                            if (bitmap != null) {
                                                messages.add(UiDisplayMessage.ImageDisplay(bitmap, senderUsername, false))
                                            } else {
                                                Log.e("MainScreenReceiver", "Failed to decode bitmap from URI: $imageUriString")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MainScreenReceiver", "Error decoding image from URI: $imageUriString", e)
                                    }
                                } else { // senderUsername or senderIp is null
                                    Log.w("MainScreenReceiver", "Received message with null sender username or IP.")
                                }
                            } else {
                                val content = intent.getStringExtra(MessageService.EXTRA_CONTENT_UI)
                                Log.d("MainScreenReceiver", "Received TEXT: Sender=$senderUsername, Content=$content")
                                if (content != null) {
                                    messages.add(UiDisplayMessage.Text(content, senderUsername, false))
                                } else { // senderUsername or senderIp is null
                                    Log.w("MainScreenReceiver", "Received message with null sender username or IP.")
                                }
                            }
                        } else {
                            Log.w("MainScreenReceiver", "Received message with null sender.")
                        }
                    } ?: run {
                        Log.e("MainScreenReceiver", "Broadcast context was null in onReceive.")
                    }
                }
            }
        }

        // Register the receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") // For older versions
            context.registerReceiver(broadcastReceiver, intentFilter)
        }
        Log.i("MainScreen", "BroadcastReceiver REGISTERED.")

        onDispose {
            context.unregisterReceiver(broadcastReceiver)
            Log.i("MainScreen", "BroadcastReceiver UNREGISTERED.")
        }
    }

    if (imageToPreview != null) {
        FullscreenImagePreview(
            bitmap = imageToPreview!!,
            onDismiss = { imageToPreview = null },
            onDownload = {
                imageToPreview?.let { bitmap -> // Use .let for a safe call and non-null bitmap
                    coroutineScope.launch {
                        saveBitmapToStorage(context, bitmap)
                    }
                }
                imageToPreview = null // Optionally dismiss preview after download starts
            }
        )
    }

    if (showSettingsScreen) {
        SettingsScreen(
            onBackPressed = { showSettingsScreen = false },
            currentUsername = currentUsername,
            onUsernameChange = onUsernameChange,
            currentIp = activeTargetIp ?: "127.0.0.1",
            onAddNewIp = { newIp ->
                if (newIp.isNotBlank()) {
                    UserTargetStorage.addOrUpdateTarget(localContext, newIp, "Unknown ($newIp)") // Use localContext
                    val updatedTargets = UserTargetStorage.loadTargets(localContext) // Use localContext
                    savedTargets.clear()
                    savedTargets.addAll(updatedTargets.sortedByDescending { it.lastUsed })
                    activeTargetIp = newIp
                    activeTargetDisplayLabel = "Unknown ($newIp)"
                }
            },
            onRemoveIp = { ipToRemove ->
                UserTargetStorage.removeTarget(localContext, ipToRemove) // Use localContext
                val updatedTargets = UserTargetStorage.loadTargets(localContext) // Use localContext
                savedTargets.clear()
                savedTargets.addAll(updatedTargets.sortedByDescending { it.lastUsed })
                if (activeTargetIp == ipToRemove || updatedTargets.none { it.ipAddress == activeTargetIp }) {
                    if (updatedTargets.isNotEmpty()) {
                        activeTargetIp = updatedTargets[0].ipAddress
                        activeTargetDisplayLabel = updatedTargets[0].username ?: updatedTargets[0].ipAddress
                    } else {
                        activeTargetIp = null
                        activeTargetDisplayLabel = "Select Target"
                    }
                }
            }
        )
    } else {
        if (isCallGestureActive && callIconVisible) {
            val iconAlpha by animateFloatAsState(targetValue = if (canInitiateCall) 1f else 0.6f, label = "iconAlpha")
            val iconSize = 64.dp

            val currentDensity = LocalDensity.current
            val initialIconOffsetYPx: Float
            val screenHeightPx: Float

            with(currentDensity) {
                screenHeightPx = screenHeight.toPx()
            }
            Box(
                modifier = Modifier.fillMaxSize(), // Overlay the whole screen
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ){
                    Icon(
                        imageVector = Icons.Filled.Call,
                        contentDescription = "Initiate Call",
                        tint = if (canInitiateCall) TextColor else DisabledTextColor,
                        modifier = Modifier
                            .size(
                                iconSize * (1 + (dragYOffset / screenHeightPx).coerceIn(
                                    0f,
                                    0.5f
                                ))
                            ) // USE screenHeightPx
                            .alpha(iconAlpha)
                            .offset {
                                IntOffset.Zero
                            }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (canInitiateCall) "Release to Call" else "Swipe further down to call",
                        color = TextColor,
                        fontSize = 18.sp,
                        modifier = Modifier.alpha(iconAlpha)
                    )
                    if(canInitiateCall) {
                        Text(
                            text = "Swipe up to cancel",
                            color = TextColor.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = modifier
                    .padding(0.dp)
                    .windowInsetsPadding(WindowInsets.ime)
            ) {
                Surface(
                    modifier = Modifier
                        .height(100.dp)
                        .fillMaxWidth()
                        .padding(bottom = 0.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    keyboardController?.hide()
                                    isCallGestureActive = true
                                    dragYOffset = 0f // Reset offset
                                    Log.d("CallGesture", "Drag Start")
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragYOffset += dragAmount.y

                                    // Now use the captured 'density' object
                                    with(density) { // <<<< ----- USE CAPTURED DENSITY -----
                                        if (dragYOffset > showCallIconThreshold.toPx()) {
                                            callIconVisible = true
                                        } else {
                                            // Optional: hide icon if dragged only a tiny bit and back up
                                            // callIconVisible = false
                                        }

                                        if (dragYOffset > initiateCallThreshold.toPx()) {
                                            canInitiateCall = true
                                            Log.d("CallGesture", "CAN INITIATE CALL")
                                        } else if (dragYOffset < cancelCallThreshold.toPx() && canInitiateCall) {
                                            // If user was able to call but dragged back up significantly
                                            canInitiateCall = false
                                            Log.d(
                                                "CallGesture",
                                                "CANNOT INITIATE CALL (dragged up)"
                                            )
                                        } else if (dragYOffset < initiateCallThreshold.toPx()) {
                                            canInitiateCall =
                                                false // General case if not dragged far enough
                                        }
                                    } // <<<< End of with(density) scope
                                    Log.d(
                                        "CallGesture",
                                        "Dragging: Yoffset = $dragYOffset, canInitiateCall = $canInitiateCall"
                                    )
                                },
                                onDragEnd = {
                                    Log.d(
                                        "CallGesture",
                                        "Drag End. CanInitiateCall: $canInitiateCall"
                                    )
                                    if (canInitiateCall) {
                                        val targetIp = activeTargetIp
                                        val targetDisplayName = activeTargetDisplayLabel
                                            ?: targetIp // Ensure targetDisplayName has a fallback

                                        if (targetIp != null && targetDisplayName != null) {
                                            // Prevent multiple call attempts if already calling or in a call
                                            if (activeCallData != null && (activeCallData?.state == CallState.OUTGOING || activeCallData?.state == CallState.ACTIVE)) {
                                                Log.w(
                                                    "CallFeature",
                                                    "Already in an outgoing or active call. Ignoring new attempt."
                                                )
                                                Toast.makeText(
                                                    localContext,
                                                    "Already in a call.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Log.i(
                                                    "CallFeature",
                                                    "Attempting to initiate CALL to $targetDisplayName ($targetIp)..."
                                                )

                                                // 1. Find a free UDP port for THIS device's audio
                                                val myAudioUdpPort =
                                                    findFreeUdpPort(tag = "MainScreen_UDP") // Use the utility
                                                if (myAudioUdpPort == -1) {
                                                    Log.e(
                                                        "CallFeature",
                                                        "Failed to find a free UDP port. Cannot initiate call."
                                                    )
                                                    Toast.makeText(
                                                        localContext,
                                                        "Error: Network port for audio unavailable.",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                    // Reset gesture states here too if call cannot proceed
                                                    isCallGestureActive = false
                                                    callIconVisible = false
                                                    canInitiateCall = false
                                                    dragYOffset = 0f
                                                    return@detectDragGestures // or continue to reset states below
                                                }

                                                val newCallId = UUID.randomUUID().toString()
                                                // Update UI immediately to "Calling..."
                                                // The activeCallData will be more formally set by the service later,
                                                // but this provides immediate user feedback.
                                                activeCallData = ActiveCallSession(
                                                    newCallId,
                                                    targetDisplayName,
                                                    targetIp,
                                                    CallState.OUTGOING,
                                                    localAudioUdpPort = myAudioUdpPort
                                                )
                                                isCallActive = true
                                                currentCallStatusText =
                                                    "Calling $targetDisplayName..."
                                                outgoingCallId =
                                                    newCallId // Track the ID of the call we are initiating

                                                // 2. Send an Intent to MessageService to handle the call initiation
                                                val serviceIntent = Intent(
                                                    localContext,
                                                    MessageService::class.java
                                                ).apply {
                                                    action =
                                                        MessageService.ACTION_INITIATE_OUTGOING_CALL // Define this action in MessageService
                                                    putExtra(
                                                        MessageService.EXTRA_CALL_ID_SERVICE,
                                                        newCallId
                                                    )
                                                    putExtra(
                                                        MessageService.EXTRA_TARGET_IP_SERVICE,
                                                        targetIp
                                                    )
                                                    putExtra(
                                                        MessageService.EXTRA_TARGET_USERNAME_SERVICE,
                                                        targetDisplayName
                                                    )
                                                    putExtra(
                                                        MessageService.EXTRA_MY_USERNAME_SERVICE,
                                                        currentUsername
                                                    ) // Your username
                                                    putExtra(
                                                        MessageService.EXTRA_LOCAL_UDP_PORT_SERVICE,
                                                        myAudioUdpPort
                                                    ) // Your chosen UDP port
                                                }
                                                localContext.startService(serviceIntent)
                                                Log.i(
                                                    "CallFeature",
                                                    "Sent intent to MessageService to initiate call $newCallId with my UDP port $myAudioUdpPort"
                                                )
                                            }
                                        } else {
                                            Toast.makeText(
                                                localContext,
                                                "No target selected to call.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            Log.w(
                                                "CallFeature",
                                                "Call initiation attempted without a target IP or display name."
                                            )
                                        }
                                    }
                                    // Reset gesture states
                                    isCallGestureActive = false
                                    callIconVisible = false
                                    canInitiateCall = false
                                    dragYOffset = 0f
                                },
                                onDragCancel = {
                                    Log.d("CallGesture", "Drag Cancel")
                                    isCallGestureActive = false
                                    callIconVisible = false
                                    canInitiateCall = false
                                    dragYOffset = 0f
                                }
                            )
                        },
                    shape = RoundedCornerShape(
                        topStart = 0.dp,
                        topEnd = 0.dp,
                        bottomStart = if (showIncomingCallBanner || isCallActive) 0.dp else 12.dp,
                        bottomEnd = if (showIncomingCallBanner || isCallActive) 0.dp else 12.dp,
                    ),
                    color = TopBGColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Button(
                            onClick = { if (!isCallGestureActive) showSettingsScreen = true },
                            modifier = Modifier
                                .padding(0.dp)
                                .width(60.dp)
                                .height(60.dp),
                            shape = RoundedCornerShape(
                                topStart = 0.dp,
                                topEnd = 0.dp,
                                bottomStart = 12.dp,
                                bottomEnd = 0.dp
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0x00000000),
                                contentColor = TextColor
                            ),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                "≡",
                                fontSize = 40.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                        ExposedDropdownMenuBox(
                            expanded = ipDropdownExpanded && !isCallGestureActive,
                            onExpandedChange = {
                                ipDropdownExpanded = !ipDropdownExpanded
                                Log.d(
                                    "Dropdown",
                                    "Expanded changed to: $ipDropdownExpanded"
                                ) // Add log
                            },
                            modifier = Modifier.weight(1f)
                        )
                        {
                            TextField(
                                value = activeTargetDisplayLabel,
                                onValueChange = { /* Read-only */ },
                                readOnly = true,
                                label = {
                                    Text(
                                        text = activeTargetIp
                                            ?: "Select Target IP", // Use activeTargetIp
                                        color = TextColor.copy(alpha = 0.7f)
                                    )
                                },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                        expanded = ipDropdownExpanded
                                    )
                                },
                                colors = ExposedDropdownMenuDefaults.textFieldColors(
                                    focusedTextColor = TextColor,
                                    unfocusedTextColor = TextColor,
                                    disabledTextColor = TextColor,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = TopBGColor,
                                    cursorColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    focusedLabelColor = TextColor.copy(alpha = 0.7f),
                                    unfocusedLabelColor = TextColor.copy(alpha = 0.7f),
                                    focusedTrailingIconColor = TextColor,
                                    unfocusedTrailingIconColor = TextColor
                                ),
                                modifier = Modifier
                                    .menuAnchor() // <<<<----- ADD THIS MODIFIER
                                    .fillMaxWidth()
                                    .height(60.dp),
                                textStyle = TextStyle(fontSize = 18.sp, color = TextColor),
                                singleLine = true
                            )

                            ExposedDropdownMenu(
                                expanded = ipDropdownExpanded,
                                onDismissRequest = { ipDropdownExpanded = false },
                                modifier = Modifier.background(Color(0xFF333333))
                            ) {
                                if (savedTargets.isEmpty()) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "No saved IPs. Add one in settings.",
                                                color = TextColor.copy(alpha = 0.7f)
                                            )
                                        },
                                        onClick = { ipDropdownExpanded = false },
                                        enabled = false
                                    )
                                }
                                savedTargets.forEach { target ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    target.username ?: "Unknown",
                                                    color = TextColor,
                                                    fontSize = 16.sp
                                                )
                                                if (target.username != null && target.username != "Unknown") { // Only show IP if username is known & not "Unknown"
                                                    Text(
                                                        target.ipAddress,
                                                        color = TextColor.copy(alpha = 0.7f),
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            activeTargetIp = target.ipAddress
                                            activeTargetDisplayLabel =
                                                target.username ?: target.ipAddress
                                            ipDropdownExpanded = false
                                            // When a target is selected, update its lastUsed time
                                            UserTargetStorage.addOrUpdateTarget(
                                                context,
                                                target.ipAddress,
                                                target.username
                                            )
                                            // Reload to reflect sort order if needed, though addOrUpdateTarget handles sorting
                                            val reloadedTargets =
                                                UserTargetStorage.loadTargets(context)
                                            savedTargets.clear()
                                            savedTargets.addAll(reloadedTargets.sortedByDescending { it.lastUsed })
                                        },
                                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                    )
                                }
                            }
                        }
                    }
                }
                Log.d("MainScreenComposable", "Recomposing: showIncomingCallBanner = $showIncomingCallBanner, incomingCallBannerInfo = $incomingCallBannerInfo, activeCallData = $activeCallData, isCallActive = $isCallActive, currentCallStatusText = '$currentCallStatusText'")
                when {
                    // Condition 1: Show Incoming Call Banner
                    showIncomingCallBanner && incomingCallBannerInfo != null -> {
                        Log.d("MainScreenComposable", "CONDITION 1 MET (Incoming Banner): For ${incomingCallBannerInfo!!.peerUsername}. Full state: showIncomingCallBanner = $showIncomingCallBanner, incomingCallBannerInfo = $incomingCallBannerInfo, activeCallData = $activeCallData, isCallActive = $isCallActive, currentCallStatusText = '$currentCallStatusText'")
                        IncomingCallUI( // Make sure this Composable is defined and imported
                            callerInfo = "${incomingCallBannerInfo!!.peerUsername} (${incomingCallBannerInfo!!.peerIpAddress})",
                            onAcceptCall = {
                                val callToAccept = incomingCallBannerInfo ?: return@IncomingCallUI
                                val myAudioUdpPort = findFreeUdpPort(tag = "MainScreen_AcceptUDP")

                                if (myAudioUdpPort == -1) {
                                    Toast.makeText(context, "Error: Network port unavailable.", Toast.LENGTH_LONG).show()
                                    // Optionally send REJECT if port finding fails and then hide banner
                                    sendCallProtocolMessage(
                                        ipAddress = callToAccept.peerIpAddress,
                                        port = Port,
                                        username = currentUsername,
                                        key = Key,
                                        messageType = MessageServer.TYPE_CALL_REJECT,
                                        callId = callToAccept.callId
                                    )
                                    showIncomingCallBanner = false
                                    incomingCallBannerInfo = null
                                    return@IncomingCallUI
                                }

                                Log.i("MainScreen", "Call ACCEPTED via UI: ID ${callToAccept.callId}. My UDP port: $myAudioUdpPort")

                                // Send ACCEPT message
                                sendCallProtocolMessage(
                                    ipAddress = callToAccept.peerIpAddress,
                                    port = Port,
                                    username = currentUsername,
                                    key = Key,
                                    messageType = MessageServer.TYPE_CALL_ACCEPT,
                                    callId = callToAccept.callId,
                                    audioUdpPort = myAudioUdpPort
                                )

                                // Update local state for ActiveCallUi
                                activeCallData = ActiveCallSession(
                                    callId = callToAccept.callId,
                                    peerUsername = callToAccept.peerUsername,
                                    peerIpAddress = callToAccept.peerIpAddress,
                                    state = CallState.ACTIVE,
                                    peerAudioUdpPort = callToAccept.peerAudioUdpPort,
                                    localAudioUdpPort = myAudioUdpPort
                                )
                                isCallActive = true // This will now trigger the ActiveCallUi in the next composition
                                currentCallStatusText = "On call with ${callToAccept.peerUsername}"

                                // Hide incoming banner FIRST, then other state changes
                                showIncomingCallBanner = false
                                incomingCallBannerInfo = null

                                // Inform MessageService
                                val serviceIntent = Intent(context, MessageService::class.java).apply {
                                    action = MessageService.ACTION_CALL_ACCEPTED_BY_USER
                                    putExtra(MessageService.EXTRA_CALL_ID_SERVICE, callToAccept.callId)
                                    putExtra(MessageService.EXTRA_CALLER_USERNAME_SERVICE, callToAccept.peerUsername)
                                    putExtra(MessageService.EXTRA_CALLER_IP_SERVICE, callToAccept.peerIpAddress)
                                    putExtra(MessageService.EXTRA_PEER_UDP_PORT_SERVICE, callToAccept.peerAudioUdpPort)
                                    putExtra(MessageService.EXTRA_LOCAL_UDP_PORT_SERVICE, myAudioUdpPort)
                                    putExtra(MessageService.EXTRA_MY_USERNAME_SERVICE, currentUsername)
                                }
                                context.startService(serviceIntent)
                            },
                            onRejectCall = {
                                val callToReject = incomingCallBannerInfo ?: return@IncomingCallUI
                                Log.i("MainScreen", "Call REJECTED via UI: ID ${callToReject.callId}")

                                sendCallProtocolMessage(
                                    ipAddress = callToReject.peerIpAddress,
                                    port = Port,
                                    username = currentUsername,
                                    key = Key,
                                    messageType = MessageServer.TYPE_CALL_REJECT,
                                    callId = callToReject.callId
                                )

                                // Inform MessageService
                                val serviceIntent = Intent(context, MessageService::class.java).apply {
                                    action = MessageService.ACTION_CALL_REJECTED_BY_USER
                                    putExtra(MessageService.EXTRA_CALL_ID_SERVICE, callToReject.callId)
                                }
                                context.startService(serviceIntent)

                                showIncomingCallBanner = false
                                incomingCallBannerInfo = null
                                currentCallStatusText = "Call rejected"
                                coroutineScope.launch {
                                    delay(2000)
                                    if (currentCallStatusText == "Call rejected" && !isCallActive && !showIncomingCallBanner) { // Check state before clearing
                                        currentCallStatusText = ""
                                    }
                                }
                            }
                        )
                    }

                    // Condition 2: Show Active Call UI (for outgoing or connected calls)
                    isCallActive && activeCallData != null -> {
                        Log.d("MainScreenComposable", "CONDITION MET: Showing ActiveCallUi for ${activeCallData?.peerUsername} - State: ${activeCallData?.state}. Full state: showIncomingCallBanner = $showIncomingCallBanner, incomingCallBannerInfo = $incomingCallBannerInfo, activeCallData = $activeCallData, isCallActive = $isCallActive, currentCallStatusText = '$currentCallStatusText'")
                        ActiveCallUi( // This is your existing Composable for active/outgoing calls
                            callerInfo = "${activeCallData?.peerUsername ?: "Unknown"} (${activeCallData?.peerIpAddress ?: "Unknown"})",
                            callStatusText = currentCallStatusText, // e.g., "Calling User..." or "On call with User"
                            onEndCallClicked = {
                                activeCallData?.let { session ->
                                    Log.i("CallFeature", "User ending call: ID ${session.callId}")
                                    sendCallProtocolMessage(
                                        ipAddress = session.peerIpAddress,
                                        port = Port,
                                        username = currentUsername,
                                        key = Key,
                                        messageType = MessageServer.TYPE_CALL_END,
                                        callId = session.callId
                                    )

                                    // Inform MessageService to stop audio and clean up
                                    val serviceIntent = Intent(context, MessageService::class.java).apply {
                                        action = MessageService.ACTION_CALL_ENDED_BY_USER
                                        putExtra(MessageService.EXTRA_CALL_ID_SERVICE, session.callId)
                                    }
                                    context.startService(serviceIntent)

                                    isCallActive = false // This will hide ActiveCallUi
                                    currentCallStatusText = "Call ended"
                                    coroutineScope.launch {
                                        delay(1500)
                                        // Only clear if no new call started and this was the call that ended
                                        if (activeCallData?.callId == session.callId || (activeCallData == null && !isCallActive && !showIncomingCallBanner)) {
                                            currentCallStatusText = ""
                                        }
                                    }
                                    activeCallData = null
                                    outgoingCallId = null // Clear outgoing call ID as well
                                }
                            }
                        )
                    }
                    else -> {
                        Log.d("MainScreenComposable", "NO UI CONDITION MET: Default content or empty. Full state: showIncomingCallBanner = $showIncomingCallBanner, incomingCallBannerInfo = $incomingCallBannerInfo, activeCallData = $activeCallData, isCallActive = $isCallActive, currentCallStatusText = '$currentCallStatusText'")
                        // Your default screen content when no call UI is active (which is the LazyColumn etc.)
                    }
                    // Condition 3: (Optional) Show brief status messages like "Call ended", "Call rejected"
                    // if currentCallStatusText is not empty AND neither call UI is active.
                    // This is helpful if you want these messages to persist for a moment even after the main call UIs are gone.
                    /*
                    currentCallStatusText.isNotBlank() && !isCallActive && !showIncomingCallBanner -> {
                        Surface( // A simple surface to display these transient messages
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min) // Adjust as needed
                                .background(TopBGColor.copy(alpha = 0.90f)), // Or some other subtle background
                            shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = currentCallStatusText,
                                    color = TextColor.copy(alpha = 0.8f),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                    */
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )
                {
                    items(messages, key = { it.id }) { message ->
                        when (message) {
                            is UiDisplayMessage.Text -> {
                                val hasBeenAnimated = animatedMessageIds.value.contains(message.id)

                                val targetAlpha = if (hasBeenAnimated) 1f else 0f
                                val initialTargetOffsetX = if (message.isSentByMe) 50f else -50f
                                val targetOffsetX = if (hasBeenAnimated) 0f else initialTargetOffsetX

                                // Animate alpha
                                val animatedAlpha by animateFloatAsState(
                                    targetValue = targetAlpha,
                                    animationSpec = tween(durationMillis = 400, delayMillis = 100, easing = QuinticOutEasing), // Adjust duration/delay
                                    label = "messageAlpha"
                                )

                                // Animate offset X
                                val animatedOffsetX by animateFloatAsState(
                                    targetValue = targetOffsetX,
                                    animationSpec = tween(durationMillis = 400, delayMillis = 100, easing = QuinticOutEasing), // Adjust duration/delay
                                    label = "messageOffsetX"
                                )

                                LaunchedEffect(key1 = message.id, key2 = hasBeenAnimated) {
                                    if (!hasBeenAnimated) {
                                        animatedMessageIds.value = animatedMessageIds.value + message.id
                                    }
                                }
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = if (message.isSentByMe) Alignment.CenterEnd else Alignment.CenterStart
                                ) {
                                    // 2. Inner Box for the message bubble's content, to which animation is applied
                                    Box(
                                        modifier = Modifier
                                            .graphicsLayer {
                                                alpha = animatedAlpha
                                                translationX = animatedOffsetX
                                            }
                                    ) {
                                        // Your existing Column structure for the message bubble
                                        Column(
                                            // No need for horizontalAlignment here, parent Box handles it.
                                            // Or keep it if you have multiple items inside this column that need it.
                                            horizontalAlignment = if (message.isSentByMe) Alignment.End else Alignment.Start,
                                            modifier = Modifier
                                                .padding(vertical = 4.dp)
                                                .fillMaxWidth(0.9f) // Message bubble is 90% of the space given by Alignment.CenterEnd/Start
                                        ) {
                                            Text(
                                                text = message.sender,
                                                color = TextColor.copy(alpha = 0.8f),
                                                fontSize = 18.sp,
                                                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp)
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = MessageBGColor,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = message.content,
                                                    color = TextColor,
                                                    fontSize = 18.sp,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            is UiDisplayMessage.ImageDisplay -> {
                                val alignment =
                                    if (message.isSentByMe) Alignment.CenterEnd else Alignment.CenterStart
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = alignment
                                ) {
                                    Column(
                                        horizontalAlignment = if (message.isSentByMe) Alignment.End else Alignment.Start,
                                        modifier = Modifier
                                            .padding(vertical = 4.dp)
                                            .fillMaxWidth(0.65f)
                                    ) {
                                        Text(
                                            text = message.sender,
                                            color = TextColor.copy(alpha = 0.8f),
                                            fontSize = 18.sp,
                                            modifier = Modifier.padding(
                                                start = 4.dp,
                                                end = 4.dp,
                                                bottom = 2.dp
                                            )
                                        )
                                        Image(
                                            bitmap = message.bitmap.asImageBitmap(),
                                            contentDescription = "Image from ${message.sender}",
                                            modifier = Modifier
                                                .sizeIn(maxHeight = 200.dp)
                                                .background(
                                                    MessageBGColor,
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .padding(6.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable {
                                                    imageToPreview = message.bitmap
                                                },
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                }
                            }

                            is UiDisplayMessage.SystemLog -> {
                                Text(
                                    message.log,
                                    color = DisabledTextColor,
                                    fontSize = 12.sp /* ... */
                                ) // Global const
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.padding(horizontal = 0.dp, vertical = 2.dp),
                ) {
                    Surface(
                        modifier = Modifier
                            .padding(0.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = TopBGColor
                    ) {
                        Row(
                            modifier = Modifier.padding(0.dp),
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            bitmapForSending?.let { currentPreviewBitmap ->
                                Image(
                                    bitmap = currentPreviewBitmap.asImageBitmap(),
                                    contentDescription = "Selected image",
                                    modifier = Modifier
                                        .height(100.dp)
                                        .padding(6.dp)
                                        .clickable(
                                            onClick = {
                                                bitmapForSending = null
                                                imageUriForSending = null
                                            }
                                        )
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Button(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier
                            .width(60.dp)
                            .defaultMinSize(minHeight = 60.dp),
                        shape = RoundedCornerShape(
                            topStart = 12.dp,
                            topEnd = 0.dp,
                            bottomStart = if (!isKeyboardOpen) 12.dp else 0.dp,
                            bottomEnd = 0.dp
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TopBGColor,
                            contentColor = TextColor
                        )
                    ) {
                        Text(
                            "+",
                            fontSize = 30.sp,
                            color = TextColor,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Message...") },
                        modifier = Modifier
                            .padding(0.dp)
                            .weight(1f)
                            .defaultMinSize(minHeight = 60.dp),
                        shape = RoundedCornerShape(0.dp),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = TextColor,
                            unfocusedTextColor = TextColor,
                            focusedContainerColor = TopBGColor,
                            unfocusedContainerColor = TopBGColor,
                            focusedIndicatorColor = Color(0xFFFFFFFF),
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Color(0xFF8800FF),
                            focusedPlaceholderColor = Color(0xFF888888),
                            unfocusedPlaceholderColor = Color(0xFF888888)
                        ),
                        textStyle = TextStyle(
                            fontSize = 18.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                    )
                    Button(
                        onClick = {
                            val targetIpForSending = activeTargetIp
                            if (targetIpForSending != null && targetIpForSending.isNotBlank()) {
                                val imageToSend = bitmapForSending
                                val textMessageContent = inputText.trim()

                                var messageSent =
                                    false // Flag to clear input only if something was actually sent

                                if (imageToSend != null) {
                                    messages.add(
                                        UiDisplayMessage.ImageDisplay(
                                            imageToSend,
                                            currentUsername,
                                            true
                                        )
                                    )

                                    sendImageToServerProtocol( // Assuming this function exists and is correctly defined elsewhere
                                        ipAddress = targetIpForSending,
                                        port = Port,
                                        bitmap = imageToSend,
                                        username = currentUsername,
                                        key = Key
                                    )
                                    bitmapForSending =
                                        null // Clear selected image state after processing
                                    imageUriForSending = null
                                }
                                if (textMessageContent.isNotBlank()) {
                                    messages.add(
                                        UiDisplayMessage.Text(
                                            textMessageContent,
                                            currentUsername,
                                            true
                                        )
                                    )
                                    sendTextToServerProtocol(
                                        ipAddress = targetIpForSending,
                                        port = Port,
                                        message = textMessageContent,
                                        username = currentUsername,
                                        key = Key
                                    )
                                }
                                inputText = ""
                            }
                        },
                        modifier = Modifier
                            .padding(0.dp)
                            //.width(85.dp)
                            .height(60.dp),
                        shape = RoundedCornerShape(
                            topStart = 0.dp,
                            topEnd = 12.dp,
                            bottomStart = 0.dp,
                            bottomEnd = if (!isKeyboardOpen) 12.dp else 0.dp,
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TopBGColor,
                            contentColor = TextColor
                        )
                    ) {
                        Text(
                            "Send",
                            fontSize = 18.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }
                if (!isKeyboardOpen) {
                    Spacer(modifier = Modifier.height(24.dp))
                }

            }
        }
    }
}

@Composable
fun FullscreenImagePreview(
    bitmap: Bitmap?,
    onDismiss: () -> Unit,
    onDownload: (Bitmap) -> Unit // Callback to trigger download
) {
    if (bitmap == null) return // Don't show if no bitmap

    Dialog(onDismissRequest = onDismiss) { // Use a Dialog for fullscreen-like behavior
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null, // No ripple effect
                    onClick = onDismiss
                ), // Click outside image to dismiss
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Fullscreen Image Preview",
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit // Fit the image within bounds
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { onDownload(bitmap) },
                    colors = ButtonDefaults.buttonColors(containerColor = TopBGColor)
                ) {
                    Text("Download Image", color = TextColor)
                }
            }
        }
    }
}
