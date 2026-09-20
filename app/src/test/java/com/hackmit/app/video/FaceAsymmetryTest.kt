package com.hackmit.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FaceAsymmetryTest {

    private fun face(
        mouthLy: Float = 0.70f,
        mouthRy: Float = 0.70f,
        cheekLy: Float = 0.55f,
        cheekRy: Float = 0.55f,
        lidOpenL: Float = 0.04f,
        lidOpenR: Float = 0.04f,
        scale: Float = 1f,
        rollRad: Float = 0f,
        cx: Float = 0.5f,
        cy: Float = 0.5f,
    ): FloatArray {
        val lm = FloatArray(468 * 2)

        fun put(i: Int, x: Float, y: Float) {
            val dx = (x - cx) * scale
            val dy = (y - cy) * scale
            lm[i * 2] = cx + dx * cos(rollRad) - dy * sin(rollRad)
            lm[i * 2 + 1] = cy + dx * sin(rollRad) + dy * cos(rollRad)
        }

        put(AsymmetryCalculator.EYE_LEFT_OUTER, 0.40f, 0.50f)
        put(AsymmetryCalculator.EYE_RIGHT_OUTER, 0.60f, 0.50f)
        put(AsymmetryCalculator.MOUTH_LEFT, 0.45f, mouthLy)
        put(AsymmetryCalculator.MOUTH_RIGHT, 0.55f, mouthRy)
        put(AsymmetryCalculator.CHEEK_LEFT, 0.35f, cheekLy)
        put(AsymmetryCalculator.CHEEK_RIGHT, 0.65f, cheekRy)
        put(AsymmetryCalculator.LID_UPPER_LEFT, 0.43f, 0.48f)
        put(AsymmetryCalculator.LID_LOWER_LEFT, 0.43f, 0.48f + lidOpenL)
        put(AsymmetryCalculator.LID_UPPER_RIGHT, 0.57f, 0.48f)
        put(AsymmetryCalculator.LID_LOWER_RIGHT, 0.57f, 0.48f + lidOpenR)
        return lm
    }

    @Test
    fun symmetricFaceHasZeroAsymmetry() {
        val f = AsymmetryCalculator.features(face())
        assertEquals(0f, f.mouthDroop, 1e-4f)
        assertEquals(0f, f.eyeAsymmetry, 1e-4f)
        assertEquals(0f, f.asymmetry, 1e-4f)
    }

    @Test
    fun droopedMouthRaisesIndexMonotonically() {
        val mild = AsymmetryCalculator.mouthDroop(face(mouthRy = 0.705f))
        val strong = AsymmetryCalculator.mouthDroop(face(mouthRy = 0.71f))
        assertEquals(0.21f, mild, 0.02f)
        assertEquals(0.42f, strong, 0.02f)
        assertTrue(strong > mild)
    }

    @Test
    fun droopAlsoRaisesCombinedIndex() {
        val symmetric = AsymmetryCalculator.asymmetryIndex(face())
        val drooped = AsymmetryCalculator.asymmetryIndex(face(mouthRy = 0.71f))
        assertTrue("drooped=$drooped symmetric=$symmetric", drooped > symmetric)
    }

    @Test
    fun rollInvariance() {
        val rolled = AsymmetryCalculator.features(face(rollRad = 0.5f))
        assertEquals(0f, rolled.mouthDroop, 1e-3f)
        assertEquals(0f, rolled.asymmetry, 1e-3f)
    }

    @Test
    fun scaleInvariance() {
        val base = AsymmetryCalculator.mouthDroop(face(mouthRy = 0.71f))
        val zoomed = AsymmetryCalculator.mouthDroop(face(mouthRy = 0.71f, scale = 1.8f))
        assertEquals(base, zoomed, 1e-3f)
    }

    @Test
    fun eyelidOpeningAsymmetryIsDetected() {
        val f = AsymmetryCalculator.features(face(lidOpenR = 0.08f))
        assertTrue("eyeAsymmetry=${f.eyeAsymmetry}", f.eyeAsymmetry > 0.1f)
    }

    @Test
    fun closedEyesAreNotScoredForEyelidAsymmetry() {
        val f = AsymmetryCalculator.features(face(lidOpenL = 0.002f, lidOpenR = 0.006f))
        assertEquals(0f, f.eyeAsymmetry, 1e-4f)
    }

    @Test
    fun shortLandmarksReturnZero() {
        val f = AsymmetryCalculator.features(FloatArray(10))
        assertEquals(0f, f.asymmetry, 0f)
        assertEquals(0f, f.mouthDroop, 0f)
    }

    @Test
    fun faceFrameRequiresLandmarks() {
        assertFalse(AsymmetryCalculator.faceFrame(null).detected)
        assertFalse(AsymmetryCalculator.faceFrame(FloatArray(4)).detected)
        assertTrue(AsymmetryCalculator.faceFrame(face()).detected)
    }
}
