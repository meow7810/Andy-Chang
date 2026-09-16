package com.andychang.clauderi.capabilities

import android.content.Context
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.data.Settings
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolExecutor
import com.andychang.clauderi.llm.ToolParam
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec

/** Holds every capability and applies the user's per-capability switches. */
class CapabilityRegistry(context: Context, private val settings: Settings, val broker: PermissionBroker) {

    val all: List<Capability> = listOf(
        NotificationCapability(context),
        CalendarCapability(context),
        ContactsCapability(context),
        ActionsCapability(context),
        ScreenCapability(context),
        GmailCapability(context),
    )

    fun byId(id: CapabilityId): Capability = all.first { it.id == id }

    fun enabled(cfg: AppSettings): List<Capability> = all.filter { cfg.has(it.id) }

    /** Tools of enabled capabilities, plus the meta tool for asking (if the user allows asking). */
    fun tools(cfg: AppSettings): List<ToolSpec> {
        val list = enabled(cfg).flatMap { it.tools(cfg) }.toMutableList()
        val askable = CapabilityId.entries.filter { !cfg.has(it) }
        if (cfg.allowAiCapabilityRequests && askable.isNotEmpty()) list += requestTool(askable)
        return list
    }

    fun promptSections(cfg: AppSettings): String {
        val sections = enabled(cfg).map { it.promptSection(cfg) }.filter { it.isNotBlank() }.toMutableList()
        val askable = CapabilityId.entries.filter { !cfg.has(it) }
        if (cfg.allowAiCapabilityRequests && askable.isNotEmpty()) {
            sections += "使用者尚未開啟的能力：" + askable.joinToString("、") { "${it.name}（${it.title}）" } +
                "。當使用者的要求需要其中一項時，用 request_capability 請求，說明用途，等使用者在畫面上按允許；" +
                "被拒絕就不要再問第二次。"
        }
        return sections.joinToString("\n\n")
    }

    private fun requestTool(askable: List<CapabilityId>) = ToolSpec(
        REQUEST_TOOL,
        "請使用者開啟一項目前未啟用的能力。畫面會跳出確認卡片，這個工具會等到使用者按允許或拒絕才回傳。" +
            "只在使用者的要求確實需要該能力時使用。",
        listOf(
            ToolParam("capability", "string", "要請求的能力", enum = askable.map { it.name }),
            ToolParam("reason", "string", "一句話說明為什麼需要，會顯示給使用者看"),
        ),
    )

    /** Re-reads settings on every call, so a capability granted mid-turn is usable in the next round. */
    fun executor() = ToolExecutor { call: ToolCall ->
        val cfg = settings.current()
        if (call.name == REQUEST_TOOL) return@ToolExecutor handleRequest(call, cfg)
        // Double check at execution time: a tool from a capability that is off is refused even if
        // the model somehow asked for it.
        val owner = enabled(cfg).firstOrNull { cap -> cap.tools(cfg).any { it.name == call.name } }
            ?: return@ToolExecutor err("工具 ${call.name} 未啟用。")
        try {
            owner.execute(call, cfg) ?: err("未知的工具 ${call.name}")
        } catch (e: SecurityException) {
            err("系統權限不足：${e.message}")
        } catch (e: Exception) {
            err("工具執行失敗：${e.message}")
        }
    }

    private suspend fun handleRequest(call: ToolCall, cfg: AppSettings): ToolResult {
        if (!cfg.allowAiCapabilityRequests) return err("使用者關閉了在對話中請求能力的功能。")
        val id = call.str("capability")?.let { runCatching { CapabilityId.valueOf(it) }.getOrNull() }
            ?: return err("未知的能力")
        if (cfg.has(id)) return ok("${id.title} 已經是開啟狀態。")
        val reason = call.str("reason").orEmpty().ifBlank { "（未說明）" }
        return when (broker.ask(id, reason)) {
            RequestOutcome.GRANTED -> ok(
                "使用者已允許並完成授權，${id.title} 的工具現在可以使用。" +
                    if (id == CapabilityId.NOTIFICATIONS) "但使用者還沒勾選允許哪些 App 的通知，請提醒他到「能力」頁勾選。" else "",
            )
            RequestOutcome.GRANTED_SYSTEM_PENDING -> ok(
                "使用者按了允許，但系統權限尚未完成授權（${byId(id).status()}）。請提醒使用者到「能力」頁完成，先不要呼叫該能力的工具。",
            )
            RequestOutcome.DENIED -> err("使用者拒絕開啟 ${id.title}。不要再請求這項能力，改用其他方式或直接說明做不到。")
            RequestOutcome.TIMEOUT -> err("使用者沒有回應請求。")
        }
    }

    companion object { const val REQUEST_TOOL = "request_capability" }
}
