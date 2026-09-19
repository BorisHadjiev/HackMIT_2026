package com.hackmit.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AcousticFeatureExtractorTest {

    private val sampleRate = 16_000

    @Test
    fun extractorRecoversPitchFromStreamedSine() {
        val extractor = AcousticFeatureExtractor(sampleRate)
        val freq = 180.0
        val n = sampleRate
        val chunk = ShortArray(512)
        var i = 0
        while (i < n) {
            for (k in chunk.indices) {
                chunk[k] = (0.6 * 32767 * sin(2 * PI * freq * (i + k) / sampleRate)).toInt().toShort()
            }
            extractor.add(chunk)
            i += chunk.size
        }
        val features = extractor.compute()
        assertTrue("f0=${features.f0Mean}", features.f0Mean > 150f && features.f0Mean < 210f)
        assertTrue("rms=${features.rms}", features.rms > 0.3f)
    }

    @Test
    fun silenceProducesEmptyFeatures() {
        val extractor = AcousticFeatureExtractor(sampleRate)
        extractor.add(ShortArray(1024))
        assertEquals(0f, extractor.compute().f0Mean, 0.001f)
    }
}
