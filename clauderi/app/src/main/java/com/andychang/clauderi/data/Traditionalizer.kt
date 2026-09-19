package com.andychang.clauderi.data

import android.content.Context
import android.util.Log

/**
 * Simplified → Taiwan Traditional, applied to every reply before it is stored or read aloud.
 *
 * The prompt already forbids simplified characters, but the model drifts, and once a simplified
 * line is in the archive it is quoted back (search_history) and summarised (memory) as is. A
 * deterministic pass after the model is the only thing that actually holds.
 *
 * Dictionaries are OpenCC's (Apache 2.0): phrase table first, then single characters, then Taiwan
 * variants (着→著, 裏→裡). Greedy longest match. Loaded once, lazily, off the main thread.
 *
 * Many characters are both valid traditional and simplified (后 in 皇后, 里 in 公里, 准 in 批准);
 * converting those blindly would damage text that was already traditional. So the text is cut at
 * punctuation and only segments containing an *unambiguously* simplified character (one that never
 * occurs on the traditional side of the tables, like 这 or 说) are converted at all.
 */
object Traditionalizer {

    private const val TAG = "Traditionalizer"
    @Volatile private var st: Table? = null
    @Volatile private var tw: Table? = null

    private class Table(val map: HashMap<String, String>, val maxKey: Int, val marker: Set<Char> = emptySet())

    private val segmentEnd = Regex("(?<=[。！？!?\\n，,、；;：:])")

    fun preload(context: Context) {
        if (st != null) return
        synchronized(this) {
            if (st != null) return
            st = runCatching { load(context, "opencc/STPhrases.txt", "opencc/STCharacters.txt") }
                .onFailure { Log.e(TAG, "load failed", it) }.getOrNull()
            tw = runCatching { load(context, "opencc/TWVariants.txt") }.getOrNull()
        }
    }

    fun convert(context: Context, text: String): String {
        preload(context)
        val a = st ?: return text
        if (text.none { it in a.marker }) return text
        return segmentEnd.split(text).joinToString("") { seg ->
            if (seg.none { it in a.marker }) seg
            else { val once = apply(a, seg); tw?.let { apply(it, once) } ?: once }
        }
    }

    private fun apply(t: Table, s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            var matched = false
            val end = minOf(s.length, i + t.maxKey)
            var j = end
            while (j > i) {
                val v = t.map[s.substring(i, j)]
                if (v != null) { sb.append(v); i = j; matched = true; break }
                j--
            }
            if (!matched) { sb.append(s[i]); i++ }
        }
        return sb.toString()
    }

    private fun load(context: Context, vararg files: String): Table {
        val map = HashMap<String, String>(64_000)
        val traditionalSide = HashSet<Char>(16_000)
        val singleKeys = HashSet<Char>(4_000)
        var maxKey = 1
        for (f in files) context.assets.open(f).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val key = line.substring(0, tab)
                val values = line.substring(tab + 1)
                val value = values.substringBefore(' ')
                for (c in values) if (c != ' ') traditionalSide += c
                if (key == value) continue
                map.putIfAbsent(key, value)
                if (key.length == 1) singleKeys += key[0]
                if (key.length > maxKey) maxKey = key.length
            }
        }
        return Table(map, maxKey, marker = singleKeys.filterTo(HashSet()) { it !in traditionalSide })
    }
}
