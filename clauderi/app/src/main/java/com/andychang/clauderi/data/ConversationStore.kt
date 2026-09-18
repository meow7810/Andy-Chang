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
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.TimeZone

enum class Source { VOICE, TEXT, PHOTO }   // PHOTO: a picture opened the turn, no words from the user

/**
 * Who is speaking, in the sense that matters for memory. A novel pasted into the chat is not a
 * fact about the user; a line the assistant guessed is not something the user said. Facts and
 * summaries only ever draw on USER_STATEMENT.
 */
enum class StatementType { USER_STATEMENT, AGENT_INFERENCE, EXTERNAL_REPORT, FICTION, EVENT, TEST }
// EVENT: something happened, nobody said it. TEST: the user was testing the assistant (test mode on); stays in
// the file, but is shown to nobody: not the model's window, not search_history, not compaction.

/**
 * Allowed uses of one line, decided at write time from who wrote it and in what mode. Provenance
 * decides what a piece of data may later become; nothing downstream may widen this set.
 *   recall  - may be quoted back (search_history, the model's history window)
 *   memory  - may feed long-term memory compaction
 *   eval    - exists for a test run only
 * "persona" and "train" are deliberately absent by default: the user grants those per line, never the app.
 */
object Uses {
    const val RECALL = "recall"; const val MEMORY = "memory"; const val EVAL = "eval"
    fun default(role: Role, statement: StatementType): Set<String> = when (statement) {
        StatementType.TEST -> setOf(EVAL)
        StatementType.USER_STATEMENT -> if (role == Role.USER) setOf(RECALL, MEMORY) else setOf(RECALL)
        else -> setOf(RECALL)
    }
}

/** What a tool actually reported back, separate from what the assistant claimed it did. */
data class ToolOutcome(val name: String, val observed: String)   // "ok" | "error" | "unknown"

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val createdAt: Long,
    val source: Source,
    val error: Boolean = false,
    val toolsUsed: List<String> = emptyList(),
    val toolErrors: List<String> = emptyList(),   // raw tool failures, shown under the bubble so they can be debugged
    val uid: String = "",                         // time-ordered, globally unique; survives merges across devices
    val tz: String = "",                          // the user's zone at the time, e.g. Asia/Taipei
    val statement: StatementType = if (role == Role.USER) StatementType.USER_STATEMENT else StatementType.AGENT_INFERENCE,
    val toolOutcomes: List<ToolOutcome> = emptyList(),
    val contextRef: String? = null,               // assistant only: JSON of what the model was actually shown this turn
    val sha: String = "",                         // sha256 over (uid, role, createdAt, text); "" on legacy lines
    val prev: String = "",                        // previous message's sha: the file is a hash chain
    val deletedAt: Long? = null,                  // tombstone: text is gone, the slot remains
    val prov: String = "",                        // authorship: "human:text" | "human:voice" | "model:<backend>:<model>" | "app:event" | "app"; "" = unknown (legacy)
    val uses: Set<String> = emptySet(),           // see [Uses]; empty on legacy lines = treated as [Uses.default]
) {
    val deleted: Boolean get() = deletedAt != null
    val allowedUses: Set<String> get() = uses.ifEmpty { Uses.default(role, statement) }
    /** "claude", "gemini", ... for a model-written line; "" otherwise. */
    val brain: String get() = if (prov.startsWith("model:")) prov.removePrefix("model:").substringBefore(':') else ""
    /** Hidden from every reader except the file itself and the eval view. */
    val hidden: Boolean get() = error || deleted || statement == StatementType.TEST
}

data class ImportReport(val messages: Int, val tombstones: Int, val chainOk: Boolean, val schema: String)

/**
 * Append-only chat history stored as JSON lines in app-private storage. Nothing is ever
 * trimmed or edited in place; [AppSettings.historyTurns] decides how much of it goes to the
 * model each turn. Voice and typed input share this one file.
 *
 * This file is the only truth. Long-term memory, summaries and anything else derived from it
 * can be recomputed; this cannot. Hence: hash chain, stable ids, time zones, tombstones instead
 * of deletes, and an export format that round-trips byte for byte.
 */
class ConversationStore(context: Context) {

    private val file = File(context.filesDir, "conversation.jsonl")
    private val backup = File(context.filesDir, "conversation.jsonl.bak")
    private val mutex = Mutex()
    private val _messages = MutableStateFlow(load())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    suspend fun append(
        role: Role, text: String, source: Source, error: Boolean = false,
        toolsUsed: List<String> = emptyList(), toolErrors: List<String> = emptyList(),
        statement: StatementType = if (role == Role.USER) StatementType.USER_STATEMENT else StatementType.AGENT_INFERENCE,
        toolOutcomes: List<ToolOutcome> = emptyList(),
        contextRef: String? = null,
        prov: String = "",
    ): ChatMessage = mutex.withLock {
        val last = _messages.value.lastOrNull()
        val now = System.currentTimeMillis()
        val uid = newUid(now)
        val msg = ChatMessage(
            id = (last?.id ?: 0L) + 1,
            role = role, text = text, createdAt = now, source = source, error = error,
            toolsUsed = toolsUsed, toolErrors = toolErrors,
            uid = uid, tz = TimeZone.getDefault().id, statement = statement,
            toolOutcomes = toolOutcomes, contextRef = contextRef,
            sha = digest(uid, role, now, text), prev = last?.sha.orEmpty(),
            prov = prov, uses = Uses.default(role, statement),
        )
        if (!file.exists()) file.appendText(HEADER + "\n")
        file.appendText(toJson(msg).toString() + "\n")
        _messages.value = _messages.value + msg
        msg
    }

