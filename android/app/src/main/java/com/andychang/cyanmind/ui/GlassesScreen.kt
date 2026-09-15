package com.andychang.cyanmind.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andychang.cyanmind.CyanApp
import com.andychang.cyanmind.ble.ConnectionState

/** Scan / connect / forget the glasses; shows firmware + battery once connected. */
@Composable
fun GlassesScreen(app: CyanApp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val conn by app.glasses.state.collectAsState()
    val name by app.glasses.deviceName.collectAsState()
    val fw by app.glasses.firmware.collectAsState()
    val battery by app.glasses.battery.collectAsState()
    val results by app.glasses.scanResults.collectAsState()
    val scanning by app.glasses.scanning.collectAsState()

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name ?: "尚未配對眼鏡", style = MaterialTheme.typography.titleMedium)
                Text("狀態：${conn.zh()}")
                fw?.let { Text("韌體：$it", style = MaterialTheme.typography.bodySmall) }
                battery?.let { Text("電量：$it%", style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (conn == ConnectionState.DISCONNECTED) {
                        Button(onClick = { app.glasses.reconnectLast() }, enabled = name != null) { Text("重新連線") }
                    } else {
                        OutlinedButton(onClick = { app.glasses.disconnect() }) { Text("中斷") }
                    }
                    OutlinedButton(onClick = { app.glasses.forget() }) { Text("解除配對") }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("搜尋附近的眼鏡", style = MaterialTheme.typography.titleSmall)
            Button(onClick = { if (scanning) app.glasses.stopScan(ctx) else app.glasses.startScan(ctx) }) {
                Text(if (scanning) "停止" else "掃描")
            }
        }
        Text(
            "提示：眼鏡要先開機，並在系統藍牙設定裡完成一次配對（HFP/A2DP 音訊靠系統配對；這裡的 BLE 連線負責控制指令）。",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.address }) { d ->
                Column(
                    Modifier.fillMaxWidth().clickable { app.glasses.stopScan(ctx); app.glasses.connect(d.address, d.name) }.padding(vertical = 10.dp),
                ) {
                    Text(d.name, style = MaterialTheme.typography.bodyLarge)
                    Text("${d.address}   RSSI ${d.rssi}", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

private fun ConnectionState.zh() = when (this) {
    ConnectionState.DISCONNECTED -> "未連線"
    ConnectionState.CONNECTING -> "連線中"
    ConnectionState.CONNECTED -> "已連線，初始化中"
    ConnectionState.READY -> "就緒"
}
