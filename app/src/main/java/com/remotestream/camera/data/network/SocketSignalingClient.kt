package com.remotestream.camera.data.network

import android.util.Log
import com.remotestream.camera.data.model.SessionRequestEvent
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.io.IOException

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
        fun onSwitchCameraRequested()
        fun onStreamEnded(message: String)
        fun onError(message: String)
    }

    private var socket: Socket? = null
    private val httpClient = OkHttpClient()

    @Volatile
    private var isConnecting: Boolean = false

    private fun syncRoomViaPost(socketId: String?) {
        if (socketId.isNullOrBlank()) return
        try {
            val base = serverUrl.trimEnd('/')
            val payload = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("deviceType", "camera")
                put("socketId", socketId)
                put("room", "camguard_room")
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("$base/api/devices/room/join")
                .post(body)
                .build()
            httpClient.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "Room sync POST failed: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Room sync setup error: ${e.message}")
        }
    }

    fun connect() {
        try {
            isConnecting = true
            val options = IO.Options().apply {
                transports = arrayOf("websocket", "polling")
                upgrade = true
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 1000
                reconnectionDelayMax = 5000
                timeout = 10000
            }
            socket = IO.socket(serverUrl, options)

            socket?.on(Socket.EVENT_CONNECT) {
                isConnecting = false
                val sid = socket?.id()
                Log.i(TAG, "[${System.currentTimeMillis()}] Socket CONNECTED id=$sid deviceId=$deviceId")
                listener.onConnected()

                // Register camera device into shared room & sync socket ID via POST route
                val registerObj = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("deviceName", deviceName)
                    put("deviceType", "camera")
                    put("pairingCode", pairingCode)
                }
                socket?.emit("register-device", registerObj)
                syncRoomViaPost(sid)
            }

            socket?.on("viewer-room-sync") {
                Log.d(TAG, "Viewer joined room — broadcasting camera readiness")
                val readyObj = JSONObject().apply {
                    put("cameraDeviceId", deviceId)
                    put("room", "camguard_room")
                }
                socket?.emit("camera-ready", readyObj)
            }

            socket?.on("socket-replaced") {
                Log.d(TAG, "Previous socket evicted by newer socket in room")
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

            socket?.on(Socket.EVENT_DISCONNECT) { args ->
                isConnecting = false
                val reason = args.firstOrNull()?.toString() ?: "unknown"
                Log.w(TAG, "[${System.currentTimeMillis()}] Socket DISCONNECTED reason=$reason")
                listener.onDisconnected()
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                isConnecting = false
                val err = args.firstOrNull()?.toString() ?: "connect_error"
                Log.w(TAG, "[${System.currentTimeMillis()}] Socket CONNECT_ERROR err=$err")
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

            socket?.on("switch-camera") {
                listener.onSwitchCameraRequested()
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

    fun sendConsentDecision(sessionId: String, viewerDeviceId: String, approved: Boolean, message: String? = null) {
        val obj = JSONObject().apply {
            put("sessionId", sessionId)
            put("viewerDeviceId", viewerDeviceId)
            put("cameraDeviceId", deviceId)
            put("approved", approved)
            if (message != null) put("message", message)
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

    fun isSocketConnected(): Boolean = socket?.connected() == true

    fun isConnectingOrConnected(): Boolean = isConnecting || isSocketConnected()

    fun sendHeartbeat() {
        if (socket?.connected() == true) {
            val obj = JSONObject().apply {
                put("deviceId", deviceId)
                put("ts", System.currentTimeMillis())
            }
            socket?.emit("camera-heartbeat", obj)
        }
    }

    fun disconnect() {
        isConnecting = false
        Log.i(TAG, "[${System.currentTimeMillis()}] Socket manual disconnect() called")
        socket?.disconnect()
        socket?.off()
        socket = null
    }

    companion object {
        private const val TAG = "SocketSignalingClient"
    }
}
