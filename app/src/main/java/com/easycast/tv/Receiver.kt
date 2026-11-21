package com.easycast.tv

import android.content.Context
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer

/** WebRTC receiver: takes the sender's screen/video stream and exposes it as a VideoTrack. */
class Receiver(
    app: Context,
    egl: EglBase,
    private val send: (String) -> Unit,
    private val onVideo: (VideoTrack) -> Unit,
    private val onMode: (String) -> Unit,
    private val onEnd: () -> Unit
) {
    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var ctrl: DataChannel? = null
    private var remoteSet = false
    private val pending = mutableListOf<IceCandidate>()

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    fun handle(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        when (o.optString("type")) {
            "mode" -> onMode(o.getString("mode"))
            "offer" -> {
                reset()
                val p = newPc().also { pc = it }
                p.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        remoteSet = true
                        pending.forEach { p.addIceCandidate(it) }; pending.clear()
                        p.createAnswer(object : Sdp() {
                            override fun onCreateSuccess(d: SessionDescription?) {
                                p.setLocalDescription(object : Sdp() {
                                    override fun onSetSuccess() {
                                        send(JSONObject().put("type", "answer").put("sdp", d!!.description).toString())
                                    }
                                }, d)
                            }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, o.getString("sdp")))
            }
            "ice" -> {
                val c = IceCandidate(o.optString("sdpMid"), o.optInt("sdpMLineIndex"), o.getString("candidate"))
                if (remoteSet) pc?.addIceCandidate(c) else pending.add(c)
            }
            "bye" -> close()
        }
    }

    /** Send a remote-control command to the sender (works in Cast Video mode). */
    fun ctrl(msg: String) {
        ctrl?.send(DataChannel.Buffer(ByteBuffer.wrap(msg.toByteArray()), false))
    }

    private fun reset() {
        try { ctrl?.close(); pc?.close() } catch (_: Exception) {}
        ctrl = null; pc = null; remoteSet = false; pending.clear()
    }

    fun close() { reset(); onEnd() }

    private fun newPc(): PeerConnection {
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                if (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.CLOSED) close()
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate) {
                send(JSONObject().put("type", "ice").put("candidate", c.sdp)
                    .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).toString())
            }
            override fun onIceCandidatesRemoved(a: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel) { ctrl = d }
            override fun onRenegotiationNeeded() {}
            override fun onTrack(t: RtpTransceiver?) {
                val tr = t?.receiver?.track()
                if (tr is VideoTrack) onVideo(tr)
            }
        })!!
    }
}
