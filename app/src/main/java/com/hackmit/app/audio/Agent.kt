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

/** Client for the gateway's local voice agent (Ollama). */
object Agent {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun ask(settings: SettingsStore, question: String, taskContext: String = ""): String? =
        withContext(Dispatchers.IO) {
            val config = settings.alertConfig.first()
            if (config.gatewayUrl.isBlank() || config.gatewayToken.isBlank()) return@withContext null
            val base = config.gatewayUrl.trim().trimEnd('/').substringBefore("/v1")
            val body = JSONObject()
                .put("question", question)
                .put("task_context", taskContext)
                .toString()
            val request = Request.Builder()
                .url("$base/v1/agent")
                .header("X-Alert-Gateway-Token", config.gatewayToken)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else JSONObject(resp.body!!.string()).optString("answer").ifBlank { null }
                }
            }.getOrNull()
        }
}