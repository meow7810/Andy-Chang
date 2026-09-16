package com.andychang.clauderi.capabilities

import android.content.Context
import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolExecutor
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec

/** Holds every capability and applies the user's per-capability switches. */
class CapabilityRegistry(context: Context) {

    val all: List<Capability> = listOf(
        NotificationCapability(context),
        CalendarCapability(context),
        ContactsCapability(context),
        ActionsCapability(context),
        ScreenCapability(context),
    )

    fun byId(id: CapabilityId): Capability = all.first { it.id == id }

    fun enabled(settings: AppSettings): List<Capability> = all.filter { settings.has(it.id) }

    fun tools(settings: AppSettings): List<ToolSpec> = enabled(settings).flatMap { it.tools(settings) }

    fun promptSections(settings: AppSettings): String =
        enabled(settings).map { it.promptSection(settings) }.filter { it.isNotBlank() }.joinToString("\n\n")

    fun executor(settings: AppSettings) = ToolExecutor { call: ToolCall ->
        // Double check at execution time: a tool from a capability that is off is refused even if
        // the model somehow asked for it.
        val owner = enabled(settings).firstOrNull { cap -> cap.tools(settings).any { it.name == call.name } }
            ?: return@ToolExecutor err("工具 ${call.name} 未啟用。")
        try {
            owner.execute(call, settings) ?: err("未知的工具 ${call.name}")
        } catch (e: SecurityException) {
            err("系統權限不足：${e.message}")
        } catch (e: Exception) {
            err("工具執行失敗：${e.message}")
        }
    }
}

