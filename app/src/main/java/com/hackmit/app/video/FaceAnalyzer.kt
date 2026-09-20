package com.hackmit.app.video

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.abs
import kotlin.math.exp
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
 * Real analyzer: CameraX ImageAnalysis frames -> MediaPipe FaceLandmarker
 * (LIVE_STREAM) -> 468 normalized landmarks -> [AsymmetryCalculator].
 */
class MediaPipeFaceAnalyzer(context: Context, private val minConfidence: Float = 0.5f) : FaceAnalyzer {

    @Volatile
    private var latest = FaceFrame(false, 0f, 0f, 0f)

    @Volatile
    private var processing = false

    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(minConfidence)
            .setResultListener { result, _ -> latest = toFrame(result) }
            .setErrorListener { _ -> }
            .build(),
    )

    override fun current(): FaceFrame = latest

    fun analyze(imageProxy: ImageProxy) {
        if (processing) {
            imageProxy.close()
            return
        }
        processing = true
        val bitmap = imageProxy.toBitmap()
        val mpImage = BitmapImageBuilder(bitmap).build()
        landmarker.detectAsync(mpImage, SystemClock.uptimeMillis())
        imageProxy.close()
    }

    fun close() {
        landmarker.close()
    }

    private fun toFrame(result: FaceLandmarkerResult): FaceFrame {
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return FaceFrame(false, 0f, 0f, 0f)
        val landmarks = faces[0]
        val arr = FloatArray(landmarks.size * 2)
        for (i in landmarks.indices) {
            arr[i * 2] = landmarks[i].x()
            arr[i * 2 + 1] = landmarks[i].y()
        }
        val feats = AsymmetryCalculator.features(arr)
        return FaceFrame(true, AsymmetryCalculator.logisticScore(arr), feats.mouthDroop, feats.eyeAsymmetry)
    }
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
 * Cross-dataset validation (mouth droop AUC): Kaggle stroke faces 0.89 (0.79 after
 * residualizing out that dataset's face-scale confound), PalsyNet-5230 0.76,
 * face-stroke-tiny 0.85. A logistic regression on the corrected geometry reaches
 * 0.78-0.90. Eyelid-opening asymmetry is weaker and noisy on in-the-wild faces.
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
     * distance). Chosen by grid search over the Kaggle stroke, PalsyNet and
     * face-stroke-tiny datasets plus LFW as a healthy control, targeting <=5% false
     * positives on LFW at the app's 0.33 threshold (was 9.3% before tuning).
     *
     * Healthy (LFW) percentiles: mouth p95 0.041 / p99 0.059; cheek p95 0.049;
     * eyelid-opening asymmetry p95 0.184 / p99 0.324.
     */
    private const val MOUTH_DROOP_FULL_SCALE = 0.12f
    private const val CHEEK_FULL_SCALE = 0.12f
    private const val EYE_OPENING_FULL_SCALE = 0.25f

    // Mouth droop is the most discriminative sign by a wide margin; eyelid asymmetry
    // is noisy on in-the-wild faces, so it only contributes a small amount.
    private const val MOUTH_WEIGHT = 0.75f
    private const val CHEEK_WEIGHT = 0.15f
    private const val EYE_WEIGHT = 0.10f

    /**
     * Below this combined eyelid opening (in interocular units) both eyes are treated
     * as closed, so eyelid asymmetry is not assessed (avoids a noisy ratio on blinks).
     */
    private const val MIN_EYE_OPENING = 0.05f

    // Logistic regression on the corrected geometry (mouth, eyelid, cheek, brow
    // perpendicular offsets), trained on stroke + PalsyNet + face-stroke-tiny
    // (StandardScaler + balanced LR). CV AUC 0.84; operating threshold 0.5535
    // keeps healthy LFW false positives ~5% (see tools/face_eval/train_lr.py).
    private const val LR_MEAN_MOUTH = 0.037833f
    private const val LR_MEAN_EYE = 0.088274f
    private const val LR_MEAN_CHEEK = 0.028482f
    private const val LR_MEAN_BROW = 0.016724f
    private const val LR_STD_MOUTH = 0.038300f
    private const val LR_STD_EYE = 0.099723f
    private const val LR_STD_CHEEK = 0.024988f
    private const val LR_STD_BROW = 0.014048f
    private const val LR_COEF_MOUTH = 1.753688f
    private const val LR_COEF_EYE = 0.073174f
    private const val LR_COEF_CHEEK = -0.323215f
    private const val LR_COEF_BROW = -0.200029f
    private const val LR_INTERCEPT = -0.019564f

    const val LOGISTIC_THRESHOLD = 0.5535f

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
        val eyeOpeningSum = eyeOpenLeft + eyeOpenRight
        val eyeOpeningAsymmetry = if (eyeOpeningSum >= MIN_EYE_OPENING) {
            abs(eyeOpenLeft - eyeOpenRight) / eyeOpeningSum
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

    /**
     * Logistic-regression probability of a stroke-like droop in [0, 1], using the
     * corrected geometry features that were trained/validated on gx10. Use
     * [LOGISTIC_THRESHOLD] for the screening decision.
     */
    fun logisticScore(landmarks: FloatArray): Float {
        if (landmarks.size < REQUIRED_LANDMARKS * 2) return 0f
        val eyeLx = landmarks[EYE_LEFT_OUTER * 2]
        val eyeLy = landmarks[EYE_LEFT_OUTER * 2 + 1]
        val eyeRx = landmarks[EYE_RIGHT_OUTER * 2]
        val eyeRy = landmarks[EYE_RIGHT_OUTER * 2 + 1]
        val ex = eyeRx - eyeLx
        val ey = eyeRy - eyeLy
        val iod = hypot(ex, ey)
        if (iod < 1e-4f) return 0f
        val midX = (eyeLx + eyeRx) / 2f
        val midY = (eyeLy + eyeRy) / 2f
        val perpX = -ey / iod
        val perpY = ex / iod
        fun perp(index: Int): Float {
            return ((landmarks[index * 2] - midX) * perpX +
                (landmarks[index * 2 + 1] - midY) * perpY) / iod
        }

        val mouth = abs(perp(MOUTH_LEFT) - perp(MOUTH_RIGHT))
        val cheek = abs(perp(CHEEK_LEFT) - perp(CHEEK_RIGHT))
        val brow = abs(perp(BROW_LEFT) - perp(BROW_RIGHT))
        val openL = hypot(
            landmarks[LID_UPPER_LEFT * 2] - landmarks[LID_LOWER_LEFT * 2],
            landmarks[LID_UPPER_LEFT * 2 + 1] - landmarks[LID_LOWER_LEFT * 2 + 1],
        ) / iod
        val openR = hypot(
            landmarks[LID_UPPER_RIGHT * 2] - landmarks[LID_LOWER_RIGHT * 2],
            landmarks[LID_UPPER_RIGHT * 2 + 1] - landmarks[LID_LOWER_RIGHT * 2 + 1],
        ) / iod
        val eyeOpenAsym = if (openL + openR > 0f) abs(openL - openR) / (openL + openR) else 0f

        val zMouth = (mouth - LR_MEAN_MOUTH) / LR_STD_MOUTH
        val zEye = (eyeOpenAsym - LR_MEAN_EYE) / LR_STD_EYE
        val zCheek = (cheek - LR_MEAN_CHEEK) / LR_STD_CHEEK
        val zBrow = (brow - LR_MEAN_BROW) / LR_STD_BROW
        val logit = LR_COEF_MOUTH * zMouth + LR_COEF_EYE * zEye +
            LR_COEF_CHEEK * zCheek + LR_COEF_BROW * zBrow + LR_INTERCEPT
        return (1f / (1f + exp(-logit))).coerceIn(0f, 1f)
    }

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
