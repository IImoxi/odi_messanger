package com.iimoxi.odi_messanger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
fun AppMenuPopup(
    currentIp: String, // Pass the current IP to display initially in TextField
    onIpChange: (String) -> Unit,
    currentUsername: String, // <-- Add this
    onUsernameChange: (String) -> Unit,
    serverRunning: Boolean, // Receive the current state
    onServerRunningChange: (Boolean) -> Unit, // Receive the lambda to change the state
    onDismissRequest: () -> Unit
) {
    var textFieldUsernameValue by remember { mutableStateOf(currentUsername) } // <-- State for Username Text
    var textFieldIpValue by remember { mutableStateOf(currentIp) }
    Popup(
        alignment = Alignment.Center, // Center the popup itself on the screen
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        // This Box acts as the background scrim, making it dismissable on click outside menu.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f)) // Semi-transparent background
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                ),
            contentAlignment = Alignment.Center // Center the actual menu content within this scrim
        ) {
            // This Surface is the actual visible pop-up content area
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .fillMaxHeight(0.6f),
                shape = RoundedCornerShape(12.dp),
                color = TopBGColor
            )
            {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom
                    ) {
                            Button(
                                onClick = {
                                    if (textFieldUsernameValue.isNotBlank()) {
                                        onUsernameChange(textFieldUsernameValue)
                                    }
                                },
                                modifier = Modifier
                                    .padding(0.dp)
                                    .weight(1f)
                                    .height(40.dp),
                                shape = RoundedCornerShape(
                                    topStart = 12.dp,
                                    topEnd = 12.dp,
                                    bottomStart = 0.dp,
                                    bottomEnd = 0.dp
                                ),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BGColor,
                                    contentColor = TextColor
                                )
                            ) {
                                Text(
                                    "Update Username",
                                    fontSize = 18.sp,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }
                    }
                    Row(
                        verticalAlignment = Alignment.Top
                    ) {
                        TextField(
                            value = textFieldUsernameValue,
                            onValueChange = { textFieldUsernameValue = it },
                            placeholder = { Text("New username...") },
                            modifier = Modifier
                                .padding(0.dp)
                                .weight(1f)
                                .height(60.dp),
                            shape = RoundedCornerShape(
                                topStart = 0.dp,
                                topEnd = 0.dp,
                                bottomStart = 12.dp,
                                bottomEnd = 12.dp
                            ),
                            colors = TextFieldDefaults.colors(
                                // ----- Text Color -----
                                focusedTextColor = TextColor,
                                unfocusedTextColor = TextColor,
                                // ----- Container (Background) Color -----
                                focusedContainerColor = BGColor,
                                unfocusedContainerColor = BGColor,
                                // ----- Indicator (Underline) Color -----
                                focusedIndicatorColor = Color(0xFFFFFFFF),
                                unfocusedIndicatorColor = TopBGColor,
                                // ----- Cursor Color -----
                                cursorColor = Color(0xFF8800FF),
                                // ----- Placeholder Color -----
                                focusedPlaceholderColor = Color(0xFF888888),
                                unfocusedPlaceholderColor = Color(0xFF888888)
                            ),
                            textStyle = TextStyle(
                                fontSize = 18.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier
                            .padding(top = 8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (textFieldIpValue.isNotBlank()) {
                                    //addMessageToScreenLocal("You are offline.", messages)
                                    onIpChange(textFieldIpValue) // Call the lambda to update the actual IP
                                }
                            },
                            modifier = Modifier
                                .padding(0.dp)
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(
                                topStart = 12.dp,
                                topEnd = 12.dp,
                                bottomStart = 0.dp,
                                bottomEnd = 0.dp
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BGColor,
                                contentColor = TextColor
                            )
                        ) {
                            Text(
                                "Change IP",
                                fontSize = 18.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.Top
                    ) {
                        TextField(
                            value = textFieldIpValue,
                            onValueChange = { textFieldIpValue = it },
                            placeholder = { Text("Enter IP") },
                            modifier = Modifier
                                .padding(0.dp)
                                .weight(1f)
                                .height(60.dp),
                            shape = RoundedCornerShape(
                                topStart = 0.dp,
                                topEnd = 0.dp,
                                bottomStart = 12.dp,
                                bottomEnd = 12.dp
                            ),
                            colors = TextFieldDefaults.colors(
                                // ----- Text Color -----
                                focusedTextColor = TextColor,
                                unfocusedTextColor = TextColor,
                                // ----- Container (Background) Color -----
                                focusedContainerColor = BGColor,
                                unfocusedContainerColor = BGColor,
                                // ----- Indicator (Underline) Color -----
                                focusedIndicatorColor = Color(0xFFFFFFFF),
                                unfocusedIndicatorColor = TopBGColor,
                                // ----- Cursor Color -----
                                cursorColor = Color(0xFF8800FF),
                                // ----- Placeholder Color -----
                                focusedPlaceholderColor = Color(0xFF888888),
                                unfocusedPlaceholderColor = Color(0xFF888888)
                            ),
                            textStyle = TextStyle(
                                fontSize = 18.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text("Run Local Server:", color = TextColor)
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = serverRunning,
                            onCheckedChange = onServerRunningChange
                        )
                        Text(
                            if (serverRunning) "ON (Port: $Port)" else "OFF",
                            color = TextColor,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .padding(17.dp)
                            .fillMaxSize(), // Fill the Surface
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        //Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onDismissRequest,
                            modifier = Modifier
                                .padding(start = 0.dp)
                                .width(140.dp)
                                .height(60.dp),
                            shape = RoundedCornerShape(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TopBGColor,
                                contentColor = TextColor
                            )
                        ) {
                            Text("Close Menu")
                        }
                        // Add other menu items here
                    }
                }
            }
        }
    }
}