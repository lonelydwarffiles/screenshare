package com.screenshare

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription

/**
 * Foreground service that owns the [WebRTCClient] and [SignalingClient] for the broadcaster.
 *
 * Running as a foreground service is required on Android 10+ to keep
 * [android.media.projection.MediaProjection] active while the user navigates away.
 */
class ScreenShareService : Service() {

    /** Callback interface so the bound [BroadcastActivity] can receive status updates. */
    fun interface StatusListener {
        fun onStatus(status: String)
    }

    /** Callback for live viewer-count updates. */
    fun interface ViewerCountListener {
        fun onViewerCount(count: Int)
    }

    /** Callback for incoming chat messages (to show in [BroadcastActivity]). */
    fun interface ChatListener {
        fun onChatMessage(text: String)
    }

    /**
     * Callback fired when a viewer is knocking (knock-to-enter mode).
     * The activity should show a dialog and call [acceptViewer] or [rejectViewer].
     */
    fun interface KnockListener {
        fun onViewerKnock(viewerId: String, displayName: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): ScreenShareService = this@ScreenShareService
    }

    private val binder = LocalBinder()
    private var statusListener: StatusListener? = null
    private var viewerCountListener: ViewerCountListener? = null
    private var chatListener: ChatListener? = null
    private var knockListener: KnockListener? = null

    private lateinit var eglBase: EglBase
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var signalingClient: SignalingClient

