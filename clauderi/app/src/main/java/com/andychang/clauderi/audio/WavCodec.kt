package com.andychang.clauderi.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal PCM16 mono -> WAV container writer (what the STT HTTP endpoints accept). */
object WavCodec {
    fun pcm16ToWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val byteRate = sampleRate * channels * 2
        val out = ByteArrayOutputStream(pcm.size + 44)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                 // PCM chunk size
        header.putShort(1)                // PCM format
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * 2).toShort())
        header.putShort(16)               // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }
}
