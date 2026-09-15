package com.andychang.cyanmind.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Records from the glasses' microphone.
 *
 * The glasses present themselves to Android as a Bluetooth headset (HFP). To get audio from
 * their mic we have to open a SCO link ("communication device") and record from
 * VOICE_COMMUNICATION. Once SCO is up the glasses' mic is the active input; if SCO fails we
 * fall back to whatever mic Android gives us (usually the phone) and report that.
 *
 * Audio is 16 kHz mono PCM16 (HFP wide-band). Recording stops on trailing silence or at
 * [maxDurationMs]. Returns a WAV byte array ready to upload.
 */
class HfpMicRecorder(private val context: Context) {

    data class Result(val wav: ByteArray, val durationMs: Long, val fromGlassesMic: Boolean)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    suspend fun record(
        maxDurationMs: Long = 30_000,
        trailingSilenceMs: Long = 1_300,
        leadingTimeoutMs: Long = 6_000,
    ): Result = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("RECORD_AUDIO permission not granted")
        }
        cancelled = false
        val routedToGlasses = routeToBluetoothMic()
        try {
            captureUntilSilence(maxDurationMs, trailingSilenceMs, leadingTimeoutMs, routedToGlasses)
        } finally {
            restoreRouting()
        }
    }

    // ------------------------------------------------------------------ routing

    @SuppressLint("MissingPermission")
    private suspend fun routeToBluetoothMic(): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sco = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (sco == null) {
                Log.w(TAG, "no Bluetooth SCO device available; using default mic")
                return false
            }
            val ok = audioManager.setCommunicationDevice(sco)
            Log.i(TAG, "setCommunicationDevice(${sco.productName}) = $ok")
            if (!ok) return false
            // Give the SCO link a moment to come up; there is no reliable callback on all OEMs.
            return waitForSco()
        } else {
            @Suppress("DEPRECATION")
            if (!audioManager.isBluetoothScoAvailableOffCall) return false
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            return waitForSco()
        }
    }

    private suspend fun waitForSco(): Boolean {
        val connected = withTimeoutOrNull(SCO_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        val st = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                        if (st == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                            runCatching { context.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(true)
                        } else if (st == AudioManager.SCO_AUDIO_STATE_ERROR) {
                            runCatching { context.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    context, receiver,
                    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                // Already connected?  (broadcast is sticky on most devices)
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    runCatching { context.unregisterReceiver(receiver) }
                    cont.resume(true)
                }
            }
        }
        Log.i(TAG, "SCO connected=$connected")
        return connected == true
    }

    private fun restoreRouting() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    // ------------------------------------------------------------------ capture

    @SuppressLint("MissingPermission")
    private fun captureUntilSilence(
        maxDurationMs: Long, trailingSilenceMs: Long, leadingTimeoutMs: Long, fromGlasses: Boolean,
    ): Result {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 5 * 2) // >= 200 ms
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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
                // adaptive noise floor from the first ~300 ms
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
        val durationMs = System.currentTimeMillis() - start
        return Result(WavCodec.pcm16ToWav(pcm.toByteArray(), SAMPLE_RATE), durationMs, fromGlasses)
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
        private const val TAG = "HfpMicRecorder"
        const val SAMPLE_RATE = 16_000
        private const val SCO_TIMEOUT_MS = 4_000L
        private const val MIN_VOICE_RMS = 350.0
    }
}
