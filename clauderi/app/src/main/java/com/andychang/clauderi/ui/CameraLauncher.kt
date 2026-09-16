package com.andychang.clauderi.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andychang.clauderi.ClaudeRiApp

/** Opens the system camera when take_photo asks for it, and reports back to the tool. */
@Composable
internal fun CameraLauncher(app: ClaudeRiApp) {
    val request by app.capabilities.camera.pending.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> app.capabilities.camera.onPhotoResult(ok) }
    LaunchedEffect(request) {
        request?.let { launcher.launch(it.target) }
    }
    request?.let { req ->
        if (req.hint.isNotBlank()) {
            Text("📷 ${req.hint}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}
