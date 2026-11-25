package com.remotestream.camera

import android.app.Application
import com.remotestream.camera.service.StreamSessionManager
import org.webrtc.PeerConnectionFactory

class CameraApp : Application() {

    val sessionManager: StreamSessionManager by lazy {
        StreamSessionManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)
    }

    companion object {
        lateinit var instance: CameraApp
            private set
    }
}
