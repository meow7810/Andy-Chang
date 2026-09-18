package com.andychang.clauderi.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What was playing while the assistant was around: one line per track, appended when the track
 * changes or playback stops. Same shape as the conversation file (JSON lines, append-only) so it
 * travels with the same export/backup story later.
 *
 * It only knows what it witnessed: recording starts the day the user turns it on, and only while
 * the notification-listener service is alive.
 */
class ListeningLog(context: Context) {

    data class Play(val at: Long, val pkg: String, val app: String, val title: String, val artist: String, val album: String, val ms: Long)

    private val file = File(context.filesDir, "listening.jsonl")
    private val lock = Any()
    private var cache: MutableList<Play>? = null

    fun all(): List<Play> = synchronized(lock) { load().toList() }

    /** Drop the in-memory copy after the file was replaced from a bundle. */
    fun reload() = synchronized(lock) { cache = null }

    fun append(p: Play) = synchronized(lock) {
        load().add(p)
        file.appendText(toJson(p).toString() + "\n")
    }

    /**
     * Plays matching [query] (title / artist / album substring, case-insensitive; blank = all)
     * within the last [days] days, newest first. Plays shorter than [minMs] are skipped so a
     * skipped-through track does not count as listened to.
     */
    fun search(query: String, days: Int, minMs: Long = 30_000): List<Play> {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val q = query.trim().lowercase()
        return all().asReversed().filter { p ->
            p.at >= since && p.ms >= minMs &&
                (q.isEmpty() || p.title.lowercase().contains(q) || p.artist.lowercase().contains(q) || p.album.lowercase().contains(q))
        }
    }

    /** Tool-facing summary: counts per track, then the most recent plays. */
    fun render(query: String, days: Int): String {
        val hits = search(query, days)
        if (hits.isEmpty()) return if (query.isBlank()) "最近 $days 天沒有聆聽紀錄。" else "最近 $days 天沒有聽過「$query」。"
        val fmt = SimpleDateFormat("MM/dd(E) HH:mm", Locale.TAIWAN)
        val byTrack = hits.groupBy { "${it.title}｜${it.artist}" }
            .entries.sortedByDescending { it.value.size }.take(10)
        val sb = StringBuilder("最近 $days 天共 ${hits.size} 次（30 秒以上才算）：\n")
        for ((k, v) in byTrack) sb.append("- ").append(k).append("：").append(v.size).append(" 次，共 ").append(v.sumOf { it.ms } / 60_000).append(" 分鐘\n")
        sb.append("最近幾次：\n")
        for (p in hits.take(5)) sb.append("- ").append(fmt.format(Date(p.at))).append(' ').append(p.title).append("（").append(p.app).append("）\n")
        return sb.toString().trimEnd()
    }

    private fun load(): MutableList<Play> {
        cache?.let { return it }
        val list = mutableListOf<Play>()
        if (file.exists()) file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching { JSONObject(line) }.getOrNull()?.let { o ->
                list += Play(
                    o.optLong("t"), o.optString("pkg"), o.optString("app"),
                    o.optString("title"), o.optString("artist"), o.optString("album"), o.optLong("ms"),
                )
            }
        }
        cache = list
        return list
    }

    private fun toJson(p: Play) = JSONObject()
        .put("t", p.at).put("pkg", p.pkg).put("app", p.app)
        .put("title", p.title).put("artist", p.artist).put("album", p.album).put("ms", p.ms)
}
