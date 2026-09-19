package com.hackmit.app.audio

import com.hackmit.app.domain.AlertLevel
import com.hackmit.app.domain.BaselineProfile
import com.hackmit.app.domain.FeatureVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlurDetectorTest {

    private fun baseline(): BaselineProfile {
        val means = FeatureVector(
            f0Mean = 150f,
            jitter = 0.01f,
            shimmer = 0.05f,
            hnr = 20f,
            wpm = 140f,
            confidence = 0.95f,
        ).toMap()
        val stds = FeatureVector(
            f0Mean = 10f,
            jitter = 0.005f,
            shimmer = 0.02f,
            hnr = 3f,
            wpm = 15f,
            confidence = 0.03f,
        ).toMap()
        return BaselineProfile(createdAtMs = 0L, sampleCount = 10, means = means, stds = stds)
    }

    @Test
    fun normalFeaturesStayNormal() {
        val detector = SlurDetector(baseline(), sensitivity = 0.55f)
        val assessment = detector.update(
            FeatureVector(f0Mean = 150f, jitter = 0.011f, shimmer = 0.05f, hnr = 20f, wpm = 140f, confidence = 0.94f),
        )
        assertEquals(AlertLevel.NORMAL, assessment.level)
    }

    @Test
    fun sustainedShiftRaisesAlert() {
        val detector = SlurDetector(baseline(), sensitivity = 0.8f)
        val shifted = FeatureVector(
            f0Mean = 120f,
            jitter = 0.05f,
            shimmer = 0.2f,
            hnr = 8f,
            wpm = 60f,
            confidence = 0.5f,
        )
        var last = detector.update(shifted)
        repeat(5) { last = detector.update(shifted) }
        assertTrue("score=${last.score}", last.score > 0.4f)
        assertNotEquals(AlertLevel.NORMAL, last.level)
    }

    @Test
    fun noBaselineIsNormal() {
        val detector = SlurDetector(null)
        assertEquals(AlertLevel.NORMAL, detector.update(FeatureVector(f0Mean = 150f)).level)
    }
}
