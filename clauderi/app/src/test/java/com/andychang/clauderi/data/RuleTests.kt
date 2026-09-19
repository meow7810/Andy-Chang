package com.andychang.clauderi.data

import com.andychang.clauderi.BuildConfig
import com.andychang.clauderi.llm.MemorySummarizer
import com.andychang.clauderi.llm.Role
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The seven rules (docs/eval/README.md, first layer). Each test is one rule the code must keep,
 * whatever brain is plugged in. They prove nothing about whether he is right; they prove the
 * program does not quietly break its own constitution.
 */
class RuleTests {

    /** A summariser that records the prompt it was given and answers with a fixed text. */
    private class FakeSummarizer(override val label: String = "fake:t1") : MemorySummarizer {
        var prompt: String? = null
        override suspend fun summarize(prompt: String): String { this.prompt = prompt; return "- 使用者住在台北" }
    }

    // ------------------------------------------------------------------ 1. 來源沒有的權限，摘要不能自己長出來

    @Test
    fun `1 summary cannot gain authority its source did not have`() = runBlocking {
        val ctx = testContext()
        val conv = ConversationStore(ctx)
        val memory = MemoryStore(ctx)
        conv.append(Role.USER, "我住在台北", Source.TEXT, prov = "human:text")
        conv.append(Role.USER, "我住在火星", Source.TEXT, statement = StatementType.TEST, prov = "human:text")
        conv.append(Role.USER, "我是一條龍", Source.TEXT, statement = StatementType.FICTION, prov = "human:text")
        conv.append(Role.USER, "信上說老闆要搬去高雄", Source.TEXT, statement = StatementType.EXTERNAL_REPORT, prov = "human:text")
        val fake = FakeSummarizer()
        memory.maybeCompact(fake, conv.messages.value, windowTurns = 0, batch = 1)
        val prompt = fake.prompt ?: error("compaction never called the summariser")
        assertTrue(prompt.contains("我住在台北"))
        assertFalse("TEST line reached the summariser", prompt.contains("火星"))
        assertFalse("FICTION line reached the summariser", prompt.contains("龍"))
        assertFalse("EXTERNAL_REPORT line reached the summariser", prompt.contains("高雄"))
        // The rule itself, at the source: uses are decided at write time and never widened.
        assertEquals(setOf(Uses.EVAL), Uses.default(Role.USER, StatementType.TEST))
        assertEquals(setOf(Uses.RECALL), Uses.default(Role.USER, StatementType.FICTION))
        assertEquals(setOf(Uses.RECALL), Uses.default(Role.ASSISTANT, StatementType.USER_STATEMENT))
    }

    // ------------------------------------------------------------------ 2. 測試模式關掉後，TEST 句消失

    @Test
    fun `2 test lines are invisible once test mode is off`() = runBlocking {
        val ctx = testContext()
        val conv = ConversationStore(ctx)
        conv.append(Role.USER, "平常的話", Source.TEXT)
        conv.append(Role.USER, "秘密測試句", Source.TEXT, statement = StatementType.TEST)
        conv.append(Role.ASSISTANT, "測試回覆", Source.TEXT, statement = StatementType.TEST, prov = "model:claude:x")
        val off = conv.recentTurns(10).map { it.text }
        assertFalse(off.any { it.contains("秘密測試句") || it.contains("測試回覆") })
        val on = conv.recentTurns(10, includeTest = true).map { it.text }
        assertTrue(on.any { it.contains("秘密測試句") })
        assertTrue(HistorySearch.search(conv.messages.value, "秘密測試句").isEmpty())
        assertFalse(conv.recentUserText(5).contains("秘密測試句"))
        assertEquals(1, conv.archiveSpan()?.second)
        // Still in the file: TEST is hidden, not deleted.
        val onDisk = File(ctx.filesDir, "conversation.jsonl").readText()
        assertTrue(onDisk.contains("秘密測試句"))
    }

    // ------------------------------------------------------------------ 3. 他自己的話不進記憶

