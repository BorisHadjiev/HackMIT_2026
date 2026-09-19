package com.hackmit.app.audio

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TranscriptUpdate(
    val transcript: String,
    val isFinal: Boolean,
    val confidence: Float,
    val wordCount: Int,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Thin Deepgram real-time streaming client.
 *
 * Sends 16 kHz mono linear16 PCM over a WebSocket and surfaces transcript updates.
 *
 * NOTE: for a real deployment do NOT ship the API key in the APK. Proxy through a
 * backend (set [endpoint] to your proxy) or use Deepgram temporary keys.
 */
class DeepgramClient(
    private val apiKey: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    @Volatile
    private var ready = false

    fun connect(
        onTranscript: (TranscriptUpdate) -> Unit,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ready = true
                    onStatus("Connected to Deepgram")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseMessage(text, onTranscript, onError)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ready = false
                    onError(t.message ?: "Deepgram connection failed")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    ready = false
                    onStatus("Deepgram closed")
                }
            },
        )
    }

    private fun parseMessage(
        text: String,
        onTranscript: (TranscriptUpdate) -> Unit,
        onError: (String) -> Unit,
    ) {
        try {
            val json = JSONObject(text)
            if (json.optString("type") != "Results") return
            val alt = json.getJSONObject("channel")
                .getJSONArray("alternatives")
                .getJSONObject(0)
            val transcript = alt.optString("transcript")
            if (transcript.isBlank()) return
            val words = alt.optJSONArray("words")
            val wordCount = words?.length() ?: 0
            var confidence = alt.optDouble("confidence", 0.0).toFloat()
            if (wordCount > 0 && confidence <= 0f) {
                var sum = 0.0
                for (i in 0 until wordCount) {
                    sum += words!!.getJSONObject(i).optDouble("confidence", 0.0)
                }
                confidence = (sum / wordCount).toFloat()
            }
            onTranscript(
                TranscriptUpdate(
                    transcript = transcript,
                    isFinal = json.optBoolean("is_final", false),
                    confidence = confidence,
                    wordCount = wordCount,
                ),
            )
        } catch (e: Exception) {
            onError("Deepgram parse error: ${e.message}")
        }
    }

    fun send(pcm: ByteArray) {
        if (ready) webSocket?.send(pcm.toByteString())
    }

    fun finish() {
        webSocket?.send("{\"type\":\"CloseStream\"}")
    }

    fun close() {
        webSocket?.close(1000, null)
        webSocket = null
        ready = false
    }

    companion object {
        const val DEFAULT_ENDPOINT: String =
            "wss://api.deepgram.com/v1/listen?model=nova-2&language=en&smart_format=true" +
                "&interim_results=true&encoding=linear16&sample_rate=16000&channels=1"
    }
}
