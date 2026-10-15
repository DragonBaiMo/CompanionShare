// Copyright (c) 2026 CompanionShare. MIT License.
package dev.companionshare.owner

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.json.JSONArray
import org.webrtc.Camera2Enumerator
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import dev.companionshare.common.RtcPeer

/** Holds the only active video capturer. A stopped session never retains a camera. */
class OwnerCapture(
    private val context: Context,
    ice: JSONArray,
    events: RtcPeer.Events,
    private val projectionEnded: () -> Unit,
) {
    val peer = RtcPeer(context, ice, events)
    private var capturer: VideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var stopping = false

    fun begin(source: String, includeAudio: Boolean, screenGrant: Intent? = null) {
        val next: VideoCapturer = if (source == "screen") {
            requireNotNull(screenGrant) { "media projection requires local consent" }
            ScreenCapturerAndroid(screenGrant, object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stopping) projectionEnded()
                }
            })
        } else {
            val enumerator = Camera2Enumerator(context)
            val cameraName = enumerator.deviceNames.firstOrNull {
                if (source == "front") enumerator.isFrontFacing(it)
                else !enumerator.isFrontFacing(it)
            } ?: throw IllegalStateException("camera unavailable")
            requireNotNull(enumerator.createCapturer(cameraName, null))
        }
        capturer = next
        val textureHelper = SurfaceTextureHelper.create("CompanionVideo", peer.egl.eglBaseContext)
        helper = textureHelper
        val videoSource = peer.factory.createVideoSource(next.isScreencast)
        peer.videoSource = videoSource
        next.initialize(textureHelper, context.applicationContext, videoSource.capturerObserver)
        next.startCapture(1280, 720, 15)
        peer.addVideo(peer.factory.createVideoTrack("owner-video", videoSource))
        if (includeAudio) peer.addAudio()
        peer.enableDataChannel()
        peer.offer()
    }

    fun stop() {
        if (stopping) return
        stopping = true
        try { capturer?.stopCapture() } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) { /* Revoked media permissions during stop */ }
        try { capturer?.dispose() } catch (_: Exception) { /* Already revoked */ }
        helper?.dispose()
        peer.close()
    }
}
