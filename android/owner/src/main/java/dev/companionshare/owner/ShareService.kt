// Copyright (c) 2026 CompanionShare. MIT License.
package dev.companionshare.owner

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.graphics.drawable.Icon
import android.util.Log
import dev.companionshare.common.RtcPeer
import dev.companionshare.common.SecretStore
import dev.companionshare.common.SignalClient
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate

/** Owner-visible, revocable foreground service. Never starts from BOOT_COMPLETED. */
class ShareService : Service() {
    companion object {
        const val ACTION_ARM = "dev.companionshare.owner.ARM"
        const val ACTION_PAUSE = "dev.companionshare.owner.PAUSE"
        const val ACTION_PAIR_CREATE = "dev.companionshare.owner.PAIR_CREATE"
        const val ACTION_PAIR_APPROVE = "dev.companionshare.owner.PAIR_APPROVE"
        const val ACTION_PAIR_REJECT = "dev.companionshare.owner.PAIR_REJECT"
        const val ACTION_UNPAIR = "dev.companionshare.owner.UNPAIR"
        const val ACTION_SCREEN_GRANT = "dev.companionshare.owner.SCREEN_GRANT"
        const val ACTION_SCREEN_DENY = "dev.companionshare.owner.SCREEN_DENY"
        const val ACTION_CHAT = "dev.companionshare.owner.CHAT"
        const val ACTION_EVENT = "dev.companionshare.owner.EVENT"
        private const val TAG = "CompanionShare"
        private const val CHANNEL = "companionshare-owner"
        private const val NOTIFY = 8421
        private val BASE_TYPES = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        @Volatile var armed = false
            private set
        @Volatile var pendingPairId: String? = null
            private set
        @Volatile var pendingScreenId: String? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var signal: SignalClient? = null
    private var session: OwnerCapture? = null
    private var request: JSONObject? = null
    private var screenGrant: Intent? = null
    private var screenServiceTypeActive = false
    private var iceServers = JSONArray()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> arm()
            ACTION_PAUSE -> pause()
            ACTION_PAIR_CREATE -> if (!pendingUnpair()) signal?.send("owner.pair.create") else Unit
            ACTION_PAIR_APPROVE -> {
                val id = intent.getStringExtra("id")
                if (id != null && id == pendingPairId) {
                    signal?.send("owner.pair.approve") { put("requestId", id) }
                    pendingPairId = null
                    updateNotification()
                }
            }
            ACTION_PAIR_REJECT -> {
                pendingPairId?.let { id -> signal?.send("owner.pair.reject") { put("requestId", id) } }
                pendingPairId = null
                if (armed) updateNotification()
            }
            ACTION_UNPAIR -> {
                endCapture()
                getSharedPreferences("companionshare", MODE_PRIVATE).edit()
                    .putBoolean("pending_unpair", true).commit()
                signal?.send("owner.arm") { put("armed", false) }
                signal?.send("owner.unpair")
                pendingPairId = null
                if (armed) updateNotification()
            }
            ACTION_SCREEN_GRANT -> grantScreen(intent)
            ACTION_SCREEN_DENY -> {
                request?.optString("requestId")?.let { id ->
                    signal?.send("owner.session.reject") { put("requestId", id) }
                }
                pendingScreenId = null
                request = null
            }
            ACTION_CHAT -> {
                val text = intent.getStringExtra("text")?.take(2000)?.trim()
                if (!text.isNullOrEmpty() && session?.peer?.sendChat(text) != true) {
                    signal?.send("chat.send") { put("text", text) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun arm() {
        if (armed) return
        val permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            inform(getString(R.string.error_permission)); stopSelf(); return
        }
        // NOTE: Sensitive service type is started while Owner Activity is visible.
        try { startForeground(NOTIFY, notification(), BASE_TYPES) }
        catch (e: Exception) { inform(e.message ?: "FOREGROUND_SERVICE_DENIED"); stopSelf(); return }
        armed = true
        inform(getString(R.string.armed))
        val auth = try {
            val credential = SecretStore.owner(this)
            JSONObject().put("type", "owner.register")
                .put("deviceId", credential.deviceId).put("secret", credential.token).also {
                    SecretStore.enrollment(this)?.let { setup -> it.put("enrollmentKey", setup) }
                }
        } catch (error: Exception) {
            Log.e(TAG, "Android Keystore credential initialization failed", error)
            inform(getString(R.string.error_credentials))
            pause()
            return
        }
        val url = SecretStore.server(this)
        signal = SignalClient(::handle, { online ->
            inform(getString(if (online) R.string.server_online else R.string.server_offline))
            if (!online) endCapture()
        }).also { channel ->
            channel.start(url, auth)
        }
    }

    private fun handle(message: JSONObject) {
        when (message.optString("type")) {
            "owner.ready" -> {
                SecretStore.clearEnrollment(this)
                // NOTE: Enrollment is a one-time capability, never resend it on reconnect.
                SecretStore.owner(this).let { credentials ->
                    signal?.updateAuthentication(JSONObject().put("type", "owner.register")
                        .put("deviceId", credentials.deviceId).put("secret", credentials.token))
                }
                if (pendingUnpair()) signal?.send("owner.unpair")
                else if (armed) signal?.send("owner.arm") { put("armed", true) }
            }
            "pair.code" -> inform(getString(R.string.pair_code, message.getString("code")))
            "pair.request" -> {
                pendingPairId = message.getString("requestId")
                inform(getString(R.string.notification_pair))
                updateNotification()
            }
            "pair.completed" -> {
                inform(getString(R.string.paired_done))
                updateNotification()
            }
            "owner.unpaired" -> {
                getSharedPreferences("companionshare", MODE_PRIVATE).edit()
                    .putBoolean("pending_unpair", false).apply()
                inform(getString(R.string.unpaired_done))
                if (armed) signal?.send("owner.arm") { put("armed", true) }
            }
            "session.request" -> incomingSession(message)
            "session.started" -> {
                if (!armed || message.optString("requestId") != request?.optString("requestId")) return
                iceServers = message.optJSONArray("iceServers") ?: JSONArray()
                startCapture()
            }
            "session.ended" -> { endCapture(); inform(getString(R.string.view_ended)) }
            "signal.answer" -> {
                if (message.optString("requestId") == request?.optString("requestId")) {
                    session?.peer?.acceptAnswer(message.getString("sdp"))
                }
            }
            "signal.ice" -> {
                if (message.optString("requestId") == request?.optString("requestId")) {
                    session?.peer?.candidate(IceCandidate(message.optString("sdpMid"),
                        message.optInt("sdpMLineIndex"), message.optString("candidate")))
                }
            }
            "chat.message" -> inform(getString(R.string.from_viewer, message.optString("text")))
            "error" -> {
                val code = message.optString("code")
                inform(getString(R.string.error_unknown, code))
                if (code in setOf("DENIED", "INVALID_CREDENTIALS", "ENROLLMENT_DENIED", "DEVICE_LIMIT")) {
                    pause()
                }
            }
        }
    }

    private fun incomingSession(incoming: JSONObject) {
        endCapture()
        if (!armed || pendingUnpair()) {
            signal?.send("owner.session.reject") { put("requestId", incoming.optString("requestId")) }
            return
        }
        request = incoming
        if (incoming.optString("source") == "screen") {
            pendingScreenId = incoming.optString("requestId")
            inform(getString(R.string.notification_screen))
            updateNotification()
        } else {
            signal?.send("owner.session.accept") { put("requestId", incoming.optString("requestId")) }
        }
    }

    @Suppress("DEPRECATION")
    private fun grantScreen(intent: Intent) {
        val id = intent.getStringExtra("id")
        val grant = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("projection", Intent::class.java)
        else intent.getParcelableExtra("projection")
        if (id == null || id != pendingScreenId || grant == null) return
        // MediaProjection foreground-service type must be enabled before obtaining projection.
        try {
            startForeground(NOTIFY, notification(), BASE_TYPES or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            screenServiceTypeActive = true
        }
        catch (e: Exception) {
            signal?.send("owner.session.reject") { put("requestId", id) }
            request = null
            pendingScreenId = null
            inform(e.message ?: "PROJECTION_DENIED")
            return
        }
        screenGrant = grant
        pendingScreenId = null
        signal?.send("owner.session.accept") { put("requestId", id) }
    }

    private fun startCapture() {
        val target = request ?: return
        val id = target.optString("requestId")
        val source = target.optString("source")
        try {
            val capture = OwnerCapture(this, iceServers, object : RtcPeer.Events {
                override fun onIce(candidate: IceCandidate) {
                    signal?.send("signal.ice") {
                        put("requestId", id).put("candidate", candidate.sdp)
                            .put("sdpMid", candidate.sdpMid ?: "")
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    }
                }
                override fun onSdp(type: String, sdp: String) {
                    signal?.send("signal.offer") { put("requestId", id).put("sdp", sdp) }
                }
                override fun onRemoteVideo(track: org.webrtc.VideoTrack) = Unit
                override fun onChat(text: String) { inform(getString(R.string.from_viewer, text)) }
                override fun onFailure(reason: String) { main.post { failSession(id, reason) } }
            }) { main.post { failSession(id, "PROJECTION_STOPPED") } }
            session = capture
            capture.begin(source, target.optBoolean("audio"), screenGrant)
            screenGrant = null
            inform(getString(R.string.view_started))
            updateNotification()
        } catch (error: Exception) { failSession(id, error.message ?: "CAPTURE_FAILED") }
    }

    private fun failSession(id: String, reason: String) {
        // Old MediaProjection callbacks must not close the replacement camera session.
        if (request?.optString("requestId") != id) return
        endCapture()
        signal?.send("session.end")
        inform(getString(R.string.error_unknown, reason))
    }

    private fun pendingUnpair(): Boolean =
        getSharedPreferences("companionshare", MODE_PRIVATE)
            .getBoolean("pending_unpair", false)

    private fun endCapture() {
        val old = session
        session = null
        old?.stop()
        request = null
        screenGrant = null
        pendingScreenId = null
        if (screenServiceTypeActive) {
            screenServiceTypeActive = false
            if (armed) {
                try { startForeground(NOTIFY, notification(), BASE_TYPES) }
                catch (e: Exception) { Log.e(TAG, "Failed to restore media service type", e) }
            }
        }
        if (armed) updateNotification()
    }

    private fun pause() {
        if (!armed) { stopSelf(); return }
        endCapture()
        armed = false
        pendingPairId = null
        signal?.send("owner.arm") { put("armed", false) }
        signal?.stop()
        signal = null
        inform(getString(R.string.waiting))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pause()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, OwnerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 11,
            Intent(this, ShareService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val description = when {
            pendingScreenId != null -> getString(R.string.notification_screen)
            pendingPairId != null -> getString(R.string.notification_pair)
            session != null -> getString(R.string.notification_streaming,
                request?.optString("source") ?: "media")
            else -> getString(R.string.notification_text)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(description)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                getString(R.string.notification_pause), stop).build())
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFY, notification())
    }

    private fun inform(text: String) {
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName).putExtra("text", text))
    }
}
