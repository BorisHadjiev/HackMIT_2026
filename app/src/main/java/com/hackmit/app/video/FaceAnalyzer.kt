package com.hackmit.app.video

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

data class FaceFrame(
    val detected: Boolean,
    val asymmetry: Float,
    val mouthDroop: Float,
    val eyeAsymmetry: Float,
)

interface FaceAnalyzer {
    fun current(): FaceFrame
}

/**
 * Placeholder analyzer driven by a slow sine wave so the UI animates during development.
 * [abnormal] pushes the readings into a clearly asymmetric range.
 */
class MockFaceAnalyzer(private val abnormal: Boolean = false) : FaceAnalyzer {

    override fun current(): FaceFrame {
        val t = System.currentTimeMillis() / 1000.0
        val base = 0.08 + 0.05 * sin(t)
        return if (abnormal) {
            FaceFrame(
                detected = true,
                asymmetry = (0.55 + 0.12 * sin(t * 1.7)).toFloat().coerceIn(0f, 1f),
                mouthDroop = (0.6 + 0.1 * sin(t * 1.3)).toFloat().coerceIn(0f, 1f),
                eyeAsymmetry = (0.35 + 0.1 * sin(t)).toFloat().coerceIn(0f, 1f),
            )
        } else {
            FaceFrame(
                detected = true,
                asymmetry = base.toFloat(),
                mouthDroop = (0.07 + 0.04 * sin(t * 1.2)).toFloat(),
                eyeAsymmetry = (0.05 + 0.03 * sin(t * 0.9)).toFloat(),
            )
        }
    }
}

/**
 * TODO: implement with CameraX ImageAnalysis + MediaPipe FaceLandmarker.
 *
 *   1. Download face_landmarker.task into app/src/main/assets.
 *   2. Build FaceLandmarker with RunningMode.LIVE_STREAM.
 *   3. Feed ImageProxy -> BitmapImageBuilder -> detectAsync.
 *   4. Map results to a normalized FloatArray [x0,y0,x1,y1,...] and call
 *      [AsymmetryCalculator.faceFrame] for the current frame.
 */
class MediaPipeFaceAnalyzer : FaceAnalyzer {
    override fun current(): FaceFrame = FaceFrame(false, 0f, 0f, 0f)
}

/**
 * Facial-asymmetry index from MediaPipe FaceMesh landmarks, corrected to be
 * roll- and scale-invariant.
 *
 * The original implementation measured each landmark's distance to the nose tip and
 * compared mirror pairs. On the "Annotated stroke and non stroke Dataset" that formula
 * scored at chance and anti-correlated with stroke (mouth AUC 0.41, combined 0.35),
 * because the nose-tip distance conflates true droop with head yaw and face scale.
 *
 * This version:
 *   1. uses the outer eye corners (33-263) as the symmetry axis (roll-invariant),
 *   2. measures the *perpendicular* offset of paired landmarks from that axis,
 *   3. normalizes by interocular distance (scale-invariant).
 *
 * Validation on that dataset (population-level, after residualizing out its face-scale
 * confound): mouth droop AUC 0.79 (0.89 raw, and 0.86-0.92 within every face-scale
 * quartile); a logistic regression on the corrected geometry reaches AUC 0.90.
 * Eyelid-opening asymmetry is weak (AUC 0.55 scale-residualized).
 */
object AsymmetryCalculator {

    // MediaPipe FaceMesh landmark indices.
    const val EYE_LEFT_OUTER = 33
    const val EYE_RIGHT_OUTER = 263
    const val MOUTH_LEFT = 61
    const val MOUTH_RIGHT = 291
    const val BROW_LEFT = 105
    const val BROW_RIGHT = 334
    const val CHEEK_LEFT = 234
    const val CHEEK_RIGHT = 454
    const val LID_UPPER_LEFT = 159
    const val LID_LOWER_LEFT = 145
    const val LID_UPPER_RIGHT = 386
    const val LID_LOWER_RIGHT = 374

    private const val REQUIRED_LANDMARKS = 468

    /**
     * Full-scale normalizers for raw perpendicular asymmetry (fraction of interocular
     * distance). Measured means on the stroke dataset: mouth 0.023 non-stroke / 0.076
     * stroke; eyelid-opening asymmetry 0.077 / 0.112.
     */
    private const val MOUTH_DROOP_FULL_SCALE = 0.10f
    private const val CHEEK_FULL_SCALE = 0.08f
    private const val EYE_OPENING_FULL_SCALE = 0.25f

