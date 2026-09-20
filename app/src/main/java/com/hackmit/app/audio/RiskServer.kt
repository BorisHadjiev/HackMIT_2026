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

/** Result of a backend /v1/risk/assess call. */
data class RiskResult(
    val riskId: String,
    val pStroke: Float,
    val severity: Float,
    val action: String,
    val timeCritical: Boolean,
    val rationale: List<String>,
)

/** Client for the gateway's stroke-risk decision layer (/v1/risk/assess). */
object RiskServer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    suspend fun assess(
        settings: SettingsStore,
        modules: Map<String, Triple<Float, String, Float>>,
        onsetMinutes: Int?,
        abrupt: Boolean,
        policy: String = "sensitive",
    ): RiskResult? = withContext(Dispatchers.IO) {
        val config = settings.alertConfig.first()
        if (config.gatewayUrl.isBlank() || config.gatewayToken.isBlank()) return@withContext null
        val base = config.gatewayUrl.trim().trimEnd('/').substringBefore("/v1")

        val mods = JSONObject()
        modules.forEach { (name, t) ->
            mods.put(
                name,
                JSONObject().put("score", t.first).put("outcome", t.second).put("quality", t.third),
            )
        }
        val ctx = JSONObject()
        if (onsetMinutes != null) ctx.put("onset_minutes", onsetMinutes)
        ctx.put("abrupt", abrupt)
        val payload = JSONObject()
            .put("modules", mods)
            .put("context", ctx)
            .put("policy", policy)

        val request = Request.Builder()
            .url("$base/v1/risk/assess")
            .header("X-Alert-Gateway-Token", config.gatewayToken)
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val j = JSONObject(resp.body!!.string())
                val rationale = mutableListOf<String>()
                j.optJSONArray("rationale")?.let { arr ->
                    for (i in 0 until arr.length()) rationale.add(arr.getString(i))
                }
                RiskResult(
                    riskId = j.optString("risk_id"),
                    pStroke = j.optDouble("p_stroke", 0.0).toFloat(),
                    severity = j.optDouble("severity", 0.0).toFloat(),
                    action = j.optString("action", "MONITOR"),
                    timeCritical = j.optBoolean("time_critical", false),
                    rationale = rationale,
                )
            }
        }.getOrNull()
    }
}