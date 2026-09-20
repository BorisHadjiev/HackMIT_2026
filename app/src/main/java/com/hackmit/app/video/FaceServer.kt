package com.hackmit.app.video

import android.graphics.Bitmap
import com.hackmit.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Client for the gateway's server-side face analysis (/v1/face/analyze). */
object FaceServer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Downscales a camera bitmap to [maxDim] and encodes it as JPEG. */
    fun jpeg(bitmap: Bitmap, maxDim: Int = 640, quality: Int = 70): ByteArray? {
        val scaled = if (maxOf(bitmap.width, bitmap.height) > maxDim) {
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true,
            )
        } else {
            bitmap
        }
        val bos = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)) return null
        return bos.toByteArray()
    }

    /** Returns null if the gateway is unreachable (so the UI can surface it). */
    suspend fun analyze(settings: SettingsStore, jpeg: ByteArray): FaceFrame? = withContext(Dispatchers.IO) {
        val config = settings.alertConfig.first()
        if (config.gatewayUrl.isBlank() || config.gatewayToken.isBlank()) return@withContext null
        val base = config.gatewayUrl.trim().trimEnd('/').substringBefore("/v1")
        val request = Request.Builder()
            .url("$base/v1/face/analyze")
            .header("X-Alert-Gateway-Token", config.gatewayToken)
            .header("Content-Type", "image/jpeg")
            .post(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val o = JSONObject(resp.body!!.string())
                FaceFrame(
                    detected = o.optBoolean("detected"),
                    asymmetry = o.optDouble("score", 0.0).toFloat(),
                    mouthDroop = o.optDouble("mouth", 0.0).toFloat(),
                    eyeAsymmetry = o.optDouble("eye", 0.0).toFloat(),
                )
            }
        }.getOrNull()
    }
}