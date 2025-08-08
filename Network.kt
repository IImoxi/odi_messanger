package com.iimoxi.odi_messanger

import android.content.Context
import android.util.Log // Keep standard logs too!
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramSocket

// Sealed class to represent different types of messages
sealed class MessageData {
    abstract val senderUsername: String
    abstract val senderIpAddress: String
    data class Text(
        override val senderUsername: String,
        override val senderIpAddress: String,
        val content: String
    ) : MessageData()

    data class Image(
        override val senderUsername: String,
        override val senderIpAddress: String,
        val bitmap: Bitmap
    ) : MessageData()

    data class CallInitiate(
        override val senderUsername: String,
        override val senderIpAddress: String,
        val callId: String, // Unique ID for the call session
        val audioUdpPort: Int // The UDP port the CALLER will listen on for audio
    ) : MessageData()

    data class CallAccept(
        override val senderUsername: String,
        override val senderIpAddress: String,
        val callId: String,
        val audioUdpPort: Int // The UDP port the ACCEPTOR will listen on for audio
    ) : MessageData()

    data class CallReject(
        override val senderUsername: String,
        override val senderIpAddress: String,
        val callId: String
    ) : MessageData()

    data class CallEnd(
        override val senderUsername: String,
        override val senderIpAddress: String,
        val callId: String
    ) : MessageData()
}


fun findFreeUdpPort(startPort: Int = 10000, endPort: Int = 10100, tag: String = "UDP_PORT"): Int {
    for (port in startPort..endPort) {
        try {
            DatagramSocket(port).use { socket ->
                Log.d(tag, "Found free UDP port: ${socket.localPort}")
                return socket.localPort // Return the successfully bound port
            }
        } catch (e: Exception) {
            // Port likely in use, try next
            Log.v(tag, "UDP Port $port is likely in use: ${e.message}")
        }
    }
    Log.e(tag, "No free UDP port found in range $startPort-$endPort.")
    return -1 // No port found in range
}

fun sendCallProtocolMessage(
    ipAddress: String,
    port: Int, // TCP port for signaling
    username: String,
    key: String, // Assuming this is for future encryption of signaling, not used in example
    messageType: String,
    callId: String,
    audioUdpPort: Int? = null // UDP port, optional
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            Socket(ipAddress, port).use { socket ->
                DataOutputStream(BufferedOutputStream(socket.outputStream)).use { dos ->
                    dos.writeUTF(username)    // 1. Username
                    dos.writeUTF(messageType) // 2. Message Type
                    dos.writeUTF(callId)      // 3. Call ID

                    // Only send UDP port if it's provided and for relevant message types
                    if (audioUdpPort != null &&
                        (messageType == MessageServer.TYPE_CALL_INITIATE || messageType == MessageServer.TYPE_CALL_ACCEPT)) {
                        dos.writeInt(audioUdpPort) // 4. Audio UDP Port
                    }
                    // Add other fields if your protocol has them before flushing

                    dos.flush()
                    Log.i("sendCallProtocol", "Sent '$messageType' for call '$callId' (UDP Port: $audioUdpPort) from '$username' to $ipAddress:$port")
                }
            }
        } catch (e: IOException) {
            Log.e("sendCallProtocol", "IOException sending call message: ${e.message}", e)
            // TODO: Notify UI/Service about the failure to send
        } catch (e: Exception) {
            Log.e("sendCallProtocol", "Exception sending call message: ${e.message}", e)
            // TODO: Notify UI/Service about the failure
        }
    }
}

