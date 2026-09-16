package com.andychang.cyanmind.ui

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.andychang.cyanmind.CyanApp
import com.andychang.cyanmind.data.AppSettings
import com.andychang.cyanmind.data.LlmBackend
import com.andychang.cyanmind.data.Personas
import com.andychang.cyanmind.data.SttBackend
import kotlinx.coroutines.launch

/** API keys, provider selection, persona, memory depth. */
@Composable
fun SettingsScreen(app: CyanApp, modifier: Modifier = Modifier) {
    val saved by app.settings.flow.collectAsState(initial = AppSettings())
    var draft by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) { draft = saved }
    val scope = rememberCoroutineScope()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("語音辨識（STT）", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SttBackend.entries.forEach { b ->
                FilterChip(selected = draft.stt == b, onClick = { draft = draft.copy(stt = b) }, label = { Text(b.name) })
            }
        }
        OutlinedTextField(draft.openAiKey, { draft = draft.copy(openAiKey = it) }, label = { Text("OpenAI API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(draft.typelessKey, { draft = draft.copy(typelessKey = it) }, label = { Text("Typeless API key（可選）") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(draft.languageHint, { draft = draft.copy(languageHint = it) }, label = { Text("語言提示（zh / en，留空自動）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)

        HorizontalDivider()
        Text("AI 模型（LLM）", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LlmBackend.entries.forEach { b ->
                FilterChip(selected = draft.llm == b, onClick = { draft = draft.copy(llm = b) }, label = { Text(b.name) })
            }
        }
        OutlinedTextField(draft.anthropicKey, { draft = draft.copy(anthropicKey = it) }, label = { Text("Anthropic API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(draft.claudeModel, { draft = draft.copy(claudeModel = it) }, label = { Text("Claude model") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(draft.openAiChatModel, { draft = draft.copy(openAiChatModel = it) }, label = { Text("OpenAI chat model") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(draft.deepSeekKey, { draft = draft.copy(deepSeekKey = it) }, label = { Text("DeepSeek API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(draft.deepSeekModel, { draft = draft.copy(deepSeekModel = it) }, label = { Text("DeepSeek model（deepseek-chat / deepseek-reasoner）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(draft.qwenKey, { draft = draft.copy(qwenKey = it) }, label = { Text("Qwen (DashScope) API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(draft.qwenModel, { draft = draft.copy(qwenModel = it) }, label = { Text("Qwen model（qwen-plus / qwen-turbo / qwen-max）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)

        HorizontalDivider()
        Text("角色", style = MaterialTheme.typography.titleMedium)
        Personas.ALL.forEach { p ->
            FilterChip(selected = draft.personaName == p.name, onClick = { draft = draft.copy(personaName = p.name) },
                label = { Text("${p.name} — ${p.tagline}") }, modifier = Modifier.fillMaxWidth())
        }
        if (draft.personaName == Personas.CUSTOM_NAME) {
            OutlinedTextField(draft.customPersonaPrompt, { draft = draft.copy(customPersonaPrompt = it) },
                label = { Text("自訂 system prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
        }

        HorizontalDivider()
        Text("記憶與播放", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            draft.historyTurns.toString(),
            { draft = draft.copy(historyTurns = it.toIntOrNull()?.coerceIn(2, 400) ?: draft.historyTurns) },
            label = { Text("每次送給模型的歷史則數（原廠 App 只有 20）") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("打字的回覆也用眼鏡朗讀")
            Switch(checked = draft.speakTextReplies, onCheckedChange = { draft = draft.copy(speakTextReplies = it) })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { app.settings.update { draft } } }) { Text("儲存") }
            OutlinedButton(onClick = { scope.launch { app.conversation.clear() } }) { Text("清除對話記憶") }
        }
    }
}
