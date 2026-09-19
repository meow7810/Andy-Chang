package com.andychang.clauderi.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 養成紀錄. Everything that shaped him on purpose, as opposed to what merely happened (that is the
 * conversation file). Two authors write here and neither may touch the other's lines:
 *
 *  - the user: "今天留下什麼" (KEEP), standing rules (RULE), corrections (CORRECTION), and changes to
 *    personality policy such as the stance switch (SETTING). These are human-authored, so they carry
 *    the highest authority in the prompt and never pass through a model on the way in.
 *  - the assistant: notes about himself (SELF), written through the `self_note` tool. The user can
 *    read them, not edit them. They travel with him across a brain swap, so the next brain's first
 *    read is what the previous one thought he was.
 *
 * Append-only JSON lines. A status change (accepted → revoked) is a new line, not an edit, so how a
 * long-term change came about and how it was withdrawn stays answerable. Model-inferred entries
 * are born `proposed`; the user promotes them. Nothing here is fed to compaction.
 */
class GrowthLog(context: Context) {

    enum class Kind { KEEP, RULE, CORRECTION, SETTING, SELF }
    enum class Status { PROPOSED, ACCEPTED, REVOKED }

    data class Entry(val id: Long, val at: Long, val kind: Kind, val text: String, val by: String, val status: Status)

    private val file = File(context.filesDir, "growth.jsonl")
    private val lock = Any()
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** [by]: "user" or "model:<backend>:<model>". User lines are accepted at once; model lines start proposed unless [kind] is SELF. */
    fun append(kind: Kind, text: String, by: String): Entry = synchronized(lock) {
        val clean = text.trim()
        val status = if (by == "user" || kind == Kind.SELF) Status.ACCEPTED else Status.PROPOSED
        val e = Entry((_entries.value.maxOfOrNull { it.id } ?: 0L) + 1, System.currentTimeMillis(), kind, clean, by, status)
        file.appendText(
            JSONObject().put("id", e.id).put("t", e.at).put("k", e.kind.name).put("text", e.text).put("by", e.by).put("s", e.status.name).toString() + "\n",
        )
        _entries.value = _entries.value + e
        e
    }

    /** Only the user changes a status, and only on lines that are not his (SELF stays his). */
    fun setStatus(id: Long, status: Status) = synchronized(lock) {
        val cur = _entries.value.firstOrNull { it.id == id } ?: return
        if (cur.kind == Kind.SELF || cur.status == status) return
        file.appendText(JSONObject().put("set", id).put("t", System.currentTimeMillis()).put("s", status.name).toString() + "\n")
        _entries.value = _entries.value.map { if (it.id == id) it.copy(status = status) else it }
    }

    fun reload() = synchronized(lock) { _entries.value = load() }

    fun accepted(vararg kinds: Kind): List<Entry> = _entries.value.filter { it.status == Status.ACCEPTED && it.kind in kinds }

    /** How many SELF notes today; the tool is rate-limited so he cannot fill the file in one mood. */
    fun selfNotesToday(): Int {
        val dayStart = System.currentTimeMillis() - (System.currentTimeMillis() + java.util.TimeZone.getDefault().rawOffset) % 86_400_000L
        return _entries.value.count { it.kind == Kind.SELF && it.at >= dayStart }
    }

    /**
     * What goes into the prompt each turn: the user's own lines first (they outrank memory), then
     * his notes about himself. Bounded so a year of entries does not crowd out the conversation.
     */
    fun promptBlock(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN)
        val user = accepted(Kind.KEEP, Kind.RULE, Kind.CORRECTION, Kind.SETTING).takeLast(30)
        val self = accepted(Kind.SELF).takeLast(12)
        if (user.isEmpty() && self.isEmpty()) return ""
        return buildString {
            if (user.isNotEmpty()) {
                append("## 使用者親手留下的（他自己寫的，比長期記憶優先）\n")
                user.forEach { append("- ").append(fmt.format(Date(it.at))).append(' ').append(labelOf(it.kind)).append(it.text).append('\n') }
            }
            if (self.isNotEmpty()) {
                if (user.isNotEmpty()) append('\n')
                append("## 你自己寫下的（關於你自己；使用者看得到、改不了）\n")
                self.forEach { append("- ").append(fmt.format(Date(it.at))).append(' ').append(it.text).append('\n') }
            }
        }.trimEnd().take(3000)
    }

    private fun labelOf(k: Kind) = when (k) {
        Kind.KEEP -> ""
        Kind.RULE -> "【規則】"
        Kind.CORRECTION -> "【糾正】"
        Kind.SETTING -> "【設定】"
        Kind.SELF -> ""
    }

    private fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        val out = mutableListOf<Entry>()
        val status = mutableMapOf<Long, Status>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            when {
                o.has("set") -> runCatching { Status.valueOf(o.getString("s")) }.getOrNull()?.let { status[o.getLong("set")] = it }
                o.has("id") -> runCatching {
                    Entry(o.getLong("id"), o.getLong("t"), Kind.valueOf(o.getString("k")), o.getString("text"), o.optString("by", "user"), Status.valueOf(o.optString("s", "ACCEPTED")))
                }.getOrNull()?.let { out += it }
            }
        }
        return out.map { e -> status[e.id]?.let { e.copy(status = it) } ?: e }
    }

    companion object {
        const val MAX_SELF_NOTES_PER_DAY = 3
        const val MAX_LEN = 300
    }
}
