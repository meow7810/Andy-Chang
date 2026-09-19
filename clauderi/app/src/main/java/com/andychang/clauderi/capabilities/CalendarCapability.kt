package com.andychang.clauderi.capabilities

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.ContentValues
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CalendarCapability(private val context: Context) : Capability {

    override val id = CapabilityId.CALENDAR
    private val fmt = SimpleDateFormat("MM/dd(E) HH:mm", Locale.TAIWAN)

    override fun promptSection(settings: AppSettings) =
        "你可以用 list_calendar_events 讀取行事曆，用 add_calendar_event 新增行程。回答行程問題前先查，不要猜；" +
            "新增前確認日期時間（今天的日期見最後一行），沒說結束時間就預設一小時。"

    override fun tools(settings: AppSettings) = listOf(
        ToolSpec(
            "list_calendar_events", "列出從現在起接下來 N 天內的行事曆事件。",
            listOf(ToolParam("days", "integer", "往後幾天（1–30），預設 7", required = false)),
        ),
        ToolSpec(
            "add_calendar_event", "在使用者的主要行事曆新增一筆行程。",
            listOf(
                ToolParam("title", "string", "標題"),
                ToolParam("start", "string", "開始時間，格式 yyyy-MM-dd HH:mm（全天行程只填 yyyy-MM-dd）"),
                ToolParam("end", "string", "結束時間，同格式；省略則為開始後一小時", required = false),
                ToolParam("location", "string", "地點", required = false),
                ToolParam("description", "string", "備註", required = false),
            ),
        ),
    )

    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun writeGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult? {
        if (call.name == "add_calendar_event") return addEvent(call)
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

    private suspend fun addEvent(call: ToolCall): ToolResult {
        if (!writeGranted()) return err("使用者尚未授權寫入行事曆。")
        val title = call.str("title")?.trim().orEmpty().ifBlank { return err("缺少 title") }
        val startRaw = call.str("start")?.trim().orEmpty().ifBlank { return err("缺少 start") }
        val allDay = startRaw.length == 10
        val start = parse(startRaw) ?: return err("start 格式不正確，要 yyyy-MM-dd HH:mm")
        val end = call.str("end")?.trim()?.takeIf { it.isNotBlank() }?.let { parse(it) ?: return err("end 格式不正確") }
            ?: (start + if (allDay) 86_400_000L else 3_600_000L)
        if (end <= start) return err("結束時間必須晚於開始時間")
        return withContext(Dispatchers.IO) {
            val calendarId = primaryCalendarId() ?: return@withContext err("手機上找不到可寫入的行事曆（要先登入 Google 帳號並同步行事曆）")
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
                call.str("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                call.str("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return@withContext err("寫入行事曆失敗")
            val shown = if (allDay) SimpleDateFormat("MM/dd(E) 全天", Locale.TAIWAN).format(Date(start))
            else "${fmt.format(Date(start))}–${SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date(end))}"
            ok("已新增：$shown  $title（id ${uri.lastPathSegment}）")
        }
    }

    /** All-day events are stored at UTC midnight; timed events in the phone's zone. */
    private fun parse(s: String): Long? = runCatching {
        if (s.length == 10) {
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time
        } else {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse(s)?.time
        }
    }.getOrNull()

    /** The primary calendar if flagged, else the first one the user can write to. */
    private fun primaryCalendarId(): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        var first: Long? = null
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj,
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (c.getInt(1) == 1) return id
                if (first == null) first = id
            }
        }
        return first
    }

    override fun status() = when {
        granted() && writeGranted() -> "行事曆權限：讀寫已授權"
        granted() -> "行事曆權限：只有讀取，尚未授權寫入"
        else -> "行事曆權限：尚未授權"
    }
}
