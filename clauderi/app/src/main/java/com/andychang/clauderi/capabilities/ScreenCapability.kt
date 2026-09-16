package com.andychang.clauderi.capabilities

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec

/**
 * Accessibility service that can read the text on screen. It is bound only after the user turns
 * it on in system settings, and it returns nothing unless the SCREEN capability is also on in
 * ClaudeRi. It never clicks or types.
 *
 * Because ClaudeRi itself is in the foreground while you talk to it, the service keeps a snapshot
 * of the last *other* app's window so "what's on my screen" refers to what you were looking at.
 */
class ScreenReaderService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!enabledInApp) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        val now = System.currentTimeMillis()
        if (now - lastSnapshotAt < 1_000) return
        lastSnapshotAt = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() == packageName) return
        lastOtherApp = pkg
        lastSnapshot = collectText(root)
    }

    fun readNow(): Pair<String, String> {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root != null && pkg != null && pkg != packageName) return pkg to collectText(root)
        return (lastOtherApp ?: "unknown") to lastSnapshot
    }

    private fun collectText(root: AccessibilityNodeInfo): String {
        val out = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || out.size > 400) return
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out.joinToString("\n").take(6_000)
    }

    companion object {
        @Volatile var instance: ScreenReaderService? = null
        @Volatile var enabledInApp = false
        @Volatile private var lastSnapshot = ""
        @Volatile private var lastOtherApp: String? = null
        @Volatile private var lastSnapshotAt = 0L
    }
}

class ScreenCapability(private val context: Context) : Capability {

    override val id = CapabilityId.SCREEN

    override fun promptSection(settings: AppSettings) =
        "你可以用 read_screen 讀取使用者剛才看的畫面文字（唯讀，不能點擊）。只有使用者問到畫面內容時才用。"

    override fun tools(settings: AppSettings) = listOf(
        ToolSpec("read_screen", "讀取使用者呼叫助理前所在 App 的畫面文字。"),
    )

    override suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult? {
        if (call.name != "read_screen") return null
        val svc = ScreenReaderService.instance ?: return err("使用者尚未在系統無障礙設定中啟用 ClaudeRi 螢幕感知服務。")
        val (pkg, text) = svc.readNow()
        return if (text.isBlank()) ok("畫面上沒有可讀取的文字（App：$pkg）。") else ok("App：$pkg\n$text")
    }

    fun serviceEnabled(): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return flat.split(':').any { it.startsWith("${context.packageName}/") }
    }

    override fun status() = if (serviceEnabled()) "無障礙服務：已啟用" else "無障礙服務：尚未啟用（按下方按鈕前往設定）"
}
