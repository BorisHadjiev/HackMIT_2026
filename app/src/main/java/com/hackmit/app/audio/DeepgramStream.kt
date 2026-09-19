package com.hackmit.app.audio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class WordInfo(
    val word: String,
    val start: Double,
    val end: Double,
    val confidence: Float,
)

data class StreamResult(
    val transcript: String,
    val isFinal: Boolean,
    val speechFinal: Boolean,
    val confidence: Float,
    val wordCount: Int,
    val words: List<WordInfo>,
    val timestampMs: Long = System.currentTimeMillis(),
)

sealed interface DeepgramEvent {
    data class Status(val text: String) : DeepgramEvent
    data class Error(val text: String) : DeepgramEvent
    data class Result(val result: StreamResult) : DeepgramEvent
    data class SpeechStarted(val timestampSec: Double) : DeepgramEvent
    data class UtteranceEnd(val lastWordEndSec: Double) : DeepgramEvent
}

/**
 * Persistent Deepgram streaming session with rich event parsing, KeepAlive during
 * silence, and exponential-backoff reconnect. Audio is only sent while speech is
 * present; silence is kept alive so one session can run for a long time.
 */
class DeepgramStream(
    private val apiKey: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val events = MutableSharedFlow<DeepgramEvent>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<DeepgramEvent> = events

    private var webSocket: WebSocket? = null
    private var keepAliveJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var ready = false

    @Volatile
    private var closedByUser = false

    private var reconnectAttempts = 0

    fun start(scope: CoroutineScope) {
        closedByUser = false
        reconnectAttempts = 0
        open(scope)
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_MS)
                if (ready) webSocket?.send("{\"type\":\"KeepAlive\"}")
            }
        }
    }

    private fun open(scope: CoroutineScope) {
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Token $apiKey")
            .build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ready = true
                    reconnectAttempts = 0
                    Log.d(TAG, "onOpen")
                    events.tryEmit(DeepgramEvent.Status("Connected"))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parse(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ready = false
                    Log.w(TAG, "onFailure: ${t.message}", t)
                    events.tryEmit(DeepgramEvent.Error(t.message ?: "connection failed"))
                    scheduleReconnect(scope)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    ready = false
                    Log.d(TAG, "onClosed code=$code reason=$reason")
                    if (!closedByUser) scheduleReconnect(scope)
                }
            },
        )
    }

    private fun scheduleReconnect(scope: CoroutineScope) {
        if (closedByUser) return
        reconnectJob?.cancel()
        val attempt = ++reconnectAttempts
        val delayMs = (500L shl (attempt.coerceAtMost(4))).coerceAtMost(8000L)
        events.tryEmit(DeepgramEvent.Status("Reconnecting in ${delayMs}ms"))
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!closedByUser) open(scope)
        }
    }

    private fun parse(text: String) {
        try {
            val json = JSONObject(text)
            when (val type = json.optString("type")) {
                "Results" -> parseResults(json)
                "SpeechStarted" -> events.tryEmit(
                    DeepgramEvent.SpeechStarted(json.optDouble("timestamp", 0.0)),
                )
                "UtteranceEnd" -> events.tryEmit(
                    DeepgramEvent.UtteranceEnd(json.optDouble("last_word_end", 0.0)),
                )
                "Error" -> {
                    val msg = json.optString("err_msg").ifBlank { text }
                    Log.w(TAG, "server error: $msg")
                    events.tryEmit(DeepgramEvent.Error(msg))
                }
                else -> Log.d(TAG, "msg type=$type")
            }
        } catch (e: Exception) {
            events.tryEmit(DeepgramEvent.Error("parse: ${e.message}"))
        }
    }

    private fun parseResults(json: JSONObject) {
        val alt = json.getJSONObject("channel")
            .getJSONArray("alternatives")
            .getJSONObject(0)
        val transcript = alt.optString("transcript")
        val wordsArray = alt.optJSONArray("words")
        val words = mutableListOf<WordInfo>()
        if (wordsArray != null) {
            for (i in 0 until wordsArray.length()) {
                val w = wordsArray.getJSONObject(i)
                words += WordInfo(
                    word = w.optString("punctuated_word").ifBlank { w.optString("word") },
                    start = w.optDouble("start", 0.0),
                    end = w.optDouble("end", 0.0),
                    confidence = w.optDouble("confidence", 0.0).toFloat(),
                )
            }
        }
        var confidence = alt.optDouble("confidence", 0.0).toFloat()
        if (words.isNotEmpty() && confidence <= 0f) {
            confidence = words.map { it.confidence }.average().toFloat()
        }
        if (transcript.isBlank() && words.isEmpty()) return
        Log.d(TAG, "Results final=${json.optBoolean("is_final", false)} speechFinal=${json.optBoolean("speech_final", false)} \"$transcript\"")
        events.tryEmit(
            DeepgramEvent.Result(
                StreamResult(
                    transcript = transcript,
                    isFinal = json.optBoolean("is_final", false),
                    speechFinal = json.optBoolean("speech_final", false),
                    confidence = confidence,
                    wordCount = words.size,
                    words = words,
                ),
            ),
        )
    }

    fun send(pcm: ByteArray) {
        if (ready) webSocket?.send(pcm.toByteString())
    }

    fun stop() {
        closedByUser = true
        keepAliveJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.send("{\"type\":\"CloseStream\"}")
        webSocket?.close(1000, null)
        webSocket = null
        ready = false
    }

    companion object {
        private const val TAG = "DG"
        const val KEEPALIVE_MS = 5000L
        const val DEFAULT_QUERY: String =
            "model=nova-3&language=en&smart_format=true" +
                "&interim_results=true&filler_words=true&encoding=linear16&sample_rate=16000" +
                "&channels=1&endpointing=300&utterance_end_ms=1000&vad_events=true"
        const val DEFAULT_ENDPOINT: String = "wss://api.deepgram.com/v1/listen?$DEFAULT_QUERY"
    }
}
