package com.iimoxi.odi_messanger

import android.content.Context
import android.content.SharedPreferences
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

private const val PREFS_NAME = "com.iimoxi.odi_messanger.AppPreferences"
private const val KEY_USERNAME = "username"

@kotlinx.serialization.Serializable // Make UserTarget serializable
data class UserTarget(
    val ipAddress: String,
    var username: String? = "Unknown", // Default to "Unknown" or null
    var lastUsed: Long = System.currentTimeMillis()
)

object UserTargetStorage {
    private const val TARGETS_PREFS_NAME = "com.iimoxi.odi_messanger.UserTargetPreferences"
    private const val KEY_TARGETS_LIST = "saved_user_targets_list"

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(TARGETS_PREFS_NAME, Context.MODE_PRIVATE)

    fun saveTargets(context: Context, targets: List<UserTarget>) {
        val jsonString = Json.encodeToString(ListSerializer(UserTarget.serializer()), targets)
        getPreferences(context).edit().putString(KEY_TARGETS_LIST, jsonString).apply()
    }

    fun loadTargets(context: Context): MutableList<UserTarget> {
        val jsonString = getPreferences(context).getString(KEY_TARGETS_LIST, null)
        return if (jsonString != null) {
            try {
                Json.decodeFromString(ListSerializer(UserTarget.serializer()), jsonString)
                    .toMutableList()
            } catch (e: Exception) {
                Log.e("UserTargetStorage", "Error decoding targets: ${e.message}", e)
                mutableListOf()
            }
        } else {
            mutableListOf()
        }
    }

    fun removeTarget(context: Context, ipToRemove: String) {
        if (ipToRemove.isBlank()) return
        val currentTargets = loadTargets(context)
        val removed = currentTargets.removeAll { it.ipAddress == ipToRemove }
        if (removed) { // Only save if something was actually removed
            saveTargets(context, currentTargets)
            Log.i("UserTargetStorage", "Removed target with IP: $ipToRemove")
        } else {
            Log.w("UserTargetStorage", "Attempted to remove non-existent target IP: $ipToRemove")
        }
    }

    fun updateUsernameForIp(context: Context, ipAddress: String, username: String) {
        if (ipAddress.isBlank() || username.isBlank()) return
        val currentTargets = loadTargets(context)
        val target = currentTargets.find { it.ipAddress == ipAddress }
        if (target != null) {
            if (target.username != username) { // Only update if different
                target.username = username
                target.lastUsed = System.currentTimeMillis() // Also update lastUsed
                currentTargets.sortByDescending { it.lastUsed }
                saveTargets(context, currentTargets)
                Log.i("UserTargetStorage", "Updated username for $ipAddress to $username")
            }
        } else {
            // If IP not found, you might want to add it as a new target
            addOrUpdateTarget(context, ipAddress, username)
        }
    }

    fun addOrUpdateTarget(context: Context, newIp: String, username: String? = null) {
        if (newIp.isBlank()) return
        val currentTargets = loadTargets(context)
        val existingTarget = currentTargets.find { it.ipAddress == newIp }

        if (existingTarget != null) {
            // Update existing target
            existingTarget.lastUsed = System.currentTimeMillis()
            if (username != null) { // Only update username if provided
                existingTarget.username = username
            }
        } else {
            // Add new target
            currentTargets.add(0, UserTarget(ipAddress = newIp, username = username ?: "Unknown"))
        }
        // Sort by lastUsed descending (most recent first)
        currentTargets.sortByDescending { it.lastUsed }
        saveTargets(context, currentTargets)
    }
}

// Function to save the username
fun saveUsername(context: Context, username: String) {
    val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val editor = prefs.edit()
    editor.putString(KEY_USERNAME, username)
    editor.apply() // Apply asynchronously
}

// Function to load the username
fun loadUsername(context: Context): String? {
    val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_USERNAME, null) // Return null if not found
}

suspend fun saveBitmapToStorage(context: Context, bitmap: Bitmap, displayName: String = "ODI_Image_${System.currentTimeMillis()}.png") {
    withContext(Dispatchers.IO) { // Perform file operations on a background thread
        val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        var imageUri = context.contentResolver.insert(imageCollection, contentValues)

        if (imageUri == null) {
            Log.e("DownloadImage", "Failed to create new MediaStore record.")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save image (URI null)", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }

        try {
            val outputStream: OutputStream? = context.contentResolver.openOutputStream(imageUri)
            outputStream?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 95, stream)) {
                    Log.e("DownloadImage", "Failed to save bitmap.")
                    // Optionally delete the pending entry if compress fails
                    context.contentResolver.delete(imageUri!!, null, null)
                    imageUri = null // Mark as failed
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                if (imageUri != null) { // Only update if not nullified by a failure
                    context.contentResolver.update(imageUri!!, contentValues, null, null)
                }
            }

            withContext(Dispatchers.Main) {
                if (imageUri != null) {
                    Toast.makeText(context, "Image saved to Pictures!", Toast.LENGTH_SHORT).show()
                    Log.i("DownloadImage", "Image saved: $imageUri")
                } else {
                    Toast.makeText(context, "Failed to save image.", Toast.LENGTH_SHORT).show()
                }
            }

        } catch (e: Exception) {
            Log.e("DownloadImage", "Error saving image: ${e.message}", e)
            if (imageUri != null) { // Attempt to clean up if an error occurs
                context.contentResolver.delete(imageUri!!, null, null)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error saving image: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}