    // -----------------------------------------------------------------------
    // Service lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val roomId     = intent?.getStringExtra(EXTRA_ROOM_ID)     ?: ""
        val serverUrl  = intent?.getStringExtra(EXTRA_SERVER_URL)  ?: DEFAULT_SERVER_URL
        val password   = intent?.getStringExtra(EXTRA_PASSWORD)
        val maxViewers = intent?.getIntExtra(EXTRA_MAX_VIEWERS, 0) ?: 0
        val useKnock   = intent?.getBooleanExtra(EXTRA_USE_KNOCK, false) ?: false
        val useMic     = intent?.getBooleanExtra(EXTRA_USE_MIC, true)   ?: true
        val qualityOrd = intent?.getIntExtra(EXTRA_QUALITY, WebRTCClient.Quality.MEDIUM.ordinal)
            ?: WebRTCClient.Quality.MEDIUM.ordinal
        val quality    = WebRTCClient.Quality.values()[qualityOrd.coerceIn(WebRTCClient.Quality.values().indices)]
        val turnUrl    = intent?.getStringExtra(EXTRA_TURN_URL)
        val turnUser   = intent?.getStringExtra(EXTRA_TURN_USER)
        val turnPass   = intent?.getStringExtra(EXTRA_TURN_PASS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (resultData != null && resultCode != -1) {
            eglBase = EglBase.create()
            setupWebRTC(resultData, quality, useMic, turnUrl, turnUser, turnPass)
            setupSignaling(serverUrl, roomId, password, maxViewers, useKnock)
        } else {
            Log.e(TAG, "Missing MediaProjection data; stopping service.")
            sendBroadcast(Intent(ACTION_ERROR).putExtra(EXTRA_ERROR_MESSAGE,
                "Screen capture permission was not granted."))
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        if (::signalingClient.isInitialized) signalingClient.disconnect()
        if (::webRTCClient.isInitialized) webRTCClient.close()
        if (::eglBase.isInitialized) eglBase.release()
    }

    // -----------------------------------------------------------------------
    // Public API (used by BroadcastActivity via the binder)
    // -----------------------------------------------------------------------

    fun setStatusListener(listener: StatusListener?) { statusListener = listener }
    fun setViewerCountListener(listener: ViewerCountListener?) { viewerCountListener = listener }
    fun setChatListener(listener: ChatListener?) { chatListener = listener }
    fun setKnockListener(listener: KnockListener?) { knockListener = listener }

    fun sendChatMessage(text: String) {
        if (::webRTCClient.isInitialized) webRTCClient.sendChatMessage(text)
    }

    fun acceptViewer(viewerId: String) {
        if (::signalingClient.isInitialized) signalingClient.acceptViewer(viewerId)
    }

    fun rejectViewer(viewerId: String) {
        if (::signalingClient.isInitialized) signalingClient.rejectViewer(viewerId)
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun setupWebRTC(
        resultData: Intent,
        quality: WebRTCClient.Quality,
        useMic: Boolean,
        turnUrl: String?,
        turnUser: String?,
        turnPass: String?,
    ) {
        webRTCClient = WebRTCClient(this, eglBase, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                updateStatus("ICE: ${state.name}")
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                if (::signalingClient.isInitialized) signalingClient.sendIceCandidate(candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
        })
        webRTCClient.initializePeerConnection(turnUrl, turnUser, turnPass)
        webRTCClient.startScreenCapture(resultData, quality)
        if (useMic) webRTCClient.startAudioCapture()

        webRTCClient.dataChannelListener = WebRTCClient.DataChannelListener { text ->
            chatListener?.onChatMessage(text)
        }
    }

    private fun setupSignaling(
        serverUrl: String,
        roomId: String,
        password: String?,
        maxViewers: Int,
        useKnock: Boolean,
    ) {
        signalingClient = SignalingClient(serverUrl, object : SignalingClient.ListenerAdapter() {
            override fun onConnected() {
                signalingClient.createRoom(
                    roomId = roomId,
                    password = password,
                    maxViewers = maxViewers,
                    useKnock = useKnock,
                )
            }

            override fun onRoomCreated(roomId: String) {
                updateStatus(getString(R.string.status_waiting_for_viewers))
            }

            override fun onViewerJoined() {
                vibrate(VIBRATE_VIEWER_JOIN)
                updateStatus(getString(R.string.status_viewer_connected))
                webRTCClient.createOffer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        webRTCClient.setLocalDescription(object : SimpleSdpObserver() {}, sdp)
                        signalingClient.sendOffer(sdp)
                    }
                    override fun onCreateFailure(error: String) {
                        updateStatus("Offer failed: $error")
                    }
                })
            }

            override fun onViewerCount(count: Int) {
                viewerCountListener?.onViewerCount(count)
            }

            override fun onViewerKnock(viewerId: String, displayName: String) {
                vibrate(VIBRATE_KNOCK)
                knockListener?.onViewerKnock(viewerId, displayName)
                    ?: run {
                        // No listener attached (activity not visible) → auto-accept.
                        signalingClient.acceptViewer(viewerId)
                    }
            }

            override fun onAnswerReceived(sdp: SessionDescription) {
                webRTCClient.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        updateStatus(getString(R.string.status_streaming))
                    }
                }, sdp)
            }

            override fun onIceCandidateReceived(candidate: IceCandidate) {
                webRTCClient.addIceCandidate(candidate)
            }

            override fun onError(message: String) {
                updateStatus("Error: $message")
            }
        })
        signalingClient.connect()
    }

    private fun updateStatus(status: String) {
        statusListener?.onStatus(status)
    }

    private fun vibrate(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) { /* vibration is a best-effort feature */ }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setSmallIcon(R.drawable.ic_cast)
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE    = "result_code"
        const val EXTRA_RESULT_DATA    = "result_data"
        const val EXTRA_ROOM_ID        = "room_id"
        const val EXTRA_SERVER_URL     = "server_url"
        const val EXTRA_PASSWORD       = "password"
        const val EXTRA_MAX_VIEWERS    = "max_viewers"
        const val EXTRA_USE_KNOCK      = "use_knock"
        const val EXTRA_USE_MIC        = "use_mic"
        const val EXTRA_QUALITY        = "quality"
        const val EXTRA_TURN_URL       = "turn_url"
        const val EXTRA_TURN_USER      = "turn_user"
        const val EXTRA_TURN_PASS      = "turn_pass"
        const val ACTION_ERROR         = "com.screenshare.ACTION_SERVICE_ERROR"
        const val EXTRA_ERROR_MESSAGE  = "error_message"
        const val DEFAULT_SERVER_URL   = "ws://10.0.2.2:8080"

        private const val CHANNEL_ID     = "screen_share_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG             = "ScreenShareService"

        /** 0 ms delay, 80 ms vibration — short tap for viewer join. */
        private val VIBRATE_VIEWER_JOIN = longArrayOf(0, 80)
        /** Two short pulses for a knock. */
        private val VIBRATE_KNOCK       = longArrayOf(0, 60, 80, 60)
    }
}
