package com.hackmit.app.video

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

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
 *      [AsymmetryCalculator.asymmetryIndex] for the current frame.
 */
class MediaPipeFaceAnalyzer : FaceAnalyzer {
    override fun current(): FaceFrame = FaceFrame(false, 0f, 0f, 0f)
}

/**
 * Computes a left/right asymmetry index from MediaPipe FaceMesh landmarks.
 * Landmarks are expected as a flat normalized array: [x0, y0, x1, y1, ...].
 */
object AsymmetryCalculator {

    // MediaPipe FaceMesh landmark indices.
    const val MOUTH_LEFT = 61
    const val MOUTH_RIGHT = 291
    const val EYE_LEFT_OUTER = 33
    const val EYE_RIGHT_OUTER = 263
    const val BROW_LEFT = 105
    const val BROW_RIGHT = 334
    const val CHEEK_LEFT = 234
    const val CHEEK_RIGHT = 454

    /**
     * Mirror-pair distance ratio: |dL - dR| / (dL + dR), normalized so 0 = perfectly symmetric.
     */
    fun pairRatio(landmarks: FloatArray, left: Int, right: Int, axisIndex: Int): Float {
        val lx = landmarks[left * 2]
        val ly = landmarks[left * 2 + 1]
        val rx = landmarks[right * 2]
        val ry = landmarks[right * 2 + 1]
        val axis = landmarks[axisIndex * 2]
        val axisY = landmarks[axisIndex * 2 + 1]
        val dl = sqrt((lx - axis) * (lx - axis) + (ly - axisY) * (ly - axisY))
        val dr = sqrt((rx - axis) * (rx - axis) + (ry - axisY) * (ry - axisY))
        val denom = dl + dr
        return if (denom == 0f) 0f else abs(dl - dr) / denom
    }

    /** FaceMesh index 1 is the nose tip, used as the symmetry axis. */
    fun asymmetryIndex(landmarks: FloatArray): Float {
        if (landmarks.size < 468 * 2) return 0f
        val mouth = pairRatio(landmarks, MOUTH_LEFT, MOUTH_RIGHT, 1)
        val eyes = pairRatio(landmarks, EYE_LEFT_OUTER, EYE_RIGHT_OUTER, 1)
        val brows = pairRatio(landmarks, BROW_LEFT, BROW_RIGHT, 1)
        val cheeks = pairRatio(landmarks, CHEEK_LEFT, CHEEK_RIGHT, 1)
        return ((mouth + eyes + brows + cheeks) / 4f * 4f).coerceIn(0f, 1f)
    }
}
