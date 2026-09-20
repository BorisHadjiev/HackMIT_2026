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

/** Server slur verdict (personal mode is the trusted path). */
data class SlurResult(
    val score: Float,
    val detected: Boolean,
    val threshold: Float,
    val mode: String,
    val confidence: String,
)

/** Client for the gateway's server-side slur classifier (/v1/slur/analyze). */
object SlurServer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Posts a WAV and returns the server verdict (null if unreachable). */
    suspend fun analyze(settings: SettingsStore, wav: ByteArray): SlurResult? = withContext(Dispatchers.IO) {
        val config = settings.alertConfig.first()
        if (config.gatewayUrl.isBlank() || config.gatewayToken.isBlank()) return@withContext null
        val base = config.gatewayUrl.trim().trimEnd('/').substringBefore("/v1")
        val request = Request.Builder()
            .url("$base/v1/slur/analyze")
            .header("X-Alert-Gateway-Token", config.gatewayToken)
            .header("Content-Type", "audio/wav")
            .post(wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val o = JSONObject(resp.body!!.string())
                if (!o.has("score")) return@use null
                SlurResult(
                    score = o.optDouble("score", 0.0).toFloat(),
                    detected = o.optBoolean("detected", false),
                    threshold = o.optDouble("threshold", 0.9447).toFloat(),
                    mode = o.optString("mode", "corpus"),
                    confidence = o.optString("confidence", "ok"),
                )
            }
        }.getOrNull()
    }

    /** Enrolls the user's voice (>= 8 s of speech) for personal-domain scoring. */
    suspend fun calibrate(settings: SettingsStore, wav: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val config = settings.alertConfig.first()
        if (config.gatewayUrl.isBlank() || config.gatewayToken.isBlank()) return@withContext false
        val base = config.gatewayUrl.trim().trimEnd('/').substringBefore("/v1")
        val request = Request.Builder()
            .url("$base/v1/slur/calibrate")
            .header("X-Alert-Gateway-Token", config.gatewayToken)
            .header("Content-Type", "audio/wav")
            .post(wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                resp.isSuccessful && JSONObject(resp.body!!.string()).optString("status") == "enrolled"
            }
        }.getOrDefault(false)
    }

    /** Returns the current server-side mode ("personal" | "corpus") or null if unreachable. */
    suspend fun mode(settings: SettingsStore): String? = withContext(Dispatchers.IO) {
        val config = settings.alertConfig.first()
        if (config.gatewayUrl.isBlank() || config.gatewayToken.isBlank()) return@withContext null
        val base = config.gatewayUrl.trim().trimEnd('/').substringBefore("/v1")
        val request = Request.Builder()
            .url("$base/v1/slur/status")
            .header("X-Alert-Gateway-Token", config.gatewayToken)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else JSONObject(resp.body!!.string()).optString("mode", "corpus")
            }
        }.getOrNull()
    }
}