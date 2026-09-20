package com.hackmit.app.audio

import android.util.Log
import com.hackmit.app.alerts.AlertManager
import com.hackmit.app.data.BaselineStore
import com.hackmit.app.data.SettingsStore
import com.hackmit.app.domain.BaselineProfile
import com.hackmit.app.domain.FeatureVector
import com.hackmit.app.domain.MonitorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground-only continuous speech monitor. Owns capture, VAD gating, on-device
 * acoustic features, the Deepgram stream, and the slur detector.
 *
 * NOTE: this runs only while the app process is in the foreground. The [begin]/[end]
 * seam is where a foreground-service implementation can be dropped in later.
 */
class SpeechMonitor(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val baselineStore: BaselineStore,
    private val alertManager: AlertManager,
) {

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val vad = Vad()
    private val extractor = AcousticFeatureExtractor()

    private var capture: AudioCapture? = null
    private var deepgram: DeepgramStream? = null
    private var processingJob: Job? = null
    private var eventJob: Job? = null

    private var detector: SlurDetector? = null
    private var baseline: BaselineProfile? = null

    private val pcmChannel = Channel<ByteArray>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val finalResults = ArrayDeque<StreamResult>()
    private val resultChannel = Channel<StreamResult>(Channel.UNLIMITED)

    @Volatile
    private var latestTranscript = ""

    private var windowFrames = 0
    private var windowSpeechFrames = 0

    @Volatile
    private var running = false

    @Volatile
    private var sentFrames = 0L

    @Volatile
    private var dgConnected = false

    @Volatile
    private var dgSpeechActive = false

    private var baselineSamples: MutableList<FeatureVector>? = null

    @Volatile
    private var calibrating = false

    /** Prefer Deepgram's server-side VAD when connected; fall back to the local VAD. */
    private fun effectiveSpeechActive(): Boolean = if (dgConnected) dgSpeechActive else vad.speechActive

    suspend fun refreshBaseline() {
        baseline = baselineStore.load()
        detector = baseline?.let { SlurDetector(it, settings.alertSensitivity.firstOrNull() ?: 0.55f) }
    }

    fun updateSensitivity(value: Float) {
        detector?.sensitivity = value
    }

    suspend fun startMonitoring() {
        if (running) return
        calibrating = false
        baseline = baselineStore.load()
        val sensitivity = settings.alertSensitivity.first()
        detector = baseline?.let { SlurDetector(it, sensitivity) }
        val route = DeepgramRouter.forStream(settings)
        begin(route.token, route.endpoint)
    }

    fun stopMonitoring() {
        baselineSamples = null
        end()
    }

    /** Records [durationMs] of speech and stores a personal baseline profile. */
    suspend fun recordBaseline(durationMs: Long): BaselineProfile? {
        if (running) end()
        baselineSamples = mutableListOf()
        calibrating = true
        val route = DeepgramRouter.forStream(settings)
        begin(route.token, route.endpoint)
        delay(durationMs)
        calibrating = false
        val samples = baselineSamples ?: emptyList()
        baselineSamples = null
        end()
        val profile = BaselineStore.fromSamples(samples)
        Log.d(TAG, "baseline samples=${profile.sampleCount}")
        if (profile.sampleCount < MIN_BASELINE_SAMPLES) return null
        baselineStore.save(profile)
        baseline = profile
        detector = SlurDetector(profile, settings.alertSensitivity.first())
        return profile
    }

    private fun begin(apiKey: String, endpoint: String = DeepgramStream.DEFAULT_ENDPOINT) {
        running = true
        vad.reset()
        extractor.reset()
        finalResults.clear()
        drainResultChannel()
        while (pcmChannel.tryReceive().isSuccess) { /* drop stale audio */ }
        sentFrames = 0L
        dgConnected = false
        dgSpeechActive = false
        latestTranscript = ""
        windowFrames = 0
        windowSpeechFrames = 0
        _state.value = MonitorState(running = true, deepgramStatus = "Starting")

        if (apiKey.isNotBlank()) {
            val stream = DeepgramStream(apiKey, endpoint)
            deepgram = stream
            stream.start(scope)
            eventJob = scope.launch {
                stream.eventFlow.collect { onDeepgramEvent(it) }
            }
        } else {
            _state.value = _state.value.copy(deepgramStatus = "No key: acoustic-only")
        }

        val recorder = AudioCapture { bytes -> pcmChannel.trySend(bytes) }
        capture = recorder
        if (!recorder.start()) {
            _state.value = _state.value.copy(lastError = "Microphone unavailable")
        }
        processingJob = scope.launch { processLoop() }
    }

    private fun end() {
        running = false
        capture?.stop()
        capture = null
        deepgram?.stop()
        deepgram = null
        eventJob?.cancel()
        eventJob = null
        processingJob?.cancel()
        processingJob = null
        dgConnected = false
        dgSpeechActive = false
        _state.value = _state.value.copy(running = false, speechActive = false)
    }

    private suspend fun processLoop() {
        val frame = ShortArray(Vad.FRAME_SAMPLES)
        var fill = 0
        var hopFrames = 0
        while (scope.isActive && running) {
            val bytes = pcmChannel.receive()
            val shorts = AudioCapture.toShorts(bytes)
            var index = 0
            while (index < shorts.size) {
                val take = minOf(Vad.FRAME_SAMPLES - fill, shorts.size - index)
                System.arraycopy(shorts, index, frame, fill, take)
                fill += take
                index += take
                if (fill == Vad.FRAME_SAMPLES) {
                    fill = 0
                    val event = vad.process(frame)
                    if (event == Vad.Event.SPEECH_START) Log.d(TAG, "speech start")
                    if (event == Vad.Event.SPEECH_END) Log.d(TAG, "speech end (sentFrames=$sentFrames)")
                    extractor.add(frame)
                    // Stream ALL audio to Deepgram and let Deepgram's server-side VAD
                    // decide speech; the local energy VAD is too fragile for gating.
                    deepgram?.send(AudioCapture.toBytes(frame))
                    sentFrames++
                    windowFrames++
                    if (effectiveSpeechActive()) windowSpeechFrames++
                    hopFrames++
                    if (hopFrames >= HOP_FRAMES) {
                        hopFrames = 0
                        computeAndUpdate()
                    }
                }
            }
        }
    }

    private fun computeAndUpdate() {
        drainResultChannel()
        val speechNow = effectiveSpeechActive()
        val hadSpeech = windowSpeechFrames > 0
        val frames = windowFrames.coerceAtLeast(1)
        val pauseRatio = 1f - (windowSpeechFrames.toFloat() / frames)
        windowFrames = 0
        windowSpeechFrames = 0
        if (!hadSpeech) {
            _state.value = _state.value.copy(speechActive = speechNow)
            return
        }

        val acoustic = extractor.compute()
        val lexical = lexicalSnapshot()
        val features = acoustic.copy(
            wpm = lexical.wpm,
            confidence = lexical.confidence,
            pauseRatio = pauseRatio,
            fillerRatio = lexical.fillerRatio,
        )

        baselineSamples?.add(features)

        val assessment = if (calibrating) null else detector?.update(features)
        _state.value = _state.value.copy(
            speechActive = speechNow,
            transcript = latestTranscript,
            features = features,
            score = assessment?.score ?: 0f,
            level = assessment?.level ?: _state.value.level,
            reasons = assessment?.reasons ?: _state.value.reasons,
        )
        if (assessment != null) alertManager.onAssessment(assessment)
        Log.d(
            TAG,
            "compute speech=$hadSpeech sent=$sentFrames words=${features.wpm} " +
                "f0=${features.f0Mean} jitter=${features.jitter} score=${assessment?.score}",
        )
    }

    private data class Lexical(val wpm: Float, val confidence: Float, val fillerRatio: Float)

    /** Moves final results from the Deepgram coroutine onto the processing coroutine. */
    private fun drainResultChannel() {
        while (true) {
            val result = resultChannel.tryReceive().getOrNull() ?: break
            finalResults.addLast(result)
        }
    }

    private fun lexicalSnapshot(): Lexical {
        val cutoff = System.currentTimeMillis() - LEXICAL_WINDOW_MS
        while (finalResults.isNotEmpty() && finalResults.first().timestampMs < cutoff) {
            finalResults.removeFirst()
        }
        if (finalResults.isEmpty()) return Lexical(0f, 0f, 0f)
        val words = finalResults.sumOf { it.wordCount }
        val confidence = finalResults.map { it.confidence }.average().toFloat()
        val fillers = finalResults.sumOf { result ->
            result.words.count { word ->
                word.word.lowercase().trim('.', ',', '!', '?') in FILLERS
            }
        }
        val wpm = words / (LEXICAL_WINDOW_MS / 60_000f)
        val fillerRatio = if (words == 0) 0f else fillers.toFloat() / words
        return Lexical(wpm, confidence, fillerRatio)
    }

    private fun onDeepgramEvent(event: DeepgramEvent) {
        when (event) {
            is DeepgramEvent.Status -> {
                dgConnected = event.text == "Connected"
                _state.value = _state.value.copy(deepgramStatus = event.text)
            }
            is DeepgramEvent.Error -> _state.value = _state.value.copy(lastError = event.text)
            is DeepgramEvent.SpeechStarted -> {
                dgSpeechActive = true
                _state.value = _state.value.copy(speechActive = true)
            }
            is DeepgramEvent.UtteranceEnd -> {
                dgSpeechActive = false
                _state.value = _state.value.copy(speechActive = false)
            }
            is DeepgramEvent.Result -> {
                latestTranscript = event.result.transcript.ifBlank { latestTranscript }
                if (event.result.isFinal) {
                    resultChannel.trySend(event.result)
                }
                _state.value = _state.value.copy(transcript = latestTranscript)
            }
        }
    }

    companion object {
        private const val TAG = "SM"
        private const val HOP_FRAMES = 62 // ~2 s at 512 samples / 16 kHz
        private const val MIN_BASELINE_SAMPLES = 5
        private const val LEXICAL_WINDOW_MS = 10_000L
        private val FILLERS = setOf("uh", "um", "er", "ah", "hmm", "like", "you", "know")
    }
}
