package com.andychang.clauderi.data

import android.content.Context
import com.andychang.clauderi.llm.ChatTurn
import com.andychang.clauderi.llm.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Source { VOICE, TEXT }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val createdAt: Long,
    val source: Source,
    val error: Boolean = false,
    val toolsUsed: List<String> = emptyList(),
)

/**
 * Append-only chat history stored as JSON lines in app-private storage. Nothing is ever
 * trimmed; [AppSettings.historyTurns] decides how much of it goes to the model each turn.
 * Voice and typed input share this one file.
 */
class ConversationStore(context: Context) {

    private val file = File(context.filesDir, "conversation.jsonl")
    private val mutex = Mutex()
    private val _messages = MutableStateFlow(load())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    suspend fun append(
        role: Role, text: String, source: Source, error: Boolean = false, toolsUsed: List<String> = emptyList(),
    ): ChatMessage = mutex.withLock {
        val msg = ChatMessage(
            id = (_messages.value.lastOrNull()?.id ?: 0L) + 1,
            role = role, text = text, createdAt = System.currentTimeMillis(),
            source = source, error = error, toolsUsed = toolsUsed,
        )
        file.appendText(toJson(msg).toString() + "\n")
        _messages.value = _messages.value + msg
        msg
    }

    suspend fun clear() = mutex.withLock {
        file.delete()
        _messages.value = emptyList()
    }

    /** Last [turns] non-error messages, provider-neutral. */
    fun recentTurns(turns: Int): List<ChatTurn> =
        _messages.value.filter { !it.error }.takeLast(turns).map { ChatTurn(it.role, it.text) }

    private fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                val tools = o.optJSONArray("tools")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
                ChatMessage(
                    id = o.getLong("id"),
                    role = Role.valueOf(o.getString("role")),
                    text = o.getString("text"),
                    createdAt = o.getLong("t"),
                    source = Source.valueOf(o.optString("src", "TEXT")),
                    error = o.optBoolean("err", false),
                    toolsUsed = tools,
                )
            }.getOrNull()
        }
    }

    private fun toJson(m: ChatMessage) = JSONObject()
        .put("id", m.id).put("role", m.role.name).put("text", m.text)
        .put("t", m.createdAt).put("src", m.source.name).put("err", m.error)
        .put("tools", JSONArray(m.toolsUsed))
}
