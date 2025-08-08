package com.iimoxi.odi_messanger

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun PermissionsDialog(
    permissions: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val permissionNames = permissions.map {
        when (it) {
            android.Manifest.permission.RECORD_AUDIO -> "Microphone (Record Audio)"
            android.Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
            else -> it
        }
    }.joinToString("\n• ", prefix = "• ")

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(text = "Permissions Required") },
        text = {
            Text(
                text = """
                To function properly, the app needs the following permissions:
                
                $permissionNames
                
                These are required for calling, messaging alerts, and core features.
                """.trimIndent()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm() }) {
                Text("Grant Permissions")
            }
        },
        dismissButton = {
            Button(onClick = { onDismiss() }) {
                Text("Cancel")
            }
        }
    )
}