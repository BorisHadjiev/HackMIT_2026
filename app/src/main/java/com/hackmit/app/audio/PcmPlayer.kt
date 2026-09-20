package com.hackmit.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Streams linear16 mono PCM to the speaker (used for the agent's voice). */
class PcmPlayer(sampleRate: Int = 16_000) {

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    fun start() {
        runCatching { track.play() }
    }

    fun write(bytes: ByteArray) {
        runCatching { track.write(bytes, 0, bytes.size) }
    }

    /** Barge-in: drop queued audio so the caller can interrupt the agent. */
    fun flush() {
        runCatching {
            track.pause()
            track.flush()
            track.play()
        }
    }

    fun stop() {
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}