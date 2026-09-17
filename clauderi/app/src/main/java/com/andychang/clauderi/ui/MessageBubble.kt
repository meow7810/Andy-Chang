package com.andychang.clauderi.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.andychang.clauderi.data.ChatMessage
import com.andychang.clauderi.data.Source
import com.andychang.clauderi.llm.Role

/** One message. Long-press opens a small menu: copy the text, or delete it (tombstone in the archive). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(m: ChatMessage, onDelete: ((ChatMessage) -> Unit)? = null) {
    val mine = m.role == Role.USER
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { menu = true },
                )
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
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("複製") }, onClick = {
                    clipboard.setText(AnnotatedString(m.text)); menu = false
                    Toast.makeText(context, "已複製", Toast.LENGTH_SHORT).show()
                })
                if (onDelete != null && !m.deleted) DropdownMenuItem(text = { Text("刪除這則（留下墓碑）") }, onClick = { menu = false; onDelete(m) })
            }
            Column {
                Text(if (m.deleted) "（已刪除）" else m.text, style = MaterialTheme.typography.bodyMedium, color = if (m.deleted) Color.Gray else Color.Unspecified)
                val meta = buildString {
                    append(if (m.source == Source.VOICE) "🎙 語音" else "⌨ 文字")
                    if (m.statement == com.andychang.clauderi.data.StatementType.FICTION) append("  📖 小說")
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
