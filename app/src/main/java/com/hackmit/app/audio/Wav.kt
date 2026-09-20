package com.hackmit.app.audio

import java.io.ByteArrayOutputStream

/** Builds a 16 kHz mono PCM16 WAV from raw little-endian PCM bytes. */
object Wav {
    fun wrapPcm16(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
        val out = ByteArrayOutputStream()
        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun leInt(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun leShort(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }
        str("RIFF"); leInt(36 + pcm.size); str("WAVE")
        str("fmt "); leInt(16); leShort(1); leShort(1)
        leInt(sampleRate); leInt(sampleRate * 2); leShort(2); leShort(16)
        str("data"); leInt(pcm.size)
        return out.toByteArray() + pcm
    }
}