package com.screenshare

import android.content.Context
import android.content.Intent
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

/**
 * Manages a single WebRTC [PeerConnection].
 *
 * For the broadcaster: call [startScreenCapture] to attach a screen-capture track
 * before creating an offer.
 * For the viewer: track is received via [PeerConnection.Observer.onAddStream] /
 * [PeerConnection.Observer.onAddTrack].
 */
class WebRTCClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val observer: PeerConnection.Observer
) {
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

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

    /** Creates the [PeerConnection] with a Google STUN server. Must be called once. */
    fun initializePeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = factory.createPeerConnection(config, observer)
    }

    /**
     * Starts capturing the device screen and adds the resulting [VideoTrack] to the
     * peer connection.  Call this before [createOffer].
     *
     * @param mediaProjectionPermissionResultData  The [Intent] returned by
     *   [android.media.projection.MediaProjectionManager.createScreenCaptureIntent]
     *   after the user grants permission.
     */
    fun startScreenCapture(
        mediaProjectionPermissionResultData: Intent,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 15
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
        screenCapturer!!.startCapture(width, height, fps)

        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        peerConnection?.addTrack(localVideoTrack!!, listOf(STREAM_ID))
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

    /** Release all resources. */
    fun close() {
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        videoSource?.dispose()
        localVideoTrack?.dispose()
        peerConnection?.dispose()
        factory.dispose()
    }

    companion object {
        private const val VIDEO_TRACK_ID = "screen_video_track"
        private const val STREAM_ID = "screen_stream"
    }
}
