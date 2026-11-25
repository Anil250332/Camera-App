package com.remotestream.camera.data.model

data class DeviceInfo(
    val id: String,
    val name: String,
    val type: String = "camera",
    val pairingCode: String,
    val status: String = "offline"
)

data class SessionRequestEvent(
    val sessionId: String,
    val viewerDeviceId: String,
    val viewerName: String,
    val requestedAt: String
)

enum class StreamState {
    IDLE,
    AWAITING_CONSENT,
    STREAMING,
    ERROR
}

data class ConnectedViewerInfo(
    val viewerId: String,
    val viewerName: String,
    val sessionId: String,
    val connectedAt: Long = System.currentTimeMillis()
)
