package com.andychang.clauderi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "clauderi_settings")

enum class SttBackend(val label: String) { OPENAI("OpenAI gpt-4o-transcribe"), ANDROID("Android 內建（免費）") }
enum class LlmBackend(val label: String) { CLAUDE("Claude"), OPENAI("OpenAI"), DEEPSEEK("DeepSeek"), QWEN("Qwen"), CUSTOM("自訂") }

/**
 * Every capability is an explicit opt-in. A capability that is off contributes no tools and no
 * text to the system prompt, so the model cannot even tell it exists.
 */
enum class CapabilityId(val title: String, val summary: String) {
    NOTIFICATIONS("通知朗讀與回覆", "讀出、列出並回覆你勾選的 App 的通知"),
    CALENDAR("行事曆", "讀取接下來幾天的行程"),
    CONTACTS("聯絡人", "依姓名查電話"),
    ACTIONS("動作：訊息、鬧鐘、計時器、音樂", "透過系統 Intent 開啟對應 App，不會偷偷送出"),
    SCREEN("螢幕感知（無障礙服務）", "讀取目前畫面上的文字。預設關閉，最後才建議打開"),
}

data class AppSettings(
    val anthropicKey: String = "",
    val openAiKey: String = "",
    val deepSeekKey: String = "",
    val qwenKey: String = "",
    val customKey: String = "",
    val customBaseUrl: String = "",                // any OpenAI-compatible endpoint, e.g. Gemini / Groq / OpenRouter
    val customModel: String = "",
    val llm: LlmBackend = LlmBackend.CLAUDE,
    val claudeModel: String = "claude-sonnet-5",
    val openAiChatModel: String = "gpt-4o",
    val deepSeekModel: String = "deepseek-chat",
    val qwenModel: String = "qwen-plus",
    val stt: SttBackend = SttBackend.OPENAI,
    val sttModel: String = "gpt-4o-transcribe",
    val languageHint: String = "",                 // empty = auto-detect (zh / en mixed)
    val historyTurns: Int = 40,                    // messages sent to the model per turn; local memory is unlimited
    val speakVoiceReplies: Boolean = true,         // read the reply aloud when the question came in by voice
    val speakTextReplies: Boolean = false,
    val autoSendTypedInput: Boolean = false,       // dictation-keyboard mode: any typed input auto-sends after 2 s idle
    val customInstructions: String = "",
    val enabledCapabilities: Set<CapabilityId> = emptySet(),
    val notificationApps: Set<String> = emptySet(), // package names allowed for capability NOTIFICATIONS
    val readNotificationsAloud: Boolean = true,
    val allowAiCapabilityRequests: Boolean = true,  // model may ask (in chat) to enable a capability; user still confirms
) {
    fun has(cap: CapabilityId) = cap in enabledCapabilities
}

