package com.andychang.clauderi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "clauderi_settings")

enum class Persona(val label: String) { LORD("克勞德大人"), PLAIN("一般助理") }
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
    ACTIONS("動作：訊息、鬧鐘、計時器、導航", "透過系統 Intent 開啟對應 App，不會偷偷送出"),
    MUSIC("音樂", "搜尋播放、暫停切歌不需權限；循環、隨機、正在播什麼、聆聽紀錄需要系統通知存取"),
    SCREEN("螢幕感知（無障礙服務）", "讀取目前畫面上的文字。預設關閉，最後才建議打開"),
    GMAIL("Gmail 信箱", "用 Google 應用程式密碼透過 IMAP 搜尋、讀信、封存（可逆）、開退訂連結；不能刪信或寄信"),
    CAMERA("相機與照片", "開相機拍一張給模型看，並可把剛拍的照片分享到其他 App"),
    WEB("網路", "open_url 讀你給的網址（免費）；web_search 自己上網找（Claude 伺服器端，每次另計費）"),
}

data class AppSettings(
    val anthropicKey: String = "",
    val openAiKey: String = "",
    val deepSeekKey: String = "",
    val qwenKey: String = "",
    val customKey: String = "",
    val customBaseUrl: String = "",                // any OpenAI-compatible endpoint, e.g. Gemini / Groq / OpenRouter
    val customModel: String = "",
    val gmailAddress: String = "",
    val gmailAppPassword: String = "",              // 16-char Google app password; stored locally only
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
    val assistOpensKeyboard: Boolean = false,      // long-press Home focuses the text field (for Typeless-style dictation keyboards) instead of recording
    val maxReplyTokens: Int = 2048,                // reply length cap (Claude): ~1200 Chinese chars
    val customInstructions: String = "",
    val enabledCapabilities: Set<CapabilityId> = emptySet(),
    val notificationApps: Set<String> = emptySet(), // package names allowed for capability NOTIFICATIONS
    val readNotificationsAloud: Boolean = true,
    val allowAiCapabilityRequests: Boolean = true,  // model may ask (in chat) to enable a capability; user still confirms
    val persona: Persona = Persona.LORD,
    val longTermMemory: Boolean = true,             // fold old turns into a curated memory file; off = sliding window only
    val memoryModel: String = "claude-haiku-4-5",   // compaction is bookkeeping, not reasoning: use the cheap model
    val memoryUseBatch: Boolean = true,             // Claude only: Batch API, half price, applied on a later turn
    val wakeGreeting: Boolean = true,               // speak the wake line when summoned by long-press Home
    val fictionMode: Boolean = false,               // everything said while on is tagged FICTION: never a fact about the user
    val listeningLog: Boolean = false,              // MUSIC: record what was played (title, artist, time listened) to listening.jsonl
    val ttsVoice: String = "",                      // Android TTS voice name; empty = engine default
    val ttsPitch: Float = 1.0f,                     // 0.5 (deep) .. 2.0
    val ttsRate: Float = 1.0f,                      // 0.5 .. 2.0
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
            gmailAddress = p[K.gmailAddress] ?: "",
            gmailAppPassword = p[K.gmailAppPassword] ?: "",
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
            assistOpensKeyboard = p[K.assistOpensKeyboard] ?: false,
            maxReplyTokens = p[K.maxReplyTokens] ?: 2048,
            customInstructions = p[K.customInstructions] ?: "",
            enabledCapabilities = (p[K.capabilities] ?: emptySet())
                .mapNotNull { runCatching { CapabilityId.valueOf(it) }.getOrNull() }.toSet(),
            notificationApps = p[K.notificationApps] ?: emptySet(),
            readNotificationsAloud = p[K.readNotificationsAloud] ?: true,
            allowAiCapabilityRequests = p[K.allowAiRequests] ?: true,
            persona = p[K.persona]?.let { runCatching { Persona.valueOf(it) }.getOrNull() } ?: Persona.LORD,
            longTermMemory = p[K.longTermMemory] ?: true,
            memoryModel = p[K.memoryModel] ?: "claude-haiku-4-5",
            memoryUseBatch = p[K.memoryUseBatch] ?: true,
            wakeGreeting = p[K.wakeGreeting] ?: true,
            fictionMode = p[K.fictionMode] ?: false,
            listeningLog = p[K.listeningLog] ?: false,
            ttsVoice = p[K.ttsVoice] ?: "",
            ttsPitch = p[K.ttsPitch] ?: 1.0f,
            ttsRate = p[K.ttsRate] ?: 1.0f,
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
            p[K.gmailAddress] = next.gmailAddress
            p[K.gmailAppPassword] = next.gmailAppPassword
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
            p[K.assistOpensKeyboard] = next.assistOpensKeyboard
            p[K.maxReplyTokens] = next.maxReplyTokens
            p[K.customInstructions] = next.customInstructions
            p[K.capabilities] = next.enabledCapabilities.map { it.name }.toSet()
            p[K.notificationApps] = next.notificationApps
            p[K.readNotificationsAloud] = next.readNotificationsAloud
            p[K.allowAiRequests] = next.allowAiCapabilityRequests
            p[K.persona] = next.persona.name
            p[K.longTermMemory] = next.longTermMemory
            p[K.memoryModel] = next.memoryModel
            p[K.memoryUseBatch] = next.memoryUseBatch
            p[K.wakeGreeting] = next.wakeGreeting
            p[K.fictionMode] = next.fictionMode
            p[K.listeningLog] = next.listeningLog
            p[K.ttsVoice] = next.ttsVoice
            p[K.ttsPitch] = next.ttsPitch
            p[K.ttsRate] = next.ttsRate
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
        val gmailAddress = stringPreferencesKey("gmail_address")
        val gmailAppPassword = stringPreferencesKey("gmail_app_password")
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
        val assistOpensKeyboard = booleanPreferencesKey("assist_opens_keyboard")
        val maxReplyTokens = intPreferencesKey("max_reply_tokens")
        val customInstructions = stringPreferencesKey("custom_instructions")
        val capabilities = stringSetPreferencesKey("capabilities")
        val notificationApps = stringSetPreferencesKey("notification_apps")
        val readNotificationsAloud = booleanPreferencesKey("read_notifications_aloud")
        val allowAiRequests = booleanPreferencesKey("allow_ai_requests")
        val persona = stringPreferencesKey("persona")
        val longTermMemory = booleanPreferencesKey("long_term_memory")
        val memoryModel = stringPreferencesKey("memory_model")
        val memoryUseBatch = booleanPreferencesKey("memory_use_batch")
        val wakeGreeting = booleanPreferencesKey("wake_greeting")
        val fictionMode = booleanPreferencesKey("fiction_mode")
        val listeningLog = booleanPreferencesKey("listening_log")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val ttsPitch = floatPreferencesKey("tts_pitch")
        val ttsRate = floatPreferencesKey("tts_rate")
    }
}
