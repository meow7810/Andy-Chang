package com.andychang.clauderi.data

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 養成資料包. Everything that makes him him, in one zip that another phone (or another brain) can
 * load: the raw conversation, long-term memory, the growth log (the user's lines and his self
 * notes), the listening log, the cost ledger, and the non-secret settings. Never a key, never a
 * capability grant: tools and permissions are re-bound on the new device by the user.
 *
 * manifest.json carries a sha256 per file, so a bundle that was edited in transit is refused
 * before anything on the phone is touched. The conversation's own hash chain is verified too.
 * Import keeps the previous files as .bak; the app reloads its stores afterwards.
 */
object Bundle {

    const val SCHEMA = "lordclaude-bundle/1"
    private const val MANIFEST = "manifest.json"
    private const val SETTINGS = "settings.json"
    private const val CONVERSATION = "conversation.jsonl"

    /** Files under filesDir that travel. The conversation is written through the store so a legacy file gets its header. */
    private val FILES = listOf(CONVERSATION, "memory.json", "growth.jsonl", "listening.jsonl", "usage.jsonl")

    data class Report(val files: Int, val messages: Int, val chainOk: Boolean, val schema: String, val settingsApplied: Boolean)

    suspend fun export(context: Context, out: OutputStream, cfg: AppSettings, conversation: ConversationStore) {
        val parts = linkedMapOf<String, ByteArray>()
        parts[CONVERSATION] = ByteArrayOutputStream().also { conversation.exportTo(it) }.toByteArray()
        for (name in FILES.drop(1)) {
            val f = File(context.filesDir, name)
            if (f.exists()) parts[name] = f.readBytes()
        }
        parts[SETTINGS] = settingsJson(cfg).toString(2).toByteArray(Charsets.UTF_8)
        val manifest = JSONObject().put("schema", SCHEMA).put("exportedAt", System.currentTimeMillis())
            .put("files", JSONObject().also { fs ->
                parts.forEach { (name, bytes) -> fs.put(name, JSONObject().put("sha256", ConversationStore.sha256(String(bytes, Charsets.UTF_8))).put("bytes", bytes.size)) }
            })
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put(MANIFEST, manifest.toString(2).toByteArray(Charsets.UTF_8))
            parts.forEach { (name, bytes) -> put(name, bytes) }
        }
    }

    /**
     * Verifies, then writes. Returns the settings block (if any) for the caller to apply, since
     * settings live in DataStore, not in a file. Throws with a readable message on any mismatch.
     */
    fun import(context: Context, input: InputStream, verifyConversation: (String) -> ImportReport): Pair<Report, JSONObject?> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zip.readBytes()
                zip.closeEntry(); e = zip.nextEntry
            }
        }
        val manifest = entries[MANIFEST]?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: throw IllegalArgumentException("不是養成資料包（沒有 manifest.json）")
        val schema = manifest.optString("schema", "")
        if (!schema.startsWith("lordclaude-bundle/")) throw IllegalArgumentException("不認識的格式：$schema")
        val listed = manifest.getJSONObject("files")
        for (name in listed.keys()) {
            val bytes = entries[name] ?: throw IllegalArgumentException("資料包缺少 $name")
            val want = listed.getJSONObject(name).getString("sha256")
            val got = ConversationStore.sha256(String(bytes, Charsets.UTF_8))
            if (want != got) throw IllegalArgumentException("$name 的雜湊不符，資料包可能被改過")
        }
        val conv = entries[CONVERSATION]?.let { String(it, Charsets.UTF_8) } ?: throw IllegalArgumentException("資料包缺少對話檔")
        val convReport = verifyConversation(conv)
        if (convReport.messages == 0) throw IllegalArgumentException("對話檔裡沒有訊息")
        // Only now touch the phone.
        var written = 0
        for (name in FILES) {
            val bytes = entries[name] ?: continue
            val f = File(context.filesDir, name)
            if (f.exists()) f.copyTo(File(context.filesDir, "$name.bak"), overwrite = true)
            f.writeBytes(bytes); written++
        }
        val settings = entries[SETTINGS]?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
        return Report(written, convReport.messages, convReport.chainOk, schema, settings != null) to settings
    }

    /** Settings worth carrying: who he is and how he is run. No keys, no grants, no device paths. */
    fun settingsJson(c: AppSettings): JSONObject = JSONObject()
        .put("persona", c.persona.name).put("customPersona", c.customPersona).put("stance", c.stance).put("customInstructions", c.customInstructions)
        .put("llm", c.llm.name).put("claudeModel", c.claudeModel).put("openAiChatModel", c.openAiChatModel)
        .put("deepSeekModel", c.deepSeekModel).put("qwenModel", c.qwenModel).put("geminiModel", c.geminiModel).put("customModel", c.customModel)
        .put("historyTurns", c.historyTurns).put("longTermMemory", c.longTermMemory)
        .put("memoryModel", c.memoryModel).put("memoryUseBatch", c.memoryUseBatch).put("memoryBackend", c.memoryBackend)
        .put("languageHint", c.languageHint).put("ttsVoice", c.ttsVoice).put("ttsPitch", c.ttsPitch.toDouble()).put("ttsRate", c.ttsRate.toDouble())
        .put("monthlyCapTwd", c.monthlyCapTwd).put("twdPerUsd", c.twdPerUsd.toDouble())

    fun applySettings(c: AppSettings, o: JSONObject): AppSettings = c.copy(
        persona = o.optString("persona", c.persona.name).let { n -> Persona.entries.firstOrNull { it.name == n } ?: c.persona },
        customPersona = o.optString("customPersona", c.customPersona),
        stance = o.optString("stance", c.stance),
        customInstructions = o.optString("customInstructions", c.customInstructions),
        llm = o.optString("llm", c.llm.name).let { n -> LlmBackend.entries.firstOrNull { it.name == n } ?: c.llm },
        claudeModel = o.optString("claudeModel", c.claudeModel), openAiChatModel = o.optString("openAiChatModel", c.openAiChatModel),
        deepSeekModel = o.optString("deepSeekModel", c.deepSeekModel), qwenModel = o.optString("qwenModel", c.qwenModel),
        geminiModel = o.optString("geminiModel", c.geminiModel), customModel = o.optString("customModel", c.customModel),
        historyTurns = o.optInt("historyTurns", c.historyTurns), longTermMemory = o.optBoolean("longTermMemory", c.longTermMemory),
        memoryModel = o.optString("memoryModel", c.memoryModel), memoryUseBatch = o.optBoolean("memoryUseBatch", c.memoryUseBatch),
        memoryBackend = o.optString("memoryBackend", c.memoryBackend),
        languageHint = o.optString("languageHint", c.languageHint), ttsVoice = o.optString("ttsVoice", c.ttsVoice),
        ttsPitch = o.optDouble("ttsPitch", c.ttsPitch.toDouble()).toFloat(), ttsRate = o.optDouble("ttsRate", c.ttsRate.toDouble()).toFloat(),
        monthlyCapTwd = o.optInt("monthlyCapTwd", c.monthlyCapTwd), twdPerUsd = o.optDouble("twdPerUsd", c.twdPerUsd.toDouble()).toFloat(),
    )
}
