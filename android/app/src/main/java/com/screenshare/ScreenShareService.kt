package com.screenshare

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
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

    inner class LocalBinder : Binder() {
        fun getService(): ScreenShareService = this@ScreenShareService
    }

    private val binder = LocalBinder()
    private var statusListener: StatusListener? = null

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
        val roomId = intent?.getStringExtra(EXTRA_ROOM_ID) ?: ""
        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: DEFAULT_SERVER_URL

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
            setupWebRTC(resultData)
            setupSignaling(serverUrl, roomId)
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

    fun setStatusListener(listener: StatusListener?) {
        statusListener = listener
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun setupWebRTC(resultData: Intent) {
        webRTCClient = WebRTCClient(this, eglBase, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                updateStatus("ICE: ${state.name}")
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                if (::signalingClient.isInitialized) {
                    signalingClient.sendIceCandidate(candidate)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
        })
        webRTCClient.initializePeerConnection()
        webRTCClient.startScreenCapture(resultData)
    }

    private fun setupSignaling(serverUrl: String, roomId: String) {
        signalingClient = SignalingClient(serverUrl, object : SignalingClient.Listener {
            override fun onConnected() {
                signalingClient.createRoom(roomId)
            }

            override fun onRoomCreated(roomId: String) {
                updateStatus("Waiting for viewers…  Room: $roomId")
            }

            override fun onRoomJoined(roomId: String) {}

            override fun onViewerJoined() {
                updateStatus("Viewer connected — creating offer…")
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

            override fun onOfferReceived(sdp: SessionDescription) {}

            override fun onAnswerReceived(sdp: SessionDescription) {
                webRTCClient.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        updateStatus("Streaming ▶")
                    }
                }, sdp)
            }

            override fun onIceCandidateReceived(candidate: IceCandidate) {
                webRTCClient.addIceCandidate(candidate)
            }

            override fun onBroadcastEnded() {}

            override fun onError(message: String) {
                updateStatus("Error: $message")
            }
        })
        signalingClient.connect()
    }

    private fun updateStatus(status: String) {
        statusListener?.onStatus(status)
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
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_SERVER_URL = "server_url"
        const val ACTION_ERROR = "com.screenshare.ACTION_SERVICE_ERROR"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val DEFAULT_SERVER_URL = "ws://10.0.2.2:8080"

        private const val CHANNEL_ID = "screen_share_channel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "ScreenShareService"
    }
}
