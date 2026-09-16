package com.andychang.clauderi.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Records from the phone microphone as 16 kHz mono PCM16 and stops on trailing silence
 * (or at [maxDurationMs]). Returns a WAV byte array ready to upload to the STT endpoint.
 */
class MicRecorder(private val context: Context) {

    data class Result(val wav: ByteArray, val durationMs: Long)

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    suspend fun record(
        maxDurationMs: Long = 30_000,
        trailingSilenceMs: Long = 1_300,
        leadingTimeoutMs: Long = 6_000,
    ): Result = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("尚未允許麥克風權限")
        }
        cancelled = false
        captureUntilSilence(maxDurationMs, trailingSilenceMs, leadingTimeoutMs)
    }

    @SuppressLint("MissingPermission")
    private fun captureUntilSilence(maxDurationMs: Long, trailingSilenceMs: Long, leadingTimeoutMs: Long): Result {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 5 * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        val pcm = ByteArrayOutputStream()
        val chunk = ByteArray(SAMPLE_RATE / 50 * 2) // 20 ms frames
        val start = System.currentTimeMillis()
        var speechStartedAt = -1L
        var lastVoiceAt = -1L
        var noiseFloor = 0.0
        var frames = 0
        try {
            recorder.startRecording()
            while (!cancelled) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n <= 0) break
                pcm.write(chunk, 0, n)
                val now = System.currentTimeMillis()
                val rms = rms(chunk, n)
                frames++
                if (frames <= 15) noiseFloor = (noiseFloor * (frames - 1) + rms) / frames
                val threshold = maxOf(noiseFloor * 2.5, MIN_VOICE_RMS)
                if (rms > threshold) {
                    if (speechStartedAt < 0) speechStartedAt = now
                    lastVoiceAt = now
                }
                if (speechStartedAt < 0 && now - start > leadingTimeoutMs) break
                if (speechStartedAt > 0 && now - lastVoiceAt > trailingSilenceMs) break
                if (now - start > maxDurationMs) break
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        return Result(WavCodec.pcm16ToWav(pcm.toByteArray(), SAMPLE_RATE), System.currentTimeMillis() - start)
    }

    private fun rms(buf: ByteArray, n: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val v = s.toShort().toDouble()
            sum += v * v
            i += 2
        }
        return sqrt(sum / maxOf(1, n / 2))
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MIN_VOICE_RMS = 350.0
    }
}
