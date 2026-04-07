package com.screenshare

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.screenshare.databinding.ActivityViewerBinding
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * Lets the user enter a room ID, connects to the signaling server, and renders
 * the received video stream full-screen.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private lateinit var eglBase: EglBase
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var signalingClient: SignalingClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eglBase = EglBase.create()

        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ScreenShareService.DEFAULT_SERVER_URL

        binding.btnConnect.setOnClickListener {
            val roomId = binding.etRoomId.text?.toString()?.trim() ?: ""
            if (roomId.length == 4) {
                connectToRoom(roomId, serverUrl)
            } else {
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = getString(R.string.error_invalid_room_id)
            }
        }
    }

    private fun connectToRoom(roomId: String, serverUrl: String) {
        binding.btnConnect.isEnabled = false
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.status_connecting)

        webRTCClient = WebRTCClient(this, eglBase, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                if (::signalingClient.isInitialized) {
                    signalingClient.sendIceCandidate(candidate)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {
                // Legacy unified-plan fallback
                stream.videoTracks.firstOrNull()?.let { renderTrack(it) }
            }
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                (receiver.track() as? VideoTrack)?.let { renderTrack(it) }
            }
        })
        webRTCClient.initializePeerConnection()
        webRTCClient.initSurfaceView(binding.svRemote)

        signalingClient = SignalingClient(serverUrl, object : SignalingClient.Listener {
            override fun onConnected() {
                signalingClient.joinRoom(roomId)
            }

            override fun onRoomCreated(roomId: String) {}

            override fun onRoomJoined(roomId: String) {
                runOnUiThread {
                    binding.tvStatus.text = getString(R.string.status_waiting_for_stream)
                }
            }

            override fun onViewerJoined() {}

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

            override fun onAnswerReceived(sdp: SessionDescription) {}

            override fun onIceCandidateReceived(candidate: IceCandidate) {
                webRTCClient.addIceCandidate(candidate)
            }

            override fun onBroadcastEnded() {
                runOnUiThread {
                    binding.svRemote.visibility = View.GONE
                    binding.layoutConnect.visibility = View.VISIBLE
                    binding.tvStatus.text = getString(R.string.status_broadcast_ended)
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.btnConnect.isEnabled = true
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    binding.tvStatus.text = getString(R.string.error_prefix, message)
                    binding.btnConnect.isEnabled = true
                }
            }
        })
        signalingClient.connect()
    }

    private fun renderTrack(track: VideoTrack) {
        runOnUiThread {
            binding.layoutConnect.visibility = View.GONE
            binding.svRemote.visibility = View.VISIBLE
            track.addSink(binding.svRemote)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::signalingClient.isInitialized) signalingClient.disconnect()
        if (::webRTCClient.isInitialized) webRTCClient.close()
        binding.svRemote.release()
        if (::eglBase.isInitialized) eglBase.release()
    }

    companion object {
        const val EXTRA_SERVER_URL = "server_url"
    }
}
