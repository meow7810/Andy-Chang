package com.andychang.clauderi.data

import com.andychang.clauderi.llm.Role
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Exact recall over the raw archive (memory M2). Long-term memory keeps a summary of facts; this
 * finds the actual words. It is a second entry point that bypasses every derived layer, so the
 * model can quote what was really said rather than what it remembers.
 *
 * Plain in-memory scan, no index: a year of daily use is a few tens of thousands of short lines,
 * which a substring pass handles in milliseconds. An FTS table becomes worth it only when the
 * archive no longer fits in memory, and the [ConversationStore] already loads it whole.
 *
 * Every hit carries its id and sha prefix so a quote can be checked against the archive.
 */
object HistorySearch {

    data class Hit(val message: ChatMessage, val score: Double)

    const val MAX_LIMIT = 20
    private const val SNIPPET = 240

    fun search(
        messages: List<ChatMessage>,
        query: String,
        limit: Int = 5,
        after: LocalDate? = null,
        before: LocalDate? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Hit> = matches(messages, query, after, before, zone).take(limit.coerceIn(1, MAX_LIMIT)).toList()

    /** Count without the limit, for the "共 N 則" line. */
    fun count(messages: List<ChatMessage>, query: String, after: LocalDate?, before: LocalDate?, zone: ZoneId = ZoneId.systemDefault()): Int =
        matches(messages, query, after, before, zone).count()

    private fun matches(
        messages: List<ChatMessage>, query: String, after: LocalDate?, before: LocalDate?, zone: ZoneId,
    ): Sequence<Hit> {
        val q = query.trim().lowercase()
        if (q.isEmpty() && after == null && before == null) return emptySequence()
        val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        val bigrams = cjkBigrams(q)
        // A person's "day" runs past midnight: what happened "on the 16th" includes 1 a.m. on the 17th.
        // So a day starts at 05:00 and the "before" day ends at 05:00 the next morning.
        val fromMs = after?.atStartOfDay(zone)?.plusHours(5)?.toInstant()?.toEpochMilli()
        val toMs = before?.plusDays(1)?.atStartOfDay(zone)?.plusHours(5)?.toInstant()?.toEpochMilli()

        return messages.asSequence()
            .filter { !it.hidden && it.text.isNotBlank() }
            .filter { fromMs == null || it.createdAt >= fromMs }
            .filter { toMs == null || it.createdAt < toMs }
            .mapNotNull { m ->
                val t = m.text.lowercase()
                val termHits = terms.count { t.contains(it) }
                val score = when {
                    // No keyword, only a date range: everything in it, in time order (score by time, oldest first).
                    q.isEmpty() -> 1.0 / (1.0 + m.createdAt / 1000.0)
                    t.contains(q) -> 2.0 + termHits
                    termHits > 0 -> termHits.toDouble()
                    bigrams.size >= 3 -> {
                        // A long CJK phrase the user half-remembers: accept if most of its bigrams appear.
                        val frac = bigrams.count { t.contains(it) }.toDouble() / bigrams.size
                        if (frac >= 0.5) frac else 0.0
                    }
                    else -> 0.0
                }
                if (score > 0) Hit(m, score) else null
            }
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.message.createdAt })
    }

    /** Tool-facing text: one block per hit with date, speaker, provenance marks and the neighbour turn. */
    fun render(messages: List<ChatMessage>, hits: List<Hit>, total: Int, zone: ZoneId = ZoneId.systemDefault(), withContext: Boolean = true): String {
        if (hits.isEmpty()) return "對話紀錄裡找不到。"
        val byId = messages.associateBy { it.id }
        val fmt = SimpleDateFormat("yyyy-MM-dd(E) HH:mm", Locale.TAIWAN).apply { timeZone = java.util.TimeZone.getTimeZone(zone) }
        val sb = StringBuilder(if (withContext) "共 $total 則相符，顯示 ${hits.size} 則（相符度高的在前，同分新的在前）：\n" else "那段時間共 $total 則，顯示前 ${hits.size} 則（按時間）：\n")
        for (h in hits) {
            val m = h.message
            sb.append("\n#").append(m.id)
            if (m.sha.isNotEmpty()) sb.append(" sha:").append(m.sha.take(8))
            sb.append("｜").append(fmt.format(Date(m.createdAt))).append("｜").append(speaker(m)).append("：")
            sb.append(clip(m.text)).append('\n')
            // The exchange around it, so a quote is not read out of context and a correction one line
            // later ("no, I meant X") is visible too.
            if (withContext) for (d in listOf(-1, 1, 2)) {
                val n = byId[m.id + d] ?: continue
                if (n.hidden || n.text.isBlank()) continue
                sb.append(if (d < 0) "    ↑ " else "    ↳ ").append(speaker(n)).append("：").append(clip(n.text, 120)).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    private fun speaker(m: ChatMessage): String {
        val who = when {
            m.role == Role.USER -> if (m.source == Source.VOICE) "使用者（語音）" else "使用者"
            m.brain.isNotEmpty() -> "助理（${m.brain}）"
            else -> "助理"
        }
        return when (m.statement) {
            StatementType.FICTION -> "$who（小說模式）"
            StatementType.EXTERNAL_REPORT -> "$who（轉述外部內容）"
            else -> who
        }
    }

    private fun clip(s: String, n: Int = SNIPPET): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= n) one else one.take(n) + "…"
    }

    private fun cjkBigrams(s: String): List<String> {
        val cjk = s.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return if (cjk.length < 2) emptyList() else (0 until cjk.length - 1).map { cjk.substring(it, it + 2) }.distinct()
    }

    fun parseDate(s: String?): LocalDate? = s?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
