package com.hackmit.app.audio

import android.util.Log
import com.hackmit.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One event from the simulated-911 voice agent. */
data class AgentEvent(
    val type: String,
    val role: String? = null,
    val text: String? = null,
)

/**
 * Streams microphone PCM to the gateway's /v1/agent/stream (Deepgram Voice Agent +
 * BYO local LLM), plays the agent's audio, and surfaces transcript events.
 *
 * The dispatcher prompt/greeting and all API keys stay on the gateway; this client
 * only passes the caller context and audio. No telephony is involved.
 */
class AgentStream(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null

    fun start(
        context: Map<String, String>,
        auto: Boolean = false,
        onEvent: (AgentEvent) -> Unit,
        onAudio: (ByteArray) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        scope.launch {
            val cfg = settings.alertConfig.first()
            if (cfg.gatewayUrl.isBlank() || cfg.gatewayToken.isBlank()) {
                onStatus("Gateway not configured")
                return@launch
            }
            val base = cfg.gatewayUrl.trim().trimEnd('/').substringBefore("/v1")
            val wsBase = base.replace("https://", "wss://").replace("http://", "ws://")
            val query = buildString {
                append("?token=").append(enc(cfg.gatewayToken))
                if (auto) append("&auto=1")
                context.forEach { (k, v) -> append("&").append(k).append("=").append(enc(v)) }
            }
            val request = Request.Builder().url("$wsBase/v1/agent/stream$query").build()
            ws = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        onStatus("Connected")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val event = parse(text) ?: return
                        if (event.type == "ConversationText") {
                            Log.d("AgentStream", "${event.role}: ${event.text}")
                        }
                        onEvent(event)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        onAudio(bytes.toByteArray())
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w("AgentStream", "ws failure: ${t.message}")
                        onStatus("Disconnected: ${t.message ?: "error"}")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        onStatus("Ended")
                    }
                },
            )
        }
    }

    fun send(pcm: ByteArray) {
        ws?.send(pcm.toByteString())
    }

    fun stop() {
        runCatching { ws?.close(1000, "bye") }
        ws = null
    }

    private fun parse(text: String): AgentEvent? = runCatching {
        val o = JSONObject(text)
        AgentEvent(
            type = o.optString("type"),
            role = o.optString("role").ifBlank { null },
            text = o.optString("content").ifBlank { null },
        )
    }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}