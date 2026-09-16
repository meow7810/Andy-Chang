package com.andychang.clauderi.data

import android.content.Context
import android.util.Log
import com.andychang.clauderi.llm.ChatProvider
import com.andychang.clauderi.llm.ChatTurn
import com.andychang.clauderi.llm.Role
import com.andychang.clauderi.llm.ToolExecutor
import com.andychang.clauderi.llm.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-term memory: a short, curated text about the user that survives the sliding window.
 *
 * The raw transcript is never trimmed (see [ConversationStore]); what the model *sees* each turn
 * is the last N messages plus this file. Messages that have fallen out of the window are folded
 * into the file by a background summarisation call, so a month from now the assistant still
 * knows about the Apple Store pilgrimage. The user can also write to it directly through the
 * `remember` tool, and can read, edit or wipe it from the settings screen.
 *
 * Stored only on the device. Syncing it elsewhere is a separate, opt-in feature.
 */
class MemoryStore(context: Context) {

    private val file = File(context.filesDir, "memory.json")
    private val mutex = Mutex()
    private val compactMutex = Mutex()

    private val _text = MutableStateFlow(load().first)
    /** The memory text as the model sees it. */
    val text: StateFlow<String> = _text.asStateFlow()

    /** Highest message id already folded into [text]. */
    @Volatile private var summarizedUpTo: Long = load().second

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN)

    suspend fun appendNote(note: String) = mutex.withLock {
        val line = "- （${fmt.format(Date())}，使用者要求記住）${note.trim()}"
        val next = (_text.value.trimEnd() + "\n" + line).trim()
        _text.value = next
        save(next, summarizedUpTo)
    }

    suspend fun replace(newText: String) = mutex.withLock {
        _text.value = newText.trim()
        save(_text.value, summarizedUpTo)
    }

    suspend fun clear() = mutex.withLock {
        _text.value = ""
        summarizedUpTo = 0
        file.delete()
    }

    /**
     * Fold messages that are older than the live window into the memory text. Runs after a turn,
     * off the critical path; a second call while one is running is a no-op.
     *
     * @param windowTurns how many recent messages stay raw (the user's historyTurns setting)
     * @param batch how many old messages must accumulate before a compaction is worth an API call
     */
    suspend fun maybeCompact(llm: ChatProvider, messages: List<ChatMessage>, windowTurns: Int, batch: Int = 20) {
        if (!compactMutex.tryLock()) return
        try {
            val eligible = messages.filter { !it.error && it.id > summarizedUpTo }.dropLast(windowTurns)
            if (eligible.size < batch) return
            val chunk = eligible.take(batch * 3)
            val transcript = chunk.joinToString("\n") { m ->
                "${fmt.format(Date(m.createdAt))} ${if (m.role == Role.USER) "使用者" else "助理"}：${m.text.take(600)}"
            }
            val prompt = buildString {
                append("你是記憶整理員。下面是「既有長期記憶」和「一段較舊的對話」。請輸出更新後的長期記憶：\n")
                append("- 只保留關於使用者的穩定事實：姓名、稱呼、偏好、習慣、重要的人、進行中的計畫、承諾過的事、曾經明確要求記住的事。\n")
                append("- 一次性的閒聊、已完成的小任務（設鬧鐘之類）不要留。\n")
                append("- 合併重複，刪除已過時或被推翻的內容；有日期的事件保留日期。\n")
                append("- 用條列，繁體中文，總長不超過 2000 字。只輸出記憶本身，不要任何開場或說明。\n\n")
                append("## 既有長期記憶\n").append(_text.value.ifBlank { "（空）" }).append("\n\n")
                append("## 較舊的對話\n").append(transcript)
            }
            val reply = llm.reply(
                systemPrompt = { "你是精確、簡潔的記憶整理員。" },
                history = listOf(ChatTurn(Role.USER, prompt)),
                tools = { emptyList() },
                executor = ToolExecutor { ToolResult("no tools", isError = true) },
            )
            val updated = reply.text.trim()
            if (updated.isBlank()) return
            mutex.withLock {
                _text.value = updated.take(4000)
                summarizedUpTo = chunk.last().id
                save(_text.value, summarizedUpTo)
            }
            Log.i(TAG, "memory compacted up to message ${chunk.last().id}")
        } catch (e: Exception) {
            Log.w(TAG, "memory compaction failed", e)
        } finally {
            compactMutex.unlock()
        }
    }

    private fun load(): Pair<String, Long> {
        if (!file.exists()) return "" to 0L
        return runCatching {
            val o = JSONObject(file.readText())
            o.optString("text", "") to o.optLong("upTo", 0L)
        }.getOrDefault("" to 0L)
    }

    private fun save(text: String, upTo: Long) {
        file.writeText(JSONObject().put("text", text).put("upTo", upTo).toString())
    }

    companion object { private const val TAG = "MemoryStore" }
}
