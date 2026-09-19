package com.hackmit.app.audio

import com.hackmit.app.domain.FeatureVector

/**
 * Accumulates the most recent ~2 s of PCM and computes acoustic features on demand.
 */
class AcousticFeatureExtractor(private val sampleRate: Int = 16_000) {

    private val windowSamples = sampleRate * 2
    private val buffer = ShortArray(windowSamples)
    private var writeIndex = 0
    private var filled = 0

    fun add(samples: ShortArray) {
        for (s in samples) {
            buffer[writeIndex] = s
            writeIndex = (writeIndex + 1) % windowSamples
            if (filled < windowSamples) filled++
        }
    }

    fun compute(): FeatureVector {
        if (filled < sampleRate / 2) return FeatureVector()
        val ordered = ordered()
        val d = DoubleArray(ordered.size) { ordered[it] / 32768.0 }
        val pitch = Dsp.pitch(d, sampleRate)
        val (jitter, shimmer) = if (pitch.voiced) {
            Dsp.jitterShimmer(d, sampleRate, pitch.f0)
        } else {
            0.0 to 0.0
        }
        return FeatureVector(
            f0Mean = pitch.f0.toFloat(),
            f0Std = Dsp.f0Std(d, sampleRate).toFloat(),
            jitter = jitter.toFloat(),
            shimmer = shimmer.toFloat(),
            hnr = pitch.hnrDb.toFloat(),
            rms = Dsp.rms(ordered).toFloat(),
            zcr = Dsp.zcr(ordered).toFloat(),
            centroid = Dsp.spectralCentroid(ordered, sampleRate).toFloat(),
            ems4hz = Dsp.envelopeModulation4Hz(d, sampleRate).toFloat(),
        )
    }

    fun reset() {
        writeIndex = 0
        filled = 0
    }

    private fun ordered(): ShortArray {
        val out = ShortArray(filled)
        val start = if (filled == windowSamples) writeIndex else 0
        for (i in 0 until filled) {
            out[i] = buffer[(start + i) % windowSamples]
        }
        return out
    }
}
