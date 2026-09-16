package com.andychang.clauderi.capabilities

import android.content.Context
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec

/**
 * Web search through the model provider's server-side tool. Nothing runs on the phone: Claude
 * searches, reads pages and cites them itself. OpenAI-compatible backends ignore the spec.
 */
class WebCapability(@Suppress("unused") private val context: Context) : Capability {

    override val id = CapabilityId.WEB

    override fun promptSection(settings: AppSettings) =
        "你可以用 web_search 上網查最新資訊（新聞、價格、營業時間、任何你不確定或可能過時的事）。" +
            "查到的內容講重點並提來源網站名稱，不要貼整段網址。時效性問題一定要查，不要憑記憶猜。"

    override fun tools(settings: AppSettings) = listOf(
        ToolSpec("web_search", "上網搜尋（伺服器端執行）。", server = true),
    )

    override suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult? = null

    override fun status() = "Claude 後端才有效；每次搜尋約台幣 0.3 元，加上讀到的網頁 token"
}
