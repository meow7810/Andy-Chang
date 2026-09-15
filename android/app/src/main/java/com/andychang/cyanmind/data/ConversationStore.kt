package com.andychang.cyanmind.data

import android.content.Context
import com.andychang.cyanmind.llm.ChatTurn
import com.andychang.cyanmind.llm.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
)

/**
 * Append-only, file-backed chat history (JSON lines in app-private storage).
 *
 * This is the "memory" that the original app capped at 10 exchanges. Here everything is
 * kept; [AppSettings.historyTurns] decides how much is sent to the model each turn.
 * Deliberately simple (no Room / KSP) so the project builds without annotation processors.
 */
class ConversationStore(context: Context) {

    private val file = File(context.filesDir, "conversation.jsonl")
    private val mutex = Mutex()
    private val _messages = MutableStateFlow<List<ChatMessage>>(load())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    suspend fun append(role: Role, text: String, source: Source, error: Boolean = false): ChatMessage = mutex.withLock {
        val msg = ChatMessage(
            id = (_messages.value.lastOrNull()?.id ?: 0L) + 1,
            role = role, text = text, createdAt = System.currentTimeMillis(), source = source, error = error,
        )
        file.appendText(toJson(msg).toString() + "\n")
        _messages.value = _messages.value + msg
        msg
    }

    suspend fun clear() = mutex.withLock {
        file.delete()
        _messages.value = emptyList()
    }

    /** Last [turns] non-error messages, as the provider-neutral history the LLM receives. */
    fun recentTurns(turns: Int): List<ChatTurn> =
        _messages.value.filter { !it.error }.takeLast(turns).map { ChatTurn(it.role, it.text) }

    private fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                ChatMessage(
                    id = o.getLong("id"),
                    role = Role.valueOf(o.getString("role")),
                    text = o.getString("text"),
                    createdAt = o.getLong("t"),
                    source = Source.valueOf(o.optString("src", "TEXT")),
                    error = o.optBoolean("err", false),
                )
            }.getOrNull()
        }
    }

    private fun toJson(m: ChatMessage) = JSONObject()
        .put("id", m.id).put("role", m.role.name).put("text", m.text)
        .put("t", m.createdAt).put("src", m.source.name).put("err", m.error)
}