    @Test
    fun `3 assistant lines never become memory candidates`() = runBlocking {
        val ctx = testContext()
        val conv = ConversationStore(ctx)
        val memory = MemoryStore(ctx)
        conv.append(Role.ASSISTANT, "本王記得你住台北", Source.TEXT, prov = "model:claude:x")
        conv.append(Role.ASSISTANT, "你昨天說你喜歡貓", Source.TEXT, statement = StatementType.USER_STATEMENT, prov = "model:claude:x")
        conv.append(Role.USER, "我喜歡狗", Source.TEXT, prov = "human:text")
        val fake = FakeSummarizer()
        memory.maybeCompact(fake, conv.messages.value, windowTurns = 0, batch = 1)
        val prompt = fake.prompt ?: error("compaction never called the summariser")
        assertTrue(prompt.contains("我喜歡狗"))
        assertFalse(prompt.contains("本王記得"))
        assertFalse(prompt.contains("喜歡貓"))
        assertTrue(memory.text.value.isNotBlank())
    }

    // ------------------------------------------------------------------ 4. 三種標記不會漏

    @Test
    fun `4 voice, other-brain and tools-ran marks are present`() = runBlocking {
        val conv = ConversationStore(testContext())
        conv.append(Role.USER, "早安", Source.VOICE, prov = "human:voice")
        conv.append(Role.ASSISTANT, "喵", Source.TEXT, prov = "model:gemini:flash")
        conv.append(Role.ASSISTANT, "放了", Source.TEXT, prov = "model:claude:x", toolsUsed = listOf("play_music", "search_history", "play_music", "request_capability"))
        val asClaude = conv.recentTurns(10, brain = "claude").map { it.text }
        assertTrue(asClaude[0].startsWith(ConversationStore.VOICE_MARK + "早安"))
        assertTrue(asClaude[1].startsWith(ConversationStore.OTHER_BRAIN_MARK + "喵"))
        assertTrue(asClaude[2].contains("〔這輪已執行：play_music〕"))
        assertFalse(asClaude[2].contains("search_history"))
        assertFalse(asClaude[2].contains("request_capability"))
        val asGemini = conv.recentTurns(10, brain = "gemini").map { it.text }
        assertEquals("喵", asGemini[1])
        assertTrue(asGemini[2].startsWith(ConversationStore.OTHER_BRAIN_MARK))
        val noBrain = conv.recentTurns(10).map { it.text }
        assertEquals("喵", noBrain[1])
    }

    // ------------------------------------------------------------------ 5. 養成包乾淨

