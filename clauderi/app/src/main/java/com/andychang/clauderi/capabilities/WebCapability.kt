package com.andychang.clauderi.capabilities

import android.content.Context
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolParam
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Two ways onto the web with different price tags:
 *  - web_search: the model provider's server-side search (Claude only; a per-search fee).
 *  - open_url:   the phone fetches a page the user gave and hands the text over. No fee beyond
 *                the page's tokens; never touches a search engine.
 */
class WebCapability(@Suppress("unused") private val context: Context) : Capability {

    override val id = CapabilityId.WEB

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    override fun promptSection(settings: AppSettings) =
        "你可以用 web_search 上網查最新資訊（新聞、價格、營業時間、任何你不確定或可能過時的事），" +
            "用 open_url 讀使用者給的網址。使用者給了網址就用 open_url，不要再搜。查到的內容講重點並提來源網站名稱，不要貼整段網址。時效性問題一定要查，不要憑記憶猜。"

    override fun tools(settings: AppSettings) = listOf(
        ToolSpec("web_search", "上網搜尋（伺服器端執行，每次另計費）。", server = true),
        ToolSpec("open_url", "下載一個網址的內容並回傳純文字（最多 8000 字）。免費。", listOf(ToolParam("url", "string", "完整網址，含 https://"))),
    )

    override suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult? {
        if (call.name != "open_url") return null
        val url = call.str("url")?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return err("網址要以 http:// 或 https:// 開頭")
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                    .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext err("HTTP ${resp.code}")
                    val type = resp.header("Content-Type").orEmpty()
                    val body = resp.body?.string().orEmpty()
                    val text = if (type.contains("html", ignoreCase = true) || body.trimStart().startsWith("<")) htmlToText(body) else body
                    val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.get(1)?.trim()?.let { decodeEntities(it) }
                    val clean = text.trim().take(8000)
                    if (clean.isBlank()) err("這頁沒有可讀的文字（可能是純圖片或需要登入）")
                    else external((title?.let { "標題：$it\n\n" } ?: "") + clean, "網頁 ${runCatching { java.net.URI(url).host }.getOrNull() ?: url}")
                }
            } catch (e: Exception) {
                err("無法開啟：${e.message}")
            }
        }
    }

    private fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|aside)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?is)<!--.*?-->"), "")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .let { decodeEntities(it) }
        .replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n\n")

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    override fun status() = "open_url 免費（手機自己抓網頁）；web_search 只有 Claude 後端有效，每次約台幣 0.3 元"
}
