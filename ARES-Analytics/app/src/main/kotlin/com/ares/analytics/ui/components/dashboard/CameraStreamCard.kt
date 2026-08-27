package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.CameraStreamIntent
import com.ares.analytics.viewmodel.CameraStreamViewModel

@Composable
fun CameraStreamCard(
    properties: Map<String, String>,
    onPropertiesChanged: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(properties["streamUrl"]) {
        CameraStreamViewModel(properties["streamUrl"], scope)
    }
    val state by viewModel.state.collectAsState()

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.onIntent(CameraStreamIntent.Disconnect)
            viewModel.close()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AresSurface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (state.isConnected) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        contentDescription = "Camera",
                        tint = if (state.isConnected) AresCyan else AresTextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "Camera Stream",
                        color = AresTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (state.isConnected) {
                        Text("• LIVE", color = AresGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    } else if (state.errorMessage != null) {
                        Text("• ERROR", color = AresRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                IconButton(
                    onClick = { viewModel.onIntent(CameraStreamIntent.SetConfiguring(!state.isConfiguring)) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Configure", tint = AresTextSecondary, modifier = Modifier.size(16.dp))
                }
            }

            HorizontalDivider(color = AresBorder, thickness = 1.dp)

            // Content
            Box(
                modifier = Modifier.fillMaxSize().background(AresBackground),
                contentAlignment = Alignment.Center
            ) {
                val currentFrame = state.currentFrame
                val currentError = state.errorMessage
                when {
                    state.isConfiguring -> {
                        // Config Panel
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("MJPEG Stream URL", color = AresTextSecondary, fontSize = 12.sp)
                            OutlinedTextField(
                                value = state.streamUrl,
                                onValueChange = { viewModel.onIntent(CameraStreamIntent.UpdateStreamUrl(it)) },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = AresTextPrimary),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AresCyan,
                                    unfocusedBorderColor = AresBorder,
                                    focusedContainerColor = AresSurfaceElevated,
                                    unfocusedContainerColor = AresSurfaceElevated
                                ),
                                placeholder = { Text("http://10.0.0.2:1181/stream.mjpg", color = AresTextTertiary) }
                            )
                            Button(
                                onClick = {
                                    viewModel.onIntent(CameraStreamIntent.Connect)
                                    onPropertiesChanged(mapOf("streamUrl" to state.streamUrl))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Connect", color = AresBackground, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    currentFrame != null -> {
                        // Video Feed
                        Image(
                            bitmap = currentFrame,
                            contentDescription = "Live Video",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        // Loading or Error State
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (currentError != null) {
                                Icon(Icons.Default.VideocamOff, contentDescription = null, tint = AresRed, modifier = Modifier.size(32.dp))
                                Text(currentError, color = AresRed, fontSize = 12.sp)
                            } else {
                                CircularProgressIndicator(color = AresCyan, modifier = Modifier.size(32.dp))
                                Text("Connecting to stream...", color = AresTextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
