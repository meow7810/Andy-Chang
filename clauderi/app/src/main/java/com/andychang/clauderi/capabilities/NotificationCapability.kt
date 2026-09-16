package com.andychang.clauderi.capabilities

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolParam
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SeenNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val canReply: Boolean,
)

/**
 * Process-wide store fed by [ClaudeRiNotificationListener]. Only notifications from packages
 * on the user's allow-list are ever kept; everything else is dropped at the door.
 */
object NotificationStore {
    @Volatile var enabled = false
    @Volatile var allowedPackages: Set<String> = emptySet()

    val active = MutableStateFlow<List<SeenNotification>>(emptyList())
    /** New allowed notifications, for the "read aloud" feature. */
    val incoming = MutableSharedFlow<SeenNotification>(extraBufferCapacity = 16)

    @Volatile internal var listener: ClaudeRiNotificationListener? = null
    internal val raw = HashMap<String, StatusBarNotification>()

    fun isAllowed(pkg: String) = enabled && pkg in allowedPackages
}

class ClaudeRiNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationStore.listener = this
        runCatching { activeNotifications?.forEach { onNotificationPosted(it, quiet = true) } }
    }

    override fun onListenerDisconnected() {
        NotificationStore.listener = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = onNotificationPosted(sbn, quiet = false)

    private fun onNotificationPosted(sbn: StatusBarNotification, quiet: Boolean) {
        if (!NotificationStore.isAllowed(sbn.packageName)) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: Bundle()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val seen = SeenNotification(
            key = sbn.key, packageName = sbn.packageName, appLabel = label, title = title, text = text,
            postedAt = sbn.postTime, canReply = replyAction(n) != null,
        )
        synchronized(NotificationStore.raw) { NotificationStore.raw[sbn.key] = sbn }
        NotificationStore.active.value = (NotificationStore.active.value.filter { it.key != sbn.key } + seen).takeLast(50)
        if (!quiet) NotificationStore.incoming.tryEmit(seen)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(NotificationStore.raw) { NotificationStore.raw.remove(sbn.key) }
        NotificationStore.active.value = NotificationStore.active.value.filter { it.key != sbn.key }
    }

    companion object {
        fun replyAction(n: Notification): Notification.Action? =
            n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
    }
}

class NotificationCapability(private val context: Context) : Capability {

    override val id = CapabilityId.NOTIFICATIONS

    private val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN)

    override fun promptSection(settings: AppSettings) =
        "你可以讀取並回覆使用者勾選的 App 的通知（list_notifications / reply_notification / dismiss_notification）。" +
            "回覆前先確認對方和內容；朗讀通知時用一兩句話摘要，不要逐字唸長串。"

    override fun tools(settings: AppSettings) = listOf(
        ToolSpec("list_notifications", "列出目前通知列上、來自使用者允許的 App 的通知（最新在最後）。"),
        ToolSpec(
            "reply_notification", "對某則支援快速回覆的通知直接回覆文字（例如 LINE、WhatsApp、簡訊）。",
            listOf(
                ToolParam("key", "string", "list_notifications 回傳的通知 key"),
                ToolParam("text", "string", "要回覆的內容"),
            ),
        ),
        ToolSpec("dismiss_notification", "清除一則通知。", listOf(ToolParam("key", "string", "通知 key"))),
    )

    override suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult? = when (call.name) {
        "list_notifications" -> {
            if (!listenerEnabled()) err("使用者尚未在系統設定授權 ClaudeRi 讀取通知。")
            else {
                val list = NotificationStore.active.value
                if (list.isEmpty()) ok("目前沒有來自允許的 App 的通知。")
                else ok(list.joinToString("\n") {
                    "[${it.key}] ${fmt.format(Date(it.postedAt))} ${it.appLabel}｜${it.title}：${it.text}" +
                        if (it.canReply) "（可回覆）" else ""
                })
            }
        }
        "reply_notification" -> {
            val key = call.str("key") ?: return err("缺少 key")
            val text = call.str("text") ?: return err("缺少 text")
            val sbn = synchronized(NotificationStore.raw) { NotificationStore.raw[key] } ?: return err("找不到這則通知（可能已被清除）")
            if (!NotificationStore.isAllowed(sbn.packageName)) return err("這個 App 不在允許清單中")
            val action = ClaudeRiNotificationListener.replyAction(sbn.notification) ?: return err("這則通知不支援快速回覆")
            val remoteInputs = action.remoteInputs
            val intent = Intent()
            val results = Bundle()
            remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(remoteInputs, intent, results)
            try {
                action.actionIntent.send(context, 0, intent)
                ok("已透過 ${sbn.packageName} 回覆：$text")
            } catch (e: PendingIntent.CanceledException) {
                err("回覆失敗：通知的回覆動作已失效")
            }
        }
        "dismiss_notification" -> {
            val key = call.str("key") ?: return err("缺少 key")
            val svc = NotificationStore.listener ?: return err("通知監聽服務未連線")
            svc.cancelNotification(key)
            ok("已清除通知 $key")
        }
        else -> null
    }

    fun listenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    override fun status(): String = if (listenerEnabled()) "系統通知存取：已授權" else "系統通知存取：尚未授權（按下方按鈕前往設定）"
}
