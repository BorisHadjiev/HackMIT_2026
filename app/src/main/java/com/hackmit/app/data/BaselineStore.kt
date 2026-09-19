package com.hackmit.app.data

import android.content.Context
import com.hackmit.app.domain.BaselineProfile
import org.json.JSONObject
import java.io.File

/**
 * Persists the personal speech baseline as JSON in the app's files dir.
 * Features-only: no raw audio is ever written.
 */
class BaselineStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, "speech_baseline.json")

    fun save(profile: BaselineProfile) {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("createdAtMs", profile.createdAtMs)
        root.put("sampleCount", profile.sampleCount)
        root.put("means", JSONObject(profile.means.mapValues { it.value.toDouble() }))
        root.put("stds", JSONObject(profile.stds.mapValues { it.value.toDouble() }))
        file.writeText(root.toString())
    }

    fun load(): BaselineProfile? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt("version", 0) < VERSION) return null
            BaselineProfile(
                createdAtMs = root.optLong("createdAtMs"),
                sampleCount = root.optInt("sampleCount"),
                means = root.optJSONObject("means").toStringMap(),
                stds = root.optJSONObject("stds").toStringMap(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun JSONObject?.toStringMap(): Map<String, Float> {
        if (this == null) return emptyMap()
        val result = mutableMapOf<String, Float>()
        keys().forEach { key -> result[key] = optDouble(key).toFloat() }
        return result
    }

    companion object {
        private const val VERSION = 2

        fun fromSamples(samples: List<com.hackmit.app.domain.FeatureVector>): BaselineProfile {
            if (samples.isEmpty()) {
                return BaselineProfile(0L, 0, emptyMap(), emptyMap())
            }
            val maps = samples.map { it.toMap() }
            val keys = maps.first().keys
            val means = mutableMapOf<String, Float>()
            val stds = mutableMapOf<String, Float>()
            keys.forEach { key ->
                val values = maps.map { it[key] ?: 0f }
                val mean = values.average().toFloat()
                val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
                means[key] = mean
                stds[key] = kotlin.math.sqrt(variance)
            }
            return BaselineProfile(
                createdAtMs = System.currentTimeMillis(),
                sampleCount = samples.size,
                means = means,
                stds = stds,
            )
        }
    }
}
