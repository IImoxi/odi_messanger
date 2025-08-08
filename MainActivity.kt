package com.iimoxi.odi_messanger

import android.app.Application
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log // For logging

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets // For UTF_8
import java.io.PrintWriter
import java.net.Socket
import androidx.compose.runtime.mutableStateOf // Add this if not already present
import androidx.compose.runtime.getValue // Add this if not already present
import androidx.compose.runtime.setValue // Add this if not already present
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.BufferedOutputStream
import java.io.IOException // For network exceptions
import java.util.UUID
import androidx.compose.ui.Modifier
import android.app.NotificationChannel
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import android.Manifest
import android.content.Intent
import android.net.Uri

// ^ Imports ^

const val Port = 7865
val Tag = "DefaultClient"
var clientSocket: Socket? = null

var Username = "Default"
val Key = "௹D1f11XD!amM0xiY00.byte/0x32௹"

val BGColor = Color(0xFF000000)
val TopBGColor = Color(0xDD0b0b0b)
val TextColor = Color(0xFFDDDDDD)
val DisabledTextColor = Color(0xFF888888)
val MessageBGColor = Color(0x33DDDDDD)
val CodeColor = Color(0xFF00FF00)

private var showPermissionsDialog by mutableStateOf(false)
private var missingPermissions = listOf<String>()

// ^ Variables ^

class MainActivity : ComponentActivity() {
    companion object {
        var isActivityInForeground = false
            private set
    }
    private var usernameState by mutableStateOf("Default")
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i("PERMISSION", "Notification permission granted")
            } else {
                Log.w("PERMISSION", "Notification permission denied")
            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        val savedUsername = loadUsername(applicationContext)
        if (savedUsername != null) {
            usernameState = savedUsername
            Username = savedUsername
        }
        startMessagingService(applicationContext)
        enableEdgeToEdge()
        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(id = R.drawable.sky_bg),
                    contentDescription = "Application background",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Scaffold(containerColor = Color.Transparent) { innerPadding ->
                    MainScreen(
                        currentUsername = usernameState,
                        onUsernameChange = { newUsername ->
                            usernameState = newUsername
                            Username = newUsername
                            saveUsername(applicationContext, newUsername)
                        }
                    )
                }

                if (showPermissionsDialog) {
                    PermissionsDialog(
                        permissions = missingPermissions,
                        onConfirm = {
                            showPermissionsDialog = false
                            requestMultiplePermissionsLauncher.launch(missingPermissions.toTypedArray())
                        },
                        onDismiss = { showPermissionsDialog = false }
                    )
                }
            }
        }
    }

    private fun checkAndPromptPermissions() {
        val requiredPermissions = mutableListOf<String>()

        // Always required
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.RECORD_AUDIO)
        }

        // Notifications (only for Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (requiredPermissions.isNotEmpty()) {
            missingPermissions = requiredPermissions
            showPermissionsDialog = true
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "onNewIntent: ${intent?.action}")
        if (intent?.action == MessageService.ACTION_SHOW_CALL_UI &&
            intent.getBooleanExtra(MessageService.EXTRA_IS_INCOMING_CALL_ACCEPTED, false)) {
            // The broadcast receiver in MainScreen should handle updating the UI.
            // This is just to confirm MainActivity received the intent.
            val callId = intent.getStringExtra(MessageService.EXTRA_CALL_ID_SERVICE)
            val peerUsername = intent.getStringExtra(MessageService.EXTRA_CALLER_USERNAME_SERVICE)
            Log.i("MainActivity", "Launched from accepted call. Call ID: $callId, Peer: $peerUsername. MainScreen receiver should handle UI.")
            // You might want to navigate to a specific chat screen if your app structure supports it
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "App is in foreground (Lifecycle)")
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "App is in background (Lifecycle)")
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private val requestSinglePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i("PERMISSION", "Notification permission granted") // Or adapt message based on which permission it was
            } else {
                Log.w("PERMISSION", "Notification permission denied")
            }
        }
    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions: Map<String, Boolean> ->
            permissions.entries.forEach {
                Log.i("PERMISSION_MULTI", "Permission: ${it.key}, Granted: ${it.value}")
                // Add specific handling here if needed, e.g., if RECORD_AUDIO was denied.
            }
        }
    private fun askNotificationPermission() {
        // This is only necessary for API level 33 and higher.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.i("PERMISSION", "Notification permission already granted")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Log.w("PERMISSION", "Showing rationale for notification permission")
                    // Use the single permission launcher
                    requestSinglePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    Log.i("PERMISSION", "Requesting notification permission")
                    // Use the single permission launcher
                    requestSinglePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}

