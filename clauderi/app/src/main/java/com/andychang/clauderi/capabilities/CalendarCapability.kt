package com.andychang.clauderi.capabilities

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolParam
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CalendarCapability(private val context: Context) : Capability {

    override val id = CapabilityId.CALENDAR
    private val fmt = SimpleDateFormat("MM/dd(E) HH:mm", Locale.TAIWAN)

    override fun promptSection(settings: AppSettings) =
        "你可以用 list_calendar_events 讀取使用者的行事曆（唯讀）。回答行程問題前先查，不要猜。"

    override fun tools(settings: AppSettings) = listOf(
        ToolSpec(
            "list_calendar_events", "列出從現在起接下來 N 天內的行事曆事件。",
            listOf(ToolParam("days", "integer", "往後幾天（1–30），預設 7", required = false)),
        ),
    )

    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult? {
        if (call.name != "list_calendar_events") return null
        if (!granted()) return err("使用者尚未授權讀取行事曆。")
        val days = (call.int("days") ?: 7).coerceIn(1, 30)
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val end = now + days * 86_400_000L
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .let { ContentUris.appendId(it, now); ContentUris.appendId(it, end); it.build() }
            val proj = arrayOf(
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            )
            val rows = mutableListOf<String>()
            context.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && rows.size < 60) {
                    val title = c.getString(0).orEmpty().ifBlank { "（無標題）" }
                    val begin = c.getLong(1)
                    val allDay = c.getInt(3) == 1
                    val loc = c.getString(4).orEmpty()
                    rows += (if (allDay) SimpleDateFormat("MM/dd(E) 全天", Locale.TAIWAN).format(Date(begin)) else fmt.format(Date(begin))) +
                        "  $title" + if (loc.isNotBlank()) " @ $loc" else ""
                }
            }
            if (rows.isEmpty()) ok("接下來 $days 天沒有行程。") else ok(rows.joinToString("\n"))
        }
    }

    override fun status() = if (granted()) "行事曆權限：已授權" else "行事曆權限：尚未授權"
}
