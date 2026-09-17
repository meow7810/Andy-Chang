package com.andychang.clauderi.data

import android.content.Context
import com.andychang.clauderi.llm.Usage
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 貓糧帳本. One line per paid call: what it was for, which backend and model, the token counts
 * the provider reported, and an *estimate* in USD from a built-in price table. The estimate is
 * not a bill: prices drift, some models are unknown (usd = null), and the provider's invoice is
 * the only truth. What this buys is a monthly total the user can see and a cap the app can stop
 * at, including the calls that are otherwise invisible (photo events, memory compaction, STT).
 *
 * JSON lines, append-only, same shape as the other files. Never contains a key.
 */
class UsageLedger(context: Context) {

    data class Entry(
        val at: Long, val purpose: String, val backend: String, val model: String,
        val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long,
        val audioSec: Double, val usd: Double?,
    )

    private val file = File(context.filesDir, "usage.jsonl")
    private val lock = Any()
    private var cache: MutableList<Entry>? = null

    /** Record a model call. [batch] halves the price (Batch API). */
    fun record(purpose: String, backend: String, model: String, usage: Usage, batch: Boolean = false) {
        val usd = estimateUsd(model, usage)?.let { if (batch) it / 2 else it }
        append(Entry(System.currentTimeMillis(), purpose, backend, model, usage.input, usage.output, usage.cacheRead, usage.cacheWrite, 0.0, usd))
    }

    /** Record a speech-to-text call, billed per minute of audio. */
    fun recordAudio(purpose: String, backend: String, model: String, audioSec: Double) {
        val perMin = STT_PER_MIN[model]
        append(Entry(System.currentTimeMillis(), purpose, backend, model, 0, 0, 0, 0, audioSec, perMin?.let { it * audioSec / 60.0 }))
    }

    fun all(): List<Entry> = synchronized(lock) { load().toList() }

    /** Entries since the first of this month (local time). */
    fun thisMonth(): List<Entry> {
        val start = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return all().filter { it.at >= start }
    }

    /** Estimated USD this month over entries with a known price; [unknownCalls] counts the rest. */
    data class MonthSummary(val usd: Double, val calls: Int, val unknownCalls: Int, val byBackend: Map<String, Double>, val byPurpose: Map<String, Double>)

    fun monthSummary(): MonthSummary {
        val m = thisMonth()
        val known = m.filter { it.usd != null }
        return MonthSummary(
            usd = known.sumOf { it.usd!! },
            calls = m.size,
            unknownCalls = m.size - known.size,
            byBackend = known.groupBy { it.backend }.mapValues { (_, v) -> v.sumOf { it.usd!! } },
            byPurpose = known.groupBy { it.purpose }.mapValues { (_, v) -> v.sumOf { it.usd!! } },
        )
    }

    /** True when the month's estimate has reached the user's cap. Cap 0 = no cap. */
    fun overCap(cfg: AppSettings): Boolean {
        if (cfg.monthlyCapTwd <= 0) return false
        return monthSummary().usd * cfg.twdPerUsd >= cfg.monthlyCapTwd
    }

    private fun append(e: Entry) = synchronized(lock) {
        load().add(e)
        file.appendText(toJson(e).toString() + "\n")
    }

    private fun load(): MutableList<Entry> {
        cache?.let { return it }
        val list = mutableListOf<Entry>()
        if (file.exists()) file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching { JSONObject(line) }.getOrNull()?.let { o ->
                list += Entry(
                    o.optLong("t"), o.optString("p"), o.optString("b"), o.optString("m"),
                    o.optLong("in"), o.optLong("out"), o.optLong("cr"), o.optLong("cw"),
                    o.optDouble("sec", 0.0), if (o.has("usd") && !o.isNull("usd")) o.getDouble("usd") else null,
                )
            }
        }
        cache = list
        return list
    }

    private fun toJson(e: Entry) = JSONObject()
        .put("t", e.at).put("p", e.purpose).put("b", e.backend).put("m", e.model)
        .put("in", e.input).put("out", e.output).put("cr", e.cacheRead).put("cw", e.cacheWrite)
        .put("sec", e.audioSec).put("usd", e.usd ?: JSONObject.NULL)

    companion object {
        /**
         * USD per million tokens (input, output). Longest matching prefix wins, so a dated model
         * id still finds its family. Cache reads are billed at 10% of input, cache writes at 125%.
         * Non-Anthropic rows are from the vendors' public lists at the time of writing and are
         * the most likely to drift; an unknown model records tokens with usd = null.
         */
        private val PRICES: List<Pair<String, Pair<Double, Double>>> = listOf(
            "claude-fable-5" to (10.0 to 50.0),
            "claude-opus-5" to (5.0 to 25.0),
            "claude-opus-4" to (5.0 to 25.0),
            "claude-sonnet-5" to (2.0 to 10.0),
            "claude-sonnet-4-6" to (3.0 to 15.0),
            "claude-sonnet-4" to (3.0 to 15.0),
            "claude-haiku-4-5" to (1.0 to 5.0),
            "gpt-4o-mini" to (0.15 to 0.60),
            "gpt-4o" to (2.50 to 10.0),
            "gpt-4.1-mini" to (0.40 to 1.60),
            "gpt-4.1" to (2.0 to 8.0),
            "gemini-2.5-flash-lite" to (0.10 to 0.40),
            "gemini-2.5-flash" to (0.30 to 2.50),
            "gemini-2.5-pro" to (1.25 to 10.0),
            "deepseek-chat" to (0.27 to 1.10),
            "deepseek-reasoner" to (0.55 to 2.19),
        ).sortedByDescending { it.first.length }

        private val STT_PER_MIN = mapOf("gpt-4o-transcribe" to 0.006, "gpt-4o-mini-transcribe" to 0.003, "whisper-1" to 0.006)

        fun estimateUsd(model: String, u: Usage): Double? {
            val (inP, outP) = PRICES.firstOrNull { model.startsWith(it.first) }?.second ?: return null
            // [Usage.input] is the uncached part; cache reads and writes are reported separately.
            return (u.input * inP + u.cacheRead * inP * 0.1 + u.cacheWrite * inP * 1.25 + u.output * outP) / 1_000_000.0
        }
    }
}