class MyApplication : Application() {

    companion object {
        const val CHANNEL_ID = "message_channel"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Messages" // User-visible name of the channel.
            val descriptionText = "Notifications for new messages" // User-visible description.
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system.
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

fun startMessagingService(context: Context) {
    val intent = Intent(context, MessageService::class.java).apply {
        action = MessageService.ACTION_START_SERVICE
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

fun stopMessagingService(context: Context) {
    val intent = Intent(context, MessageService::class.java).apply {
        action = MessageService.ACTION_STOP_SERVICE
    }
    context.startService(intent)
}

fun convertBitmap(bitmap: Bitmap): ByteArray {
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) // Or JPEG
    return stream.toByteArray()
}

fun sendLocalMessage(message: String, messagesList: MutableList<String>) {
    messagesList.add(message)
}

suspend fun sendMessageToServer(
    ipAddress: String,
    port: Int,
    message: String
): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val socket = Socket(ipAddress, port)
            socket.use {
                val writer = PrintWriter(OutputStreamWriter(it.outputStream, StandardCharsets.UTF_8), true)
                writer.println(message)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

sealed class UiDisplayMessage(val id: String = UUID.randomUUID().toString()) {
    data class Text(val content: String, val sender: String, val isSentByMe: Boolean) : UiDisplayMessage()
    data class ImageDisplay(val bitmap: Bitmap, val sender: String, val isSentByMe: Boolean) : UiDisplayMessage()
    data class SystemLog(val log: String) : UiDisplayMessage()
}

fun sendTextToServerProtocol(
    ipAddress: String,
    port: Int,
    message: String,
    username: String,
    key: String,
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val encryptedContent = encryptString(message, key) // Encrypt the actual message content
            if (encryptedContent == null) {
                Log.e(Tag, "Encryption failed for text message content.")
                return@launch
            }

            Socket(ipAddress, port).use { socket ->
                DataOutputStream(BufferedOutputStream(socket.outputStream)).use { dos ->
                    // CORRECTED PROTOCOL ORDER:
                    Log.d("SEND_TEXT_PROTOCOL", "Sending username: '$username'") // Log before sending
                    dos.writeUTF(username)                     // 1. Username
                    Log.d("SEND_TEXT_PROTOCOL", "Sending type: '${MessageServer.TYPE_TEXT}'") // Log
                    dos.writeUTF(MessageServer.TYPE_TEXT)      // 2. Message Type ("TEXT")
                    Log.d("SEND_TEXT_PROTOCOL", "Sending encrypted content...") // Log
                    dos.writeUTF(encryptedContent)             // 3. Encrypted Message Content
                    dos.flush()
                    Log.i(Tag, "Sent text from $username to $ipAddress:$port")
                }
            }
        } catch (e: IOException) {
            Log.e(Tag, "IOException sending text: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(Tag, "Exception sending text: ${e.message}", e)
        }
    }
}

// New function for sending images using the TEXT/IMAGE protocol
fun sendImageToServerProtocol(
    ipAddress: String,
    port: Int,
    bitmap: Bitmap,
    username: String, // Pass username
    key: String,      // Pass key for encryption
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val rawImageBytes = convertBitmap(bitmap) // From your existing function
            if (rawImageBytes.isEmpty()) {
                return@launch
            }

            // Encrypt the raw image bytes
            val encryptedImageBytes = encryptBytes(rawImageBytes, key)

            if (encryptedImageBytes == null) {
                Log.e(Tag, "Image encryption failed, not sending.")
                return@launch
            }

            Socket(ipAddress, port).use { socket ->
                DataOutputStream(BufferedOutputStream(socket.outputStream)).use { dos ->
                    dos.writeUTF(username)                // 1. Username (plain text)
                    dos.writeUTF(MessageServer.TYPE_IMAGE)  // 2. Message Type ("IMAGE")
                    dos.writeInt(encryptedImageBytes.size)  // 3. Length of ENCRYPTED image byte array
                    dos.write(encryptedImageBytes)          // 4. ENCRYPTED image bytes
                    dos.flush()

                    Log.i(Tag, "Sent encrypted image (${encryptedImageBytes.size} bytes) to $ipAddress:$port from $username")
                }
            }
        } catch (e: IOException) {
            Log.e(Tag, "IOException sending image: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(Tag, "Exception sending image: ${e.message}", e)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MessagingScreenPreview() {
    MainScreen(
        currentUsername = "",
        onUsernameChange = {}
    )
}