    /**
     * The one exception to "never delete": the user asks for a message to go. The text is
     * removed from the in-memory copy and a tombstone is appended; the original line stays in
     * the file only until the next [compactTombstones], so the hash chain still verifies while
     * the content itself is gone from what anyone reads.
     */
    suspend fun tombstone(id: Long) = mutex.withLock {
        val idx = _messages.value.indexOfFirst { it.id == id }
        if (idx < 0 || _messages.value[idx].deleted) return@withLock
        val now = System.currentTimeMillis()
        file.appendText(JSONObject().put("tomb", id).put("t", now).toString() + "\n")
        rewrite(_messages.value.mapIndexed { i, m -> if (i == idx) m.copy(text = "", deletedAt = now) else m })
    }

    suspend fun clear() = mutex.withLock {
        file.delete()
        backup.delete()
        _messages.value = emptyList()
    }

    /** Re-read the file after it was replaced from a bundle (the bundle verified the chain first). */
    suspend fun reload() = mutex.withLock { _messages.value = load() }

    /** Copies the file out verbatim (header first if the file predates it). The export IS the format. */
    suspend fun exportTo(out: OutputStream) = mutex.withLock {
        out.bufferedWriter().use { w ->
            if (!file.exists()) { w.write(HEADER); w.newLine(); return@use }
            val lines = file.readLines()
            if (lines.firstOrNull()?.contains("\"schema\"") != true) { w.write(HEADER); w.newLine() }
            lines.forEach { w.write(it); w.newLine() }
        }
    }

    /**
     * Replaces the local history with an exported file after checking it parses and its hash
     * chain holds. The previous file is kept as a .bak until the next import or clear.
     */
    suspend fun importFrom(input: InputStream): ImportReport = mutex.withLock {
        val text = input.bufferedReader().readText()
        val report = verify(text)
        if (report.messages == 0 && report.tombstones == 0) return@withLock report
        if (file.exists()) file.copyTo(backup, overwrite = true)
        file.writeText(if (text.endsWith("\n")) text else text + "\n")
        _messages.value = load()
        report
    }

