package com.andychang.clauderi.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
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
 * The voice-interaction XML must name a RecognitionService. We do transcription through
 * OpenAI ourselves, so this one just refuses politely if anything binds to it.
 */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