    // Mouth droop is the most discriminative sign by a wide margin.
    private const val MOUTH_WEIGHT = 0.70f
    private const val CHEEK_WEIGHT = 0.20f
    private const val EYE_WEIGHT = 0.10f

    data class Features(
        val asymmetry: Float,
        val mouthDroop: Float,
        val eyeAsymmetry: Float,
    )

    /**
     * Landmarks are a flat normalized array: [x0, y0, x1, y1, ...]. Returns severity in
     * [0, 1] for the combined index, mouth droop and eyelid asymmetry.
     */
    fun features(landmarks: FloatArray): Features {
        if (landmarks.size < REQUIRED_LANDMARKS * 2) return Features(0f, 0f, 0f)

        val eyeLx = landmarks[EYE_LEFT_OUTER * 2]
        val eyeLy = landmarks[EYE_LEFT_OUTER * 2 + 1]
        val eyeRx = landmarks[EYE_RIGHT_OUTER * 2]
        val eyeRy = landmarks[EYE_RIGHT_OUTER * 2 + 1]

        val ex = eyeRx - eyeLx
        val ey = eyeRy - eyeLy
        val interocular = hypot(ex, ey)
        if (interocular < 1e-4f) return Features(0f, 0f, 0f)

        val midX = (eyeLx + eyeRx) / 2f
        val midY = (eyeLy + eyeRy) / 2f
        // Unit vector perpendicular to the eye line.
        val perpX = -ey / interocular
        val perpY = ex / interocular

        fun perpendicularOffset(index: Int): Float {
            val dx = landmarks[index * 2] - midX
            val dy = landmarks[index * 2 + 1] - midY
            return (dx * perpX + dy * perpY) / interocular
        }

        val mouth = abs(perpendicularOffset(MOUTH_LEFT) - perpendicularOffset(MOUTH_RIGHT))
        val cheek = abs(perpendicularOffset(CHEEK_LEFT) - perpendicularOffset(CHEEK_RIGHT))

        val eyeOpenLeft = hypot(
            landmarks[LID_UPPER_LEFT * 2] - landmarks[LID_LOWER_LEFT * 2],
            landmarks[LID_UPPER_LEFT * 2 + 1] - landmarks[LID_LOWER_LEFT * 2 + 1],
        ) / interocular
        val eyeOpenRight = hypot(
            landmarks[LID_UPPER_RIGHT * 2] - landmarks[LID_LOWER_RIGHT * 2],
            landmarks[LID_UPPER_RIGHT * 2 + 1] - landmarks[LID_LOWER_RIGHT * 2 + 1],
        ) / interocular
        val eyeOpeningAsymmetry = if (eyeOpenLeft + eyeOpenRight > 0f) {
            abs(eyeOpenLeft - eyeOpenRight) / (eyeOpenLeft + eyeOpenRight)
        } else {
            0f
        }

        val mouthDroop = (mouth / MOUTH_DROOP_FULL_SCALE).coerceIn(0f, 1f)
        val cheekSeverity = (cheek / CHEEK_FULL_SCALE).coerceIn(0f, 1f)
        val eyeAsymmetry = (eyeOpeningAsymmetry / EYE_OPENING_FULL_SCALE).coerceIn(0f, 1f)
        val asymmetry = (MOUTH_WEIGHT * mouthDroop +
            CHEEK_WEIGHT * cheekSeverity +
            EYE_WEIGHT * eyeAsymmetry).coerceIn(0f, 1f)

        return Features(asymmetry, mouthDroop, eyeAsymmetry)
    }

    /** Overall facial-asymmetry severity in [0, 1] (0 = symmetric). */
    fun asymmetryIndex(landmarks: FloatArray): Float = features(landmarks).asymmetry

    /** Mouth-corner droop asymmetry relative to the eye line, as [0, 1] severity. */
    fun mouthDroop(landmarks: FloatArray): Float = features(landmarks).mouthDroop

    /** Eyelid-opening asymmetry, as [0, 1] severity. */
    fun eyeAsymmetry(landmarks: FloatArray): Float = features(landmarks).eyeAsymmetry

    /** Maps FaceMesh landmarks to a [FaceFrame]; null/short input yields "not detected". */
    fun faceFrame(landmarks: FloatArray?): FaceFrame {
        if (landmarks == null || landmarks.size < REQUIRED_LANDMARKS * 2) {
            return FaceFrame(false, 0f, 0f, 0f)
        }
        val f = features(landmarks)
        return FaceFrame(true, f.asymmetry, f.mouthDroop, f.eyeAsymmetry)
    }
}
