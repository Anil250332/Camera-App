package com.remotestream.camera.data.network

import android.util.Log
import com.remotestream.camera.data.model.SessionRequestEvent
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class SocketSignalingClient(
    private val serverUrl: String,
    private val deviceId: String,
    private val deviceName: String,
    private val pairingCode: String,
    private val listener: SignalingEventListener
) {
    interface SignalingEventListener {
        fun onConnected()
        fun onDisconnected()
        fun onRegistered(iceServers: List<PeerConnection.IceServer>)
        fun onStreamRequested(request: SessionRequestEvent)
        fun onOfferReceived(sdp: SessionDescription, sessionId: String, senderDeviceId: String)
        fun onAnswerReceived(sdp: SessionDescription, sessionId: String, senderDeviceId: String)
        fun onIceCandidateReceived(candidate: IceCandidate, sessionId: String, senderDeviceId: String)
        fun onStreamEnded(message: String)
        fun onError(message: String)
    }

    private var socket: Socket? = null

    fun connect() {
        try {
            val options = IO.Options().apply {
                transports = arrayOf("websocket", "polling")
                reconnection = true
                reconnectionAttempts = 10
                reconnectionDelay = 2000
            }
            socket = IO.socket(serverUrl, options)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Socket connected: ${socket?.id()}")
                listener.onConnected()

                // Register camera device on server — include pairingCode so dashboard can find us
                val registerObj = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("deviceName", deviceName)
                    put("deviceType", "camera")
                    put("pairingCode", pairingCode)
                }
                socket?.emit("register-device", registerObj)
            }

            socket?.on("registered") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val iceServersList = mutableListOf<PeerConnection.IceServer>()

                if (data.has("iceServers")) {
                    val arr = data.getJSONArray("iceServers")
                    for (i in 0 until arr.length()) {
                        val serverObj = arr.getJSONObject(i)
                        val uri = serverObj.getString("urls")
                        val builder = PeerConnection.IceServer.builder(uri)
                        if (serverObj.has("username")) {
                            builder.setUsername(serverObj.getString("username"))
                        }
                        if (serverObj.has("credential")) {
                            builder.setPassword(serverObj.getString("credential"))
                        }
                        iceServersList.add(builder.createIceServer())
                    }
                }
                listener.onRegistered(iceServersList)
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Socket disconnected")
                listener.onDisconnected()
            }

            socket?.on("stream-requested") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val req = SessionRequestEvent(
                    sessionId = data.getString("sessionId"),
                    viewerDeviceId = data.getString("viewerDeviceId"),
                    viewerName = data.optString("viewerName", "Viewer Device"),
                    requestedAt = data.optString("requestedAt", "")
                )
                listener.onStreamRequested(req)
            }

            socket?.on("offer") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val sdpObj = data.getJSONObject("sdp")
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(sdpObj.getString("type")),
                    sdpObj.getString("sdp")
                )
                val sessionId = data.getString("sessionId")
                val senderDeviceId = data.getString("senderDeviceId")
                listener.onOfferReceived(sdp, sessionId, senderDeviceId)
            }

            socket?.on("answer") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val sdpObj = data.getJSONObject("sdp")
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(sdpObj.getString("type")),
                    sdpObj.getString("sdp")
                )
                val sessionId = data.getString("sessionId")
                val senderDeviceId = data.getString("senderDeviceId")
                listener.onAnswerReceived(sdp, sessionId, senderDeviceId)
            }

            socket?.on("ice-candidate") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val candObj = data.getJSONObject("candidate")
                val candidate = IceCandidate(
                    candObj.getString("sdpMid"),
                    candObj.getInt("sdpMLineIndex"),
                    candObj.getString("candidate")
                )
                val sessionId = data.getString("sessionId")
                val senderDeviceId = data.getString("senderDeviceId")
                listener.onIceCandidateReceived(candidate, sessionId, senderDeviceId)
            }

            socket?.on("stream-ended") { args ->
                val data = args[0] as? JSONObject
                val msg = data?.optString("message") ?: "Streaming ended by remote viewer."
                listener.onStreamEnded(msg)
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Signaling connection error", e)
            listener.onError(e.message ?: "Failed to connect to signaling server")
        }
    }

    fun sendConsentDecision(sessionId: String, viewerDeviceId: String, approved: Boolean) {
        val obj = JSONObject().apply {
            put("sessionId", sessionId)
            put("viewerDeviceId", viewerDeviceId)
            put("cameraDeviceId", deviceId)
            put("approved", approved)
        }
        socket?.emit("stream-decision", obj)
    }

    fun sendOffer(targetDeviceId: String, sessionId: String, sdp: SessionDescription) {
        val sdpObj = JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }
        val obj = JSONObject().apply {
            put("targetDeviceId", targetDeviceId)
            put("sessionId", sessionId)
            put("sdp", sdpObj)
        }
        socket?.emit("offer", obj)
    }

    fun sendAnswer(targetDeviceId: String, sessionId: String, sdp: SessionDescription) {
        val sdpObj = JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }
        val obj = JSONObject().apply {
            put("targetDeviceId", targetDeviceId)
            put("sessionId", sessionId)
            put("sdp", sdpObj)
        }
        socket?.emit("answer", obj)
    }

    fun sendIceCandidate(targetDeviceId: String, sessionId: String, candidate: IceCandidate) {
        val candObj = JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }
        val obj = JSONObject().apply {
            put("targetDeviceId", targetDeviceId)
            put("sessionId", sessionId)
            put("candidate", candObj)
        }
        socket?.emit("ice-candidate", obj)
    }

    fun endStream(sessionId: String, targetDeviceId: String) {
        val obj = JSONObject().apply {
            put("sessionId", sessionId)
            put("targetDeviceId", targetDeviceId)
        }
        socket?.emit("end-stream", obj)
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }

    companion object {
        private const val TAG = "SocketSignalingClient"
    }
}
