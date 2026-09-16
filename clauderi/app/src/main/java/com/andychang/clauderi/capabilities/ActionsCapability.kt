package com.andychang.clauderi.capabilities

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolParam
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec

/**
 * Actions go through system Intents, so the matching app (Messages, Clock, music player) opens
 * with everything pre-filled. Alarms and timers are set directly (EXTRA_SKIP_UI); messages are
 * never sent silently, the SMS app opens with the text ready and the user presses send.
 */
class ActionsCapability(private val context: Context) : Capability {

    override val id = CapabilityId.ACTIONS

    override fun promptSection(settings: AppSettings) =
        "你可以執行動作：send_message（開啟簡訊 App 並填好收件人與內容，由使用者按送出）、" +
            "set_alarm、set_timer、play_music。動作會直接執行，執行前如果資訊不完整先問清楚。"

    override fun tools(settings: AppSettings) = listOf(
        ToolSpec(
            "send_message", "準備一則簡訊：開啟簡訊 App，收件人與內容已填好。收件人可以是電話號碼或聯絡人姓名（需開啟聯絡人能力）。",
            listOf(ToolParam("to", "string", "電話號碼或聯絡人姓名"), ToolParam("body", "string", "訊息內容")),
        ),
        ToolSpec(
            "set_alarm", "設定鬧鐘（24 小時制）。",
            listOf(
                ToolParam("hour", "integer", "0–23"), ToolParam("minute", "integer", "0–59"),
                ToolParam("label", "string", "鬧鐘標籤", required = false),
            ),
        ),
        ToolSpec(
            "set_timer", "設定倒數計時器。",
            listOf(ToolParam("seconds", "integer", "總秒數"), ToolParam("label", "string", "標籤", required = false)),
        ),
        ToolSpec("play_music", "用預設音樂 App 搜尋並播放。", listOf(ToolParam("query", "string", "歌名、歌手或播放清單"))),
    )

    override suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult? {
        return when (call.name) {
            "send_message" -> {
                val to = call.str("to")?.trim().orEmpty()
                val body = call.str("body").orEmpty()
                if (to.isEmpty()) err("缺少收件人")
                else {
                    val number = resolveNumber(to, settings) ?: return err("找不到「$to」的電話；請提供號碼")
                    launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", body))
                        ?.let { err(it) } ?: ok("已開啟簡訊 App，收件人 $number，內容已填好，等使用者按送出。")
                }
            }
            "set_alarm" -> {
                val h = call.int("hour") ?: return err("缺少 hour")
                val m = call.int("minute") ?: 0
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                call.str("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                launch(i)?.let { err(it) } ?: ok("已設定 %02d:%02d 的鬧鐘。".format(h, m))
            }
            "set_timer" -> {
                val s = call.int("seconds") ?: return err("缺少 seconds")
                val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                call.str("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                launch(i)?.let { err(it) } ?: ok("已設定 $s 秒的計時器。")
            }
            "play_music" -> {
                val q = call.str("query").orEmpty()
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .putExtra(android.app.SearchManager.QUERY, q)
                launch(i)?.let { err(it) } ?: ok("已請音樂 App 播放「$q」。")
            }
            else -> null
        }
    }

    private suspend fun resolveNumber(to: String, settings: AppSettings): String? {
        if (to.any { it.isDigit() } && to.all { it.isDigit() || it in "+-() " }) return to.filter { it.isDigit() || it == '+' }
        if (!settings.has(CapabilityId.CONTACTS)) return null
        val contacts = ContactsCapability(context)
        if (!contacts.granted()) return null
        return contacts.search(to).firstOrNull()?.second
    }

    /** Returns an error string, or null on success. */
    private fun launch(intent: Intent): String? = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: ActivityNotFoundException) {
        "手機上沒有可以處理這個動作的 App"
    }

    override fun status() = "透過系統 Intent 執行，不需額外權限"
}
