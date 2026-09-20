package com.hackmit.app.audio

import com.hackmit.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Transcription result from the gateway /v1/asr/transcribe (whisper | deepgram). */
data class Transcript(
    val provider: String,
    val text: String,
    val wpm: Float,
    val confidence: Float,
    val fillerRatio: Float,
)

/** Client for the gateway's provider-switchable transcription endpoint. */
object TranscribeServer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(settings: SettingsStore, wav: ByteArray): Transcript? =
        withContext(Dispatchers.IO) {
            val config = settings.alertConfig.first()
            if (config.gatewayUrl.isBlank() || config.gatewayToken.isBlank()) return@withContext null
            val base = config.gatewayUrl.trim().trimEnd('/').substringBefore("/v1")
            val request = Request.Builder()
                .url("$base/v1/asr/transcribe")
                .header("X-Alert-Gateway-Token", config.gatewayToken)
                .header("Content-Type", "audio/wav")
                .post(wav.toRequestBody("audio/wav".toMediaType()))
                .build()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val j = JSONObject(resp.body!!.string())
                    if (j.has("error")) return@use null
                    Transcript(
                        provider = j.optString("provider", "?"),
                        text = j.optString("text", ""),
                        wpm = j.optDouble("wpm", 0.0).toFloat(),
                        confidence = j.optDouble("confidence", 0.0).toFloat(),
                        fillerRatio = j.optDouble("filler_ratio", 0.0).toFloat(),
                    )
                }
            }.getOrNull()
        }
}