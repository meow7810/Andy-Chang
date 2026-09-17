package com.andychang.clauderi.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings as SysSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.andychang.clauderi.ClaudeRiApp
import com.andychang.clauderi.capabilities.CalendarCapability
import com.andychang.clauderi.capabilities.ContactsCapability
import com.andychang.clauderi.capabilities.GmailCapability
import com.andychang.clauderi.capabilities.NotificationCapability
import com.andychang.clauderi.capabilities.RequestOutcome
import com.andychang.clauderi.capabilities.ScreenCapability
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import kotlinx.coroutines.launch

/**
 * The in-chat permission prompt. The model asked for a capability via request_capability and is
 * now blocked waiting; the user decides here. 允許 flips the app switch and, where Android needs
 * it, runs the system permission dialog (calendar / contacts) or opens the system settings page
 * (notification access / accessibility) before answering the model.
 */
@Composable
internal fun CapabilityRequestCard(app: ClaudeRiApp) {
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
                            CapabilityId.ACTIONS, CapabilityId.MUSIC -> finish(true)
                            CapabilityId.GMAIL -> finish(systemReady(app, req.capability, cfg))
                            CapabilityId.CAMERA, CapabilityId.WEB -> finish(true)
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
internal fun systemReady(app: ClaudeRiApp, cap: CapabilityId, cfg: AppSettings): Boolean = when (cap) {
    CapabilityId.NOTIFICATIONS -> (app.capabilities.byId(cap) as NotificationCapability).listenerEnabled()
    CapabilityId.SCREEN -> (app.capabilities.byId(cap) as ScreenCapability).serviceEnabled()
    CapabilityId.CALENDAR -> (app.capabilities.byId(cap) as CalendarCapability).let { it.granted() && it.writeGranted() }
    CapabilityId.CONTACTS -> (app.capabilities.byId(cap) as ContactsCapability).granted()
    CapabilityId.ACTIONS, CapabilityId.MUSIC -> true
    CapabilityId.GMAIL -> (app.capabilities.byId(cap) as GmailCapability).configured(cfg)
    CapabilityId.CAMERA, CapabilityId.WEB -> true
}
