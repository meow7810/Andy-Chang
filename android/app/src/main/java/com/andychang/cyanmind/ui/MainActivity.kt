package com.andychang.cyanmind.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Visibility
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
import com.andychang.cyanmind.CyanApp

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimePermissions()
        val app = application as CyanApp
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4FC3F7), background = Color(0xFF0B1220))) {
                Surface(color = MaterialTheme.colorScheme.background) { Root(app) }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

private enum class Tab(val label: String) { CHAT("對話"), GLASSES("眼鏡"), SETTINGS("設定") }

@Composable
private fun Root(app: CyanApp) {
    var tab by remember { mutableStateOf(Tab.CHAT) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.CHAT, onClick = { tab = Tab.CHAT },
                    icon = { Icon(Icons.Filled.Chat, null) }, label = { Text(Tab.CHAT.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.GLASSES, onClick = { tab = Tab.GLASSES },
                    icon = { Icon(Icons.Outlined.Visibility, null) }, label = { Text(Tab.GLASSES.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS, onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text(Tab.SETTINGS.label) },
                )
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            Tab.CHAT -> ChatScreen(app, m)
            Tab.GLASSES -> GlassesScreen(app, m)
            Tab.SETTINGS -> SettingsScreen(app, m)
        }
    }
}
