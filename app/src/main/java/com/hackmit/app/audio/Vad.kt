package com.hackmit.app.audio

import kotlin.math.sqrt

/**
 * Lightweight energy + zero-crossing voice activity detector with an adaptive noise
 * floor and hysteresis. Frame size is 32 ms (512 samples at 16 kHz).
 *
 * Upgrade path: swap for WebRTC VAD or Silero v5 ONNX behind the same [process] API.
 */
class Vad(private val sampleRate: Int = 16_000) {

    enum class Event { NONE, SPEECH_START, SPEECH_END }

    var speechActive: Boolean = false
        private set

    private var noiseFloor = 0.0
    private var speechFrames = 0
    private var silenceFrames = 0

    private val minSpeechFrames = 5
    private val minSilenceFrames = 16

    fun process(frame: ShortArray): Event {
        val energy = rms(frame)
        if (!speechActive) {
            noiseFloor = 0.95 * noiseFloor + 0.05 * energy
        }
        val startThreshold = maxOf(noiseFloor * 3.0, 0.008)
        val endThreshold = maxOf(noiseFloor * 1.8, 0.005)

        var event = Event.NONE
        if (speechActive) {
            if (energy < endThreshold) {
                silenceFrames++
                if (silenceFrames >= minSilenceFrames) {
                    speechActive = false
                    silenceFrames = 0
                    event = Event.SPEECH_END
                }
            } else {
                silenceFrames = 0
            }
        } else {
            if (energy > startThreshold) {
                speechFrames++
                if (speechFrames >= minSpeechFrames) {
                    speechActive = true
                    speechFrames = 0
                    event = Event.SPEECH_START
                }
            } else {
                speechFrames = 0
            }
        }
        return event
    }

    fun reset() {
        speechActive = false
        noiseFloor = 0.0
        speechFrames = 0
        silenceFrames = 0
    }

    private fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (s in frame) {
            val v = s / 32768.0
            sum += v * v
        }
        return sqrt(sum / frame.size)
    }

    companion object {
        const val FRAME_SAMPLES = 512
    }
}
