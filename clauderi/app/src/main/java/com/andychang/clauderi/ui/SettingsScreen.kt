package com.andychang.clauderi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.andychang.clauderi.ClaudeRiApp
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.LlmBackend
import kotlinx.coroutines.launch

/** API keys, model choice, memory depth, speech options. Capability switches live on their own screen. */
@Composable
fun SettingsScreen(app: ClaudeRiApp, modifier: Modifier = Modifier) {
    val saved by app.settings.flow.collectAsState(initial = AppSettings())
    var draft by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) { draft = saved }
    val scope = rememberCoroutineScope()
    var savedHint by remember { mutableStateOf(false) }

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
                Plain(draft.claudeModel, "Claude model（預設 claude-opus-5）") { draft = draft.copy(claudeModel = it) }
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
        }

        HorizontalDivider()
        Text("語音辨識（OpenAI）", style = MaterialTheme.typography.titleMedium)
        Secret(draft.openAiKey, "OpenAI API key（語音辨識用；選 OpenAI 模型時也用這把）") { draft = draft.copy(openAiKey = it) }
        Plain(draft.sttModel, "辨識模型（預設 gpt-4o-transcribe）") { draft = draft.copy(sttModel = it) }
        Plain(draft.languageHint, "語言提示（留空 = 自動偵測中英文）") { draft = draft.copy(languageHint = it) }

        HorizontalDivider()
        Text("記憶與朗讀", style = MaterialTheme.typography.titleMedium)
        Text("對話全部存在手機本地，沒有上限；這裡只決定每次送多少則給模型。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        OutlinedTextField(
            draft.historyTurns.toString(),
            { draft = draft.copy(historyTurns = it.toIntOrNull()?.coerceIn(2, 400) ?: draft.historyTurns) },
            label = { Text("每次送給模型的歷史則數") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("語音提問的回覆用語音朗讀")
            Switch(checked = draft.speakVoiceReplies, onCheckedChange = { draft = draft.copy(speakVoiceReplies = it) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("打字提問的回覆也朗讀")
            Switch(checked = draft.speakTextReplies, onCheckedChange = { draft = draft.copy(speakTextReplies = it) })
        }
        OutlinedTextField(
            draft.customInstructions, { draft = draft.copy(customInstructions = it) },
            label = { Text("額外指示（例如：我是交易員，回答用台灣用語）") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { app.settings.update { draft }; savedHint = true } }) { Text("儲存") }
            OutlinedButton(onClick = { scope.launch { app.conversation.clear() } }) { Text("清除對話記憶") }
        }
        if (savedHint) Text("已儲存", color = Color(0xFF81C784))
    }
}