    /** Parses without touching disk: how many messages, whether the chain verifies. */
    fun verify(text: String): ImportReport {
        var schema = "(none)"
        var msgs = 0; var tombs = 0; var chainOk = true; var prevSha = ""
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            when {
                o.has("schema") -> schema = o.getString("schema")
                o.has("tomb") -> tombs++
                o.has("id") -> {
                    msgs++
                    val m = fromJson(o) ?: return@forEach
                    if (m.sha.isNotEmpty()) {
                        if (m.prev != prevSha && prevSha.isNotEmpty()) chainOk = false
                        if (!m.deleted && digest(m.uid, m.role, m.createdAt, m.text) != m.sha) chainOk = false
                        prevSha = m.sha
                    }
                }
            }
        }
        return ImportReport(msgs, tombs, chainOk, schema)
    }

    /** What the user said in their last [count] messages, joined; used to ground `remember` notes. */
    fun recentUserText(count: Int): String =
        _messages.value.asReversed().asSequence()
            .filter { it.role == Role.USER && !it.hidden && it.statement == StatementType.USER_STATEMENT }
            .take(count).joinToString("\n") { it.text }

    /** (first message time, live message count) for the prompt's "there is more history" hint; null when empty. */
    fun archiveSpan(): Pair<Long, Int>? {
        val live = _messages.value.filter { !it.hidden }
        val first = live.firstOrNull() ?: return null
        return first.createdAt to live.size
    }

    /**
     * Last [turns] visible messages, provider-neutral. Tombstoned, error and test lines are skipped.
     * Each turn carries its provenance in plain text so the model can tell a spoken line from a
     * typed one, and a reply written by a previous brain ([brain] = the current provider id) from
     * its own. Without these marks a swapped-in model reads the whole window as itself.
     */
    fun recentTurns(turns: Int, brain: String = "", includeTest: Boolean = false): List<ChatTurn> =
        window(turns, includeTest).map { ChatTurn(it.role, markedText(it, brain)) }

    private fun markedText(m: ChatMessage, brain: String): String = when {
        m.role == Role.USER && m.source == Source.VOICE -> "$VOICE_MARK" + m.text
        m.role == Role.ASSISTANT && m.brain.isNotEmpty() && brain.isNotEmpty() && m.brain != brain -> "$OTHER_BRAIN_MARK" + m.text
        else -> m.text
    }

    /** The ids the model will see for [turns], so the assistant's reply can record what it was shown. */
    fun windowIds(turns: Int, includeTest: Boolean = false): Pair<Long, Long>? =
        window(turns, includeTest).let { w -> if (w.isEmpty()) null else w.first().id to w.last().id }

    /**
     * TEST lines are hidden from the window once test mode is off. While it is on ([includeTest]),
     * the model sees the test conversation like any other, so a multi-turn test works and the
     * question being asked right now always reaches the model (providers require the last turn
     * to be the user's). Search and compaction never see TEST lines either way.
     */
    private fun window(turns: Int, includeTest: Boolean) =
        _messages.value.filter { !it.error && !it.deleted && (includeTest || it.statement != StatementType.TEST) }.takeLast(turns)

    // ------------------------------------------------------------------ file format

    private fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        val out = mutableListOf<ChatMessage>()
        val tombs = mutableMapOf<Long, Long>()
        file.readLines().forEach { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            when {
                o.has("tomb") -> tombs[o.getLong("tomb")] = o.optLong("t", 0L)
                o.has("id") -> fromJson(o)?.let { out += it }
            }
        }
        if (tombs.isEmpty()) return out
        return out.map { m -> tombs[m.id]?.let { t -> m.copy(text = "", deletedAt = t) } ?: m }
    }

    /** Rewrites the file from memory. Only used after a tombstone, so the dead text leaves the disk too. */
    private fun rewrite(msgs: List<ChatMessage>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write(HEADER); w.newLine()
            msgs.forEach { w.write(toJson(it).toString()); w.newLine() }
        }
        tmp.renameTo(file)
        _messages.value = msgs
    }

    private fun fromJson(o: JSONObject): ChatMessage? = runCatching {
        val role = Role.valueOf(o.getString("role"))
        val tools = o.optJSONArray("tools")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
        val terr = o.optJSONArray("terr")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList()
        val outcomes = o.optJSONArray("tobs")?.let { arr ->
            List(arr.length()) { i -> arr.getJSONObject(i).let { ToolOutcome(it.getString("n"), it.getString("o")) } }
        } ?: emptyList()
        ChatMessage(
            id = o.getLong("id"),
            role = role,
            text = o.getString("text"),
            createdAt = o.getLong("t"),
            source = Source.valueOf(o.optString("src", "TEXT")),
            error = o.optBoolean("err", false),
            toolsUsed = tools,
            toolErrors = terr,
            uid = o.optString("uid", ""),
            tz = o.optString("tz", ""),
            statement = o.optString("st", "").let { s -> StatementType.entries.firstOrNull { it.name == s } }
                ?: if (role == Role.USER) StatementType.USER_STATEMENT else StatementType.AGENT_INFERENCE,
            toolOutcomes = outcomes,
            contextRef = o.optString("ctx", "").ifBlank { null },
            sha = o.optString("sha", ""),
            prev = o.optString("prev", ""),
            deletedAt = if (o.has("del")) o.getLong("del") else null,
            prov = o.optString("prov", ""),
            uses = o.optJSONArray("uses")?.let { arr -> List(arr.length()) { arr.getString(it) }.toSet() } ?: emptySet(),
        )
    }.getOrNull()

    private fun toJson(m: ChatMessage) = JSONObject()
        .put("id", m.id).put("uid", m.uid).put("role", m.role.name).put("text", m.text)
        .put("t", m.createdAt).put("tz", m.tz).put("src", m.source.name).put("err", m.error)
        .put("st", m.statement.name)
        .put("tools", JSONArray(m.toolsUsed)).put("terr", JSONArray(m.toolErrors))
        .put("tobs", JSONArray(m.toolOutcomes.map { JSONObject().put("n", it.name).put("o", it.observed) }))
        .put("sha", m.sha).put("prev", m.prev)
        .also { o ->
            m.contextRef?.let { o.put("ctx", it) }
            m.deletedAt?.let { o.put("del", it) }
            if (m.prov.isNotEmpty()) o.put("prov", m.prov)
            if (m.uses.isNotEmpty()) o.put("uses", JSONArray(m.uses.toList()))
        }

    companion object {
        /** Bump when a field changes meaning. Readers must tolerate unknown fields and missing new ones. */
        const val SCHEMA = "lordclaude-conversation/2"   // /2: prov, uses, statement TEST. /1 files read unchanged
        const val VOICE_MARK = "〔語音〕"
        const val OTHER_BRAIN_MARK = "〔換腦前〕"
        private val HEADER = JSONObject().put("schema", SCHEMA).toString()
        private val rng = SecureRandom()

        /** 12 hex digits of millis (sorts by time) + 20 random hex digits. */
        fun newUid(now: Long): String {
            val b = ByteArray(10); rng.nextBytes(b)
            return "%012x".format(now) + b.joinToString("") { "%02x".format(it) }
        }

        fun digest(uid: String, role: Role, createdAt: Long, text: String): String =
            sha256("$uid|${role.name}|$createdAt|$text")

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
