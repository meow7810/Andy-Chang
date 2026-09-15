package com.andychang.cyanmind.audio

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

/**
 * Text-to-speech playback. Uses the Android system TTS engine so it works offline and costs
 * nothing; audio routes to the glasses automatically because they are the connected A2DP sink.
 *
 * Swap this for a cloud TTS (OpenAI `audio/speech`, ElevenLabs, ...) later if you want a nicer
 * voice: the only contract is [speak].
 */
class Speaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    @Volatile private var currentUtterance: String? = null

    suspend fun speak(text: String, locale: Locale = Locale.TRADITIONAL_CHINESE) {
        if (!ready.await()) { Log.w(TAG, "TTS engine unavailable"); return }
        if (text.isBlank()) return
        tts.setLanguage(locale)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        val id = UUID.randomUUID().toString()
        currentUtterance = id
        suspendCancellableCoroutine<Unit> { cont ->
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

    fun shutdown() { runCatching { tts.shutdown() } }

    /** LLMs love markdown; reading "asterisk asterisk" aloud is not. */
    private fun stripMarkdown(s: String): String =
        s.replace(Regex("[*_`#>]+"), "")
            .replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()

    companion object { private const val TAG = "Speaker" }
}
