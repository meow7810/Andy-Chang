package com.andychang.cyanmind.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cyanmind_settings")

enum class SttBackend { OPENAI, TYPELESS }
enum class LlmBackend { CLAUDE, OPENAI }

data class AppSettings(
    val anthropicKey: String = "",
    val openAiKey: String = "",
    val typelessKey: String = "",
    val stt: SttBackend = SttBackend.OPENAI,
    val llm: LlmBackend = LlmBackend.CLAUDE,
    val claudeModel: String = "claude-opus-5",
    val openAiChatModel: String = "gpt-4o",
    val personaName: String = Personas.DEFAULT.name,
    val customPersonaPrompt: String = "",
    val historyTurns: Int = 40,
    val speakTextReplies: Boolean = false,
    val languageHint: String = "zh",
) {
    val systemPrompt: String
        get() = Personas.byName(personaName)?.prompt?.takeIf { personaName != Personas.CUSTOM_NAME }
            ?: customPersonaPrompt.ifBlank { Personas.DEFAULT.prompt }
}

/**
 * Persisted app configuration. API keys live in DataStore inside the app's private storage.
 * NOTE: for a store release you would want EncryptedSharedPreferences / Keystore instead.
 */
class Settings(private val context: Context) {

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            anthropicKey = p[K.anthropicKey] ?: "",
            openAiKey = p[K.openAiKey] ?: "",
            typelessKey = p[K.typelessKey] ?: "",
            stt = p[K.stt]?.let { runCatching { SttBackend.valueOf(it) }.getOrNull() } ?: SttBackend.OPENAI,
            llm = p[K.llm]?.let { runCatching { LlmBackend.valueOf(it) }.getOrNull() } ?: LlmBackend.CLAUDE,
            claudeModel = p[K.claudeModel] ?: "claude-opus-5",
            openAiChatModel = p[K.openAiChatModel] ?: "gpt-4o",
            personaName = p[K.persona] ?: Personas.DEFAULT.name,
            customPersonaPrompt = p[K.customPersona] ?: "",
            historyTurns = p[K.historyTurns] ?: 40,
            speakTextReplies = p[K.speakText] ?: false,
            languageHint = p[K.language] ?: "zh",
        )
    }

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        val next = block(current())
        context.dataStore.edit { p ->
            p[K.anthropicKey] = next.anthropicKey
            p[K.openAiKey] = next.openAiKey
            p[K.typelessKey] = next.typelessKey
            p[K.stt] = next.stt.name
            p[K.llm] = next.llm.name
            p[K.claudeModel] = next.claudeModel
            p[K.openAiChatModel] = next.openAiChatModel
            p[K.persona] = next.personaName
            p[K.customPersona] = next.customPersonaPrompt
            p[K.historyTurns] = next.historyTurns
            p[K.speakText] = next.speakTextReplies
            p[K.language] = next.languageHint
        }
    }

    private object K {
        val anthropicKey = stringPreferencesKey("anthropic_key")
        val openAiKey = stringPreferencesKey("openai_key")
        val typelessKey = stringPreferencesKey("typeless_key")
        val stt = stringPreferencesKey("stt")
        val llm = stringPreferencesKey("llm")
        val claudeModel = stringPreferencesKey("claude_model")
        val openAiChatModel = stringPreferencesKey("openai_chat_model")
        val persona = stringPreferencesKey("persona")
        val customPersona = stringPreferencesKey("custom_persona")
        val historyTurns = intPreferencesKey("history_turns")
        val speakText = booleanPreferencesKey("speak_text")
        val language = stringPreferencesKey("language")
    }
}
