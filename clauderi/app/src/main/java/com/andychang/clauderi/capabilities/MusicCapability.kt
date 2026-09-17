package com.andychang.clauderi.capabilities

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.data.ListeningLog
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolParam
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec

/**
 * Music as one block: start something (system search Intent), steer it (media keys), ask what is
 * on / set repeat & shuffle (the active MediaSession), and remember what was listened to.
 *
 * Two levels of access, both explicit:
 *  - play / pause / next / previous need nothing: the system routes them like a headset button.
 *  - now-playing, repeat, shuffle and the listening log go through MediaSessionManager, which
 *    Android only opens to apps holding notification-listener access. Without it those tools
 *    say so and the rest keeps working.
 */
class MusicCapability(private val context: Context, private val log: ListeningLog) : Capability {

    override val id = CapabilityId.MUSIC

    override fun promptSection(settings: AppSettings) =
        "你可以放音樂：play_music（搜尋並播放，會開啟音樂 App；使用者只給氣氛時自己挑一首具體的歌）、" +
            "control_media（暫停、繼續、切歌、循環、隨機）、now_playing（現在播什麼）" +
            (if (settings.listeningLog) "、listening_history（使用者聽過什麼、聽了幾次；只從開始記錄那天算起）" else "") + "。"

    override fun tools(settings: AppSettings): List<ToolSpec> {
        val list = mutableListOf(
            ToolSpec(
                "play_music", "用預設音樂 App（例如 YouTube Music）搜尋並播放。使用者只給氣氛或情境時，自己挑一首具體的歌名加歌手當 query。",
                listOf(ToolParam("query", "string", "歌名、歌手、專輯或播放清單，越具體越準")),
            ),
            ToolSpec(
                "control_media",
                "控制正在播放的音樂。pause/play/next/previous/stop 對任何 App 都有效；" +
                    "repeat_one（單曲循環）、repeat_all（全部循環）、repeat_off、shuffle_on、shuffle_off 需要使用者已授權「系統通知存取」，且看該 App 支不支援。",
                listOf(ToolParam("action", "string", "要做的事", enum = listOf(
                    "pause", "play", "next", "previous", "stop", "repeat_one", "repeat_all", "repeat_off", "shuffle_on", "shuffle_off",
                ))),
            ),
            ToolSpec("now_playing", "查現在正在播什麼（歌名、歌手、哪個 App、播放中或暫停）。需要使用者已授權「系統通知存取」。"),
        )
        if (settings.listeningLog) list += ToolSpec(
            "listening_history",
            "查使用者的聆聽紀錄：某首歌或歌手最近聽了幾次、最近都在聽什麼。只有開啟紀錄之後、而且助理在場時播的才算，30 秒以上才算一次。",
            listOf(
                ToolParam("query", "string", "歌名、歌手或專輯的關鍵字；留空看全部", required = false),
                ToolParam("days", "integer", "回看幾天（預設 7，上限 365）", required = false),
            ),
        )
        return list
    }

    override suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult? = when (call.name) {
        "play_music" -> {
            val q = call.str("query").orEmpty()
            val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(android.app.SearchManager.QUERY, q)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(i); ok("已請音樂 App 播放「$q」。") } catch (e: ActivityNotFoundException) { err("手機上沒有可以播放音樂的 App") }
        }
        "control_media" -> controlMedia(call.str("action").orEmpty())
        "now_playing" -> nowPlaying()
        "listening_history" ->
            if (!settings.listeningLog) err("使用者沒有開啟聆聽紀錄。")
            else ok(log.render(call.str("query").orEmpty(), (call.int("days") ?: 7).coerceIn(1, 365)))
        else -> null
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
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            return ok("已送出 $action。")
        }
        val session = activeSession(context) ?: return err(NO_SESSION)
        // Repeat / shuffle are not on the framework controller; the compat one wraps the same session.
        val tc = MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(session.sessionToken)).transportControls
        when (action) {
            "repeat_one" -> tc.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_ONE)
            "repeat_all" -> tc.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_ALL)
            "repeat_off" -> tc.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE)
            "shuffle_on" -> tc.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL)
            "shuffle_off" -> tc.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE)
            else -> return err("未知的 action")
        }
        return ok("已請 ${appLabel(context, session.packageName)} 設定 $action。不是每個 App 都接受這個指令，如果沒生效請告訴使用者要在 App 裡手動按。")
    }

    private fun nowPlaying(): ToolResult {
        val session = activeSession(context) ?: return err(NO_SESSION)
        val md = session.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val state = stateLabel(session.playbackState?.state)
        val app = appLabel(context, session.packageName)
        if (title.isEmpty()) return ok("$app 有媒體工作階段但沒有曲目資訊（$state）。")
        return ok("$app｜$state｜$title" + (if (artist.isNotEmpty()) "｜$artist" else "") + (if (album.isNotEmpty()) "｜$album" else ""))
    }

    override fun status(): String =
        if (listenerEnabled(context)) "播放與暫停切歌不需權限；系統通知存取：已授權（循環、隨機、正在播什麼、聆聽紀錄可用）"
        else "播放與暫停切歌不需權限；循環、隨機、正在播什麼、聆聽紀錄需要「系統通知存取」：尚未授權"

    companion object {
        private const val NO_SESSION = "拿不到正在播放的媒體：可能沒有 App 在播，或使用者尚未授權「系統通知存取」（請他到能力頁按前往設定）。暫停與切歌不受影響。"

        fun listenerEnabled(context: Context): Boolean =
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /** The session the system would send a media key to (first = highest priority), or null. */
        fun activeSession(context: Context): MediaController? {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            return try { msm.getActiveSessions(listenerComponent(context)).firstOrNull() } catch (e: SecurityException) { null }
        }

        fun listenerComponent(context: Context) = ComponentName(context, ClaudeRiNotificationListener::class.java)

        fun appLabel(context: Context, pkg: String): String =
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

        fun stateLabel(state: Int?) = when (state) {
            PlaybackState.STATE_PLAYING -> "播放中"
            PlaybackState.STATE_PAUSED -> "暫停"
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, null -> "停止"
            else -> "切換中"
        }
    }
}

