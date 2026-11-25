package com.remotestream.camera.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotestream.camera.data.model.StreamState
import com.remotestream.camera.ui.theme.*
import com.remotestream.camera.ui.viewmodel.CameraViewModel

@Composable
fun MainScreen(viewModel: CameraViewModel) {
    val streamState by viewModel.streamState.collectAsState()
    val isConnected by viewModel.isConnectedToSignaling.collectAsState()
    val connectedViewer by viewModel.connectedViewer.collectAsState()
    val pendingConsent by viewModel.pendingConsentRequest.collectAsState()
    val qrBitmap by viewModel.qrCodeBitmap.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val trustedCount by viewModel.trustedViewersCount.collectAsState()
    var showServerDialog by remember { mutableStateOf(false) }
    var tempUrl by remember { mutableStateOf(serverUrl) }

    // Show consent dialog on top of everything
    pendingConsent?.let { request ->
        ConsentDialog(
            request = request,
            onApproveOnce = { viewModel.approveStream(request, rememberForever = false) },
            onApproveAlways = { viewModel.approveStream(request, rememberForever = true) },
            onReject = { viewModel.rejectStream(request) }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(rememberScrollState())
    ) {
        // ─── Header ───────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(PrimaryBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column {
                    Text("Camera Streamer", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(viewModel.deviceName, fontSize = 11.sp, color = TextSecondary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Connection status dot
                Box(
                    modifier = Modifier.size(10.dp).clip(CircleShape)
                        .background(if (isConnected) AccentGreen else Color(0xFFEF4444))
                        .align(Alignment.CenterVertically)
                )
                IconButton(onClick = { tempUrl = serverUrl; showServerDialog = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Server Settings", tint = TextSecondary)
                }
            }
        }

        // ─── Live Privacy Banner ───────────────────────────────────────
        AnimatedVisibility(visible = streamState == StreamState.STREAMING, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33DC2626))
                    .border(width = 1.dp, color = AccentRed.copy(alpha = 0.4f))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(AccentRed))
                Text(
                    "🔴 CAMERA & MICROPHONE ACTIVE — Streaming live footage to viewer",
                    fontSize = 12.sp, color = AccentRed, fontWeight = FontWeight.SemiBold
                )
            }
        }

        AnimatedVisibility(visible = streamState == StreamState.AWAITING_CONSENT, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33F59E0B))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFF59E0B))
                Text("Viewer requesting stream — Approve or Deny below", fontSize = 12.sp, color = Color(0xFFF59E0B))
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ─── Main Card ───────────────────────────────────────────────
        if (streamState == StreamState.STREAMING && connectedViewer != null) {
            // Active streaming view
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Live indicator ring
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .border(3.dp, AccentRed, CircleShape)
                            .background(Color(0x1ADC2626)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null, tint = AccentRed, modifier = Modifier.size(38.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Live Streaming", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Connected to: ${connectedViewer!!.viewerName}", fontSize = 13.sp, color = TextSecondary)
                    Text(
                        "Viewer ID: ${connectedViewer!!.viewerId.take(16)}...",
                        fontSize = 11.sp, color = Color.Gray, fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { viewModel.stopStreaming() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Icon(Icons.Default.StopCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Stop Streaming", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        } else {
            // Standby / Pairing view
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Pairing Code", fontSize = 13.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(6.dp))

                    // Big pairing code
                    Text(
                        text = viewModel.pairingCode,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = PrimaryBlue,
                        letterSpacing = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // QR Code
                    qrBitmap?.let { bmp ->
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(12.dp)
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Pairing QR",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Dashboard पर यह code डालें या QR scan करें",
                        fontSize = 13.sp, color = TextSecondary, textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Stream शुरू होने से पहले आपकी permission ली जाएगी",
                        fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ─── Info Card ────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark.copy(alpha = 0.6f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Status:", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        text = when {
                            streamState == StreamState.STREAMING -> "🔴 Streaming"
                            streamState == StreamState.AWAITING_CONSENT -> "⏳ Approval Needed"
                            isConnected -> "🟢 Online (Standby)"
                            else -> "⚫ Offline"
                        },
                        fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium
                    )
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Device ID:", fontSize = 12.sp, color = Color.Gray)
                    Text(viewModel.deviceId, fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Server:", fontSize = 12.sp, color = Color.Gray)
                    Text(serverUrl, fontSize = 11.sp, color = PrimaryBlue, fontFamily = FontFamily.Monospace)
                }
                if (trustedCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Trusted Viewers: Active", fontSize = 12.sp, color = AccentGreen, fontWeight = FontWeight.Medium)
                        TextButton(
                            onClick = { viewModel.revokeAllTrustedViewers() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Revoke Trust", fontSize = 11.sp, color = AccentRed)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }

    // ─── Server URL Dialog ───────────────────────────────────────────
    if (showServerDialog) {
        AlertDialog(
            onDismissRequest = { showServerDialog = false },
            containerColor = CardDark,
            title = { Text("Signaling Server URL", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Render deploy URL या local IP डालें। Format: http://IP:5000",
                        fontSize = 12.sp, color = TextSecondary
                    )
                    OutlinedTextField(
                        value = tempUrl, onValueChange = { tempUrl = it },
                        label = { Text("Server URL") }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.setServerUrl(tempUrl); showServerDialog = false }) {
                    Text("Save & Reconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerDialog = false }) { Text("Cancel") }
            }
        )
    }
}
