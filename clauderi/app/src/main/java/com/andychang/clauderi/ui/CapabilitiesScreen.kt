package com.andychang.clauderi.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Settings as SysSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andychang.clauderi.ClaudeRiApp
import com.andychang.clauderi.capabilities.NotificationCapability
import com.andychang.clauderi.capabilities.ScreenCapability
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import kotlinx.coroutines.launch

/**
 * The heart of the "user controls every permission" idea: one switch per capability.
 * Turning a switch on asks for the system permission it needs; an off capability contributes
 * nothing to the model's tool list or system prompt.
 */
@Composable
fun CapabilitiesScreen(app: ClaudeRiApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cfg by app.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) } // bump to re-read system permission status

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    fun open(action: String) = runCatching { settingsLauncher.launch(Intent(action)) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("能力（逐項授權）", style = MaterialTheme.typography.titleLarge)
        Text("關掉的能力，模型連它存在都不知道。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        // 1. Default assistant
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("1. 預設助理（長按 Home 呼叫）", style = MaterialTheme.typography.titleMedium)
                val isAssistant = remember(refresh) { isDefaultAssistant(context) }
                Text(
                    when (isAssistant) { true -> "目前是預設助理 ✓"; false -> "目前不是預設助理"; null -> "此 Android 版本無法查詢，請到設定確認" },
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                Text("設定 → 應用程式 → 預設應用程式 → 數位助理應用程式 → 選 ClaudeRi", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { open(SysSettings.ACTION_VOICE_INPUT_SETTINGS).onFailure { open(SysSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) } }) {
                    Text("前往系統設定")
                }
            }
        }

        // 2..5 capabilities
        app.capabilities.all.forEachIndexed { idx, cap ->
            val on = cfg.has(cap.id)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${idx + 2}. ${cap.id.title}", style = MaterialTheme.typography.titleMedium)
                            Text(cap.id.summary, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = on, onCheckedChange = { checked ->
                            scope.launch { app.settings.setCapability(cap.id, checked) }
                            if (checked) when (cap.id) {
                                CapabilityId.CALENDAR -> permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                                CapabilityId.CONTACTS -> permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                                CapabilityId.NOTIFICATIONS -> if (!(cap as NotificationCapability).listenerEnabled()) open(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                CapabilityId.SCREEN -> if (!(cap as ScreenCapability).serviceEnabled()) open(SysSettings.ACTION_ACCESSIBILITY_SETTINGS)
                                CapabilityId.ACTIONS -> Unit
                            }
                        })
                    }
                    val status = remember(refresh, on) { cap.status() }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = if (status.contains("尚未")) Color(0xFFFFB74D) else Color.Gray)

                    when (cap.id) {
                        CapabilityId.NOTIFICATIONS -> if (on) {
                            OutlinedButton(onClick = { open(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) { Text("系統通知存取設定") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("新通知自動朗讀")
                                Switch(checked = cfg.readNotificationsAloud, onCheckedChange = { v -> scope.launch { app.settings.update { it.copy(readNotificationsAloud = v) } } })
                            }
                            HorizontalDivider()
                            Text("允許哪些 App 的通知：", style = MaterialTheme.typography.labelLarge)
                            AppAllowList(app, cfg)
                        }
                        CapabilityId.SCREEN -> if (on) {
                            OutlinedButton(onClick = { open(SysSettings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("系統無障礙設定") }
                            Text("只讀文字，不會點擊或輸入。建議其他能力都用順了再開。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        CapabilityId.CALENDAR, CapabilityId.CONTACTS -> if (on && status.contains("尚未")) {
                            OutlinedButton(onClick = {
                                permissionLauncher.launch(arrayOf(if (cap.id == CapabilityId.CALENDAR) Manifest.permission.READ_CALENDAR else Manifest.permission.READ_CONTACTS))
                            }) { Text("再次要求權限") }
                        }
                        CapabilityId.ACTIONS -> Unit
                    }
                }
            }
        }
        Spacer(Modifier.width(1.dp))
    }
}

@Composable
private fun AppAllowList(app: ClaudeRiApp, cfg: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    apps.forEach { (pkg, label) ->
        val checked = pkg in cfg.notificationApps
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { v ->
                scope.launch { app.settings.update { it.copy(notificationApps = if (v) it.notificationApps + pkg else it.notificationApps - pkg) } }
            })
            Column {
                Text(label)
                Text(pkg, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

private fun isDefaultAssistant(context: android.content.Context): Boolean? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val rm = context.getSystemService(RoleManager::class.java) ?: return null
    return runCatching { rm.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrNull()
}
