package com.remotestream.camera.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.remotestream.camera.data.model.ConnectedViewerInfo
import com.remotestream.camera.data.model.SessionRequestEvent
import com.remotestream.camera.data.model.StreamState
import com.remotestream.camera.data.network.SocketSignalingClient
import com.remotestream.camera.webrtc.WebRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.*

/**
 * Application-scoped singleton that owns SocketSignalingClient and WebRtcManager.
 * Keeping this outside ViewModel ensures swiping the Activity from Recents
 * does NOT disconnect the camera from the signaling server.
 */
class StreamSessionManager(private val context: Context) :
    SocketSignalingClient.SignalingEventListener,
    WebRtcManager.WebRtcEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("streamer_prefs", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString("device_id", null)
        ?: ("cam_" + System.currentTimeMillis().toString(36)).also {
            prefs.edit().putString("device_id", it).apply()
        }

    val pairingCode: String = prefs.getString("pairing_code", null)
        ?: generatePairingCode().also {
            prefs.edit().putString("pairing_code", it).apply()
        }

    val deviceName: String = Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL

    private val _serverUrl = MutableStateFlow(
        prefs.getString("server_url", "https://camera-app-backend-q7p5.onrender.com")
            ?: "https://camera-app-backend-q7p5.onrender.com"
    )
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _streamState = MutableStateFlow(StreamState.IDLE)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    private val _isConnectedToSignaling = MutableStateFlow(false)
    val isConnectedToSignaling: StateFlow<Boolean> = _isConnectedToSignaling.asStateFlow()

    private val _pendingConsentRequest = MutableStateFlow<SessionRequestEvent?>(null)
    val pendingConsentRequest: StateFlow<SessionRequestEvent?> = _pendingConsentRequest.asStateFlow()

    private val _connectedViewer = MutableStateFlow<ConnectedViewerInfo?>(null)
    val connectedViewer: StateFlow<ConnectedViewerInfo?> = _connectedViewer.asStateFlow()

    private val _qrCodeBitmap = MutableStateFlow<Bitmap?>(null)
    val qrCodeBitmap: StateFlow<Bitmap?> = _qrCodeBitmap.asStateFlow()

    private val _trustedViewersCount = MutableStateFlow(
        prefs.getStringSet("trusted_viewers", emptySet())?.size ?: 0
    )
    val trustedViewersCount: StateFlow<Int> = _trustedViewersCount.asStateFlow()

    private var signalingClient: SocketSignalingClient? = null
    private var webRtcManager: WebRtcManager? = null
    private val rootEglBase = EglBase.create()
    private var iceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    init {
        generateQrBitmap()
        initWebRtc()
    }

    private fun initWebRtc() {
        webRtcManager = WebRtcManager(context, rootEglBase.eglBaseContext, this)
    }

    fun setServerUrl(url: String) {
        val trimmed = url.trim().trimEnd('/')
        _serverUrl.value = trimmed
        prefs.edit().putString("server_url", trimmed).apply()
        reconnectSignaling()
    }

    fun ensureConnectedAndServiceRunning() {
        CameraStreamingService.startStandby(context)
        connectIfNeeded()
    }

    fun connectIfNeeded() {
        if (signalingClient == null || !_isConnectedToSignaling.value) {
            reconnectSignaling()
        }
    }

    fun reconnectSignaling() {
        signalingClient?.disconnect()
        signalingClient = SocketSignalingClient(_serverUrl.value, deviceId, deviceName, pairingCode, this)
        signalingClient?.connect()
    }

    private fun generatePairingCode(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun generateQrBitmap() {
        scope.launch(Dispatchers.Default) {
            try {
                val qrData = """{"type":"cam_stream","code":"$pairingCode","deviceId":"$deviceId","server":"${_serverUrl.value}"}"""
                val writer = QRCodeWriter()
                val matrix = writer.encode(qrData, BarcodeFormat.QR_CODE, 512, 512)
                val w = matrix.width; val h = matrix.height
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                for (x in 0 until w) {
                    for (y in 0 until h) {
                        bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
                    }
                }
                _qrCodeBitmap.value = bmp
            } catch (e: Exception) {
                Log.e(TAG, "QR generation error", e)
            }
        }
    }

    fun approveStream(request: SessionRequestEvent, rememberForever: Boolean = false) {
        scope.launch {
            if (_streamState.value == StreamState.STREAMING) {
                // Clean up previous session first so new dashboard login connects cleanly
                webRtcManager?.stopStreaming()
                initWebRtc()
            }

            if (rememberForever) {
                val existing = prefs.getStringSet("trusted_viewers", emptySet())?.toMutableSet() ?: mutableSetOf()
                existing.add(request.viewerDeviceId)
                existing.add(request.viewerName)
                prefs.edit().putStringSet("trusted_viewers", existing).apply()
                _trustedViewersCount.value = existing.size
            }

            _pendingConsentRequest.value = null
            _streamState.value = StreamState.STREAMING
            _connectedViewer.value = ConnectedViewerInfo(
                viewerId = request.viewerDeviceId,
                viewerName = request.viewerName,
                sessionId = request.sessionId
            )

            try {
                CameraStreamingService.start(context, request.viewerName)
                webRtcManager?.startLocalMediaCapture(isFrontFacing = false)
                webRtcManager?.createPeerConnection(iceServers)
                signalingClient?.sendConsentDecision(request.sessionId, request.viewerDeviceId, approved = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start streaming", e)
                stopStreaming()
            }
        }
    }

    fun rejectStream(request: SessionRequestEvent) {
        _pendingConsentRequest.value = null
        signalingClient?.sendConsentDecision(request.sessionId, request.viewerDeviceId, approved = false)
        if (_streamState.value == StreamState.AWAITING_CONSENT) {
            _streamState.value = StreamState.IDLE
        }
    }

    fun revokeAllTrustedViewers() {
        prefs.edit().remove("trusted_viewers").apply()
        _trustedViewersCount.value = 0
        stopStreaming()
    }

    fun stopStreaming() {
        scope.launch {
            if (_streamState.value == StreamState.IDLE && _connectedViewer.value == null) return@launch
            val current = _connectedViewer.value
            _streamState.value = StreamState.IDLE
            _connectedViewer.value = null
            _pendingConsentRequest.value = null

            if (current != null) {
                signalingClient?.endStream(current.sessionId, current.viewerId)
            }
            webRtcManager?.stopStreaming()
            CameraStreamingService.startStandby(context)
            initWebRtc()
        }
    }

    // Signaling Callbacks
    override fun onConnected() { _isConnectedToSignaling.value = true }
    override fun onDisconnected() { _isConnectedToSignaling.value = false }
    override fun onRegistered(iceServers: List<PeerConnection.IceServer>) {
        if (iceServers.isNotEmpty()) this.iceServers = iceServers
    }

    override fun onStreamRequested(request: SessionRequestEvent) {
        val trusted = prefs.getStringSet("trusted_viewers", emptySet()) ?: emptySet()
        if (trusted.contains(request.viewerDeviceId) || trusted.contains(request.viewerName)) {
            Log.d(TAG, "Auto-approving trusted viewer: ${request.viewerName}")
            approveStream(request, rememberForever = false)
        } else {
            _pendingConsentRequest.value = request
            _streamState.value = StreamState.AWAITING_CONSENT
        }
    }

    override fun onOfferReceived(sdp: SessionDescription, sessionId: String, senderDeviceId: String) {
        scope.launch {
            webRtcManager?.handleRemoteOffer(sdp) { answer ->
                signalingClient?.sendAnswer(senderDeviceId, sessionId, answer)
            }
        }
    }

    override fun onAnswerReceived(sdp: SessionDescription, sessionId: String, senderDeviceId: String) {
        scope.launch { webRtcManager?.handleRemoteAnswer(sdp) }
    }

    override fun onIceCandidateReceived(candidate: IceCandidate, sessionId: String, senderDeviceId: String) {
        scope.launch { webRtcManager?.addRemoteIceCandidate(candidate) }
    }

    override fun onSwitchCameraRequested() {
        scope.launch { webRtcManager?.switchCamera() }
    }

    override fun onStreamEnded(message: String) {
        stopStreaming()
    }

    override fun onError(message: String) {
        Log.e(TAG, "Signaling error: $message")
    }

    // WebRTC Callbacks
    override fun onIceCandidate(candidate: IceCandidate) {
        val current = _connectedViewer.value ?: return
        signalingClient?.sendIceCandidate(current.viewerId, current.sessionId, candidate)
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
        if (newState == PeerConnection.IceConnectionState.FAILED ||
            newState == PeerConnection.IceConnectionState.CLOSED) {
            stopStreaming()
        }
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        if (newState == PeerConnection.PeerConnectionState.FAILED ||
            newState == PeerConnection.PeerConnectionState.CLOSED) {
            stopStreaming()
        }
    }

    companion object {
        private const val TAG = "StreamSessionManager"
    }
}
