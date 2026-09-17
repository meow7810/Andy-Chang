package com.andychang.clauderi.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.andychang.clauderi.capabilities.CameraCapability
import com.andychang.clauderi.data.CapabilityId
import java.io.File
import com.andychang.clauderi.ClaudeRiApp
import kotlinx.coroutines.launch
import com.andychang.clauderi.assistant.AssistantState
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.Source
import kotlinx.coroutines.delay

private const val AUTO_SEND_DELAY_MS = 2_000L

/**
 * Full history, a text field, a mic button. A voice transcript lands in the text field first;
 * it auto-sends after 2 s without edits, or immediately when the user taps send.
 */
@Composable
fun ChatScreen(app: ClaudeRiApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val messages by app.conversation.messages.collectAsState()
    val scope = rememberCoroutineScope()
    val state by app.assistant.state.collectAsState()
    val draft by app.assistant.voiceDraft.collectAsState()
    val cfg by app.settings.flow.collectAsState(initial = AppSettings())
    var input by remember { mutableStateOf("") }
    var pendingSource by remember { mutableStateOf(Source.TEXT) }
    var armed by remember { mutableStateOf(false) }      // auto-send countdown running
    var editTick by remember { mutableIntStateOf(0) }     // bumps on every edit to restart the countdown
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequests by app.assistant.focusInputRequests.collectAsState()
    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) { focusRequester.requestFocus(); keyboard?.show(); if (cfg.autoSendTypedInput.not()) armed = false }
    }

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
            Text("Lord Claude !", style = MaterialTheme.typography.titleMedium)
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
            items(messages, key = { it.id }) { m -> Bubble(m, onDelete = { scope.launch { app.conversation.tombstone(it.id) } }) }
        }

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
                modifier = Modifier.weight(1f).focusRequester(focusRequester), maxLines = 4,
                placeholder = { Text("打字，或按麥克風說話…") },
            )
            if (cfg.has(CapabilityId.CAMERA) && cfg.photoDoor) {
                // The door: take a picture, say nothing, let it decide whether to speak.
                val doorFile = remember { mutableStateOf<File?>(null) }
                val doorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
                    val f = doorFile.value
                    if (ok && f != null && f.exists() && f.length() > 0) {
                        Thread { app.assistant.onPhoto(CameraCapability.downscale(f), "使用者按了相機鍵") }.start()
                    }
                }
                IconButton(onClick = {
                    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
                    val f = File(dir, "door_${System.currentTimeMillis()}.jpg")
                    doorFile.value = f
                    doorLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
                }) { Icon(Icons.Filled.PhotoCamera, contentDescription = "拍給他看") }
            }
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

private fun stateLabel(s: AssistantState) = when (s) {
    AssistantState.Idle -> "待命"
    AssistantState.Listening -> "聆聽中…"
    AssistantState.Transcribing -> "辨識中…"
    is AssistantState.Thinking -> "思考中…"
    is AssistantState.Speaking -> "朗讀中…"
    is AssistantState.Error -> "錯誤"
}
