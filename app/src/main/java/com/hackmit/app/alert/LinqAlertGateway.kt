package com.hackmit.app.alert

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Gateway contract implemented by the app-owned service that calls Linq. */
interface AlertGateway {
    suspend fun deliver(draft: AlertDraft, config: AlertConfig): AlertDelivery
}

/**
 * Posts a stable, idempotent alert envelope to an app-owned backend.
 *
 * The backend should authenticate the app/user, enforce a trusted-contact allowlist,
 * then use Linq's Partner API with its server-side integration token. It should return
 * a JSON response containing an optional `trace_id` for delivery troubleshooting.
 */
class LinqAlertGateway(
    private val client: OkHttpClient = OkHttpClient(),
) : AlertGateway {

    override suspend fun deliver(draft: AlertDraft, config: AlertConfig): AlertDelivery {
        if (config.gatewayUrl.isBlank() || config.trustedContactPhone.isBlank()) {
            return AlertDelivery(
                status = AlertDeliveryStatus.NOT_CONFIGURED,
                message = "Add an alert gateway URL and trusted-contact phone number in Settings.",
            )
        }
        val gatewayUrl = config.gatewayUrl.toHttpUrlOrNull()
        if (gatewayUrl == null || !gatewayUrl.isHttps) {
            return AlertDelivery(
                status = AlertDeliveryStatus.NOT_CONFIGURED,
                message = "Use a valid HTTPS alert gateway URL in Settings.",
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("alert_id", draft.id)
                    put("source", draft.source.name.lowercase())
                    put("kind", draft.kind.name.lowercase())
                    put("severity", draft.severity.name.lowercase())
                    put("title", draft.title)
                    put("body", draft.body)
                    put("created_at_ms", draft.createdAtMs)
                    put("recipient", JSONObject().apply {
                        put("name", config.trustedContactName)
                        put("phone", config.trustedContactPhone)
                    })
                }
                val request = Request.Builder()
                    .url(gatewayUrl)
                    .header("Idempotency-Key", draft.id)
                    .apply {
                        if (config.gatewayToken.isNotBlank()) {
                            header("X-Alert-Gateway-Token", config.gatewayToken)
                        }
                    }
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()
                    if (response.isSuccessful) {
                        val traceId = runCatching { JSONObject(responseBody).optString("trace_id") }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                        AlertDelivery(AlertDeliveryStatus.SENT, "Alert accepted for delivery.", traceId)
                    } else {
                        AlertDelivery(
                            AlertDeliveryStatus.FAILED,
                            "Alert gateway returned ${response.code}. Please try again or call directly.",
                        )
                    }
                }
            } catch (error: IOException) {
                AlertDelivery(
                    AlertDeliveryStatus.FAILED,
                    "Could not reach the alert gateway. Please call directly if this is an emergency.",
                )
            } catch (error: Exception) {
                AlertDelivery(AlertDeliveryStatus.FAILED, "Could not send alert: ${error.message}")
            }
        }
    }
}
