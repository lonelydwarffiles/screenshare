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
 * Message protocol (JSON):
 *  Outgoing: create | join | offer | answer | ice_candidate | accept | reject
 *  Incoming: created | joined | viewer_joined | viewer_count | viewer_knock |
 *            knock_sent | knock_accepted | knock_rejected |
 *            offer | answer | ice_candidate | broadcast_ended | rejected | error
 */
class SignalingClient(
    private val serverUrl: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onRoomCreated(roomId: String)
        fun onRoomJoined(roomId: String)
        fun onViewerJoined()
        fun onViewerCount(count: Int)
        /** A viewer is knocking (broadcaster side). [viewerId] is opaque; [displayName] for display. */
        fun onViewerKnock(viewerId: String, displayName: String)
        /** The viewer's knock was sent and is waiting for approval. */
        fun onKnockSent()
        /** Broadcaster accepted this viewer's knock. */
        fun onKnockAccepted()
        /** Broadcaster rejected this viewer's knock. */
        fun onKnockRejected()
        fun onOfferReceived(sdp: SessionDescription)
        fun onAnswerReceived(sdp: SessionDescription)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onBroadcastEnded()
        /** Viewer was turned away (wrong password, room full, or broadcast ended while pending). */
        fun onRejected(reason: String)
        fun onError(message: String)
    }

    /** Convenience no-op adapter so subclasses only override what they need. */
    open class ListenerAdapter : Listener {
        override fun onConnected() {}
        override fun onRoomCreated(roomId: String) {}
        override fun onRoomJoined(roomId: String) {}
        override fun onViewerJoined() {}
        override fun onViewerCount(count: Int) {}
        override fun onViewerKnock(viewerId: String, displayName: String) {}
        override fun onKnockSent() {}
        override fun onKnockAccepted() {}
        override fun onKnockRejected() {}
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
                "created"        -> listener.onRoomCreated(json.get("roomId").asString)
                "joined"         -> listener.onRoomJoined(json.get("roomId").asString)
                "viewer_joined"  -> listener.onViewerJoined()
                "viewer_count"   -> listener.onViewerCount(json.get("count").asInt)
                "viewer_knock"   -> listener.onViewerKnock(
                    json.get("viewerId").asString,
                    json.get("displayName").asString,
                )
                "knock_sent"     -> listener.onKnockSent()
                "knock_accepted" -> listener.onKnockAccepted()
                "knock_rejected" -> listener.onKnockRejected()
                "offer"          -> listener.onOfferReceived(
                    SessionDescription(SessionDescription.Type.OFFER, json.get("sdp").asString)
                )
                "answer"         -> listener.onAnswerReceived(
                    SessionDescription(SessionDescription.Type.ANSWER, json.get("sdp").asString)
                )
                "ice_candidate"  -> {
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
     * Creates a room on the server.
     *
     * @param password   Optional PIN; viewers must supply the same value.
     * @param maxViewers Maximum simultaneous viewers (0 = unlimited).
     * @param useKnock   If true, viewers must knock and be accepted.
     * @param hidden     If true, the room is excluded from the discovery page.
     */
    fun createRoom(
        roomId: String,
        password: String? = null,
        maxViewers: Int = 0,
        useKnock: Boolean = false,
        hidden: Boolean = false,
    ) = send(buildMap {
        put("type", "create")
        put("roomId", roomId)
        if (!password.isNullOrBlank()) put("password", password)
        if (maxViewers > 0) put("maxViewers", maxViewers)
        if (useKnock) put("useKnock", true)
        if (hidden) put("hidden", true)
    })

    /**
     * Joins an existing room.
     *
     * @param password    PIN to supply for password-protected rooms.
     * @param displayName Optional display name shown to the broadcaster for knock-to-enter.
     */
    fun joinRoom(
        roomId: String,
        password: String? = null,
        displayName: String? = null,
    ) = send(buildMap {
        put("type", "join")
        put("roomId", roomId)
        if (!password.isNullOrBlank()) put("password", password)
        if (!displayName.isNullOrBlank()) put("displayName", displayName)
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

    /** Broadcaster accepts a pending viewer (knock-to-enter flow). */
    fun acceptViewer(viewerId: String) = send(mapOf("type" to "accept", "viewerId" to viewerId))

    /** Broadcaster rejects a pending viewer (knock-to-enter flow). */
    fun rejectViewer(viewerId: String) = send(mapOf("type" to "reject", "viewerId" to viewerId))

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
