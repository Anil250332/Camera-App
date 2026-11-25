package com.remotestream.camera.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import com.remotestream.camera.CameraApp
import com.remotestream.camera.data.model.ConnectedViewerInfo
import com.remotestream.camera.data.model.SessionRequestEvent
import com.remotestream.camera.data.model.StreamState
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin ViewModel delegating to application-scoped StreamSessionManager
 * so closing/swiping the Activity never drops the background connection.
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = (application as CameraApp).sessionManager

    val deviceId: String get() = manager.deviceId
    val pairingCode: String get() = manager.pairingCode
    val deviceName: String get() = manager.deviceName

    val serverUrl: StateFlow<String> = manager.serverUrl
    val streamState: StateFlow<StreamState> = manager.streamState
    val isConnectedToSignaling: StateFlow<Boolean> = manager.isConnectedToSignaling
    val pendingConsentRequest: StateFlow<SessionRequestEvent?> = manager.pendingConsentRequest
    val connectedViewer: StateFlow<ConnectedViewerInfo?> = manager.connectedViewer
    val qrCodeBitmap: StateFlow<Bitmap?> = manager.qrCodeBitmap
    val trustedViewersCount: StateFlow<Int> = manager.trustedViewersCount

    fun setServerUrl(url: String) = manager.setServerUrl(url)
    fun reconnectSignaling() = manager.ensureConnectedAndServiceRunning()
    fun approveStream(request: SessionRequestEvent, rememberForever: Boolean = false) =
        manager.approveStream(request, rememberForever)
    fun rejectStream(request: SessionRequestEvent) = manager.rejectStream(request)
    fun revokeAllTrustedViewers() = manager.revokeAllTrustedViewers()
    fun stopStreaming() = manager.stopStreaming()
}
