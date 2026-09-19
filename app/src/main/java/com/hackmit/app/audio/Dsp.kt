package com.hackmit.app.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small, dependency-free DSP toolkit for the speech monitor. Everything is pure
 * Kotlin so it can be unit-tested on the JVM without Android.
 */
object Dsp {

    data class PitchResult(val f0: Double, val hnrDb: Double, val voiced: Boolean)

    /** In-place iterative radix-2 FFT. [re] and [im] must have a power-of-two length. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0
                var curI = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * curR - im[i + k + len / 2] * curI
                    val vi = re[i + k + len / 2] * curI + im[i + k + len / 2] * curR
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nR
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) {
            val v = s / 32768.0
            sum += v * v
        }
        return sqrt(sum / samples.size)
    }

    fun zcr(samples: ShortArray): Double {
        if (samples.size < 2) return 0.0
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i - 1] >= 0) != (samples[i] >= 0)) crossings++
        }
        return crossings.toDouble() / samples.size
    }

    /** Pitch (autocorrelation) plus an HNR estimate. Returns voiced=false when aperiodic. */
    fun pitch(samples: DoubleArray, sampleRate: Int): PitchResult {
        val n = samples.size
        if (n < sampleRate / 25) return PitchResult(0.0, 0.0, false)

        val mean = samples.average()
        val x = DoubleArray(n) { samples[it] - mean }
        var r0 = 0.0
        for (v in x) r0 += v * v
        if (r0 <= 1e-9) return PitchResult(0.0, 0.0, false)

        // Search 60-500 Hz.
        val minLag = (sampleRate / 500.0).toInt().coerceAtLeast(2)
        val maxLag = min((sampleRate / 60.0).toInt(), n - 2)
        if (maxLag <= minLag + 1) return PitchResult(0.0, 0.0, false)

        val r = DoubleArray(maxLag + 2)
        for (lag in (minLag - 1).coerceAtLeast(1)..maxLag + 1) {
            var sum = 0.0
            val count = n - lag
            for (i in 0 until count) sum += x[i] * x[i + lag]
            r[lag] = sum / count
        }

        val power = r0 / n
        val target = 0.5 * power

        // Pick the first strong local maximum, which avoids octave/subharmonic errors.
        var chosen = -1
        for (lag in minLag + 1 until maxLag) {
            if (r[lag] > target && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
                chosen = lag
                break
            }
        }
        if (chosen < 0) {
            var best = minLag
            for (lag in minLag..maxLag) if (r[lag] > r[best]) best = lag
            chosen = best
        }

        val bestR = r[chosen]
        val norm = (bestR / power).coerceIn(0.0, 1.0)
        if (norm < 0.3) return PitchResult(0.0, 0.0, false)

        var refinedLag = chosen.toDouble()
        val ym = r[chosen - 1]
        val y0 = r[chosen]
        val yp = r[chosen + 1]
        val denom = ym - 2 * y0 + yp
        if (abs(denom) > 1e-12) refinedLag += 0.5 * (ym - yp) / denom

        val f0 = sampleRate / refinedLag
        val hnrDb = 10.0 * log10(norm / (1.0 - norm).coerceAtLeast(1e-6))
        return PitchResult(f0, hnrDb.coerceIn(-20.0, 40.0), true)
    }

    /** Pitch variability across 40 ms sub-frames. */
    fun f0Std(samples: DoubleArray, sampleRate: Int): Double {
        val frame = sampleRate * 40 / 1000
        if (samples.size < frame * 2) return 0.0
        val values = mutableListOf<Double>()
        var start = 0
        while (start + frame <= samples.size) {
            val p = pitch(samples.copyOfRange(start, start + frame), sampleRate)
            if (p.voiced) values += p.f0
            start += frame / 2
        }
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.map { (it - mean) * (it - mean) }.average())
    }

    /** Crude local jitter/shimmer from per-period peaks. */
    fun jitterShimmer(samples: DoubleArray, sampleRate: Int, f0: Double): Pair<Double, Double> {
        if (f0 <= 0) return 0.0 to 0.0
        val period = (sampleRate / f0).toInt()
        if (period < 2) return 0.0 to 0.0
        val peaks = mutableListOf<Int>()
        val amps = mutableListOf<Double>()
        var i = 0
        while (i < samples.size) {
            val end = min(samples.size, i + period)
            // Signed maximum: exactly one positive peak per period (abs would double it).
            var maxV = -1e9
            var maxI = i
            for (k in i until end) {
                val a = samples[k]
                if (a > maxV) {
                    maxV = a
                    maxI = k
                }
            }
            if (maxV > 1e-4) {
                peaks += maxI
                amps += maxV
            }
            i += period
        }
        if (peaks.size < 3) return 0.0 to 0.0

        val periods = IntArray(peaks.size - 1) { peaks[it + 1] - peaks[it] }
        if (periods.size < 2) return 0.0 to 0.0

        var jitterSum = 0.0
        for (k in 1 until periods.size) {
            jitterSum += abs(periods[k] - periods[k - 1])
        }
        var shimmerSum = 0.0
        for (k in 1 until amps.size) {
            shimmerSum += abs(amps[k] - amps[k - 1])
        }
        val meanPeriod = periods.average()
        val meanAmp = amps.average()
        val jitter = if (meanPeriod > 0) (jitterSum / (periods.size - 1)) / meanPeriod else 0.0
        val shimmer = if (meanAmp > 0) (shimmerSum / (amps.size - 1)) / meanAmp else 0.0
        return jitter to shimmer
    }

    fun spectralCentroid(samples: ShortArray, sampleRate: Int): Double {
        val n = Integer.highestOneBit(samples.size)
        if (n < 32) return 0.0
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until n) {
            val window = 0.5 * (1 - cos(2.0 * Math.PI * i / (n - 1)))
            re[i] = (samples[i] / 32768.0) * window
        }
        fft(re, im)
        var weighted = 0.0
        var total = 0.0
        val freqRes = sampleRate.toDouble() / n
        for (k in 1 until n / 2) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            weighted += k * freqRes * mag
            total += mag
        }
        return if (total > 0) weighted / total else 0.0
    }

    /** Ratio of speech-envelope energy in the 3-5 Hz band (a dysarthria rhythm marker). */
    fun envelopeModulation4Hz(samples: DoubleArray, sampleRate: Int): Double {
        if (samples.size < sampleRate / 2) return 0.0
        val envRate = 200
        val step = (sampleRate / envRate).coerceAtLeast(1)
        val env = ArrayList<Double>(samples.size / step)
        var i = 0
        while (i < samples.size) {
            var s = 0.0
            var c = 0
            for (k in i until min(samples.size, i + step)) {
                s += abs(samples[k])
                c++
            }
            env += if (c > 0) s / c else 0.0
            i += step
        }
        val n = Integer.highestOneBit(env.size)
        if (n < 32) return 0.0
        val mean = env.take(n).average()
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (k in 0 until n) re[k] = env[k] - mean
        fft(re, im)
        val freqRes = envRate.toDouble() / n
        var total = 0.0
        var band = 0.0
        for (k in 1 until n / 2) {
            val mag = re[k] * re[k] + im[k] * im[k]
            val f = k * freqRes
            total += mag
            if (f in 3.0..5.0) band += mag
        }
        return if (total > 0) band / total else 0.0
    }
}
