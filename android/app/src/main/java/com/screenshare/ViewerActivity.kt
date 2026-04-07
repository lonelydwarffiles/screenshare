package com.screenshare

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.screenshare.databinding.ActivityViewerBinding
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Lets the user enter a room ID (or receive one via deep-link), connects to the
 * signaling server, and renders the received video stream full-screen.
 *
 * Features:
 * - Optional password input for password-protected rooms
 * - Two-way text chat overlay via WebRTC DataChannel
 * - Automatic reconnect with exponential back-off on connection drop
 * - Deep-link support: screenshare://room/<roomId>
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var eglBase: EglBase
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var signalingClient: SignalingClient

    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectDelayMs = RECONNECT_DELAY_MIN_MS
    private var isReconnecting = false
    private var currentRoomId: String = ""
    private var currentServerUrl: String = ""
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eglBase = EglBase.create()

        currentServerUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ScreenShareService.DEFAULT_SERVER_URL

        // Handle deep-link: screenshare://room/1234
        val deepLinkRoomId = intent.data?.takeIf { it.scheme == "screenshare" }?.lastPathSegment
        if (deepLinkRoomId != null) {
            binding.etRoomId.setText(deepLinkRoomId)
        }

        binding.btnConnect.setOnClickListener { attemptConnect() }

        // Chat send
        binding.btnSendChat.setOnClickListener { sendChat() }
        binding.etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendChat(); true } else false
        }
    }

    private fun attemptConnect() {
        val roomId = binding.etRoomId.text?.toString()?.trim() ?: ""
        if (roomId.isEmpty()) {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = getString(R.string.error_invalid_room_id)
            return
        }
        val password = binding.etPassword.text?.toString()?.trim()
        connectToRoom(roomId, currentServerUrl, password)
    }

    private fun connectToRoom(roomId: String, serverUrl: String, password: String?) {
        currentRoomId = roomId
        binding.btnConnect.isEnabled = false
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.status_connecting)

        // Clean up any previous session.
        if (::signalingClient.isInitialized) signalingClient.disconnect()
        if (::webRTCClient.isInitialized) {
            webRTCClient.close()
            binding.svRemote.release()
        }

        webRTCClient = WebRTCClient(this, eglBase, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.FAILED
                ) {
                    scheduleReconnect()
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                if (::signalingClient.isInitialized) signalingClient.sendIceCandidate(candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {
                stream.videoTracks.firstOrNull()?.let { renderVideoTrack(it) }
            }
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {
                // Viewer receives the chat DataChannel created by the broadcaster.
                webRTCClient.attachViewerDataChannel(channel)
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                when (val track = receiver.track()) {
                    is VideoTrack -> renderVideoTrack(track)
                    is AudioTrack -> track.setEnabled(true)
                }
            }
        })
        webRTCClient.initializePeerConnection()
        webRTCClient.initSurfaceView(binding.svRemote)

        webRTCClient.dataChannelListener = WebRTCClient.DataChannelListener { text ->
            runOnUiThread { appendChat("📡 $text") }
        }

        signalingClient = SignalingClient(serverUrl, object : SignalingClient.ListenerAdapter() {
            override fun onConnected() {
                signalingClient.joinRoom(roomId, password)
            }

            override fun onRoomJoined(roomId: String) {
                resetReconnectDelay()
                runOnUiThread {
                    binding.tvStatus.text = getString(R.string.status_waiting_for_stream)
                }
            }

            override fun onKnockSent() {
                runOnUiThread {
                    binding.tvStatus.text = getString(R.string.status_knock_sent)
                }
            }

            override fun onKnockAccepted() {
                runOnUiThread {
                    binding.tvStatus.text = getString(R.string.status_knock_accepted)
                }
            }

            override fun onKnockRejected() {
                runOnUiThread {
                    binding.tvStatus.text = getString(R.string.status_knock_rejected)
                    binding.btnConnect.isEnabled = true
                }
            }

            override fun onOfferReceived(sdp: SessionDescription) {
                webRTCClient.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        webRTCClient.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(sdp: SessionDescription) {
                                webRTCClient.setLocalDescription(
                                    object : SimpleSdpObserver() {}, sdp
                                )
                                signalingClient.sendAnswer(sdp)
                            }
                        })
                    }
                }, sdp)
            }

            override fun onIceCandidateReceived(candidate: IceCandidate) {
                webRTCClient.addIceCandidate(candidate)
            }

            override fun onBroadcastEnded() {
                runOnUiThread { showConnectForm(getString(R.string.status_broadcast_ended)) }
            }

            override fun onRejected(reason: String) {
                val msgRes = when (reason) {
                    "wrong_password" -> R.string.error_wrong_password
                    "room_full"      -> R.string.error_room_full
                    else             -> R.string.error_rejected
                }
                runOnUiThread {
                    showConnectForm(getString(msgRes))
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    binding.tvStatus.text = getString(R.string.error_prefix, message)
                    binding.btnConnect.isEnabled = true
                }
                scheduleReconnect()
            }
        })
        signalingClient.connect()
    }

    private fun renderVideoTrack(track: VideoTrack) {
        runOnUiThread {
            binding.layoutConnect.visibility = View.GONE
            binding.svRemote.visibility = View.VISIBLE
            binding.layoutChatOverlay.visibility = View.VISIBLE
            track.addSink(binding.svRemote)
        }
    }

    private fun showConnectForm(statusText: String) {
        binding.svRemote.visibility = View.GONE
        binding.layoutChatOverlay.visibility = View.GONE
        binding.layoutConnect.visibility = View.VISIBLE
        binding.tvStatus.text = statusText
        binding.tvStatus.visibility = View.VISIBLE
        binding.btnConnect.isEnabled = true
    }

    // -----------------------------------------------------------------------
    // Reconnect with exponential back-off
    // -----------------------------------------------------------------------

    private fun scheduleReconnect() {
        if (destroyed || isReconnecting || currentRoomId.isEmpty()) return
        isReconnecting = true
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_DELAY_MAX_MS)
        runOnUiThread {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = getString(R.string.status_reconnecting, delay / 1000)
        }
        mainHandler.postDelayed({
            isReconnecting = false
            if (!destroyed && currentRoomId.isNotEmpty()) {
                val password = binding.etPassword.text?.toString()?.trim()
                connectToRoom(currentRoomId, currentServerUrl, password)
            }
        }, delay)
    }

    private fun resetReconnectDelay() {
        reconnectDelayMs = RECONNECT_DELAY_MIN_MS
    }

    // -----------------------------------------------------------------------
    // Chat helpers
    // -----------------------------------------------------------------------

    private fun sendChat() {
        val text = binding.etChatInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        if (::webRTCClient.isInitialized) webRTCClient.sendChatMessage(text)
        appendChat("👁 $text")
        binding.etChatInput.text?.clear()
    }

    private fun appendChat(line: String) {
        val current = binding.tvChatLog.text?.toString() ?: ""
        binding.tvChatLog.text = if (current.isEmpty()) line else "$current\n$line"
        binding.scrollChat.post { binding.scrollChat.fullScroll(View.FOCUS_DOWN) }
    }

    // -----------------------------------------------------------------------

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
        if (::signalingClient.isInitialized) signalingClient.disconnect()
        if (::webRTCClient.isInitialized) webRTCClient.close()
        binding.svRemote.release()
        if (::eglBase.isInitialized) eglBase.release()
    }

    companion object {
        const val EXTRA_SERVER_URL = "server_url"

        private const val RECONNECT_DELAY_MIN_MS = 1_000L
        private const val RECONNECT_DELAY_MAX_MS = 30_000L
    }
}
