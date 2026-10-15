// Copyright (c) 2026 CompanionShare. MIT License.
package dev.companionshare.common

import android.content.Context
import org.json.JSONArray
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** One media session. Instances and all capture resources are closed after each viewing session. */
class RtcPeer(
    context: Context,
    iceServers: JSONArray,
    private val events: Events,
) {
    interface Events {
        fun onIce(candidate: IceCandidate)
        fun onSdp(type: String, sdp: String)
        fun onRemoteVideo(track: VideoTrack)
        fun onChat(text: String)
        fun onFailure(reason: String)
    }

    companion object {
        private val initialized = AtomicBoolean(false)
        private fun ensureInitialized(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions(),
                )
            }
        }
    }

    private val appContext = context.applicationContext
    val egl: EglBase = EglBase.create()
    private val audioDeviceModule: JavaAudioDeviceModule
    val factory: PeerConnectionFactory
    val connection: PeerConnection
    var videoSource: VideoSource? = null
    var videoTrack: VideoTrack? = null
    var audioSource: AudioSource? = null
    var audioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null
    private var remoteReady = false
    private val queuedIce = mutableListOf<IceCandidate>()
    private var closed = false

    init {
        ensureInitialized(appContext)
        audioDeviceModule = JavaAudioDeviceModule.builder(appContext).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        val candidates = mutableListOf<PeerConnection.IceServer>()
        for (i in 0 until iceServers.length()) {
            val item = iceServers.getJSONObject(i)
            val urls = item.getJSONArray("urls")
            val list = (0 until urls.length()).map { urls.getString(it) }
            candidates += PeerConnection.IceServer.builder(list)
                .setUsername(item.optString("username"))
                .setPassword(item.optString("credential"))
                .createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(candidates).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        connection = requireNotNull(factory.createPeerConnection(config, object : PeerObserver() {
            override fun onIceCandidate(candidate: IceCandidate) { events.onIce(candidate) }
            override fun onDataChannel(channel: DataChannel) { attachChannel(channel) }
            override fun onTrack(transceiver: RtpTransceiver) {
                (transceiver.receiver.track() as? VideoTrack)?.let(events::onRemoteVideo)
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED) events.onFailure("ICE_FAILED")
            }
        })) { "WebRTC PeerConnection allocation failed" }
    }

    fun enableDataChannel() {
        val channel = connection.createDataChannel("chat", DataChannel.Init())
        attachChannel(channel)
    }

    private fun attachChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                events.onChat(String(data, Charsets.UTF_8))
            }
        })
    }

    fun sendChat(text: String): Boolean {
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray()), false))
    }

    fun addVideo(track: VideoTrack) {
        videoTrack = track
        connection.addTrack(track, listOf("companionshare"))
    }

    fun addAudio() {
        val source = factory.createAudioSource(MediaConstraints())
        audioSource = source
        audioTrack = factory.createAudioTrack("owner-audio", source).also {
            connection.addTrack(it, listOf("companionshare"))
        }
    }

    fun offer() {
        connection.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(description: SessionDescription) { setLocal(description) }
        }, MediaConstraints())
    }

    fun acceptOffer(sdp: String) {
        setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp)) {
            connection.createAnswer(object : SdpAdapter() {
                override fun onCreateSuccess(description: SessionDescription) { setLocal(description) }
            }, MediaConstraints())
        }
    }

    fun acceptAnswer(sdp: String) {
        setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp)) {}
    }

    private fun setLocal(description: SessionDescription) {
        connection.setLocalDescription(object : SdpAdapter() {
            override fun onSetSuccess() { events.onSdp(description.type.canonicalForm(), description.description) }
        }, description)
    }

    private fun setRemote(description: SessionDescription, after: () -> Unit) {
        connection.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                remoteReady = true
                synchronized(queuedIce) {
                    queuedIce.forEach { connection.addIceCandidate(it) }
                    queuedIce.clear()
                }
                after()
            }
        }, description)
    }

    fun candidate(candidate: IceCandidate) {
        if (closed) return
        synchronized(queuedIce) {
            if (remoteReady) connection.addIceCandidate(candidate)
            else if (queuedIce.size < 256) queuedIce.add(candidate)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        dataChannel?.close()
        dataChannel?.dispose()
        connection.close()
        connection.dispose()
        videoTrack?.dispose()
        videoSource?.dispose()
        audioTrack?.dispose()
        audioSource?.dispose()
        factory.dispose()
        audioDeviceModule.release()
        egl.release()
    }

    private open inner class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) { events.onFailure(error) }
        override fun onSetFailure(error: String) { events.onFailure(error) }
    }
}

/** Empty defaults for WebRTC observer callbacks that are not part of app business logic. */
open class PeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
    override fun onIceCandidate(candidate: IceCandidate) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
    override fun onAddStream(stream: MediaStream) = Unit
    override fun onRemoveStream(stream: MediaStream) = Unit
    override fun onDataChannel(channel: DataChannel) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
}
