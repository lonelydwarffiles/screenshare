package com.screenshare

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.screenshare.databinding.ActivityViewerBinding
import org.json.JSONObject
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
 * Viewer screen: enter a session code (or receive one via deep-link), connect to the
 * signaling server, and render the received video stream full-screen.
 *
 * Interactive controls (sent to broadcaster via DataChannel):
 *   ❤️🔥👀🥵😈 – emoji reactions    (shown as floating animation on broadcaster)
 *   📳 Buzz      – vibrates broadcaster's device + Lovense toy
 *   🔒 Lock      – shows/hides fullscreen lock overlay on broadcaster's device
 *   Command cards – pre-defined chat shortcuts
 *
 * Incoming from broadcaster:
 *   blackout   – shows/hides this device's blackout overlay
 *   countdown  – starts a synced countdown timer
 *   freeze     – no action needed (video track stops updating naturally)
 *   buzz       – vibrates this device + Lovense toy
 *   chat       – displayed in the chat log
 *
 * Deep-link: screenshare://session/<code>
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var eglBase: EglBase
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var signalingClient: SignalingClient

    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectDelayMs = RECONNECT_DELAY_MIN_MS
    private var isReconnecting = false
    private var currentSessionId: String = ""
    private var currentServerUrl: String = ""
    private var destroyed = false
    private var countDownTimer: CountDownTimer? = null
    private var isLockOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eglBase = EglBase.create()

        currentServerUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ScreenShareService.DEFAULT_SERVER_URL

        // Handle deep-link: screenshare://session/<code>
        val deepLinkCode = intent.data
            ?.takeIf { it.scheme == "screenshare" && it.host == "session" }
            ?.lastPathSegment
        if (deepLinkCode != null) {
            binding.etSessionCode.setText(deepLinkCode)
        }

        binding.btnConnect.setOnClickListener { attemptConnect() }

        // Emoji reaction buttons
        val emojiButtons = listOf(
            binding.btnEmoji1 to "❤️",
            binding.btnEmoji2 to "🔥",
            binding.btnEmoji3 to "👀",
            binding.btnEmoji4 to "🥵",
            binding.btnEmoji5 to "😈",
        )
        emojiButtons.forEach { (btn, emoji) ->
            btn.setOnClickListener {
                if (::webRTCClient.isInitialized) {
                    webRTCClient.sendControlMessage("emoji", mapOf("value" to emoji))
                }
            }
        }

        // Buzz button — vibrate broadcaster + Lovense
        binding.btnBuzz.setOnClickListener {
            if (::webRTCClient.isInitialized) {
                webRTCClient.sendControlMessage("buzz", mapOf(
                    "pattern" to listOf(0, 80, 50, 80)
                ))
            }
        }

        // Lock / Unlock broadcaster
        binding.btnLock.setOnClickListener {
            isLockOn = !isLockOn
            if (::webRTCClient.isInitialized) {
                webRTCClient.sendControlMessage("lock", mapOf("on" to isLockOn))
            }
            binding.btnLock.text = if (isLockOn)
                getString(R.string.action_unlock_broadcaster)
            else
                getString(R.string.action_lock_broadcaster)
        }

        // Command cards
        val commands = resources.getStringArray(R.array.command_cards)
        commands.forEach { cmd ->
            val btn = com.google.android.material.button.MaterialButton(this).apply {
                text = cmd
                setOnClickListener {
                    if (::webRTCClient.isInitialized) webRTCClient.sendChatMessage(cmd)
                    appendChat("👁 $cmd")
                }
            }
            binding.layoutCommandCards.addView(btn)
        }

        // Chat send
        binding.btnSendChat.setOnClickListener { sendChat() }
        binding.etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendChat(); true } else false
        }
    }

    private fun attemptConnect() {
        val code = binding.etSessionCode.text?.toString()?.trim() ?: ""
        if (code.isEmpty()) {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = getString(R.string.error_invalid_session_code)
            return
        }
        val password = binding.etPassword.text?.toString()?.trim()
        connectToSession(code, currentServerUrl, password)
    }

    private fun connectToSession(sessionId: String, serverUrl: String, password: String?) {
        currentSessionId = sessionId
        binding.btnConnect.isEnabled = false
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.status_connecting)

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

        webRTCClient.dataChannelListener = WebRTCClient.DataChannelListener { json ->
            handleIncomingDataMessage(json)
        }

        signalingClient = SignalingClient(serverUrl, object : SignalingClient.ListenerAdapter() {
            override fun onConnected() {
                signalingClient.joinSession(sessionId, password)
            }

            override fun onSessionJoined(sessionId: String) {
                resetReconnectDelay()
                runOnUiThread {
                    binding.tvStatus.text = getString(R.string.status_waiting_for_stream)
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
                    "session_full"   -> R.string.error_session_full
                    "not_found"      -> R.string.error_session_not_found
                    else             -> R.string.error_rejected
                }
                runOnUiThread { showConnectForm(getString(msgRes)) }
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

    // -----------------------------------------------------------------------
    // Incoming DataChannel messages (from broadcaster)
    // -----------------------------------------------------------------------

    private fun handleIncomingDataMessage(json: String) {
        try {
            val obj = JSONObject(json)
            when (obj.getString("type")) {
                "chat" -> {
                    val text = obj.optString("text")
                    runOnUiThread { appendChat("📡 $text") }
                }
                "blackout" -> {
                    val on = obj.optBoolean("on", false)
                    runOnUiThread {
                        binding.viewBlackout.visibility = if (on) View.VISIBLE else View.GONE
                    }
                }
                "countdown" -> {
                    val seconds = obj.optInt("seconds", 10)
                    runOnUiThread { startCountdown(seconds) }
                }
                "buzz" -> {
                    val patternArray = obj.optJSONArray("pattern")
                    val pattern = if (patternArray != null) {
                        LongArray(patternArray.length()) { patternArray.getLong(it) }
                    } else {
                        longArrayOf(0, 80, 50, 80)
                    }
                    vibrateDevice(pattern)
                    LovenseManager.getInstance(application).vibrate(LOVENSE_BUZZ_LEVEL)
                    val totalMs = pattern.sum()
                    mainHandler.postDelayed({
                        LovenseManager.getInstance(application).stopAll()
                    }, totalMs + 100)
                }
                "app_violation" -> {
                    val appName = obj.optString("appName", "an app")
                    runOnUiThread {
                        appendChat("⚠️ Broadcaster opened: $appName")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ViewerActivity", "Failed to parse DataChannel msg: $json", e)
        }
    }

    // -----------------------------------------------------------------------
    // Countdown
    // -----------------------------------------------------------------------

    private fun startCountdown(seconds: Int) {
        countDownTimer?.cancel()
        binding.tvCountdown.visibility = View.VISIBLE
        countDownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(ms: Long) {
                binding.tvCountdown.text = (ms / 1000 + 1).toString()
            }
            override fun onFinish() {
                binding.tvCountdown.text = "✨"
                // Hide blackout if it was on — revelation moment!
                binding.viewBlackout.visibility = View.GONE
                mainHandler.postDelayed({
                    binding.tvCountdown.visibility = View.GONE
                }, 1500)
            }
        }.start()
    }

    // -----------------------------------------------------------------------
    // Render / show/hide helpers
    // -----------------------------------------------------------------------

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTimeMs = 0L

    private fun renderVideoTrack(track: VideoTrack) {
        runOnUiThread {
            binding.layoutConnect.visibility = View.GONE
            binding.svRemote.visibility = View.VISIBLE
            binding.layoutControls.visibility = View.VISIBLE
            track.addSink(binding.svRemote)
            attachRemoteTouchCapture()
        }
    }

    /**
     * Captures touch events on the video surface and forwards them to the broadcaster
     * as normalised [0.0, 1.0] coordinates over the DataChannel (`remote_touch` message).
     *
     * The broadcaster's [RestrictedAppsAccessibilityService] converts these back to absolute
     * screen pixels and injects the gesture via [AccessibilityService.dispatchGesture].
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachRemoteTouchCapture() {
        binding.svRemote.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x / view.width.coerceAtLeast(1)
                    touchStartY = event.y / view.height.coerceAtLeast(1)
                    touchStartTimeMs = System.currentTimeMillis()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!::webRTCClient.isInitialized) return@setOnTouchListener false
                    val nx = event.x / view.width.coerceAtLeast(1)
                    val ny = event.y / view.height.coerceAtLeast(1)
                    val durationMs = System.currentTimeMillis() - touchStartTimeMs
                    val dx = Math.abs(nx - touchStartX)
                    val dy = Math.abs(ny - touchStartY)

                    if (durationMs < TAP_MAX_DURATION_MS && dx < TAP_MAX_DRIFT && dy < TAP_MAX_DRIFT) {
                        // Short, stationary — treat as a tap.
                        webRTCClient.sendControlMessage("remote_touch", mapOf(
                            "action" to "tap",
                            "x" to nx.toDouble(),
                            "y" to ny.toDouble(),
                        ))
                    } else {
                        // Longer or moved — treat as a swipe.
                        webRTCClient.sendControlMessage("remote_touch", mapOf(
                            "action"   to "swipe",
                            "x1"       to touchStartX.toDouble(),
                            "y1"       to touchStartY.toDouble(),
                            "x2"       to nx.toDouble(),
                            "y2"       to ny.toDouble(),
                            "duration" to durationMs,
                        ))
                    }
                }
            }
            // Return false so the gesture is also passed to the SurfaceView's own renderer.
            false
        }
    }

    private fun showConnectForm(statusText: String) {
        countDownTimer?.cancel()
        binding.viewBlackout.visibility = View.GONE
        binding.svRemote.visibility = View.GONE
        binding.layoutControls.visibility = View.GONE
        binding.layoutConnect.visibility = View.VISIBLE
        binding.tvStatus.text = statusText
        binding.tvStatus.visibility = View.VISIBLE
        binding.btnConnect.isEnabled = true
        isLockOn = false
        binding.btnLock.text = getString(R.string.action_lock_broadcaster)
    }

    // -----------------------------------------------------------------------
    // Reconnect with exponential back-off
    // -----------------------------------------------------------------------

    private fun scheduleReconnect() {
        if (destroyed || isReconnecting || currentSessionId.isEmpty()) return
        isReconnecting = true
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_DELAY_MAX_MS)
        runOnUiThread {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = getString(R.string.status_reconnecting, delay / 1000)
        }
        mainHandler.postDelayed({
            isReconnecting = false
            if (!destroyed && currentSessionId.isNotEmpty()) {
                val password = binding.etPassword.text?.toString()?.trim()
                connectToSession(currentSessionId, currentServerUrl, password)
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
    // Device vibration
    // -----------------------------------------------------------------------

    private fun vibrateDevice(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(VibratorManager::class.java)
                mgr?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {}
    }

    // -----------------------------------------------------------------------

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        countDownTimer?.cancel()
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
        private const val LOVENSE_BUZZ_LEVEL      = 10   // 0–20
        private const val TAP_MAX_DURATION_MS     = 250L // touches shorter than this = tap
        private const val TAP_MAX_DRIFT           = 0.03f // normalised drift tolerance for taps
    }
}
