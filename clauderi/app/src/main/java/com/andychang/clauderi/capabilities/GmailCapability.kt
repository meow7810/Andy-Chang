package com.andychang.clauderi.capabilities

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolParam
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec
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
import javax.mail.search.BodyTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.SubjectTerm

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
        "你可以用 search_email 搜尋使用者的 Gmail（Gmail 搜尋語法：from:、subject:、newer_than:7d、is:unread、has:attachment；" +
            "盡量用英文語法組合條件，只有真的需要中文關鍵字時才放中文，中文查詢只會比對主旨、寄件者和內文），" +
            "用 read_email 讀完整內文。搜尋只回摘要；內文要使用者明確要求才讀。" +
            "整理用 archive_emails（從收件匣封存，可逆，信仍在「所有郵件」）和 unsubscribe（開該信的退訂連結給使用者按）。" +
            "封存前一定先用 search_email 讓使用者看到會動到哪些信、講出數量，得到明確同意再執行。不能刪信、不能寄信。"

    override fun tools(settings: AppSettings) = listOf(
        ToolSpec(
            "search_email", "用 Gmail 搜尋語法搜尋信件，回傳 id、寄件者、主旨、時間、是否未讀、開頭摘要。",
            listOf(
                ToolParam("query", "string", "Gmail 搜尋語法，例如 from:wemo newer_than:7d"),
                ToolParam("max", "integer", "最多幾封（1–20），預設 8", required = false),
            ),
        ),
        ToolSpec("read_email", "讀取一封信的完整純文字內文（最多 4000 字）。", listOf(ToolParam("id", "string", "search_email 回傳的 id"))),
        ToolSpec(
            "archive_emails", "把符合 Gmail 搜尋語法的信從收件匣封存（移出 INBOX，仍可在「所有郵件」找到，可逆）。一次最多 20000 封，大量時會跑幾分鐘。執行前必須先讓使用者確認。",
            listOf(ToolParam("query", "string", "Gmail 搜尋語法，例如 from:notifications@github.com older_than:30d")),
        ),
        ToolSpec(
            "unsubscribe", "讀取一封信的 List-Unsubscribe 標頭，開啟退訂網頁或退訂郵件讓使用者按下確認。",
            listOf(ToolParam("id", "string", "search_email 回傳的 id")),
        ),
    )

    fun configured(settings: AppSettings) = settings.gmailAddress.isNotBlank() && settings.gmailAppPassword.isNotBlank()

    override suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult? {
        if (call.name !in setOf("search_email", "read_email", "archive_emails", "unsubscribe")) return null
        if (!configured(settings)) return err("使用者尚未在設定頁填 Gmail 帳號與應用程式密碼。")
        return withContext(Dispatchers.IO) {
            try {
                withStore(settings) { store ->
                    if (call.name == "archive_emails") return@withStore archive(store, call.str("query").orEmpty())
                    val folder = allMailFolder(store).also { it.open(Folder.READ_ONLY) }
                    try {
                        when (call.name) {
                            "search_email" -> search(folder, call.str("query").orEmpty(), (call.int("max") ?: 8).coerceIn(1, 20))
                            "unsubscribe" -> unsubscribe(folder, call.str("id")?.toLongOrNull() ?: return@withStore err("id 不正確"))
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
        val uids: List<Long> = runCatching { gmailRawSearch(folder, query) }.getOrElse { standardSearch(folder, query) }
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
        return external("共 ${uids.size} 封，顯示最新 ${lines.size} 封：\n" + lines.joinToString("\n"), "Gmail 信件")
    }

    /**
     * Gmail search syntax through the X-GM-RAW extension. Gmail only accepts it as a *quoted*
     * string; a literal ({n}\r\n...) makes it answer "BAD Could not parse command", and javax.mail
     * turns any non-ASCII string argument into a literal. So the whole command is written as one
     * string: Protocol.writeCommand emits the low byte of every char, so the UTF-8 bytes of the
     * query are re-wrapped as ISO-8859-1 chars and reach the wire unchanged. Gmail reads the
     * quoted string as UTF-8, which is what makes Chinese keywords work.
     */
    private fun gmailRawSearch(folder: IMAPFolder, query: String): List<Long> {
        val quoted = "\"" + query.replace("\\", "").replace("\"", "") + "\""
        val wire = String(quoted.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        @Suppress("UNCHECKED_CAST")
        return folder.doCommand { p ->
            val responses = p.command("UID SEARCH X-GM-RAW $wire", null)
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
    }

    /** Fallback if the X-GM-RAW command is refused: plain IMAP SEARCH over subject / sender / body. */
    private fun standardSearch(folder: IMAPFolder, query: String): List<Long> {
        val term = OrTerm(arrayOf(SubjectTerm(query), FromStringTerm(query), BodyTerm(query)))
        return folder.search(term).map { folder.getUID(it) }
    }

    private fun read(folder: IMAPFolder, uid: Long): ToolResult {
        val m = folder.getMessageByUID(uid) ?: return err("找不到 id $uid 的信（可能已被刪除）")
        val from = m.from?.joinToString { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() } ?: "?"
        val body = textOf(m).trim().take(4000)
        return external("寄件者：$from\n主旨：${decode(m.subject)}\n時間：${m.sentDate?.let { fmt.format(it) } ?: ""}\n\n$body", "Gmail 信件（寄件者 $from）")
    }

    /**
     * Archive = remove from INBOX. In Gmail's IMAP, deleting + expunging inside INBOX only drops the
     * Inbox label (the message stays in All Mail), so this is the reversible "archive", not a delete.
     */
    private fun archive(store: IMAPStore, query: String): ToolResult {
        if (query.isBlank()) return err("缺少 query")
        val inbox = store.getFolder("INBOX") as IMAPFolder
        inbox.open(Folder.READ_WRITE)
        try {
            val uids = if (query.all { it.code < 128 }) gmailRawSearch(inbox, query) else standardSearch(inbox, query)
            if (uids.isEmpty()) return ok("收件匣裡沒有符合「$query」的信。")
            val batch = uids.take(20_000)
            var done = 0
            batch.chunked(500).forEach { chunk ->
                val msgs = inbox.getMessagesByUID(chunk.toLongArray()).filterNotNull().toTypedArray()
                inbox.setFlags(msgs, Flags(Flags.Flag.DELETED), true)
                done += msgs.size
            }
            inbox.expunge()
            val rest = uids.size - batch.size
            return ok("已封存 $done 封。" + if (rest > 0) "還有 $rest 封符合條件，再叫一次可以繼續。" else "")
        } finally {
            runCatching { inbox.close(true) }
        }
    }

    /** RFC 2369 List-Unsubscribe: prefer an https link (opens the browser), else a mailto (opens the mail app). */
    private fun unsubscribe(folder: IMAPFolder, uid: Long): ToolResult {
        val m = folder.getMessageByUID(uid) ?: return err("找不到 id $uid 的信")
        val header = m.getHeader("List-Unsubscribe")?.joinToString(",").orEmpty()
        if (header.isBlank()) return err("這封信沒有 List-Unsubscribe 標頭，只能在信內文找退訂連結，用 read_email 讀內文找 unsubscribe 字樣。")
        val targets = Regex("<([^>]+)>").findAll(header).map { it.groupValues[1].trim() }.toList()
        val https = targets.firstOrNull { it.startsWith("http", ignoreCase = true) }
        val mailto = targets.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
        val target = https ?: mailto ?: return err("List-Unsubscribe 格式無法解析：$header")
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ok(if (https != null) "已開啟退訂網頁，請使用者在頁面上按確認。" else "已開啟郵件 App 準備退訂信，請使用者按送出。")
        } catch (e: ActivityNotFoundException) {
            err("手機上沒有能開啟這個連結的 App：$target")
        }
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

    override fun status() = "帳號與應用程式密碼在「設定」頁填寫；可讀、可封存（可逆）、可開退訂連結；不能刪信或寄信"
}
