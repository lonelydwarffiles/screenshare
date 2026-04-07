package com.screenshare

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Handles WebSocket-based signaling between broadcaster and viewer.
 *
 * Strict 1:1 model — one broadcaster, one viewer per session.
 *
 * Message protocol (JSON):
 *  Outgoing: create | join | offer | answer | ice_candidate
 *  Incoming: created | joined | viewer_joined | viewer_left |
 *            offer | answer | ice_candidate | broadcast_ended | rejected | error
 */
class SignalingClient(
    private val serverUrl: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onSessionCreated(sessionId: String)
        fun onSessionJoined(sessionId: String)
        fun onViewerJoined()
        fun onViewerLeft()
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onBroadcastEnded()
        /** Viewer was turned away: not_found | wrong_password | session_full */
        fun onRejected(reason: String)
        fun onError(message: String)
    }

    /** Convenience no-op adapter so subclasses only override what they need. */
    open class ListenerAdapter : Listener {
        override fun onConnected() {}
        override fun onSessionCreated(sessionId: String) {}
        override fun onSessionJoined(sessionId: String) {}
        override fun onViewerJoined() {}
        override fun onViewerLeft() {}
        override fun onOfferReceived(sdp: SessionDescription) {}
        override fun onAnswerReceived(sdp: SessionDescription) {}
        override fun onIceCandidateReceived(candidate: IceCandidate) {}
        override fun onBroadcastEnded() {}
        override fun onRejected(reason: String) {}
        override fun onError(message: String) {}
    }

    private val gson = Gson()
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                listener.onError(t.message ?: "Connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed ($code): $reason")
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            when (json.get("type").asString) {
                "created"         -> listener.onSessionCreated(json.get("roomId").asString)
                "joined"          -> listener.onSessionJoined(json.get("roomId").asString)
                "viewer_joined"   -> listener.onViewerJoined()
                "viewer_left"     -> listener.onViewerLeft()
                "offer"           -> listener.onOfferReceived(
                    SessionDescription(SessionDescription.Type.OFFER, json.get("sdp").asString)
                )
                "answer"          -> listener.onAnswerReceived(
                    SessionDescription(SessionDescription.Type.ANSWER, json.get("sdp").asString)
                )
                "ice_candidate"   -> {
                    val c = json.getAsJsonObject("candidate")
                    listener.onIceCandidateReceived(
                        IceCandidate(
                            c.get("sdpMid").asString,
                            c.get("sdpMLineIndex").asInt,
                            c.get("candidate").asString
                        )
                    )
                }
                "broadcast_ended" -> listener.onBroadcastEnded()
                "rejected"        -> listener.onRejected(
                    json.get("reason")?.asString ?: "unknown"
                )
                "error"           -> listener.onError(json.get("message").asString)
                else              -> Log.w(TAG, "Unknown message: $text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
        }
    }

    /**
     * Creates a 1:1 session on the server with a custom slug.
     *
     * @param sessionId The session slug chosen by the broadcaster.
     * @param password  Optional PIN; the viewer must supply the same value.
     */
    fun createSession(sessionId: String, password: String? = null) = send(buildMap {
        put("type", "create")
        put("roomId", sessionId)
        if (!password.isNullOrBlank()) put("password", password)
    })

    /**
     * Joins an existing session by slug.
     *
     * @param sessionId The session slug to join.
     * @param password  PIN for password-protected sessions.
     */
    fun joinSession(sessionId: String, password: String? = null) = send(buildMap {
        put("type", "join")
        put("roomId", sessionId)
        if (!password.isNullOrBlank()) put("password", password)
    })

    fun sendOffer(sdp: SessionDescription) =
        send(mapOf("type" to "offer", "sdp" to sdp.description))

    fun sendAnswer(sdp: SessionDescription) =
        send(mapOf("type" to "answer", "sdp" to sdp.description))

    fun sendIceCandidate(candidate: IceCandidate) = send(
        mapOf(
            "type" to "ice_candidate",
            "candidate" to mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "candidate" to candidate.sdp
            )
        )
    )

    private fun send(data: Any) {
        webSocket?.send(gson.toJson(data))
    }

    fun disconnect() {
        webSocket?.close(1000, "Goodbye")
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}
