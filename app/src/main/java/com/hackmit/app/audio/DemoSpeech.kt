package com.hackmit.app.audio

import android.content.Context
import com.hackmit.app.data.BaselineStore
import com.hackmit.app.domain.AlertLevel
import com.hackmit.app.domain.BaselineProfile
import com.hackmit.app.domain.FeatureVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One analyzed 2 s window of a demo clip. */
data class DemoWindow(
    val features: FeatureVector,
    val score: Float,
    val raw: Float,
    val level: AlertLevel,
    val reasons: List<String>,
)

/**
 * Offline demo: feeds bundled WAV samples through the exact on-device DSP and
 * detector used by the live monitor, so we can demonstrate slur detection on
 * real/synthesized recordings without a microphone.
 */
object DemoSpeech {

    suspend fun decodeWav(context: Context, asset: String): FloatArray = withContext(Dispatchers.IO) {
        val bytes = context.assets.open(asset).readBytes()
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val size = leInt(bytes, i + 4)
            if (id == "data") {
                val end = minOf(i + 8 + size, bytes.size)
                val pcm = bytes.copyOfRange(i + 8, end)
                val out = FloatArray(pcm.size / 2)
                for (k in out.indices) {
                    val lo = pcm[k * 2].toInt() and 0xFF
                    val hi = pcm[k * 2 + 1].toInt()
                    out[k] = (((hi shl 8) or lo).toShort()).toFloat() / 32768f
                }
                return@withContext out
            }
            i += 8 + size + (size and 1)
        }
        floatArrayOf()
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    /** 2 s windows of features with 1 s overlap (mirrors the monitor's 2 s windows,
     *  but hops faster so short demo clips still yield enough baseline samples). */
    fun windows(samples: FloatArray, sampleRate: Int = 16_000, hopSeconds: Float = 1f): List<FeatureVector> {
        val winLen = sampleRate * 2
        val hopLen = (sampleRate * hopSeconds).toInt()
        val out = mutableListOf<FeatureVector>()
        var start = 0
        while (start + winLen <= samples.size) {
            val extractor = AcousticFeatureExtractor(sampleRate)
            val shorts = ShortArray(winLen) { (samples[start + it] * 32767).toInt().toShort() }
            extractor.add(shorts)
            out.add(extractor.compute())
            start += hopLen
        }
        return out
    }

    fun buildBaseline(samples: FloatArray, sampleRate: Int = 16_000): BaselineProfile =
        BaselineStore.fromSamples(windows(samples, sampleRate))

    fun score(
        windows: List<FeatureVector>,
        baseline: BaselineProfile?,
        sensitivity: Float = 0.75f,
    ): List<DemoWindow> {
        val detector = SlurDetector(baseline, sensitivity)
        return windows.map { fv ->
            val assessment = detector.update(fv)
            DemoWindow(fv, assessment.score, assessment.raw, assessment.level, assessment.reasons)
        }
    }
}