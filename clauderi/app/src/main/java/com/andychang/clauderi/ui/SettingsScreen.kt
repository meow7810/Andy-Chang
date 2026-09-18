package com.andychang.clauderi.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.andychang.clauderi.ClaudeRiApp
import com.andychang.clauderi.audio.Speaker
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.GrowthLog
import com.andychang.clauderi.data.LlmBackend
import com.andychang.clauderi.data.Persona
import com.andychang.clauderi.data.SttBackend
import kotlinx.coroutines.launch

/** API keys, model choice, memory depth, speech options. Capability switches live on their own screen. */
@Composable
fun SettingsScreen(app: ClaudeRiApp, modifier: Modifier = Modifier) {
    val saved by app.settings.flow.collectAsState(initial = AppSettings())
    var draft by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) { draft = saved }
    val scope = rememberCoroutineScope()
    var savedHint by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var archiveHint by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { context.contentResolver.openOutputStream(uri)!!.use { app.conversation.exportTo(it) } }
                .onSuccess { archiveHint = "已匯出 ${app.conversation.messages.value.size} 則" }
                .onFailure { archiveHint = "匯出失敗：${it.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { context.contentResolver.openInputStream(uri)!!.use { app.conversation.importFrom(it) } }
                .onSuccess { r ->
                    archiveHint = "已還原 ${r.messages} 則、${r.tombstones} 個墓碑，格式 ${r.schema}，" +
                        if (r.chainOk) "雜湊鏈完整" else "注意：雜湊鏈不完整（檔案可能被改過）"
                }
                .onFailure { archiveHint = "還原失敗：${it.message}" }
        }
    }

    @Composable
    fun Secret(value: String, label: String, onChange: (String) -> Unit) = OutlinedTextField(
        value, onChange, label = { Text(label) }, visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(), singleLine = true,
    )

    @Composable
    fun Plain(value: String, label: String, onChange: (String) -> Unit) = OutlinedTextField(
        value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
    )

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI 模型", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LlmBackend.entries.forEach { b ->
                FilterChip(selected = draft.llm == b, onClick = { draft = draft.copy(llm = b) }, label = { Text(b.label) })
            }
        }
        when (draft.llm) {
            LlmBackend.CLAUDE -> {
                Secret(draft.anthropicKey, "Anthropic API key") { draft = draft.copy(anthropicKey = it) }
                Plain(draft.claudeModel, "Claude model（預設 claude-sonnet-5；想要最強改 claude-opus-5）") { draft = draft.copy(claudeModel = it) }
            }
            LlmBackend.GEMINI -> {
                Text(
                    "AI Studio 的 API key。Google AI Pro 的每月 US\$10 開發者抵用額要先在 Developer Program 兌領並綁 Cloud 帳單帳戶，key 的專案掛在那個帳戶上才會扣到抵用額。",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                Secret(draft.geminiKey, "Gemini API key") { draft = draft.copy(geminiKey = it) }
                Plain(draft.geminiModel, "Gemini model（gemini-3.6-flash 便宜快；gemini-3.6-pro 較強。Google 會下架舊模型，404 說哪個就改哪個）") { draft = draft.copy(geminiModel = it) }
            }
            LlmBackend.OPENAI -> Plain(draft.openAiChatModel, "OpenAI chat model") { draft = draft.copy(openAiChatModel = it) }
            LlmBackend.DEEPSEEK -> {
                Secret(draft.deepSeekKey, "DeepSeek API key") { draft = draft.copy(deepSeekKey = it) }
                Plain(draft.deepSeekModel, "DeepSeek model（deepseek-chat / deepseek-reasoner）") { draft = draft.copy(deepSeekModel = it) }
            }
            LlmBackend.QWEN -> {
                Secret(draft.qwenKey, "Qwen (DashScope) API key") { draft = draft.copy(qwenKey = it) }
                Plain(draft.qwenModel, "Qwen model（qwen-plus / qwen-max）") { draft = draft.copy(qwenModel = it) }
            }
            LlmBackend.CUSTOM -> {
                Text(
                    "任何 OpenAI 相容端點。例：Gemini https://generativelanguage.googleapis.com/v1beta/openai、" +
                        "Groq https://api.groq.com/openai/v1、OpenRouter https://openrouter.ai/api/v1",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                Plain(draft.customBaseUrl, "Base URL（到 /v1 為止，不含 /chat/completions）") { draft = draft.copy(customBaseUrl = it) }
                Secret(draft.customKey, "API key") { draft = draft.copy(customKey = it) }
                Plain(draft.customModel, "模型名稱（照供應商文件填）") { draft = draft.copy(customModel = it) }
            }
        }

        HorizontalDivider()
        Text("貓糧", style = MaterialTheme.typography.titleMedium)
        run {
            val summary = remember(savedHint) { app.usage.monthSummary() }
            val rate = draft.twdPerUsd
            Text(
                "本月估算：NT\$${"%.0f".format(summary.usd * rate)}（US\$${"%.2f".format(summary.usd)}），${summary.calls} 次呼叫" +
                    (if (summary.unknownCalls > 0) "，其中 ${summary.unknownCalls} 次模型價格未知未計入" else ""),
            )
            if (summary.byPurpose.isNotEmpty()) {
                Text(
                    summary.byPurpose.entries.sortedByDescending { it.value }.joinToString("　") { (k, v) ->
                        val label = when (k) { "chat" -> "對話"; "photo" -> "看照片"; "memory" -> "整理記憶"; "stt" -> "語音辨識"; else -> k }
                        "$label NT\$${"%.0f".format(v * rate)}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
            Text("這是 app 按供應商回報的 token 數和內建價目表估的，不是帳單。真正的金額以供應商的帳單為準。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            // Local text state so half-typed numbers ("32.") do not snap back while editing.
            var capText by remember { mutableStateOf(if (draft.monthlyCapTwd == 0) "" else draft.monthlyCapTwd.toString()) }
            var rateText by remember { mutableStateOf(draft.twdPerUsd.toString()) }
            Plain(capText, "每月上限（NT\$，空白＝不設）") { v -> capText = v; draft = draft.copy(monthlyCapTwd = v.trim().toIntOrNull() ?: 0) }
            Plain(rateText, "匯率（1 美元 = 幾元台幣，只影響顯示）") { v -> rateText = v; v.trim().toFloatOrNull()?.let { draft = draft.copy(twdPerUsd = it) } }
            Text("到上限後不再發出任何付費呼叫：對話、看照片、整理記憶都停，重開 app 也一樣，直到你調高或下個月。已送出的呼叫不會追回。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }

        HorizontalDivider()
        Text("語音辨識", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SttBackend.entries.forEach { b ->
                FilterChip(selected = draft.stt == b, onClick = { draft = draft.copy(stt = b) }, label = { Text(b.label) })
            }
        }
        if (draft.stt == SttBackend.OPENAI) {
            Plain(draft.sttModel, "辨識模型（預設 gpt-4o-transcribe）") { draft = draft.copy(sttModel = it) }
        } else {
            Text("用手機自己的辨識引擎（Pixel、多數旗艦可離線），不花 API 額度。中英夾雜準確度略低。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Plain(draft.languageHint, "語言提示（留空 = 自動偵測中英文；只講中文可填 zh）") { draft = draft.copy(languageHint = it) }
        Secret(draft.openAiKey, "OpenAI API key（OpenAI 語音辨識或 OpenAI 模型時需要）") { draft = draft.copy(openAiKey = it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("聽寫鍵盤模式")
                Text("用 Typeless 之類的語音鍵盤打進輸入框，停 2 秒自動送出", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(checked = draft.autoSendTypedInput, onCheckedChange = { draft = draft.copy(autoSendTypedInput = it) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("長按 Home 召喚時直接開鍵盤")
                Text("給 Typeless 這類語音鍵盤用：召喚後游標進輸入框、鍵盤彈出，對鍵盤講話。關閉則用上面選的辨識引擎錄音", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(checked = draft.assistOpensKeyboard, onCheckedChange = { draft = draft.copy(assistOpensKeyboard = it) })
        }

        HorizontalDivider()
        Text("Gmail（開啟「Gmail 信箱」能力時使用）", style = MaterialTheme.typography.titleMedium)
        Text(
            "Google 帳號 → 安全性 → 兩步驟驗證 → 應用程式密碼，新增一組貼在這裡。唯讀，可隨時在 Google 那邊撤銷。",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
        Plain(draft.gmailAddress, "Gmail 帳號") { draft = draft.copy(gmailAddress = it) }
        Secret(draft.gmailAppPassword, "應用程式密碼（16 碼）") { draft = draft.copy(gmailAppPassword = it) }

        HorizontalDivider()
        Text("記憶與朗讀", style = MaterialTheme.typography.titleMedium)
        Text("對話全部存在手機本地，沒有上限；這裡只決定每次送多少則給模型。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        OutlinedTextField(
            draft.historyTurns.toString(),
            { draft = draft.copy(historyTurns = it.toIntOrNull()?.coerceIn(2, 400) ?: draft.historyTurns) },
            label = { Text("每次送給模型的歷史則數") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("長期記憶")
                Text("超出上面則數的舊對話會在背景壓縮成「關於你的事」，永久保留；也可對它說「記住…」", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(checked = draft.longTermMemory, onCheckedChange = { draft = draft.copy(longTermMemory = it) })
        }
        if (draft.longTermMemory) {
            Text("整理記憶用哪個腦", style = MaterialTheme.typography.labelLarge)
            Text("整理是記帳不是聊天，不需要人設，可以交給便宜的後端（例如 Gemini 的抵用額）。失敗會下次再試，你感覺不到。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(selected = draft.memoryBackend.isBlank(), onClick = { draft = draft.copy(memoryBackend = "") }, label = { Text("跟主模型一樣") })
                LlmBackend.entries.forEach { b ->
                    FilterChip(selected = draft.memoryBackend == b.name, onClick = { draft = draft.copy(memoryBackend = b.name) }, label = { Text(b.label) })
                }
            }
            if (draft.memoryBackendOrMain() == LlmBackend.CLAUDE) {
                Plain(draft.memoryModel, "整理記憶用的模型（預設 claude-haiku-4-5，便宜五倍）") { draft = draft.copy(memoryModel = it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("整理記憶走 Batch API")
                        Text("半價；結果幾分鐘到最多 24 小時後、下次對話時套用", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Switch(checked = draft.memoryUseBatch, onCheckedChange = { draft = draft.copy(memoryUseBatch = it) })
                }
            }
            val memoryText by app.memory.text.collectAsState()
            var editing by remember { mutableStateOf(false) }
            var memoryDraft by remember(memoryText) { mutableStateOf(memoryText) }
            if (editing) {
                OutlinedTextField(memoryDraft, { memoryDraft = it }, label = { Text("長期記憶（可直接編輯）") }, modifier = Modifier.fillMaxWidth(), minLines = 6)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { app.memory.replace(memoryDraft); editing = false } }) { Text("儲存記憶") }
                    OutlinedButton(onClick = { editing = false }) { Text("取消") }
                }
            } else {
                Text(
                    memoryText.ifBlank { "（目前是空的）" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = true }) { Text("編輯記憶") }
                    OutlinedButton(onClick = { scope.launch { app.memory.clear() } }) { Text("清除長期記憶") }
                }
            }
        }
        HorizontalDivider()
        Text("養成紀錄", style = MaterialTheme.typography.titleMedium)
        Text("你親手留下的話，比長期記憶優先，不經過任何模型。每天一句「今天留下什麼」就夠；跳過也沒關係。下面也看得到他寫給自己的自述（你改不了）。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        val growthEntries by app.growth.entries.collectAsState()
        var keepDraft by remember { mutableStateOf("") }
        OutlinedTextField(keepDraft, { keepDraft = it }, label = { Text("今天留下什麼") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = keepDraft.isNotBlank(), onClick = { app.growth.append(GrowthLog.Kind.KEEP, keepDraft, "user"); keepDraft = "" }) { Text("留下") }
            OutlinedButton(enabled = keepDraft.isNotBlank(), onClick = { app.growth.append(GrowthLog.Kind.RULE, keepDraft, "user"); keepDraft = "" }) { Text("當成規則") }
        }
        growthEntries.takeLast(12).asReversed().forEach { e ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (e.kind == GrowthLog.Kind.SELF) "🐈 他寫的：" else "") + e.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (e.status == GrowthLog.Status.REVOKED) Color.Gray else Color.Unspecified,
                    )
                    Text(
                        java.text.SimpleDateFormat("MM/dd", java.util.Locale.TAIWAN).format(java.util.Date(e.at)) + "  " + e.kind.name.lowercase() + "  " +
                            when (e.status) { GrowthLog.Status.ACCEPTED -> "有效"; GrowthLog.Status.PROPOSED -> "待確認"; GrowthLog.Status.REVOKED -> "已撤回" } +
                            if (e.by != "user") "  " + e.by.removePrefix("model:") else "",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                    )
                }
                if (e.kind != GrowthLog.Kind.SELF) when (e.status) {
                    GrowthLog.Status.ACCEPTED -> OutlinedButton(onClick = { app.growth.setStatus(e.id, GrowthLog.Status.REVOKED) }) { Text("撤回") }
                    GrowthLog.Status.PROPOSED -> Button(onClick = { app.growth.setStatus(e.id, GrowthLog.Status.ACCEPTED) }) { Text("確認") }
                    GrowthLog.Status.REVOKED -> OutlinedButton(onClick = { app.growth.setStatus(e.id, GrowthLog.Status.ACCEPTED) }) { Text("恢復") }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("小說模式")
                Text("開著的時候，你和它說的話都標成「小說」：不會變成關於你的事實，也不會進長期記憶。貼故事、寫角色台詞時開。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(checked = draft.fictionMode, onCheckedChange = { draft = draft.copy(fictionMode = it) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("測試模式")
                Text("開著的時候說的話都標成「測試」：留在檔案裡，但他之後看不到（不進視窗、不進搜尋、不進記憶）。做回憶測試時開，測完關，測試題就不會污染下一次。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(checked = draft.testMode, onCheckedChange = { draft = draft.copy(testMode = it) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("他可以不順著你")
                Text("開著：意見不同時他有理由就守住立場，不因為你不高興就改口。關掉：說一次他的看法，然後照你的。這是個性設定，改動會留在養成紀錄裡。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(checked = draft.stance == "hold", onCheckedChange = { draft = draft.copy(stance = if (it) "hold" else "yield") })
        }
        Text("對話檔案", style = MaterialTheme.typography.titleSmall)
        Text("對話是唯一的原始紀錄，其他都是從它算出來的。匯出的檔案就是原始格式（JSON lines，含雜湊鏈），可以在另一支手機還原。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("lordclaude-conversation.jsonl") }) { Text("匯出對話") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("還原對話") }
        }
        if (archiveHint.isNotBlank()) Text(archiveHint, style = MaterialTheme.typography.bodySmall, color = Color(0xFF81C784))
        OutlinedTextField(
            draft.maxReplyTokens.toString(),
            { draft = draft.copy(maxReplyTokens = it.toIntOrNull()?.coerceIn(256, 8192) ?: draft.maxReplyTokens) },
            label = { Text("回覆長度上限（token，2048 約 1200 中文字，最多 8192）") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("語音提問的回覆用語音朗讀")
            Switch(checked = draft.speakVoiceReplies, onCheckedChange = { draft = draft.copy(speakVoiceReplies = it) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("打字提問的回覆也朗讀")
            Switch(checked = draft.speakTextReplies, onCheckedChange = { draft = draft.copy(speakTextReplies = it) })
        }
        Text("氣場", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Persona.entries.forEach { p ->
                FilterChip(selected = draft.persona == p, onClick = { draft = draft.copy(persona = p) }, label = { Text(p.label) })
            }
        }
        Text("聲音", style = MaterialTheme.typography.titleSmall)
        val voices = remember { app.assistant.speaker.voices() }
        if (voices.isEmpty()) {
            Text("手機沒有可用的離線中文/英文語音。設定 → 系統 → 文字轉語音 下載語音包後再回來。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = draft.ttsVoice.isBlank(), onClick = { draft = draft.copy(ttsVoice = "") }, label = { Text("系統預設") })
                    IconButton(onClick = { scope.launch { app.assistant.speaker.preview("", draft.ttsPitch, draft.ttsRate, Speaker.DEMO_ZH) } }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "試聽")
                    }
                }
                voices.forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = draft.ttsVoice == v.name, onClick = { draft = draft.copy(ttsVoice = v.name) },
                            label = { Text("${v.label}  ${v.name.substringAfterLast('-').take(24)}") },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            scope.launch {
                                app.assistant.speaker.preview(v.name, draft.ttsPitch, draft.ttsRate, if (v.language == "en") Speaker.DEMO_EN else Speaker.DEMO_ZH)
                            }
                        }) { Icon(Icons.Filled.PlayArrow, contentDescription = "試聽") }
                    }
                }
            }
        }
        Text("音高 ${"%.2f".format(draft.ttsPitch)}（低 = 魔王）", style = MaterialTheme.typography.bodySmall)
        Slider(value = draft.ttsPitch, onValueChange = { draft = draft.copy(ttsPitch = it) }, valueRange = 0.5f..1.5f)
        Text("語速 ${"%.2f".format(draft.ttsRate)}", style = MaterialTheme.typography.bodySmall)
        Slider(value = draft.ttsRate, onValueChange = { draft = draft.copy(ttsRate = it) }, valueRange = 0.6f..1.6f)
        OutlinedButton(onClick = {
            scope.launch { app.assistant.speaker.preview(draft.ttsVoice, draft.ttsPitch, draft.ttsRate, Speaker.DEMO_ZH) }
        }) { Text("用目前設定試聽") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("長按 Home 召喚時先說「何事需要驚動本王？」")
                Text("只在克勞德大人模式有效；會多花約一秒再開始聆聽", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(checked = draft.wakeGreeting, onCheckedChange = { draft = draft.copy(wakeGreeting = it) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("允許 AI 在對話中請求能力")
                Text("模型會知道有哪些能力可以請求；開關仍由你在卡片上按允許才打開", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(checked = draft.allowAiCapabilityRequests, onCheckedChange = { draft = draft.copy(allowAiCapabilityRequests = it) })
        }
        OutlinedTextField(
            draft.customInstructions, { draft = draft.copy(customInstructions = it) },
            label = { Text("額外指示（例如：我是交易員，回答用台灣用語）") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                // A personality-policy change is part of his upbringing, not just a preference: it goes in the record with a date.
                if (draft.stance != saved.stance) app.growth.append(GrowthLog.Kind.SETTING, "他可以不順著你：" + if (draft.stance == "hold") "開（守住立場）" else "關（順著使用者）", "user")
                if (draft.persona != saved.persona) app.growth.append(GrowthLog.Kind.SETTING, "人設改為 " + draft.persona.label, "user")
                scope.launch { app.settings.update { draft }; savedHint = true }
            }) { Text("儲存") }
            OutlinedButton(onClick = { scope.launch { app.conversation.clear() } }) { Text("清除對話記憶") }
        }
        if (savedHint) Text("已儲存", color = Color(0xFF81C784))
    }
}
