// Copyright (c) 2026 CompanionShare. MIT License.
package dev.companionshare.viewer

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.companionshare.common.RtcPeer
import dev.companionshare.common.SecretStore
import dev.companionshare.common.SignalClient
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Read-only Viewer UI. No input injection, filesystem access or high-privilege commands. */
class ViewerActivity : Activity() {
    private lateinit var server: EditText
    private lateinit var code: EditText
    private lateinit var chat: EditText
    private lateinit var status: TextView
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var audio: CheckBox
    private var signal: SignalClient? = null
    private var rtc: RtcPeer? = null
    private var streamId: String? = null
    private var ice = JSONArray()
    private var currentVideo: VideoTrack? = null
    private var initializedRenderer = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 30, 24, 24)
        }
        layout.addView(TextView(this).apply { setText(R.string.title); textSize = 20f })
        server = EditText(this).apply {
            setText(SecretStore.server(this@ViewerActivity))
            hint = getString(R.string.server_hint)
            isSingleLine = true
        }
        layout.addView(server)
        code = EditText(this).apply {
            hint = getString(R.string.code_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        layout.addView(code)
        fun button(res: Int, command: () -> Unit) {
            layout.addView(Button(this).apply { setText(res); setOnClickListener { command() } })
        }
        button(R.string.pair) { connect(true) }
        button(R.string.connect) { connect(false) }
        renderer = SurfaceViewRenderer(this)
        layout.addView(renderer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 520))
        audio = CheckBox(this).apply { setText(R.string.audio); isChecked = true }
        layout.addView(audio)
        button(R.string.front) { watch("front") }
        button(R.string.back) { watch("back") }
        button(R.string.screen) { watch("screen") }
        button(R.string.stop) {
            signal?.send("session.end")
            clearSession()
        }
        chat = EditText(this).apply { hint = getString(R.string.chat_hint); maxLines = 3 }
        layout.addView(chat)
        button(R.string.send) {
            val text = chat.text.toString().trim().take(2000)
            if (text.isNotEmpty()) {
                if (rtc?.sendChat(text) != true) signal?.send("chat.send") { put("text", text) }
                append(getString(R.string.chat_sent, text))
                chat.text.clear()
            }
        }
        status = TextView(this).apply { setText(R.string.waiting); textSize = 15f }
        layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun connect(pair: Boolean) {
        try { SecretStore.saveServer(this, server.text.toString().trim()) }
        catch (_: Exception) { append(getString(R.string.error_server)); return }
        val credentials = if (!pair) SecretStore.viewer(this) else null
        if (!pair && credentials == null) {
            append(getString(R.string.no_pairing)); return
        }
        val request = if (pair) {
            val codeText = code.text.toString().trim()
            if (!Regex("[0-9]{8}").matches(codeText)) {
                append(getString(R.string.bad_code)); return
            }
            JSONObject().put("type", "viewer.pair").put("code", codeText)
        } else JSONObject().put("type", "viewer.login")
            .put("deviceId", credentials!!.deviceId).put("token", credentials.token)
        signal?.stop()
        clearSession()
        signal = SignalClient(::handle, { online ->
            if (!online) { clearSession(); append(getString(R.string.disconnected)) }
        }).also { it.start(SecretStore.server(this), request) }
    }

    private fun watch(source: String) {
        signal?.send("viewer.session.request") { put("source", source).put("audio", audio.isChecked) }
        append(getString(R.string.stream_waiting))
    }

    private fun handle(message: JSONObject) {
        when (message.optString("type")) {
            "pair.waiting" -> append(getString(R.string.pair_waiting))
            "pair.accepted" -> {
                val deviceId = message.getString("deviceId")
                val token = message.getString("token")
                SecretStore.saveViewer(this, deviceId, token)
                // NOTE: The pairing code is single-use; reconnect with the issued credential.
                signal?.updateAuthentication(JSONObject().put("type", "viewer.login")
                    .put("deviceId", deviceId).put("token", token))
                append(getString(R.string.paired))
            }
            "pair.rejected", "pair.expired" -> {
                signal?.stop()
                append(getString(R.string.pair_rejected))
            }
            "viewer.ready" -> {
                ice = message.optJSONArray("iceServers") ?: JSONArray()
                append(getString(R.string.connected))
            }
            "viewer.revoked" -> {
                SecretStore.clearViewer(this)
                signal?.stop()
                clearSession()
                append(getString(R.string.revoked))
            }
            "owner.status" -> append(if (message.optBoolean("armed"))
                getString(R.string.owner_armed) else getString(R.string.owner_unavailable))
            "session.waiting" -> append(getString(R.string.stream_waiting))
            "session.accepted" -> startSession(message.getString("requestId"))
            "session.rejected" -> append(getString(R.string.owner_unavailable))
            "session.ended" -> { clearSession(); append(getString(R.string.stream_ended)) }
            "signal.offer" -> {
                if (message.optString("requestId") == streamId) rtc?.acceptOffer(message.getString("sdp"))
            }
            "signal.ice" -> {
                if (message.optString("requestId") == streamId) {
                    rtc?.candidate(IceCandidate(message.optString("sdpMid"),
                        message.optInt("sdpMLineIndex"), message.optString("candidate")))
                }
            }
            "chat.message" -> append(getString(R.string.from_owner, message.optString("text")))
            "error" -> {
                val result = message.optString("code")
                append(getString(R.string.error_unknown, result))
                if (result in setOf("DENIED", "INVALID_CREDENTIALS", "INVALID_PAIR_CODE", "RATE_LIMITED")) {
                    signal?.stop()
                }
            }
        }
    }

    private fun startSession(id: String) {
        clearSession()
        streamId = id
        try {
            lateinit var peer: RtcPeer
            peer = RtcPeer(this, ice, object : RtcPeer.Events {
                override fun onIce(candidate: IceCandidate) {
                    signal?.send("signal.ice") {
                        put("requestId", id).put("candidate", candidate.sdp)
                            .put("sdpMid", candidate.sdpMid ?: "")
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    }
                }
                override fun onSdp(type: String, sdp: String) {
                    signal?.send("signal.answer") { put("requestId", id).put("sdp", sdp) }
                }
                override fun onRemoteVideo(track: VideoTrack) {
                    runOnUiThread {
                        if (rtc !== peer || isDestroyed) return@runOnUiThread
                        currentVideo?.removeSink(renderer)
                        currentVideo = track
                        track.addSink(renderer)
                        append(getString(R.string.streaming))
                    }
                }
                override fun onChat(text: String) {
                    runOnUiThread { if (!isDestroyed) append(getString(R.string.from_owner, text)) }
                }
                override fun onFailure(reason: String) {
                    runOnUiThread {
                        if (streamId != id) return@runOnUiThread
                        append(getString(R.string.error_unknown, reason))
                        signal?.send("session.end")
                        clearSession()
                    }
                }
            })
            rtc = peer
            renderer.init(peer.egl.eglBaseContext, null)
            renderer.setEnableHardwareScaler(true)
            initializedRenderer = true
            // NOTE: Owner is not allowed to send an offer until the renderer is initialized.
            if (signal?.send("viewer.session.ready") { put("requestId", id) } != true) {
                throw IllegalStateException("SIGNAL_DISCONNECTED")
            }
        } catch (e: Exception) {
            signal?.send("session.end")
            clearSession()
            append(getString(R.string.error_unknown, e.message ?: "RTC_INIT"))
        }
    }

    private fun clearSession() {
        currentVideo?.removeSink(renderer)
        currentVideo = null
        rtc?.close()
        rtc = null
        streamId = null
        if (initializedRenderer) {
            renderer.release()
            initializedRenderer = false
        }
    }

    private fun append(text: String) { status.append("\n$text") }

    override fun onDestroy() {
        signal?.stop()
        clearSession()
        super.onDestroy()
    }
}
