package com.andychang.clauderi.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Text-to-speech through the system TTS engine (offline, free). Only contract: [speak]. */
class Speaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    /** Voice name (from [voices]), pitch and rate. Applied on the next [speak]. */
    @Volatile var voiceName: String = ""
    @Volatile var pitch: Float = 1.0f
    @Volatile var rate: Float = 1.0f

    data class VoiceInfo(val name: String, val label: String, val language: String)

    /** Installed offline voices for Chinese / English. Empty until the engine is ready. */
    fun voices(): List<VoiceInfo> = runCatching {
        tts.voices.orEmpty()
            .filter { v -> v.locale.language == "zh" || v.locale.language == "en" }
            .filter { v -> !v.isNetworkConnectionRequired }
            .sortedWith(compareBy({ it.locale.language != "zh" }, { it.locale.toString() }, { it.name }))
            .map { VoiceInfo(it.name, it.locale.displayName, it.locale.language) }
    }.getOrDefault(emptyList())

    /** Preview a specific voice with given pitch/rate without changing the saved settings. */
    suspend fun preview(voice: String, pitchValue: Float, rateValue: Float, text: String) {
        val savedVoice = voiceName; val savedPitch = pitch; val savedRate = rate
        voiceName = voice; pitch = pitchValue; rate = rateValue
        try { speak(text) } finally { voiceName = savedVoice; pitch = savedPitch; rate = savedRate }
    }

    companion object {
        // Demo lines are the summons only; what he answers stays a surprise for the conversation.
        const val DEMO_ZH = "嘿，克勞德大人！"
        const val DEMO_EN = "Hey, Lord Claude!"
        private const val TAG = "Speaker"
    }

    suspend fun speak(text: String, locale: Locale = Locale.TRADITIONAL_CHINESE) {
        if (!ready.await()) { Log.w(TAG, "TTS engine unavailable"); return }
        if (text.isBlank()) return
        val chosen = voiceName.takeIf { it.isNotBlank() }?.let { name -> runCatching { tts.voices?.firstOrNull { it.name == name } }.getOrNull() }
        if (chosen != null) tts.voice = chosen else tts.setLanguage(locale)
        tts.setPitch(pitch.coerceIn(0.5f, 2.0f))
        tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine<Unit> { cont ->
            tts.stop()   // lets a previous speak() still awaiting receive onStop and resume
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { tts.stop() }
            val r = tts.speak(stripMarkdown(text), TextToSpeech.QUEUE_FLUSH, null, id)
            if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
        }
    }

    fun stop() { runCatching { tts.stop() } }

    /** LLMs love markdown; reading "asterisk asterisk" aloud is not. */
    private fun stripMarkdown(s: String): String =
        s.replace(Regex("[*_`#>]+"), "")
            .replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
}
