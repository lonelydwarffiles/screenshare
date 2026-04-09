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
 *
 * When a session password is set the broadcaster generates a random salt,
 * derives an AES-256-GCM key via PBKDF2(password, salt), and sends the salt
 * in the `create` message.  The server stores the salt and relays it in the
 * `joined` response so the viewer can derive the identical key.  All
 * subsequent offer / answer / ice_candidate payloads are encrypted so the
 * relay server cannot read or tamper with the WebRTC negotiation.
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

    /** Password stored by [joinSession] until the server returns the salt in `joined`. */
    private var pendingPassword: String? = null
    /** Non-null when a password is in use; encrypts/decrypts signaling payloads. */
    private var sessionCrypto: SessionCrypto? = null

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

                "joined"          -> {
                    // Viewer side: initialise crypto from the salt relayed by the server.
                    val saltHex = json.get("salt")?.takeIf { !it.isJsonNull }?.asString
                    val pass    = pendingPassword
                    if (!saltHex.isNullOrBlank() && !pass.isNullOrBlank()) {
                        sessionCrypto = SessionCrypto(pass, SessionCrypto.saltFromHex(saltHex))
                    }
                    pendingPassword = null
                    listener.onSessionJoined(json.get("roomId").asString)
                }

                "viewer_joined"   -> listener.onViewerJoined()
                "viewer_left"     -> listener.onViewerLeft()

                "offer"           -> {
                    val sdpString = decryptSignalingPayload(json) ?: return
                    listener.onOfferReceived(
                        SessionDescription(SessionDescription.Type.OFFER, sdpString)
                    )
                }

                "answer"          -> {
                    val sdpString = decryptSignalingPayload(json) ?: return
                    listener.onAnswerReceived(
                        SessionDescription(SessionDescription.Type.ANSWER, sdpString)
                    )
                }

                "ice_candidate"   -> {
                    val crypto = sessionCrypto
                    if (crypto != null && json.has("iv") && json.has("ct")) {
                        // Encrypted ICE: ct decrypts to the candidate JSON object.
                        val plain = crypto.decrypt(
                            SessionCrypto.EncryptedPayload(
                                json.get("iv").asString,
                                json.get("ct").asString,
                            )
                        )
                        val cObj = gson.fromJson(plain, JsonObject::class.java)
                        listener.onIceCandidateReceived(
                            IceCandidate(
                                cObj.get("sdpMid").asString,
                                cObj.get("sdpMLineIndex").asInt,
                                cObj.get("candidate").asString,
                            )
                        )
                    } else {
                        val cObj = json.getAsJsonObject("candidate")
                        listener.onIceCandidateReceived(
                            IceCandidate(
                                cObj.get("sdpMid").asString,
                                cObj.get("sdpMLineIndex").asInt,
                                cObj.get("candidate").asString,
                            )
                        )
                    }
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
     * Decrypts an offer/answer payload when crypto is active, or falls back to
     * reading the plain `sdp` field.  Returns null and logs on failure.
     */
    private fun decryptSignalingPayload(json: JsonObject): String? {
        val crypto = sessionCrypto
        return if (crypto != null && json.has("iv") && json.has("ct")) {
            try {
                val plain = crypto.decrypt(
                    SessionCrypto.EncryptedPayload(
                        json.get("iv").asString,
                        json.get("ct").asString,
                    )
                )
                // ct decrypts to {"sdp":"..."} — extract the value.
                gson.fromJson(plain, JsonObject::class.java).get("sdp").asString
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt signaling payload", e)
                null
            }
        } else {
            json.get("sdp")?.asString
        }
    }

    /**
     * Creates a 1:1 session on the server with a custom slug.
     *
     * When [password] is non-blank a random salt is generated, a [SessionCrypto]
     * instance is initialised for the broadcaster side, and the salt is included
     * in the `create` message so the server can relay it to the viewer.
     *
     * @param sessionId The session slug chosen by the broadcaster.
     * @param password  Optional PIN; the viewer must supply the same value.
     */
    fun createSession(sessionId: String, password: String? = null) {
        if (!password.isNullOrBlank()) {
            val salt = SessionCrypto.generateSalt()
            sessionCrypto = SessionCrypto(password, salt)
            send(buildMap {
                put("type",   "create")
                put("roomId", sessionId)
                put("password", password)
                put("salt",   SessionCrypto.saltToHex(salt))
            })
        } else {
            send(buildMap {
                put("type",   "create")
                put("roomId", sessionId)
            })
        }
    }

    /**
     * Joins an existing session by slug.
     *
     * [password] is stored temporarily until the server returns the salt in the
     * `joined` response, at which point [SessionCrypto] is initialised.
     *
     * @param sessionId The session slug to join.
     * @param password  PIN for password-protected sessions.
     */
    fun joinSession(sessionId: String, password: String? = null) {
        pendingPassword = password.takeIf { !it.isNullOrBlank() }
        send(buildMap {
            put("type",   "join")
            put("roomId", sessionId)
            if (!password.isNullOrBlank()) put("password", password)
        })
    }

    fun sendOffer(sdp: SessionDescription) {
        val crypto = sessionCrypto
        if (crypto != null) {
            val payload = crypto.encrypt(gson.toJson(mapOf("sdp" to sdp.description)))
            send(mapOf("type" to "offer", "iv" to payload.iv, "ct" to payload.ct))
        } else {
            send(mapOf("type" to "offer", "sdp" to sdp.description))
        }
    }

    fun sendAnswer(sdp: SessionDescription) {
        val crypto = sessionCrypto
        if (crypto != null) {
            val payload = crypto.encrypt(gson.toJson(mapOf("sdp" to sdp.description)))
            send(mapOf("type" to "answer", "iv" to payload.iv, "ct" to payload.ct))
        } else {
            send(mapOf("type" to "answer", "sdp" to sdp.description))
        }
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val crypto = sessionCrypto
        if (crypto != null) {
            val inner = gson.toJson(mapOf(
                "sdpMid"        to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "candidate"     to candidate.sdp,
            ))
            val payload = crypto.encrypt(inner)
            send(mapOf("type" to "ice_candidate", "iv" to payload.iv, "ct" to payload.ct))
        } else {
            send(mapOf(
                "type" to "ice_candidate",
                "candidate" to mapOf(
                    "sdpMid"        to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "candidate"     to candidate.sdp,
                )
            ))
        }
    }

    private fun send(data: Any) {
        webSocket?.send(gson.toJson(data))
    }

    fun disconnect() {
        webSocket?.close(1000, "Goodbye")
        sessionCrypto   = null
        pendingPassword = null
    }

    companion object {
        private const val TAG = "SignalingClient"
    }
}

