package com.andychang.clauderi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as SysSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.andychang.clauderi.ClaudeRiApp
import com.andychang.clauderi.assistant.AssistantState
import com.andychang.clauderi.capabilities.CalendarCapability
import com.andychang.clauderi.capabilities.ContactsCapability
import com.andychang.clauderi.capabilities.GmailCapability
import com.andychang.clauderi.capabilities.NotificationCapability
import com.andychang.clauderi.capabilities.RequestOutcome
import com.andychang.clauderi.capabilities.ScreenCapability
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.ChatMessage
import com.andychang.clauderi.data.Source
import com.andychang.clauderi.llm.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AUTO_SEND_DELAY_MS = 2_000L

/**
 * Full history, a text field, a mic button. A voice transcript lands in the text field first;
 * it auto-sends after 2 s without edits, or immediately when the user taps send.
 */
@Composable
fun ChatScreen(app: ClaudeRiApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val messages by app.conversation.messages.collectAsState()
    val state by app.assistant.state.collectAsState()
    val draft by app.assistant.voiceDraft.collectAsState()
    val cfg by app.settings.flow.collectAsState(initial = AppSettings())
    var input by remember { mutableStateOf("") }
    var pendingSource by remember { mutableStateOf(Source.TEXT) }
    var armed by remember { mutableStateOf(false) }      // auto-send countdown running
    var editTick by remember { mutableIntStateOf(0) }     // bumps on every edit to restart the countdown
    val listState = rememberLazyListState()

    fun sendNow() {
        if (input.isBlank()) return
        app.assistant.send(input, pendingSource)
        input = ""; armed = false; pendingSource = Source.TEXT
    }

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    LaunchedEffect(draft) {
        draft?.let { d ->
            input = d.text; pendingSource = Source.VOICE; armed = true; editTick++
            app.assistant.consumeVoiceDraft()
        }
    }

    LaunchedEffect(armed, editTick) {
        if (armed) {
            delay(AUTO_SEND_DELAY_MS)
            if (armed) sendNow()
        }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ClaudeRi", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(stateLabel(state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (state is AssistantState.Listening || state is AssistantState.Transcribing || state is AssistantState.Thinking) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        (state as? AssistantState.Error)?.let {
            Text(it.message, color = Color(0xFFFF8A80), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp)) {
            items(messages, key = { it.id }) { Bubble(it) }
        }

        CapabilityRequestCard(app)

        if (armed) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("2 秒內沒動就送出；可直接修改。", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { armed = false }) { Text("先不要送") }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { v ->
                    input = v
                    if (v.isBlank()) armed = false
                    else if (armed || cfg.autoSendTypedInput) { armed = true; editTick++ }
                },
                modifier = Modifier.weight(1f), maxLines = 4,
                placeholder = { Text("打字，或按麥克風說話…") },
            )
            IconButton(onClick = { sendNow() }) { Icon(Icons.Filled.Send, contentDescription = "送出") }
            val busy = state is AssistantState.Listening || state is AssistantState.Transcribing || state is AssistantState.Speaking
            FilledIconButton(onClick = {
                if (busy) app.assistant.cancelVoiceTurn()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) app.assistant.startVoiceTurn()
                else (context as? MainActivity)?.requestMic()
            }) {
                Icon(if (busy) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = "語音")
            }
        }
    }
}

/**
 * The in-chat permission prompt. The model asked for a capability via request_capability and is
 * now blocked waiting; the user decides here. 允許 flips the app switch and, where Android needs
 * it, runs the system permission dialog (calendar / contacts) or opens the system settings page
 * (notification access / accessibility) before answering the model.
 */
@Composable
private fun CapabilityRequestCard(app: ClaudeRiApp) {
    val request by app.capabilities.broker.pending.collectAsState()
    val req = request ?: return
    val scope = rememberCoroutineScope()
    val broker = app.capabilities.broker
    val cfg by app.settings.flow.collectAsState(initial = AppSettings())

    fun finish(systemOk: Boolean) = broker.resolve(if (systemOk) RequestOutcome.GRANTED else RequestOutcome.GRANTED_SYSTEM_PENDING)

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        finish(result.values.all { it })
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val cap = broker.pending.value?.capability ?: return@rememberLauncherForActivityResult
        finish(systemReady(app, cap, cfg))
    }

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ClaudeRi 想開啟「${req.capability.title}」", style = MaterialTheme.typography.titleSmall)
            Text(req.reason, style = MaterialTheme.typography.bodyMedium)
            Text(req.capability.summary, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { broker.resolve(RequestOutcome.DENIED) }) { Text("拒絕") }
                Button(onClick = {
                    scope.launch {
                        app.settings.setCapability(req.capability, true)
                        when (req.capability) {
                            CapabilityId.ACTIONS -> finish(true)
                            CapabilityId.GMAIL -> finish(systemReady(app, req.capability, cfg))
                            CapabilityId.CALENDAR -> permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                            CapabilityId.CONTACTS -> permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                            CapabilityId.NOTIFICATIONS ->
                                if (systemReady(app, req.capability, cfg)) finish(true)
                                else settingsLauncher.launch(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            CapabilityId.SCREEN ->
                                if (systemReady(app, req.capability, cfg)) finish(true)
                                else settingsLauncher.launch(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                }) { Text("允許") }
            }
        }
    }
}

/** Whether the Android-side permission behind a capability is already in place. */
private fun systemReady(app: ClaudeRiApp, cap: CapabilityId, cfg: AppSettings): Boolean = when (cap) {
    CapabilityId.NOTIFICATIONS -> (app.capabilities.byId(cap) as NotificationCapability).listenerEnabled()
    CapabilityId.SCREEN -> (app.capabilities.byId(cap) as ScreenCapability).serviceEnabled()
    CapabilityId.CALENDAR -> (app.capabilities.byId(cap) as CalendarCapability).let { it.granted() && it.writeGranted() }
    CapabilityId.CONTACTS -> (app.capabilities.byId(cap) as ContactsCapability).granted()
    CapabilityId.ACTIONS -> true
    CapabilityId.GMAIL -> (app.capabilities.byId(cap) as GmailCapability).configured(cfg)
}

private fun stateLabel(s: AssistantState) = when (s) {
    AssistantState.Idle -> "待命"
    AssistantState.Listening -> "聆聽中…"
    AssistantState.Transcribing -> "辨識中…"
    is AssistantState.Thinking -> "思考中…"
    is AssistantState.Speaking -> "朗讀中…"
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
                        else -> Color(0xFF2A2438)
                    },
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                Text(m.text, style = MaterialTheme.typography.bodyMedium)
                val meta = buildString {
                    append(if (m.source == Source.VOICE) "🎙 語音" else "⌨ 文字")
                    if (m.toolsUsed.isNotEmpty()) append("  🔧 ").append(m.toolsUsed.distinct().joinToString(", "))
                }
                Text(meta, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                m.toolErrors.forEach { e ->
                    Text("⚠ $e", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB74D))
                }
            }
        }
    }
}
