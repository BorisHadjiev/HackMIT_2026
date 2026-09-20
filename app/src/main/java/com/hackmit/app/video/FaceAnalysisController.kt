package com.hackmit.app.video

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hackmit.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Throttles camera frames and uploads them to the gateway for server-side face
 * analysis. Exposes the latest [frame] and whether the server is reachable.
 */
class FaceAnalysisController(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = 600,
) {
    val frame: MutableState<FaceFrame> = mutableStateOf(FaceFrame(false, 0f, 0f, 0f))
    var serverOk: Boolean by mutableStateOf(false)
        private set

    @Volatile
    private var inFlight = false

    @Volatile
    private var lastUpload = 0L

    // Temporal smoothing: the raw LR probability jitters frame-to-frame.
    private var smoothed = 0f
    private val smoothAlpha = 0.35f

    /** Warms the connection so the UI flips to "connected" before the first frame. */
    fun warmUp() {
        scope.launch {
            if (FaceServer.ping(settings)) serverOk = true
        }
    }

    fun handle(proxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (inFlight || now - lastUpload < minIntervalMs) {
            proxy.close()
            return
        }
        inFlight = true
        lastUpload = now
        val jpeg = runCatching { FaceServer.jpeg(proxy.toBitmap()) }.getOrNull()
        proxy.close()
        scope.launch {
            val result = if (jpeg != null) FaceServer.analyze(settings, jpeg) else null
            if (result != null) {
                serverOk = true
                if (result.detected) {
                    smoothed = smoothAlpha * result.asymmetry + (1 - smoothAlpha) * smoothed
                    frame.value = result.copy(asymmetry = smoothed)
                } else {
                    // Keep the last smoothed value during blinks/face loss.
                    frame.value = FaceFrame(false, smoothed, 0f, 0f)
                }
            }
            inFlight = false
        }
    }
}