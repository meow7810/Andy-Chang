package com.andychang.clauderi.capabilities

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.view.KeyEvent
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
            "set_alarm、set_timer、play_music（搜尋並播放，會開啟音樂 App）、control_media（暫停、繼續、切歌、循環、隨機，作用在正在播的 App）、now_playing（現在播什麼）、" +
            "navigate_to（開啟 Google Maps 開始導航；「剛才那個地址」就用對話裡提過的地點）。" +
            "動作會直接執行，執行前如果資訊不完整先問清楚。"

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
        ToolSpec(
            "play_music", "用預設音樂 App（例如 YouTube Music）搜尋並播放。使用者只給氣氛或情境時，自己挑一首具體的歌名加歌手當 query。",
            listOf(ToolParam("query", "string", "歌名、歌手、專輯或播放清單，越具體越準")),
        ),
        ToolSpec(
            "control_media",
            "控制正在播放的音樂。pause/play/next/previous/stop 對任何 App 都有效；" +
                "repeat_one（單曲循環）、repeat_all（全部循環）、repeat_off、shuffle_on、shuffle_off 需要使用者已授權「系統通知存取」（在通知能力頁完成），且看該 App 支不支援。",
            listOf(ToolParam("action", "string", "要做的事", enum = listOf(
                "pause", "play", "next", "previous", "stop", "repeat_one", "repeat_all", "repeat_off", "shuffle_on", "shuffle_off",
            ))),
        ),
        ToolSpec("now_playing", "查現在正在播什麼（歌名、歌手、哪個 App、播放中或暫停）。需要使用者已授權「系統通知存取」。"),
        ToolSpec(
            "navigate_to", "開啟地圖 App 並開始導航到指定地點。",
            listOf(
                ToolParam("destination", "string", "地址、店名或地標"),
                ToolParam("mode", "string", "交通方式", required = false, enum = listOf("driving", "walking", "transit", "bicycling")),
            ),
        ),
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
            "navigate_to" -> {
                val dest = call.str("destination")?.trim().orEmpty()
                if (dest.isEmpty()) err("缺少 destination")
                else {
                    val modeFlag = when (call.str("mode")) { "walking" -> "w"; "transit" -> "r"; "bicycling" -> "b"; else -> "d" }
                    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=$modeFlag"))
                        .setPackage("com.google.android.apps.maps")
                    // Fall back to any maps app if Google Maps is not installed.
                    (launch(nav)?.let { launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}"))) })
                        ?.let { err(it) } ?: ok("已開始導航到 $dest。")
                }
            }
            "play_music" -> {
                val q = call.str("query").orEmpty()
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .putExtra(android.app.SearchManager.QUERY, q)
                launch(i)?.let { err(it) } ?: ok("已請音樂 App 播放「$q」。")
            }
            "control_media" -> controlMedia(call.str("action").orEmpty())
            "now_playing" -> nowPlaying()
            else -> null
        }
    }

    private fun controlMedia(action: String): ToolResult {
        val key = when (action) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> null
        }
        if (key != null) {
            // Same path as a headset button: the system routes it to whichever app holds the media session.
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            return ok("已送出 $action。")
        }
        // Repeat / shuffle have no media key; they go through the app's MediaSession, which the
        // system only exposes to apps holding notification-listener access.
        val session = activeSession() ?: return err(NO_SESSION)
        val tc = session.transportControls
        when (action) {
            "repeat_one" -> tc.setRepeatMode(PlaybackState.REPEAT_MODE_ONE)
            "repeat_all" -> tc.setRepeatMode(PlaybackState.REPEAT_MODE_ALL)
            "repeat_off" -> tc.setRepeatMode(PlaybackState.REPEAT_MODE_NONE)
            "shuffle_on" -> tc.setShuffleMode(PlaybackState.SHUFFLE_MODE_ALL)
            "shuffle_off" -> tc.setShuffleMode(PlaybackState.SHUFFLE_MODE_NONE)
            else -> return err("未知的 action")
        }
        val app = appLabel(session.packageName)
        return ok("已請 $app 設定 $action。不是每個 App 都接受這個指令，如果沒生效請告訴使用者要在 App 裡手動按。")
    }

    private fun nowPlaying(): ToolResult {
        val session = activeSession() ?: return err(NO_SESSION)
        val md = session.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val state = when (session.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "播放中"
            PlaybackState.STATE_PAUSED -> "暫停"
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, null -> "停止"
            else -> "切換中"
        }
        if (title.isEmpty()) return ok("${appLabel(session.packageName)} 有媒體工作階段但沒有曲目資訊（$state）。")
        return ok("${appLabel(session.packageName)}｜$state｜$title" + (if (artist.isNotEmpty()) "｜$artist" else "") + (if (album.isNotEmpty()) "｜$album" else ""))
    }

    /** The session the system would send a media key to (first = highest priority), or null. */
    private fun activeSession(): MediaController? {
        val listener = ComponentName(context, ClaudeRiNotificationListener::class.java)
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        return try { msm.getActiveSessions(listener).firstOrNull() } catch (e: SecurityException) { null }
    }

    private fun appLabel(pkg: String): String =
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

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

    override fun status() = "透過系統 Intent 執行，不需額外權限；循環／隨機／正在播什麼需要「系統通知存取」（通知能力頁）"

    private companion object {
        const val NO_SESSION = "拿不到正在播放的媒體：可能沒有 App 在播，或使用者尚未授權「系統通知存取」（請他到通知能力頁按前往設定）。暫停與切歌不受影響。"
    }
}
