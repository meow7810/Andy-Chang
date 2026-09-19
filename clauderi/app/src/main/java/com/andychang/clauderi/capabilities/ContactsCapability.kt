package com.andychang.clauderi.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolParam
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactsCapability(private val context: Context) : Capability {

    override val id = CapabilityId.CONTACTS

    override fun promptSection(settings: AppSettings) =
        "你可以用 search_contacts 依姓名查聯絡人的電話（唯讀）。"

    override fun tools(settings: AppSettings) = listOf(
        ToolSpec("search_contacts", "依姓名（部分比對）搜尋聯絡人，回傳姓名與電話。", listOf(ToolParam("query", "string", "姓名或關鍵字"))),
    )

    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult? {
        if (call.name != "search_contacts") return null
        if (!granted()) return err("使用者尚未授權讀取聯絡人。")
        val q = call.str("query")?.trim().orEmpty()
        if (q.isEmpty()) return err("缺少 query")
        val hits = search(q)
        return if (hits.isEmpty()) ok("找不到「$q」。") else ok(hits.joinToString("\n") { "${it.first}：${it.second}" })
    }

    /** name -> phone pairs, at most 20. Shared with ActionsCapability for name-to-number lookup. */
    suspend fun search(q: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Pair<String, String>>()
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )?.use { c ->
            while (c.moveToNext() && out.size < 20) out += (c.getString(0).orEmpty() to c.getString(1).orEmpty())
        }
        out.distinct()
    }

    override fun status() = if (granted()) "聯絡人權限：已授權" else "聯絡人權限：尚未授權"
}