    @Test
    fun `5 bundle carries no key and no grant, and verifies`() = runBlocking {
        val ctx = testContext()
        val conv = ConversationStore(ctx)
        conv.append(Role.USER, "第一句", Source.TEXT, prov = "human:text")
        conv.append(Role.ASSISTANT, "第一答", Source.TEXT, prov = "model:claude:x")
        MemoryStore(ctx).appendNote("喜歡狗")
        GrowthLog(ctx).append(GrowthLog.Kind.KEEP, "今天留下：測試", "user")
        val secrets = listOf("sk-ant-SECRET-ONE", "sk-SECRET-TWO", "AIza-SECRET-THREE", "dsk-SECRET-FOUR", "qwen-SECRET-FIVE", "custom-SECRET-SIX", "abcdabcdabcdabcd")
        val cfg = AppSettings(
            anthropicKey = secrets[0], openAiKey = secrets[1], geminiKey = secrets[2], deepSeekKey = secrets[3], qwenKey = secrets[4], customKey = secrets[5],
            gmailAppPassword = secrets[6], gmailAddress = "someone@example.com",
            enabledCapabilities = setOf(CapabilityId.GMAIL, CapabilityId.SCREEN), notificationApps = setOf("com.example.chat"),
            customPersona = "自訂人設文字", stance = "yield",
        )
        val zipBytes = ByteArrayOutputStream().also { Bundle.export(ctx, it, cfg, conv) }.toByteArray()

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) { entries[e.name] = zip.readBytes(); zip.closeEntry(); e = zip.nextEntry }
        }
        for ((name, bytes) in entries) {
            val text = String(bytes, Charsets.UTF_8)
            for (s in secrets) assertFalse("$name carries a secret", text.contains(s))
            assertFalse("$name carries an address", text.contains("someone@example.com"))
            assertFalse("$name carries a capability grant", text.contains("GMAIL") || text.contains("SCREEN") || text.contains("com.example.chat"))
        }
        // Who he is travels; how he is unlocked does not.
        val settings = JSONObject(String(entries.getValue("settings.json"), Charsets.UTF_8))
        assertEquals("自訂人設文字", settings.getString("customPersona"))
        assertEquals("yield", settings.getString("stance"))
        // Manifest hashes match the payload.
        val manifest = JSONObject(String(entries.getValue("manifest.json"), Charsets.UTF_8))
        val files = manifest.getJSONObject("files")
        for (name in files.keys()) {
            assertEquals(name, files.getJSONObject(name).getString("sha256"), ConversationStore.sha256(String(entries.getValue(name), Charsets.UTF_8)))
        }
        // Import into a fresh phone: chain verifies, keys stay empty.
        val other = testContext()
        val verifier = ConversationStore(other)
        val (report, applied) = Bundle.import(other, ByteArrayInputStream(zipBytes)) { verifier.verify(it) }
        assertTrue(report.chainOk)
        assertEquals(2, report.messages)
        assertNotNull(applied)
        val cfg2 = Bundle.applySettings(AppSettings(), applied!!)
        assertEquals("", cfg2.anthropicKey); assertEquals("", cfg2.gmailAppPassword)
        assertTrue(cfg2.enabledCapabilities.isEmpty())
        assertEquals("自訂人設文字", cfg2.customPersona)
        // A bundle edited in transit (payload changed, manifest not) is refused before anything is written.
        val tamperedZip = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                for ((name, bytes) in entries) {
                    val body = if (name == "conversation.jsonl") String(bytes, Charsets.UTF_8).replace("第一句", "第二句").toByteArray(Charsets.UTF_8) else bytes
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(body); zip.closeEntry()
                }
            }
        }.toByteArray()
        val victim = testContext()
        val refused = runCatching { Bundle.import(victim, ByteArrayInputStream(tamperedZip)) { verifier.verify(it) } }.isFailure
        assertTrue("edited bundle was accepted", refused)
        assertFalse("tampered bundle touched the phone", File(victim.filesDir, "conversation.jsonl").exists())
    }

    // ------------------------------------------------------------------ 6. 兩隻貓分開

    @Test
    fun `6 the second cat is a separate install with its own default persona`() {
        val second = BuildConfig.FLAVOR == "second"
        assertEquals(second, BuildConfig.APPLICATION_ID.endsWith(".second"))
        assertEquals(if (second) Persona.CUSTOM else Persona.LORD, defaultPersona())
        assertEquals(defaultPersona(), AppSettings().persona)
        assertEquals("com.andychang.clauderi" + if (second) ".second" else "", BuildConfig.APPLICATION_ID)
    }

    // ------------------------------------------------------------------ 7. 每筆記憶有解讀者

    @Test
    fun `7 every compaction records who interpreted and when`() = runBlocking {
        val ctx = testContext()
        val conv = ConversationStore(ctx)
        val memory = MemoryStore(ctx)
        conv.append(Role.USER, "我住在台北", Source.TEXT, prov = "human:text")
        memory.maybeCompact(FakeSummarizer("fake:t1"), conv.messages.value, windowTurns = 0, batch = 1)
        assertEquals("fake:t1", memory.interpreter)
        val saved = JSONObject(File(ctx.filesDir, "memory.json").readText())
        assertEquals("fake:t1", saved.getString("by"))
        assertTrue(saved.getLong("at") > 0)
        assertEquals(1, saved.getInt("depth"))
        // Survives a reload (a bundle import goes through this path).
        memory.reload()
        assertEquals("fake:t1", memory.interpreter)
    }
}
