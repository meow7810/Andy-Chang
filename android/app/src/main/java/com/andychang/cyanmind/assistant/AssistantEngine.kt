package com.andychang.cyanmind.assistant

import android.content.Context
import android.util.Log
import com.andychang.cyanmind.audio.HfpMicRecorder
import com.andychang.cyanmind.audio.Speaker
import com.andychang.cyanmind.ble.AiState
import com.andychang.cyanmind.ble.GlassesEvent
import com.andychang.cyanmind.ble.GlassesManager
import com.andychang.cyanmind.data.AppSettings
import com.andychang.cyanmind.data.ConversationStore
import com.andychang.cyanmind.data.LlmBackend
import com.andychang.cyanmind.data.Settings
import com.andychang.cyanmind.data.Source
import com.andychang.cyanmind.data.SttBackend
import com.andychang.cyanmind.llm.ChatProvider
import com.andychang.cyanmind.llm.ClaudeChatProvider
import com.andychang.cyanmind.llm.OpenAiChatProvider
import com.andychang.cyanmind.llm.Role
import com.andychang.cyanmind.stt.OpenAiSpeechToText
import com.andychang.cyanmind.stt.SpeechToText
import com.andychang.cyanmind.stt.TypelessSpeechToText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

sealed class AssistantState {
    data object Idle : AssistantState()
    data object Listening : AssistantState()
    data object Transcribing : AssistantState()
    data class Thinking(val userText: String) : AssistantState()
    data class Speaking(val text: String) : AssistantState()
    data class Error(val message: String) : AssistantState()
}

/**
 * The brain of the app. One pipeline, two entry points:
 *
 *   voice:  wake word / mic button -> HFP mic -> STT -> LLM -> TTS (to glasses) -> save
 *   text:   keyboard -> LLM -> save (-> optional TTS)
 *
 * While a voice turn runs, the glasses' LED state is driven with [AiState] so the hardware
 * behaves like it does with the vendor app (thinking tone, speaking indicator, etc.).
 */
class AssistantEngine(
    private val context: Context,
    private val settings: Settings,
    private val store: ConversationStore,
    private val glasses: GlassesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val speaker = Speaker(context)
    private val recorder = HfpMicRecorder(context)
    private val turnMutex = Mutex()
    private var voiceJob: Job? = null

    private val _state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    fun start() {
        scope.launch {
            glasses.events.collect { ev ->
                when (ev) {
                    GlassesEvent.WakeWord -> startVoiceTurn()
                    GlassesEvent.PausePlayback -> speaker.stop()
                    else -> Unit
                }
            }
        }
    }

    /** Triggered by the wake word on the glasses or by the mic button in the UI. */
    fun startVoiceTurn() {
        if (voiceJob?.isActive == true) return
        voiceJob = scope.launch { runVoiceTurn() }
    }

    fun cancelVoiceTurn() {
        recorder.cancel()
        speaker.stop()
        voiceJob?.cancel()
        glasses.setAiState(AiState.SPEAK_STOP)
        glasses.stopHeartbeat()
        _state.value = AssistantState.Idle
    }

    /** Typed input from the chat screen. */
    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        scope.launch {
            val cfg = settings.current()
            runLlmTurn(clean, Source.TEXT, speakReply = cfg.speakTextReplies, cfg = cfg)
        }
    }

    // ------------------------------------------------------------------ pipeline

    private suspend fun runVoiceTurn() {
        val cfg = settings.current()
        val stt = buildStt(cfg) ?: run {
            _state.value = AssistantState.Error("尚未設定語音辨識的 API key（設定頁）")
            return
        }
        glasses.startHeartbeat()
        try {
            _state.value = AssistantState.Listening
            val rec = recorder.record()
            if (!rec.fromGlassesMic) Log.w(TAG, "recorded from phone mic, not glasses (SCO unavailable)")
            if (rec.durationMs < 400) { _state.value = AssistantState.Idle; return }

            glasses.setAiState(AiState.THINKING_START)
            _state.value = AssistantState.Transcribing
            val text = try {
                stt.transcribe(rec.wav, cfg.languageHint.ifBlank { null })
            } catch (e: Exception) {
                Log.e(TAG, "stt failed", e)
                glasses.setAiState(AiState.NO_NETWORK)
                _state.value = AssistantState.Error("語音辨識失敗：${e.message}")
                return
            }
            if (text.isBlank()) { glasses.setAiState(AiState.THINKING_STOP); _state.value = AssistantState.Idle; return }

            runLlmTurn(text, Source.VOICE, speakReply = true, cfg = cfg)
        } finally {
            glasses.stopHeartbeat()
        }
    }

    private suspend fun runLlmTurn(userText: String, source: Source, speakReply: Boolean, cfg: AppSettings) = turnMutex.withLock {
        val llm = buildLlm(cfg) ?: run {
            _state.value = AssistantState.Error("尚未設定 LLM 的 API key（設定頁）")
            glasses.setAiState(AiState.THINKING_STOP)
            return@withLock
        }
        store.append(Role.USER, userText, source)
        _state.value = AssistantState.Thinking(userText)
        if (source == Source.TEXT) glasses.setAiState(AiState.THINKING_START)

        val reply = try {
            llm.reply(cfg.systemPrompt, store.recentTurns(cfg.historyTurns))
        } catch (e: Exception) {
            Log.e(TAG, "llm failed", e)
            glasses.setAiState(AiState.NO_NETWORK)
            store.append(Role.ASSISTANT, "（回覆失敗：${e.message}）", source, error = true)
            _state.value = AssistantState.Error("AI 回覆失敗：${e.message}")
            return@withLock
        }
        glasses.setAiState(AiState.THINKING_STOP)
        store.append(Role.ASSISTANT, reply, source)

        if (speakReply) {
            _state.value = AssistantState.Speaking(reply)
            glasses.setAiState(AiState.SPEAK_START)
            try {
                speaker.speak(reply, Locale.TRADITIONAL_CHINESE)
            } finally {
                glasses.setAiState(AiState.SPEAK_STOP)
            }
        }
        _state.value = AssistantState.Idle
    }

    // ------------------------------------------------------------------ provider factories

    private fun buildStt(cfg: AppSettings): SpeechToText? = when (cfg.stt) {
        SttBackend.OPENAI -> cfg.openAiKey.takeIf { it.isNotBlank() }?.let { OpenAiSpeechToText(it) }
        SttBackend.TYPELESS -> cfg.typelessKey.takeIf { it.isNotBlank() }?.let { TypelessSpeechToText(it) }
    }

    private fun buildLlm(cfg: AppSettings): ChatProvider? = when (cfg.llm) {
        LlmBackend.CLAUDE -> cfg.anthropicKey.takeIf { it.isNotBlank() }?.let { ClaudeChatProvider(it, cfg.claudeModel) }
        LlmBackend.OPENAI -> cfg.openAiKey.takeIf { it.isNotBlank() }?.let { OpenAiChatProvider(it, cfg.openAiChatModel) }
    }

    companion object { private const val TAG = "AssistantEngine" }
}
