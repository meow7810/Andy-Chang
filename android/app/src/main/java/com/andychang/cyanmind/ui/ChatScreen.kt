package com.andychang.cyanmind.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.andychang.cyanmind.CyanApp
import com.andychang.cyanmind.assistant.AssistantState
import com.andychang.cyanmind.ble.ConnectionState
import com.andychang.cyanmind.data.ChatMessage
import com.andychang.cyanmind.llm.Role

/**
 * Chat screen: full history, a text field (so you can type like Siri's keyboard mode),
 * and a mic button that runs the same voice pipeline the wake word triggers.
 */
@Composable
fun ChatScreen(app: CyanApp, modifier: Modifier = Modifier) {
    val messages by app.conversation.messages.collectAsState()
    val state by app.assistant.state.collectAsState()
    val conn by app.glasses.state.collectAsState()
    val deviceName by app.glasses.deviceName.collectAsState()
    val battery by app.glasses.battery.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val (label, color) = when (conn) {
                ConnectionState.READY -> "${deviceName ?: "眼鏡"} 已連線" to Color(0xFF4CAF50)
                ConnectionState.CONNECTED -> "初始化中…" to Color(0xFFFFC107)
                ConnectionState.CONNECTING -> "連線中…" to Color(0xFFFFC107)
                ConnectionState.DISCONNECTED -> "眼鏡未連線" to Color(0xFFF44336)
            }
            AssistChip(onClick = {}, label = { Text(label) },
                leadingIcon = { Box(Modifier.width(10.dp).background(color, RoundedCornerShape(50)).padding(5.dp)) })
            battery?.let { AssistChip(onClick = {}, label = { Text("🔋 $it%") }) }
            Spacer(Modifier.weight(1f))
            Text(stateLabel(state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (state is AssistantState.Listening || state is AssistantState.Transcribing || state is AssistantState.Thinking) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        (state as? AssistantState.Error)?.let {
            Text(it.message, color = Color(0xFFFF8A80), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            items(messages, key = { it.id }) { Bubble(it) }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), maxLines = 4,
                placeholder = { Text("打字或按麥克風說話…") },
            )
            IconButton(onClick = { if (input.isNotBlank()) { app.assistant.sendText(input); input = "" } }) {
                Icon(Icons.Filled.Send, contentDescription = "送出")
            }
            val busy = state !is AssistantState.Idle && state !is AssistantState.Error
            FilledIconButton(onClick = { if (busy) app.assistant.cancelVoiceTurn() else app.assistant.startVoiceTurn() }) {
                Icon(if (busy) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = "語音")
            }
        }
    }
}

private fun stateLabel(s: AssistantState) = when (s) {
    AssistantState.Idle -> "待命"
    AssistantState.Listening -> "聆聽中…"
    AssistantState.Transcribing -> "辨識中…"
    is AssistantState.Thinking -> "思考中…"
    is AssistantState.Speaking -> "播放中…"
    is AssistantState.Error -> "錯誤"
}

@Composable
private fun Bubble(m: ChatMessage) {
    val mine = m.role == Role.USER
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    when {
                        m.error -> Color(0xFF5A1F1F)
                        mine -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        else -> Color(0xFF1E2A3A)
                    },
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                Text(m.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (m.source == com.andychang.cyanmind.data.Source.VOICE) "🎙 語音" else "⌨ 文字",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                )
            }
        }
    }
}
