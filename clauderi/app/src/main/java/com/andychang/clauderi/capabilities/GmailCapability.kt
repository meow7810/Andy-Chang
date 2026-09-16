package com.andychang.clauderi.capabilities

import android.content.Context
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolParam
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec
import com.sun.mail.iap.Argument
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import com.sun.mail.imap.protocol.IMAPResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeUtility

/**
 * Gmail over IMAP with a Google "app password" (Google's official mechanism for third-party
 * mail clients; no OAuth project, no 7-day expiry). Read-only: the folder is opened READ_ONLY so
 * fetching a body never marks it as read.
 *
 * Search uses Gmail's X-GM-RAW extension, so the model can write normal Gmail search syntax
 * (`from:wemo newer_than:7d is:unread`).
 */
class GmailCapability(@Suppress("unused") private val context: Context) : Capability {

    override val id = CapabilityId.GMAIL
    private val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN)

    override fun promptSection(settings: AppSettings) =
        "你可以用 search_email 搜尋使用者的 Gmail（Gmail 搜尋語法：from:、subject:、newer_than:7d、is:unread、has:attachment），" +
            "用 read_email 讀完整內文。搜尋只回摘要；內文要使用者明確要求才讀。唯讀，不能寄信或刪信。"

    override fun tools(settings: AppSettings) = listOf(
        ToolSpec(
            "search_email", "用 Gmail 搜尋語法搜尋信件，回傳 id、寄件者、主旨、時間、是否未讀、開頭摘要。",
            listOf(
                ToolParam("query", "string", "Gmail 搜尋語法，例如 from:wemo newer_than:7d"),
                ToolParam("max", "integer", "最多幾封（1–20），預設 8", required = false),
            ),
        ),
        ToolSpec("read_email", "讀取一封信的完整純文字內文（最多 4000 字）。", listOf(ToolParam("id", "string", "search_email 回傳的 id"))),
    )

    fun configured(settings: AppSettings) = settings.gmailAddress.isNotBlank() && settings.gmailAppPassword.isNotBlank()

    override suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult? {
        if (call.name != "search_email" && call.name != "read_email") return null
        if (!configured(settings)) return err("使用者尚未在設定頁填 Gmail 帳號與應用程式密碼。")
        return withContext(Dispatchers.IO) {
            try {
                withStore(settings) { store ->
                    val folder = allMailFolder(store).also { it.open(Folder.READ_ONLY) }
                    try {
                        when (call.name) {
                            "search_email" -> search(folder, call.str("query").orEmpty(), (call.int("max") ?: 8).coerceIn(1, 20))
                            else -> read(folder, call.str("id")?.toLongOrNull() ?: return@withStore err("id 不正確"))
                        }
                    } finally {
                        runCatching { folder.close(false) }
                    }
                }
            } catch (e: javax.mail.AuthenticationFailedException) {
                err("Gmail 登入失敗：請確認帳號、應用程式密碼（16 碼，不是一般密碼），且 Google 帳號已開啟兩步驟驗證。")
            } catch (e: Exception) {
                err("Gmail 讀取失敗：${e.message}")
            }
        }
    }

    private inline fun <T> withStore(settings: AppSettings, block: (IMAPStore) -> T): T {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", "imap.gmail.com")
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
        }
        val store = Session.getInstance(props).getStore("imaps") as IMAPStore
        store.connect("imap.gmail.com", settings.gmailAddress.trim(), settings.gmailAppPassword.replace(" ", ""))
        try { return block(store) } finally { runCatching { store.close() } }
    }

    /** Gmail's "All Mail" is localized ([Gmail]/所有郵件); find it by the \All attribute, else INBOX. */
    private fun allMailFolder(store: IMAPStore): IMAPFolder {
        val all = runCatching {
            store.defaultFolder.list("*").filterIsInstance<IMAPFolder>()
                .firstOrNull { f -> f.attributes.any { it.equals("\\All", ignoreCase = true) } }
        }.getOrNull()
        return all ?: (store.getFolder("INBOX") as IMAPFolder)
    }

    private fun search(folder: IMAPFolder, query: String, max: Int): ToolResult {
        if (query.isBlank()) return err("缺少 query")
        @Suppress("UNCHECKED_CAST")
        val uids = folder.doCommand { p ->
            val args = Argument().writeAtom("CHARSET").writeAtom("UTF-8").writeAtom("X-GM-RAW").writeString(query, "UTF-8")
            val responses = p.command("UID SEARCH", args)
            val found = mutableListOf<Long>()
            for (r in responses) {
                if (r is IMAPResponse && r.keyEquals("SEARCH")) {
                    var n = r.readLong()
                    while (n != -1L) { found += n; n = r.readLong() }
                }
            }
            p.notifyResponseHandlers(responses)
            p.handleResult(responses.last())
            found
        } as List<Long>
        if (uids.isEmpty()) return ok("找不到符合「$query」的信。")
        val newest = uids.sortedDescending().take(max)
        val msgs = folder.getMessagesByUID(newest.toLongArray()).filterNotNull()
        val lines = msgs.map { m ->
            val uid = folder.getUID(m)
            val unread = !m.isSet(Flags.Flag.SEEN)
            val from = (m.from?.firstOrNull() as? InternetAddress)?.let { it.personal?.takeIf { p -> p.isNotBlank() } ?: it.address } ?: "?"
            val date = m.sentDate?.let { fmt.format(it) } ?: ""
            val snippet = runCatching { textOf(m) }.getOrDefault("").replace(Regex("\\s+"), " ").take(160)
            "[$uid] $date ${if (unread) "●" else "○"} $from｜${decode(m.subject)}\n    $snippet"
        }
        return ok("共 ${uids.size} 封，顯示最新 ${lines.size} 封：\n" + lines.joinToString("\n"))
    }

    private fun read(folder: IMAPFolder, uid: Long): ToolResult {
        val m = folder.getMessageByUID(uid) ?: return err("找不到 id $uid 的信（可能已被刪除）")
        val from = m.from?.joinToString { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() } ?: "?"
        val body = textOf(m).trim().take(4000)
        return ok("寄件者：$from\n主旨：${decode(m.subject)}\n時間：${m.sentDate?.let { fmt.format(it) } ?: ""}\n\n$body")
    }

    private fun decode(s: String?): String = runCatching { MimeUtility.decodeText(s ?: "") }.getOrDefault(s ?: "")

    /** Prefer text/plain; fall back to stripped text/html. */
    private fun textOf(part: Part): String {
        if (part.isMimeType("text/plain")) return part.content.toString()
        if (part.isMimeType("text/html")) return stripHtml(part.content.toString())
        if (part.isMimeType("multipart/alternative")) {
            val mp = part.content as Multipart
            var html = ""
            for (i in 0 until mp.count) {
                val bp = mp.getBodyPart(i)
                if (bp.isMimeType("text/plain")) return bp.content.toString()
                if (bp.isMimeType("text/html")) html = stripHtml(bp.content.toString())
            }
            return html
        }
        if (part.isMimeType("multipart/*")) {
            val mp = part.content as Multipart
            val sb = StringBuilder()
            for (i in 0 until mp.count) {
                val bp = mp.getBodyPart(i)
                if (Part.ATTACHMENT.equals(bp.disposition, ignoreCase = true)) { sb.append("[附件：${decode(bp.fileName)}]\n"); continue }
                sb.append(textOf(bp)).append('\n')
            }
            return sb.toString()
        }
        return ""
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace(Regex("\\n{3,}"), "\n\n")

    override fun status() = "帳號與應用程式密碼在「設定」頁填寫；唯讀，可隨時在 Google 帳號頁撤銷"
}
