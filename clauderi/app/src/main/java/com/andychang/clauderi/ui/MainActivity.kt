package com.andychang.clauderi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.andychang.clauderi.ClaudeRiApp

class MainActivity : ComponentActivity() {

    private val app get() = application as ClaudeRiApp

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && app.assistant.pendingAssistLaunch.value) {
            app.assistant.pendingAssistLaunch.value = false
            app.assistant.startVoiceTurn(greet = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD8B4FE), secondary = Color(0xFF7DD3FC),
                    background = Color(0xFF14111C), surface = Color(0xFF1C1826),
                ),
            ) {
                Surface(color = MaterialTheme.colorScheme.background) { Root(app) }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Long-press Home (assistant session) or ACTION_ASSIST both land here. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val fromAssist = intent.action == Intent.ACTION_ASSIST || intent.getBooleanExtra(EXTRA_START_VOICE, false)
        if (!fromAssist) return
        intent.removeExtra(EXTRA_START_VOICE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            app.assistant.startVoiceTurn(greet = true)
        } else {
            app.assistant.pendingAssistLaunch.value = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun requestMic() = micPermission.launch(Manifest.permission.RECORD_AUDIO)

    companion object { const val EXTRA_START_VOICE = "start_voice" }
}

private enum class Tab(val label: String) { CHAT("對話"), CAPABILITIES("能力"), SETTINGS("設定") }

@Composable
private fun Root(app: ClaudeRiApp) {
    var tab by remember { mutableStateOf(Tab.CHAT) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t }, label = { Text(t.label) },
                        icon = {
                            Icon(
                                when (t) { Tab.CHAT -> Icons.Filled.Chat; Tab.CAPABILITIES -> Icons.Filled.Tune; Tab.SETTINGS -> Icons.Filled.Settings },
                                contentDescription = t.label,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            Tab.CHAT -> ChatScreen(app, m)
            Tab.CAPABILITIES -> CapabilitiesScreen(app, m)
            Tab.SETTINGS -> SettingsScreen(app, m)
        }
    }
}
