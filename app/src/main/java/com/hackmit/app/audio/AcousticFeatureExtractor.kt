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

        // Pitch/jitter/HNR over voiced 40 ms sub-frames only. Computing over the whole
        // 2 s window lets unvoiced consonants wash out the autocorrelation (negative
        // HNR, inflated jitter), so we gate on voicing like f0Std already does.
        val frame = sampleRate * 40 / 1000
        val pitches = mutableListOf<Dsp.PitchResult>()
        val voicedSegments = mutableListOf<DoubleArray>()
        var start = 0
        while (start + frame <= d.size) {
            val sub = d.copyOfRange(start, start + frame)
            val p = Dsp.pitch(sub, sampleRate)
            if (p.voiced) {
                pitches += p
                voicedSegments += sub
            }
            start += frame / 2
        }

        val rms = Dsp.rms(ordered).toFloat()
        val zcr = Dsp.zcr(ordered).toFloat()
        val centroid = Dsp.spectralCentroid(ordered, sampleRate).toFloat()
        val ems4hz = Dsp.envelopeModulation4Hz(d, sampleRate).toFloat()
        if (pitches.size < 2) {
            return FeatureVector(rms = rms, zcr = zcr, centroid = centroid, ems4hz = ems4hz)
        }

        val f0Mean = pitches.map { it.f0 }.average()
        val f0Std = kotlin.math.sqrt(pitches.map { (it.f0 - f0Mean) * (it.f0 - f0Mean) }.average())
        val hnr = pitches.map { it.hnrDb }.average()
        var jitterSum = 0.0
        var shimmerSum = 0.0
        var voicedCount = 0
        for (i in pitches.indices) {
            val (jitter, shimmer) = Dsp.jitterShimmer(voicedSegments[i], sampleRate, pitches[i].f0)
            if (jitter > 0 && shimmer > 0) {
                jitterSum += jitter
                shimmerSum += shimmer
                voicedCount++
            }
        }
        val jitter = if (voicedCount > 0) jitterSum / voicedCount else 0.0
        val shimmer = if (voicedCount > 0) shimmerSum / voicedCount else 0.0

        return FeatureVector(
            f0Mean = f0Mean.toFloat(),
            f0Std = f0Std.toFloat(),
            jitter = jitter.toFloat(),
            shimmer = shimmer.toFloat(),
            hnr = hnr.toFloat(),
            rms = rms,
            zcr = zcr,
            centroid = centroid,
            ems4hz = ems4hz,
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
