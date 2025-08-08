package com.iimoxi.odi_messanger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun SettingsScreen(onBackPressed: () -> Unit,
    // Username
    currentUsername: String,
    onUsernameChange: (String) -> Unit,
    // IP
    currentIp: String,
    onAddNewIp: (String) -> Unit,
    onRemoveIp: (String) -> Unit
) {
    // Username
    var textFieldUsernameValue by remember { mutableStateOf(currentUsername) }
    // IP
    var textFieldIpValue by remember { mutableStateOf(currentIp) }
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Surface(
                modifier = Modifier.height(100.dp).fillMaxWidth(),
                shape = RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp
                ),
                color = TopBGColor
            ) {
                    Row(
                        modifier = Modifier.padding(0.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "Settings",
                            color = TextColor,
                            fontSize = 24.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.padding(0.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = onBackPressed, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextColor
                            )
                        }
                    }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues) // Apply padding from Scaffold
                .padding(16.dp), // Add your own content padding
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
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
                        containerColor = TopBGColor,
                        contentColor = TextColor
                    )
                ) {
                    Text(
                        "Update Username (Click)",
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
                        focusedTextColor = TextColor,
                        unfocusedTextColor = TextColor,
                        focusedContainerColor = Color(0xFF0d0d0d),
                        unfocusedContainerColor = Color(0xFF0d0d0d),
                        focusedIndicatorColor = Color(0xFFFFFFFF),
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color(0xFF8800FF),
                        focusedPlaceholderColor = Color(0xFF888888),
                        unfocusedPlaceholderColor = Color(0xFF888888)
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(
                        imeAction = ImeAction.Done // Specify the action type
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (textFieldUsernameValue.isNotBlank()) {
                                onUsernameChange(textFieldUsernameValue)
                                // Optionally, you could clear focus or hide the keyboard here
                            }
                        }
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
                            onAddNewIp(textFieldIpValue) // This will call the corrected lambda in MainScreen
                            textFieldIpValue = "" // You can clear it if you want
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
                        containerColor = TopBGColor,
                        contentColor = TextColor
                    )
                ) {
                    Text(
                        "Add (Click)",
                        fontSize = 18.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                }
                Button(
                    onClick = {
                        if (textFieldIpValue.isNotBlank()) {
                            onRemoveIp(textFieldIpValue)
                            textFieldIpValue = ""
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
                        containerColor = TopBGColor,
                        contentColor = TextColor
                    )
                ) {
                    Text(
                        "Delete (Click)",
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
                        focusedTextColor = TextColor,
                        unfocusedTextColor = TextColor,
                        focusedContainerColor = Color(0xFF0d0d0d),
                        unfocusedContainerColor = Color(0xFF0d0d0d),
                        focusedIndicatorColor = Color(0xFFFFFFFF),
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color(0xFF8800FF),
                        focusedPlaceholderColor = Color(0xFF888888),
                        unfocusedPlaceholderColor = Color(0xFF888888)
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done // Specify the action type
                    ),
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 18.sp,
                        fontFamily = FontFamily.SansSerif
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (textFieldIpValue.isNotBlank()) {
                                onAddNewIp(textFieldIpValue)
                                textFieldIpValue = "" // Clear after adding
                                // Optionally, you could clear focus or hide the keyboard here
                            }
                        }
                    )
                )
            }
            Text(
                "Change Wallpaper (WIP)",
                fontSize = 18.sp,
                fontFamily = FontFamily.SansSerif
            )
        }
    }
}