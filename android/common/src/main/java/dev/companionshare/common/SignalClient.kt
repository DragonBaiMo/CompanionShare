// Copyright (c) 2026 CompanionShare. MIT License.
package dev.companionshare.common

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** TLS-only signaling channel. All callbacks run on the Android main thread. */
class SignalClient(
    private val onMessage: (JSONObject) -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(60, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var running = false
    private var attempts = 0
    private var endpoint = ""
    private var authentication = JSONObject()
    private val reconnect = Runnable { connect() }

    fun start(url: String, auth: JSONObject) {
        require(url.startsWith("wss://") && url.endsWith("/ws"))
        stop()
        endpoint = url
        authentication = JSONObject(auth.toString())
        running = true
        attempts = 0
        connect()
    }

    /** Replace the reconnect identity after a one-time pairing or enrollment completes. */
    fun updateAuthentication(auth: JSONObject) {
        authentication = JSONObject(auth.toString())
    }

    private fun connect() {
        if (!running) return
        val request = Request.Builder().url(endpoint).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (!running || socket !== webSocket) return@post
                    attempts = 0
                    onState(true)
                    webSocket.send(authentication.toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post {
                    if (!running || socket !== webSocket) return@post
                    try { onMessage(JSONObject(text)) } catch (_: Exception) { /* Malformed remote payload */ }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { lost(webSocket) }
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                main.post { lost(webSocket) }
            }
        })
    }

    private fun lost(webSocket: WebSocket) {
        if (!running || socket !== webSocket) return
        socket = null
        onState(false)
        attempts = (attempts + 1).coerceAtMost(6)
        main.removeCallbacks(reconnect)
        val backoff = minOf(30_000L, 1_000L shl attempts)
        main.postDelayed(reconnect, backoff)
    }

    fun send(type: String, configure: JSONObject.() -> Unit = {}): Boolean {
        val message = JSONObject().put("type", type).apply(configure)
        return socket?.send(message.toString()) == true
    }

    fun stop() {
        running = false
        main.removeCallbacks(reconnect)
        socket?.close(1000, "owner stopped")
        socket = null
    }
}
