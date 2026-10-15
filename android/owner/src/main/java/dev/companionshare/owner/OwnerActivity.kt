// Copyright (c) 2026 CompanionShare. MIT License.
package dev.companionshare.owner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.companionshare.common.SecretStore

/** Every sensitive session is enabled or revoked here, using explicit Owner actions. */
class OwnerActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var enrollment: EditText
    private lateinit var status: TextView
    private lateinit var chat: EditText
    private var shownPair: String? = null
    private var shownScreen: String? = null
    private val permissionCode = 301
    private val captureCode = 302

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("text")?.let { status.append("\n$it") }
            ShareService.takePendingChats().forEach { status.append("\n$it") }
            inspectRequests()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 30, 28, 24)
        }
        fun label(res: Int) { column.addView(TextView(this).apply { setText(res); textSize = 20f }) }
        fun button(res: Int, click: () -> Unit) {
            column.addView(Button(this).apply { setText(res); setOnClickListener { click() } })
        }
        label(R.string.title)
        url = EditText(this).apply {
            hint = getString(R.string.server_hint)
            setText(SecretStore.server(this@OwnerActivity))
            isSingleLine = true
        }
        column.addView(url)
        enrollment = EditText(this).apply {
            hint = getString(R.string.enrollment_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        column.addView(enrollment)
        button(R.string.arm) { beginSharing() }
        button(R.string.pause) { action(ShareService.ACTION_PAUSE) }
        button(R.string.pair) { action(ShareService.ACTION_PAIR_CREATE) }
        button(R.string.unpair) {
            AlertDialog.Builder(this).setMessage(R.string.unpair_confirm)
                .setPositiveButton(R.string.allow) { _, _ -> action(ShareService.ACTION_UNPAIR) }
                .setNegativeButton(R.string.cancel, null).show()
        }
        chat = EditText(this).apply { hint = getString(R.string.chat_hint); maxLines = 3 }
        column.addView(chat)
        button(R.string.send) {
            val text = chat.text.toString().trim()
            if (text.isNotEmpty()) {
                action(ShareService.ACTION_CHAT, "text", text)
                status.append("\n${getString(R.string.chat_sent, text)}")
                chat.text.clear()
            }
        }
        status = TextView(this).apply { setText(R.string.waiting); textSize = 16f }
        column.addView(status)
        setContentView(ScrollView(this).apply { addView(column) })
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(events, IntentFilter(ShareService.ACTION_EVENT), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(events, IntentFilter(ShareService.ACTION_EVENT))
        }
    }

    override fun onResume() {
        super.onResume()
        inspectRequests()
        val state = when {
            ShareService.armed -> R.string.armed
            ShareService.standbyRunning -> R.string.standby
            else -> R.string.waiting
        }
        status.append("\n${getString(state)}")
        ShareService.takePendingChats().forEach { status.append("\n$it") }
        getSystemService(NotificationManager::class.java).cancel(ShareService.CHAT_NOTIFY)
        val local = getSharedPreferences("companionshare", MODE_PRIVATE)
        if (local.getBoolean("boot_needs_rearm", false)) {
            status.append("\n${getString(R.string.needs_rearm)}")
            local.edit().putBoolean("boot_needs_rearm", false).apply()
        }
    }

    private fun beginSharing() {
        try { SecretStore.saveServer(this, url.text.toString().trim()) }
        catch (_: Exception) { status.append("\n${getString(R.string.error_server)}"); return }
        if (enrollment.text.isNotBlank()) {
            try {
                SecretStore.saveEnrollment(this, enrollment.text.toString().trim())
                enrollment.text.clear()
            } catch (_: Exception) { status.append("\n${getString(R.string.bad_enrollment)}"); return }
        }
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), permissionCode)
        else startForegroundService(Intent(this, ShareService::class.java).setAction(ShareService.ACTION_ARM))
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != permissionCode) return
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) beginSharing()
        else status.append("\n${getString(R.string.error_permission)}")
    }

    private fun inspectRequests() {
        val pair = ShareService.pendingPairId
        if (pair != null && pair != shownPair) {
            shownPair = pair
            AlertDialog.Builder(this).setMessage(R.string.confirm_pair)
                .setPositiveButton(R.string.allow) { _, _ ->
                    action(ShareService.ACTION_PAIR_APPROVE, "id", pair)
                }
                .setNegativeButton(R.string.deny) { _, _ ->
                    action(ShareService.ACTION_PAIR_REJECT)
                }.show()
        }
        val screen = ShareService.pendingScreenId
        if (screen != null && screen != shownScreen) {
            shownScreen = screen
            AlertDialog.Builder(this).setMessage(R.string.screen_consent)
                .setPositiveButton(R.string.allow) { _, _ ->
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(manager.createScreenCaptureIntent(), captureCode)
                }
                .setNegativeButton(R.string.deny) { _, _ ->
                    action(ShareService.ACTION_SCREEN_DENY)
                }.show()
        }
    }

    @Deprecated("Used for MediaProjection system consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureCode) return
        val id = ShareService.pendingScreenId ?: return
        if (resultCode == RESULT_OK && data != null) {
            val service = Intent(this, ShareService::class.java)
                .setAction(ShareService.ACTION_SCREEN_GRANT)
                .putExtra("id", id).putExtra("projection", data)
            startService(service)
        } else action(ShareService.ACTION_SCREEN_DENY)
    }

    private fun action(command: String, key: String? = null, value: String? = null) {
        val intent = Intent(this, ShareService::class.java).setAction(command)
        if (key != null && value != null) intent.putExtra(key, value)
        // An already-active service processes commands; never start sensitive capture from background.
        if (ShareService.armed || ShareService.standbyRunning) startService(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(events)
        super.onDestroy()
    }
}
