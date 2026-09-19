package com.hackmit.app.audio

import kotlin.math.abs

data class SpeechMetrics(
    val wordCount: Int,
    val meanConfidence: Float,
    val wordsPerMinute: Float,
    val longPauseCount: Int,
    val fillerRatio: Float,
    val slurScore: Float,
)

/**
 * Turns a stream of Deepgram transcript updates into slur-related metrics.
 *
 * Deepgram does not return "slurred" directly, so we derive it from:
 *  - low word confidence (mumbling)
 *  - unusually slow or fast rate
 *  - long pauses between final segments
 *  - high filler ratio
 *
 * TODO: calibrate thresholds against labelled samples during the hackathon.
 */
class SpeechAnalyzer(private val baselineWpm: Float? = null) {

    private val fillers = setOf("uh", "um", "er", "ah", "hmm", "like", "you", "know")

    private var words = 0
    private var confidenceSum = 0f
    private var confidenceSamples = 0
    private var fillerCount = 0
    private var longPauses = 0
    private var lastFinalMs = 0L
    private var startedMs = 0L

    fun start() {
        startedMs = System.currentTimeMillis()
        lastFinalMs = startedMs
    }

    fun onTranscript(update: TranscriptUpdate) {
        // Only final segments count, otherwise interim results double-count words.
        if (!update.isFinal) return

        val now = update.timestampMs
        val gap = now - lastFinalMs
        if (lastFinalMs != 0L && gap > 1200) longPauses++
        lastFinalMs = now

        words += update.wordCount
        if (update.confidence > 0f) {
            confidenceSum += update.confidence
            confidenceSamples++
        }
        update.transcript.lowercase()
            .split(Regex("\\s+"))
            .forEach { token ->
                if (token.trim('.', ',', '!', '?') in fillers) fillerCount++
            }
    }

    fun finish(): SpeechMetrics {
        val elapsedMin = ((System.currentTimeMillis() - startedMs).coerceAtLeast(1L)) / 60_000f
        val wpm = words / elapsedMin
        val confidence = if (confidenceSamples == 0) 0f else confidenceSum / confidenceSamples
        val fillerRatio = if (words == 0) 0f else fillerCount.toFloat() / words

        val slow = baselineWpm?.let { abs(wpm - it) / it } ?: 0f
        val slur = (
            0.45f * (1f - confidence.coerceIn(0f, 1f)) +
                0.25f * slow.coerceIn(0f, 1f) +
                0.20f * (longPauses / 5f).coerceIn(0f, 1f) +
                0.10f * (fillerRatio * 4f).coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)

        return SpeechMetrics(
            wordCount = words,
            meanConfidence = confidence,
            wordsPerMinute = wpm,
            longPauseCount = longPauses,
            fillerRatio = fillerRatio,
            slurScore = slur,
        )
    }
}
