package com.andychang.clauderi.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.andychang.clauderi.stt.SystemRecognizer
import com.andychang.clauderi.ui.MainActivity

/**
 * Capability 1: being the phone's "Default digital assistant app".
 *
 * Android requires a VoiceInteractionService + a session service. When the user long-presses
 * Home (or swipes from a corner), the system shows our session; we simply hand off to
 * [MainActivity] in voice mode and close the session. Everything else stays in the normal app.
 */
class ClaudeRiVoiceInteractionService : VoiceInteractionService()

class ClaudeRiSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = ClaudeRiSession(this)
}

class ClaudeRiSession(private val service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(service, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_VOICE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startAssistantActivity(intent)
        hide()
    }
}

/**
 * The voice-interaction XML must name a RecognitionService, and when ClaudeRi becomes the default
 * assistant Android also makes that service the phone-wide default for `SpeechRecognizer`.
 * A dead stub would break voice typing in every other app, so this one forwards everything to
 * the phone's real engine (Google's, Samsung's, ...).
 */
class ProxyRecognitionService : RecognitionService() {

    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val cb = listener ?: return
        val rec = SystemRecognizer.create(this) ?: run { runCatching { cb.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }; return }
        delegate?.destroy()
        delegate = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { runCatching { cb.readyForSpeech(params ?: Bundle()) } }
            override fun onBeginningOfSpeech() { runCatching { cb.beginningOfSpeech() } }
            override fun onRmsChanged(rmsdB: Float) { runCatching { cb.rmsChanged(rmsdB) } }
            override fun onBufferReceived(buffer: ByteArray?) { buffer?.let { b -> runCatching { cb.bufferReceived(b) } } }
            override fun onEndOfSpeech() { runCatching { cb.endOfSpeech() } }
            override fun onError(error: Int) { runCatching { cb.error(error) } }
            override fun onResults(results: Bundle?) { runCatching { cb.results(results ?: Bundle()) } }
            override fun onPartialResults(partialResults: Bundle?) { runCatching { cb.partialResults(partialResults ?: Bundle()) } }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(recognizerIntent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
    }

    override fun onCancel(listener: Callback?) { delegate?.cancel() }
    override fun onStopListening(listener: Callback?) { delegate?.stopListening() }
    override fun onDestroy() { delegate?.destroy(); delegate = null; super.onDestroy() }
}
