package com.hackmit.app.audio

import android.content.Context
import android.media.MediaPlayer
import com.hackmit.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches synthesized speech from the local gateway (gx10 /v1/tts) and plays it.
 * The gateway owns the TTS engine; the APK never ships a TTS model.
 */
object Tts {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun synthesize(settings: SettingsStore, text: String): ByteArray? = withContext(Dispatchers.IO) {
        val config = settings.alertConfig.first()
        if (config.gatewayUrl.isBlank() || config.gatewayToken.isBlank()) return@withContext null
        val base = config.gatewayUrl.trim().trimEnd('/').substringBefore("/v1")
        val body = JSONObject().put("text", text).toString()
        val request = Request.Builder()
            .url("$base/v1/tts")
            .header("X-Alert-Gateway-Token", config.gatewayToken)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().use { if (it.isSuccessful) it.body?.bytes() else null } }
            .getOrNull()
    }

    suspend fun speak(context: Context, settings: SettingsStore, text: String, onError: (String) -> Unit = {}) {
        val wav = synthesize(settings, text)
        if (wav == null) {
            onError("Set an alert gateway URL + token in Settings to enable local voice.")
            return
        }
        val file = File(context.cacheDir, "tts_${System.currentTimeMillis()}.wav")
        file.writeBytes(wav)
        withContext(Dispatchers.Main) {
            runCatching {
                val player = MediaPlayer()
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener { it.release(); file.delete() }
                player.setOnErrorListener { _, _, _ -> player.release(); file.delete(); true }
                player.prepare()
                player.start()
            }.onFailure { onError(it.message ?: "Could not play voice") }
        }
    }
}