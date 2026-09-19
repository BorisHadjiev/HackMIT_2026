package com.hackmit.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DspTest {

    private val sampleRate = 16_000

    private fun sine(freq: Double, seconds: Double, amplitude: Double = 0.6): DoubleArray {
        val n = (sampleRate * seconds).toInt()
        return DoubleArray(n) { amplitude * sin(2 * PI * freq * it / sampleRate) }
    }

    private fun toShorts(values: DoubleArray): ShortArray =
        ShortArray(values.size) { (values[it] * 32767).toInt().toShort() }

    @Test
    fun detectsPitchOfSine() {
        val p = Dsp.pitch(sine(440.0, 0.5), sampleRate)
        assertTrue("should be voiced", p.voiced)
        assertEquals(440.0, p.f0, 12.0)
    }

    @Test
    fun silenceIsUnvoiced() {
        assertFalse(Dsp.pitch(DoubleArray(sampleRate / 2), sampleRate).voiced)
    }

    @Test
    fun rmsOfFullScaleSquare() {
        val square = ShortArray(1000) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertEquals(1.0, Dsp.rms(square), 0.05)
    }

    @Test
    fun zcrMatchesFrequency() {
        val expected = 2 * 440.0 / sampleRate
        assertEquals(expected, Dsp.zcr(toShorts(sine(440.0, 0.5))), 0.01)
    }

    @Test
    fun cleanSineHasLowJitterAndShimmer() {
        val d = sine(150.0, 1.0)
        val p = Dsp.pitch(d, sampleRate)
        assertTrue(p.voiced)
        val (jitter, shimmer) = Dsp.jitterShimmer(d, sampleRate, p.f0)
        assertTrue("jitter=$jitter", jitter < 0.05)
        assertTrue("shimmer=$shimmer", shimmer < 0.1)
    }

    @Test
    fun spectralCentroidOfSineNearItsFrequency() {
        val centroid = Dsp.spectralCentroid(toShorts(sine(1000.0, 0.1)), sampleRate)
        assertEquals(1000.0, centroid, 250.0)
    }

    @Test
    fun fftPeakAtExpectedBin() {
        val n = 1024
        val re = DoubleArray(n) { sin(2 * PI * 64 * it / n) }
        val im = DoubleArray(n)
        Dsp.fft(re, im)
        var bestBin = 0
        var bestMag = 0.0
        for (k in 1 until n / 2) {
            val mag = re[k] * re[k] + im[k] * im[k]
            if (mag > bestMag) {
                bestMag = mag
                bestBin = k
            }
        }
        assertEquals(64, bestBin)
    }
}
