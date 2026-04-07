package com.screenshare

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** No-op base implementation of [SdpObserver] for convenient overriding. */
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
