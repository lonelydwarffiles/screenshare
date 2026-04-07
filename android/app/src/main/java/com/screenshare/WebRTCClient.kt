package com.screenshare

import android.content.Context
import android.content.Intent
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaProjection
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Manages a single WebRTC [PeerConnection].
 *
 * For the broadcaster: call [startScreenCapture] (and optionally [startAudioCapture])
 * before [createOffer].
 * For the viewer: tracks are received via [PeerConnection.Observer.onAddTrack].
 *
 * A "chat" [DataChannel] is created by the broadcaster in [initializePeerConnection].
 * Incoming text messages are delivered via [dataChannelListener].
 */
class WebRTCClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val observer: PeerConnection.Observer
) {
    /** Stream quality preset. */
    enum class Quality(val width: Int, val height: Int, val fps: Int) {
        LOW(854, 480, 10),
        MEDIUM(1280, 720, 15),
        HIGH(1920, 1080, 24),
    }

    /** Called on the WebRTC internal thread when a chat message arrives. */
    fun interface DataChannelListener {
        fun onMessage(text: String)
    }

    var dataChannelListener: DataChannelListener? = null

    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    /** Chat DataChannel – non-null on the broadcaster side after [initializePeerConnection]. */
    private var chatChannel: DataChannel? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /**
     * Creates the [PeerConnection] with Google STUN and optional TURN servers.
     *
     * @param turnUrl  Optional TURN server URI, e.g. `turn:turn.example.com:3478`
     * @param turnUser TURN username
     * @param turnPass TURN credential
     */
    fun initializePeerConnection(
        turnUrl: String? = null,
        turnUser: String? = null,
        turnPass: String? = null,
    ) {
        val iceServers = buildList {
            add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            if (!turnUrl.isNullOrBlank()) {
                val builder = PeerConnection.IceServer.builder(turnUrl)
                if (!turnUser.isNullOrBlank()) builder.setUsername(turnUser)
                if (!turnPass.isNullOrBlank()) builder.setPassword(turnPass)
                add(builder.createIceServer())
            }
        }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(config, observer) ?: return
        peerConnection = pc

        // Broadcaster creates the chat DataChannel; viewer receives it via onDataChannel.
        val dcInit = DataChannel.Init().apply {
            ordered = true
            negotiated = false
        }
        chatChannel = pc.createDataChannel(CHAT_CHANNEL_LABEL, dcInit)
        chatChannel?.registerObserver(makeDcObserver())
    }

    /**
     * Starts capturing the device screen and adds the resulting [VideoTrack] to the
     * peer connection.  Call this before [createOffer].
     */
    fun startScreenCapture(
        mediaProjectionPermissionResultData: Intent,
        quality: Quality = Quality.MEDIUM,
    ) {
        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCapThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(/* isScreencast= */ true)

        screenCapturer = ScreenCapturerAndroid(
            mediaProjectionPermissionResultData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // MediaProjection was stopped externally (e.g. system revoked it).
                }
            }
        )
        screenCapturer!!.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
        screenCapturer!!.startCapture(quality.width, quality.height, quality.fps)

        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        peerConnection?.addTrack(localVideoTrack!!, listOf(STREAM_ID))
    }

    /**
     * Creates a microphone [AudioTrack] and adds it to the peer connection.
     * Should be called after [initializePeerConnection] and before [createOffer].
     * Requires [android.Manifest.permission.RECORD_AUDIO].
     */
    fun startAudioCapture() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        audioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        peerConnection?.addTrack(localAudioTrack!!, listOf(STREAM_ID))
    }

    /** Initialise a [SurfaceViewRenderer] for rendering remote (or local preview) video. */
    fun initSurfaceView(view: SurfaceViewRenderer) {
        view.init(eglBase.eglBaseContext, null)
        view.setEnableHardwareScaler(true)
        view.setMirror(false)
    }

    fun createOffer(sdpObserver: SdpObserver) {
        peerConnection?.createOffer(sdpObserver, MediaConstraints())
    }

    fun createAnswer(sdpObserver: SdpObserver) {
        peerConnection?.createAnswer(sdpObserver, MediaConstraints())
    }

    fun setLocalDescription(sdpObserver: SdpObserver, sdp: SessionDescription) {
        peerConnection?.setLocalDescription(sdpObserver, sdp)
    }

    fun setRemoteDescription(sdpObserver: SdpObserver, sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(sdpObserver, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    /**
     * Registers this side's [DataChannel] observer when the viewer receives the channel
     * via [PeerConnection.Observer.onDataChannel].
     */
    fun attachViewerDataChannel(channel: DataChannel) {
        chatChannel = channel
        channel.registerObserver(makeDcObserver())
    }

    /** Send a text chat message over the DataChannel (both sides). */
    fun sendChatMessage(text: String) {
        val channel = chatChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    /** Release all resources. */
    fun close() {
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        videoSource?.dispose()
        localVideoTrack?.dispose()
        audioSource?.dispose()
        localAudioTrack?.dispose()
        chatChannel?.dispose()
        peerConnection?.dispose()
        factory.dispose()
    }

    // ---------------------------------------------------------------------------

    private fun makeDcObserver() = object : DataChannel.Observer {
        override fun onBufferedAmountChange(amount: Long) {}
        override fun onStateChange() {}
        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            val text = String(bytes, StandardCharsets.UTF_8)
            dataChannelListener?.onMessage(text)
        }
    }

    companion object {
        private const val VIDEO_TRACK_ID = "screen_video_track"
        private const val AUDIO_TRACK_ID = "screen_audio_track"
        private const val STREAM_ID = "screen_stream"
        const val CHAT_CHANNEL_LABEL = "chat"
    }
}