class Settings(private val context: Context) {

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            anthropicKey = p[K.anthropicKey] ?: "",
            openAiKey = p[K.openAiKey] ?: "",
            deepSeekKey = p[K.deepSeekKey] ?: "",
            qwenKey = p[K.qwenKey] ?: "",
            customKey = p[K.customKey] ?: "",
            customBaseUrl = p[K.customBaseUrl] ?: "",
            customModel = p[K.customModel] ?: "",
            llm = p[K.llm]?.let { runCatching { LlmBackend.valueOf(it) }.getOrNull() } ?: LlmBackend.CLAUDE,
            claudeModel = p[K.claudeModel] ?: "claude-sonnet-5",
            openAiChatModel = p[K.openAiChatModel] ?: "gpt-4o",
            deepSeekModel = p[K.deepSeekModel] ?: "deepseek-chat",
            qwenModel = p[K.qwenModel] ?: "qwen-plus",
            stt = p[K.stt]?.let { runCatching { SttBackend.valueOf(it) }.getOrNull() } ?: SttBackend.OPENAI,
            sttModel = p[K.sttModel] ?: "gpt-4o-transcribe",
            languageHint = p[K.language] ?: "",
            historyTurns = p[K.historyTurns] ?: 40,
            speakVoiceReplies = p[K.speakVoice] ?: true,
            speakTextReplies = p[K.speakText] ?: false,
            autoSendTypedInput = p[K.autoSendTyped] ?: false,
            customInstructions = p[K.customInstructions] ?: "",
            enabledCapabilities = (p[K.capabilities] ?: emptySet())
                .mapNotNull { runCatching { CapabilityId.valueOf(it) }.getOrNull() }.toSet(),
            notificationApps = p[K.notificationApps] ?: emptySet(),
            readNotificationsAloud = p[K.readNotificationsAloud] ?: true,
            allowAiCapabilityRequests = p[K.allowAiRequests] ?: true,
        )
    }

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        val next = block(current())
        context.dataStore.edit { p ->
            p[K.anthropicKey] = next.anthropicKey
            p[K.openAiKey] = next.openAiKey
            p[K.deepSeekKey] = next.deepSeekKey
            p[K.qwenKey] = next.qwenKey
            p[K.customKey] = next.customKey
            p[K.customBaseUrl] = next.customBaseUrl
            p[K.customModel] = next.customModel
            p[K.llm] = next.llm.name
            p[K.claudeModel] = next.claudeModel
            p[K.openAiChatModel] = next.openAiChatModel
            p[K.deepSeekModel] = next.deepSeekModel
            p[K.qwenModel] = next.qwenModel
            p[K.stt] = next.stt.name
            p[K.sttModel] = next.sttModel
            p[K.language] = next.languageHint
            p[K.historyTurns] = next.historyTurns
            p[K.speakVoice] = next.speakVoiceReplies
            p[K.speakText] = next.speakTextReplies
            p[K.autoSendTyped] = next.autoSendTypedInput
            p[K.customInstructions] = next.customInstructions
            p[K.capabilities] = next.enabledCapabilities.map { it.name }.toSet()
            p[K.notificationApps] = next.notificationApps
            p[K.readNotificationsAloud] = next.readNotificationsAloud
            p[K.allowAiRequests] = next.allowAiCapabilityRequests
        }
    }

    suspend fun setCapability(cap: CapabilityId, on: Boolean) = update {
        it.copy(enabledCapabilities = if (on) it.enabledCapabilities + cap else it.enabledCapabilities - cap)
    }

    private object K {
        val anthropicKey = stringPreferencesKey("anthropic_key")
        val openAiKey = stringPreferencesKey("openai_key")
        val deepSeekKey = stringPreferencesKey("deepseek_key")
        val qwenKey = stringPreferencesKey("qwen_key")
        val customKey = stringPreferencesKey("custom_key")
        val customBaseUrl = stringPreferencesKey("custom_base_url")
        val customModel = stringPreferencesKey("custom_model")
        val llm = stringPreferencesKey("llm")
        val claudeModel = stringPreferencesKey("claude_model")
        val openAiChatModel = stringPreferencesKey("openai_chat_model")
        val deepSeekModel = stringPreferencesKey("deepseek_model")
        val qwenModel = stringPreferencesKey("qwen_model")
        val stt = stringPreferencesKey("stt")
        val sttModel = stringPreferencesKey("stt_model")
        val language = stringPreferencesKey("language")
        val historyTurns = intPreferencesKey("history_turns")
        val speakVoice = booleanPreferencesKey("speak_voice")
        val speakText = booleanPreferencesKey("speak_text")
        val autoSendTyped = booleanPreferencesKey("auto_send_typed")
        val customInstructions = stringPreferencesKey("custom_instructions")
        val capabilities = stringSetPreferencesKey("capabilities")
        val notificationApps = stringSetPreferencesKey("notification_apps")
        val readNotificationsAloud = booleanPreferencesKey("read_notifications_aloud")
        val allowAiRequests = booleanPreferencesKey("allow_ai_requests")
    }
}