/**
 * Watches active media sessions and writes a line to the listening log whenever a track ends
 * (metadata changes, playback stops, or the session goes away). Attached by the notification
 * listener service, which is the process the system grants session access to.
 *
 * Only time actually spent playing is counted, so pausing a song for an hour is not an hour.
 */
object ListeningWatcher {
    @Volatile var enabled = false          // mirrors AppSettings.listeningLog && MUSIC capability on
    @Volatile var log: ListeningLog? = null

    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var appContext: Context? = null
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> resync(list.orEmpty()) }

    private class Current(val pkg: String, val title: String, val artist: String, val album: String) {
        var playedMs = 0L
        var playingSince: Long? = null
        var startedAt = System.currentTimeMillis()
    }
    private val current = HashMap<String, Current>()   // by package

    fun attach(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager = msm
        val component = MusicCapability.listenerComponent(ctx)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            resync(msm.getActiveSessions(component))
        } catch (e: SecurityException) { /* access not granted yet; attach again on next connect */ }
    }

    fun detach() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        synchronized(callbacks) {
            for ((c, cb) in callbacks) runCatching { c.unregisterCallback(cb) }
            callbacks.clear()
        }
        synchronized(current) { current.keys.toList().forEach { flush(it) } }
    }

    private fun resync(list: List<MediaController>) {
        synchronized(callbacks) {
            val gone = callbacks.keys.filter { c -> list.none { it.packageName == c.packageName } }
            for (c in gone) { runCatching { c.unregisterCallback(callbacks.remove(c)!!) }; synchronized(current) { flush(c.packageName) } }
            for (c in list) {
                if (callbacks.keys.any { it.packageName == c.packageName }) continue
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = onMetadata(c, metadata)
                    override fun onPlaybackStateChanged(state: PlaybackState?) = onState(c, state)
                    override fun onSessionDestroyed() { synchronized(current) { flush(c.packageName) } }
                }
                c.registerCallback(cb, main)
                callbacks[c] = cb
                onMetadata(c, c.metadata)
                onState(c, c.playbackState)
            }
        }
    }

    private fun onMetadata(c: MediaController, md: MediaMetadata?) {
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        synchronized(current) {
            val cur = current[c.packageName]
            if (cur != null && cur.title == title && cur.artist == artist) return
            flush(c.packageName)
            if (title.isNotEmpty()) {
                val n = Current(c.packageName, title, artist, album)
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) n.playingSince = System.currentTimeMillis()
                current[c.packageName] = n
            }
        }
    }

    private fun onState(c: MediaController, st: PlaybackState?) {
        synchronized(current) {
            val cur = current[c.packageName] ?: return
            val now = System.currentTimeMillis()
            if (st?.state == PlaybackState.STATE_PLAYING) {
                if (cur.playingSince == null) cur.playingSince = now
            } else {
                cur.playingSince?.let { cur.playedMs += now - it; cur.playingSince = null }
                if (st?.state == PlaybackState.STATE_STOPPED) flush(c.packageName)
            }
        }
    }

    /** Close the current track for [pkg] and write it if recording is on. Caller holds `current`. */
    private fun flush(pkg: String) {
        val cur = current.remove(pkg) ?: return
        cur.playingSince?.let { cur.playedMs += System.currentTimeMillis() - it }
        if (!enabled || cur.playedMs < 5_000) return
        val ctx = appContext ?: return
        log?.append(ListeningLog.Play(cur.startedAt, cur.pkg, MusicCapability.appLabel(ctx, cur.pkg), cur.title, cur.artist, cur.album, cur.playedMs))
    }
}
