package com.hackmit.app.audio

import com.hackmit.app.data.SettingsStore
import kotlinx.coroutines.flow.first

/** A resolved Deepgram connection: token + WebSocket endpoint. */
data class DeepgramRoute(
    val token: String,
    val endpoint: String,
)

/**
 * Decides whether to stream through the backend Deepgram proxy or hit Deepgram
 * directly. Proxy mode is used when both a proxy URL and a gateway token are
 * configured; the gateway token authenticates the proxy connection.
 */
object DeepgramRouter {

    /** Route for the continuous monitor (long-lived DeepgramStream session). */
    suspend fun forStream(settings: SettingsStore): DeepgramRoute {
        val config = settings.alertConfig.first()
        val proxy = settings.deepgramProxyUrl.first()
        val key = settings.deepgramKey.first()
        return if (proxy.isNotBlank() && config.gatewayToken.isNotBlank()) {
            DeepgramRoute(config.gatewayToken, "$proxy?${DeepgramStream.DEFAULT_QUERY}")
        } else {
            DeepgramRoute(key, DeepgramStream.DEFAULT_ENDPOINT)
        }
    }

    /** Route for one-off speech calibration/test sessions (DeepgramClient). */
    suspend fun forSession(settings: SettingsStore): DeepgramRoute {
        val config = settings.alertConfig.first()
        val proxy = settings.deepgramProxyUrl.first()
        val key = settings.deepgramKey.first()
        return if (proxy.isNotBlank() && config.gatewayToken.isNotBlank()) {
            DeepgramRoute(config.gatewayToken, "$proxy?${DeepgramClient.DEFAULT_QUERY}")
        } else {
            DeepgramRoute(key, DeepgramClient.DEFAULT_ENDPOINT)
        }
    }

    /** Non-suspend predicate for composable state reads. */
    fun isProxy(proxyUrl: String, gatewayToken: String): Boolean =
        proxyUrl.isNotBlank() && gatewayToken.isNotBlank()
}