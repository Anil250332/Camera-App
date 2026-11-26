package com.remotestream.camera.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*

class WebRtcManager(
    private val context: Context,
    private val eglBaseContext: EglBase.Context,
    private val listener: WebRtcEventListener
) {
    interface WebRtcEventListener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onIceConnectionChange(newState: PeerConnection.IceConnectionState)
        fun onConnectionChange(newState: PeerConnection.PeerConnectionState)
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var connectionGeneration: Long = 0L

    init {
        buildPeerConnectionFactory()
    }

    private fun buildPeerConnectionFactory() {
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBaseContext,
            true, // enableIntelVp8Encoder
            true  // enableH264HighProfile
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        val options = PeerConnectionFactory.Options()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun startLocalMediaCapture(isFrontFacing: Boolean = false) {
        val factory = peerConnectionFactory ?: return

        // 1. Audio setup
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)
        audioTrack?.setEnabled(true)

        // 2. Video capturer setup using Camera2Enumerator
        val cameraEnumerator = Camera2Enumerator(context)
        val deviceNames = cameraEnumerator.deviceNames
        var targetDevice: String? = null

        for (deviceName in deviceNames) {
            if (isFrontFacing && cameraEnumerator.isFrontFacing(deviceName)) {
                targetDevice = deviceName
                break
            }
            if (!isFrontFacing && cameraEnumerator.isBackFacing(deviceName)) {
                targetDevice = deviceName
                break
            }
        }
        if (targetDevice == null && deviceNames.isNotEmpty()) {
            targetDevice = deviceNames[0]
        }

        if (targetDevice != null) {
            videoCapturer = cameraEnumerator.createCapturer(targetDevice, null)
            surfaceTextureHelper = SurfaceTextureHelper.create("WebRtcCaptureThread", eglBaseContext)
            videoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            // Stream at optimized 960x540 at 30fps for ultra-low latency mobile streaming
            videoCapturer?.startCapture(960, 540, 30)

            videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource)
            videoTrack?.setEnabled(true)
            Log.d(TAG, "Local media capture started successfully")
        } else {
            Log.e(TAG, "No suitable camera found on device")
        }
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val gen = ++connectionGeneration
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (gen != connectionGeneration) return
                listener.onIceCandidate(candidate)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                if (gen != connectionGeneration) return
                Log.d(TAG, "ICE connection state: $newState")
                listener.onIceConnectionChange(newState)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (gen != connectionGeneration) return
                Log.d(TAG, "PeerConnection state: $newState")
                listener.onConnectionChange(newState)
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        })

        // Add local video & audio tracks
        val streamIds = listOf("ARDAMS")
        videoTrack?.let { peerConnection?.addTrack(it, streamIds) }
        audioTrack?.let { peerConnection?.addTrack(it, streamIds) }
    }

    fun handleRemoteOffer(offer: SessionDescription, onAnswerCreated: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                val sdpConstraints = MediaConstraints()
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                onAnswerCreated(answer)
                            }
                        }, answer)
                    }
                }, sdpConstraints)
            }
        }, offer)
    }

    fun handleRemoteAnswer(answer: SessionDescription) {
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), answer)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun switchCamera(onDone: (Boolean) -> Unit = {}) {
        val capturer = videoCapturer as? CameraVideoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                Log.d(TAG, "Switched camera. Front facing: $isFrontCamera")
                onDone(isFrontCamera)
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.e(TAG, "Camera switch error: $errorDescription")
            }
        })
    }

    fun restartIce() {
        peerConnection?.restartIce()
    }

    fun stopStreaming() {
        connectionGeneration++
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capturer", e)
        }
        videoCapturer?.dispose()
        videoCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        videoTrack?.dispose()
        videoTrack = null

        videoSource?.dispose()
        videoSource = null

        audioTrack?.dispose()
        audioTrack = null

        audioSource?.dispose()
        audioSource = null

        peerConnection?.close()
        peerConnection = null
        Log.d(TAG, "WebRTC resources fully disposed")
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {
            Log.e(TAG, "SDP onCreateFailure: $error")
        }
        override fun onSetFailure(error: String) {
            Log.e(TAG, "SDP onSetFailure: $error")
        }
    }

    companion object {
        private const val TAG = "WebRtcManager"
    }
}
