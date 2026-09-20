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

/** Sends the user's voice sample to the gateway for speaker enrollment (gating). */
object Speaker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun enroll(settings: SettingsStore, pcm: ByteArray): String = withContext(Dispatchers.IO) {
        val config = settings.alertConfig.first()
        if (config.gatewayUrl.isBlank() || config.gatewayToken.isBlank()) {
            return@withContext "Set a gateway URL + token in Settings to enroll."
        }
        val base = config.gatewayUrl.trim().trimEnd('/').substringBefore("/v1")
        val request = Request.Builder()
            .url("$base/v1/speaker/enroll")
            .header("X-Alert-Gateway-Token", config.gatewayToken)
            .post(pcm.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) "enroll failed (${resp.code})"
                else "Enrolled: " + JSONObject(resp.body!!.string()).optString("status")
            }
        }.getOrDefault("enroll failed")
    }
}