class MessageServer(
    private val context: Context,
    private val port: Int,
    private val onMessageReceived: (message: MessageData) -> Unit,
    private val scope: CoroutineScope, // Make sure this is CoroutineScope
    private val onBackgroundNotificationRequired: ((sender: String, contentPreview: String?) -> Unit)? = null
) {
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private val clientSockets = mutableListOf<Socket>()
    private val clientOutputStreams = mutableMapOf<Socket, DataOutputStream>()

    companion object {
        const val SERVER_TAG = "MessageServer"
        const val TYPE_TEXT = "TEXT"
        const val TYPE_IMAGE = "IMAGE"
        const val TYPE_CALL_INITIATE = "CALL_INITIATE" // New
        const val TYPE_CALL_ACCEPT = "CALL_ACCEPT"     // New
        const val TYPE_CALL_REJECT = "CALL_REJECT"     // New
        const val TYPE_CALL_END = "CALL_END"         // New

        const val CALL_NOTIFICATION_ID = 2 // Different from foreground service and message notifications
        const val CALL_CHANNEL_ID = "call_channel"
        internal const val NOTIFICATION_ID_OFFSET = 1000
    }

    fun start() {
        if (job?.isActive == true) { // Check if already active
            Log.i(SERVER_TAG, "Server is already running.")
            return
        }
        job = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                Log.i(SERVER_TAG, "[${Thread.currentThread().name}] Server listening on port $port. Entering accept loop...") // Log thread

                while (job?.isActive == true && serverSocket?.isClosed == false) {
                    Log.d(SERVER_TAG, "[${Thread.currentThread().name}] Top of accept loop. isActive: ${job?.isActive}, isClosed: ${serverSocket?.isClosed}. Waiting for accept()...") // ADD THIS
                    try {
                        val clientSocket = serverSocket!!.accept() // Blocking call
                        Log.i(SERVER_TAG, "[${Thread.currentThread().name}] Client accepted: ${clientSocket.inetAddress}. isActive: ${job?.isActive}") // ADD THIS + thread

                        if (job?.isActive == true) { // Re-check isActive after blocking accept() returns
                            Log.i(SERVER_TAG, "Client connected: ${clientSocket.inetAddress}") // Your existing log
                            launch { handleClient(clientSocket) } // Launch client handling in the server's scope
                        } else {
                            Log.i(SERVER_TAG, "[${Thread.currentThread().name}] Job became inactive after accept() returned but before launching handleClient. Closing client socket.")
                            try { clientSocket.close() } catch (e: IOException) { /* Ignore */ }
                            break // Exit the while loop as the job is no longer active
                        }
                    } catch (e: java.net.SocketException) {
                        if (job?.isActive == true && serverSocket?.isClosed == false) {
                            // Only log as an error if the server wasn't supposed to be stopping
                            Log.e(SERVER_TAG, "[${Thread.currentThread().name}] SocketException in server accept loop (server likely NOT stopping yet): ${e.message}", e)
                        } else {
                            Log.i(SERVER_TAG, "[${Thread.currentThread().name}] SocketException in server accept loop (server IS stopping or socket closed): ${e.message}")
                        }
                        break // Exit loop on socket exception
                    } catch (e: Exception) {
                        if (job?.isActive == true) {
                            Log.e(SERVER_TAG, "[${Thread.currentThread().name}] Generic Exception in server accept loop", e)
                        } else {
                            Log.i(SERVER_TAG, "[${Thread.currentThread().name}] Generic Exception in server accept loop (job no longer active)", e)
                        }
                        // Consider if you should break here always or only for specific exceptions
                        // break // For safety, maybe break on any unexpected exception in accept loop
                    }
                }
            } catch (e: java.net.BindException) { // Specific catch for BindException
                Log.e(SERVER_TAG, "[${Thread.currentThread().name}] Server loop error - BindException: Address already in use on port $port", e)
                // No need to call stopSelf from here, MessageService's catch block will handle it
            } catch (e: Exception) {
                if (job?.isActive == true) { // Check before logging
                    Log.e(SERVER_TAG, "[${Thread.currentThread().name}] Server loop error - Outer catch", e)
                } else {
                    Log.i(SERVER_TAG, "[${Thread.currentThread().name}] Server loop error - Outer catch (job no longer active)", e)
                }
            } finally {
                Log.i(SERVER_TAG, "[${Thread.currentThread().name}] Server main loop ended. isActive: ${job?.isActive}, isClosed: ${serverSocket?.isClosed}")
                cleanupSockets()
            }
        }
        Log.i(SERVER_TAG, "MessageServer start method completed.")
    }

    private fun handleClient(clientSocket: Socket) {
        scope.launch(Dispatchers.IO) {
            val clientAddress = clientSocket.inetAddress
            var senderUsername: String? = null

            try {
                val dataInputStream = DataInputStream(BufferedInputStream(clientSocket.getInputStream()))
                // val dataOutputStream = DataOutputStream(BufferedOutputStream(clientSocket.getOutputStream())) // Keep for broadcasting

                senderUsername = dataInputStream.readUTF()
                if (senderUsername.isNullOrBlank()) {
                    Log.w(SERVER_TAG, "Received blank username from $clientAddress. Closing connection.")
                    clientSocket.close()
                    return@launch
                }
                Log.i(SERVER_TAG, "Client connected: $senderUsername@$clientAddress")

                // Add to clientOutputStreams for broadcasting if you still use it
                synchronized(clientOutputStreams) {
                    clientOutputStreams[clientSocket] = DataOutputStream(BufferedOutputStream(clientSocket.getOutputStream()))
                }
                synchronized(clientSockets) {
                    clientSockets.add(clientSocket)
                }

                while (clientSocket.isConnected && !clientSocket.isClosed) {
                    val messageType = dataInputStream.readUTF()
                    val clientIpAddress = clientSocket.inetAddress.hostAddress // Get the IP string
                    var decryptedContentPreview: String? = null // For the notification callback

                    when (messageType) {
                        TYPE_TEXT -> {
                            val encryptedTextContent = dataInputStream.readUTF()
                            val decryptedText = decryptString(encryptedTextContent, Key) // Always decrypt

                            if (decryptedText != null) {
                                Log.d(SERVER_TAG, "Received and decrypted TEXT from $senderUsername: $decryptedText")
                                val textData = MessageData.Text(senderUsername, clientIpAddress, decryptedText)
                                onMessageReceived(textData) // Always call with decrypted data
                                decryptedContentPreview = decryptedText // For notification
                                // showTextNotification is now primarily handled by MessageService based on foreground state
                                // broadcastMessage(textData, clientSocket) // If you still need broadcast
                            } else {
                                Log.e(SERVER_TAG, "Failed to decrypt text from $senderUsername.")
                                // Optionally call onMessageReceived with an error indicator or skip
                            }
                        }
                        TYPE_IMAGE -> {
                            val encryptedImageSize = dataInputStream.readInt()
                            if (encryptedImageSize > 0 && encryptedImageSize < 15 * 1024 * 1024) { // Max 15MB
                                val encryptedImageBytes = ByteArray(encryptedImageSize) // Renamed
                                dataInputStream.readFully(encryptedImageBytes)
                                val decryptedImageBytes = decryptBytes(encryptedImageBytes, Key) // Always decrypt

                                if (decryptedImageBytes != null) {
                                    val bitmap = BitmapFactory.decodeByteArray(decryptedImageBytes, 0, decryptedImageBytes.size)
                                    if (bitmap != null) {
                                        Log.d(SERVER_TAG, "Received and decrypted IMAGE from $senderUsername")
                                        val imageData = MessageData.Image(senderUsername, clientIpAddress, bitmap)
                                        onMessageReceived(imageData) // Always call with decrypted data
                                        decryptedContentPreview = "Image" // For notification
                                        // broadcastMessage for images if needed
                                    } else {
                                        Log.e(SERVER_TAG, "Failed to decode DECRYPTED image from $senderUsername")
                                    }
                                } else {
                                    Log.e(SERVER_TAG, "Failed to DECRYPT image from $senderUsername.")
                                }
                            } else {
                                Log.w(SERVER_TAG, "Received IMAGE with invalid size $encryptedImageSize from $senderUsername")
                                if (encryptedImageSize >= 15 * 1024 * 1024) clientSocket.close()
                            }
                        }
                        TYPE_CALL_INITIATE, TYPE_CALL_ACCEPT -> { // Group types that send audioUdpPort
                            val callId = dataInputStream.readUTF()
                            val audioUdpPort = dataInputStream.readInt() // Read UDP port for these types
                            Log.d(SERVER_TAG, "Received $messageType for call ID $callId from $senderUsername with UDP port $audioUdpPort")
                            val callData = if (messageType == TYPE_CALL_INITIATE) {
                                MessageData.CallInitiate(senderUsername, clientIpAddress, callId, audioUdpPort)
                            } else { // Must be TYPE_CALL_ACCEPT
                                MessageData.CallAccept(senderUsername, clientIpAddress, callId, audioUdpPort)
                            }
                            onMessageReceived(callData)
                        }
                        TYPE_CALL_REJECT, TYPE_CALL_END -> { // Group types that DO NOT send audioUdpPort
                            val callId = dataInputStream.readUTF()
                            // DO NOT read audioUdpPort here
                            Log.d(SERVER_TAG, "Received $messageType for call ID $callId from $senderUsername")
                            val callData = if (messageType == TYPE_CALL_REJECT) {
                                MessageData.CallReject(senderUsername, clientIpAddress, callId)
                            } else { // Must be TYPE_CALL_END
                                MessageData.CallEnd(senderUsername, clientIpAddress, callId)
                            }
                            onMessageReceived(callData)
                        }
                        else -> {
                            Log.w(SERVER_TAG, "Unknown message type '$messageType' from $senderUsername@$clientAddress.")
                            clientSocket.close()
                            break // Exit while loop for this client
                        }
                    }

                    // Signal that a message was processed, MessageService will decide about notification
                    if (senderUsername != null && decryptedContentPreview != null) {
                        onBackgroundNotificationRequired?.invoke(senderUsername, decryptedContentPreview)
                    }
                }
            } catch (e: java.io.EOFException) {
                Log.i(SERVER_TAG, "Client $senderUsername@$clientAddress disconnected (EOF).")
            } catch (e: IOException) {
                if (this@MessageServer.job?.isActive == true && clientSocket.isConnected) {
                    Log.e(SERVER_TAG, "I/O error with client $senderUsername@$clientAddress", e)
                }
            } catch (e: Exception) {
                if (this@MessageServer.job?.isActive == true && senderUsername != null) {
                    Log.e(SERVER_TAG, "Error handling client $senderUsername@$clientAddress", e)
                }
            } finally {
                removeClient(clientSocket)
                Log.i(SERVER_TAG, "Finished handling client $senderUsername@$clientAddress. Socket closed: ${clientSocket.isClosed}")
            }
        }
    }

    private fun removeClient(clientSocket: Socket) {
        synchronized(clientOutputStreams) { // For clientOutputStreams
            clientOutputStreams.remove(clientSocket)
        }
        synchronized(clientSockets) { // For clientSockets list
            clientSockets.remove(clientSocket)
        }
        try {
            if (!clientSocket.isClosed) {
                clientSocket.close() // Ensure it's closed
                Log.i(SERVER_TAG, "Closed client socket ${clientSocket.inetAddress}")
            }
        } catch (e: IOException) {
            Log.e(SERVER_TAG, "Error closing client socket ${clientSocket.inetAddress}", e)
        }
    }

    fun stop() {
        // Log with a stack trace to see who is calling stop()
        Log.w(SERVER_TAG, "MessageServer.stop() CALLED", Exception("StackTrace for MessageServer.stop"))
        job?.cancel() // This should trigger the finally block in the server job
        // DO NOT call cleanupSockets() here directly if it's reliably called in the 'finally' of the server's job.
        // If job is null or already inactive, manual cleanup might be needed, but the primary way is job cancellation.
    }

    private fun cleanupSockets() {
        synchronized(clientSockets) {
            // Close all output streams first
            clientOutputStreams.values.forEach { stream ->
                try {
                    stream.close()
                } catch (e: IOException) {
                    Log.w(SERVER_TAG, "Error closing a client output stream during cleanup: ${e.message}")
                }
            }
            clientOutputStreams.clear()

            // Then close all client sockets
            clientSockets.forEach { socket ->
                try {
                    if (!socket.isClosed) socket.close()
                } catch (e: IOException) {
                    Log.w(SERVER_TAG, "Error closing a client socket during cleanup: ${e.message}")
                }
            }
            clientSockets.clear()
            Log.i(SERVER_TAG, "All client sockets and streams cleaned up.")
        }
    }
}

enum class CallState {
    IDLE,
    OUTGOING,
    INCOMING,
    ACTIVE,
    ENDED,
    REJECTED_BY_PEER,
    REJECTED_BY_ME,
    MISSED,
    TIMED_OUT,
    ERROR
}

data class ActiveCallSession(
    val callId: String,
    val peerUsername: String,
    val peerIpAddress: String,
    var state: CallState,
    var peerAudioUdpPort: Int? = null,
    var localAudioUdpPort: Int? = null
)