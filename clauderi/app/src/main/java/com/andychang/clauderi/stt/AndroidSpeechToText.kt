package com.andychang.clauderi.stt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Free speech recognition through the phone's own engine (Google's, on-device on Pixel and most
 * flagships). Unlike the OpenAI path it records by itself, so it is a "listen" API, not a
 * "transcribe this WAV" API.
 *
 * Because ClaudeRi registers its own (proxy) RecognitionService for the assistant role, we must
 * NOT use the system default recognizer (that may be us). [SystemRecognizer.pick] finds the
 * real engine explicitly.
 */
class AndroidSpeechToText(private val context: Context) {

    suspend fun listen(languageHint: String?): String = withContext(Dispatchers.Main) {
        val recognizer = SystemRecognizer.create(context) ?: throw SttException("這支手機沒有可用的系統語音辨識引擎")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Empty hint = let the engine use its default; "zh" -> Taiwanese Mandarin; anything else as-is.
            when (languageHint?.takeIf { it.isNotBlank() }) {
                null -> Unit
                "zh" -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                else -> putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageHint)
            }
        }
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (cont.isActive) cont.resume(text.trim())
                    }
                    override fun onError(error: Int) {
                        if (!cont.isActive) return
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> cont.resume("")
                            else -> cont.resumeWithException(SttException("系統語音辨識失敗（錯誤碼 $error）"))
                        }
                    }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                cont.invokeOnCancellation { runCatching { recognizer.cancel() } }
                recognizer.startListening(intent)
            }
        } finally {
            runCatching { recognizer.destroy() }
        }
    }
}

/** Locates the phone's real speech engine, skipping ClaudeRi's own proxy service. */
object SystemRecognizer {

    fun delegateComponent(context: Context): ComponentName? {
        val pm = context.packageManager
        val services = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
        return services.firstOrNull { it.packageName.startsWith("com.google.android") }
            ?: services.firstOrNull { it.packageName.contains("samsung") }
            ?: services.firstOrNull()
    }

    fun create(context: Context): SpeechRecognizer? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        val component = delegateComponent(context) ?: return null
        return SpeechRecognizer.createSpeechRecognizer(context, component)
    }
}
