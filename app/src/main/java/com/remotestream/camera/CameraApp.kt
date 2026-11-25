package com.remotestream.camera

import android.app.Application
import org.webrtc.PeerConnectionFactory

class CameraApp : Application() {
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
