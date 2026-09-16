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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.andychang.clauderi.data.ChatMessage
import com.andychang.clauderi.data.Source
import com.andychang.clauderi.llm.Role

/** One message. Long-press copies the whole text to the clipboard (for pasting elsewhere). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(m: ChatMessage) {
    val mine = m.role == Role.USER
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboard.setText(AnnotatedString(m.text))
                        Toast.makeText(context, "已複製", Toast.LENGTH_SHORT).show()
                    },
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
