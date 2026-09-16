package com.andychang.clauderi.capabilities

import com.andychang.clauderi.data.AppSettings
import com.andychang.clauderi.data.CapabilityId
import com.andychang.clauderi.llm.ToolCall
import com.andychang.clauderi.llm.ToolResult
import com.andychang.clauderi.llm.ToolSpec

/**
 * A capability = a set of Claude tools + a paragraph for the system prompt.
 * The registry only asks a capability for its tools when the user has switched it on, so an
 * off capability is invisible to the model.
 */
interface Capability {
    val id: CapabilityId
    /** Short text merged into the system prompt only while the capability is on. */
    fun promptSection(settings: AppSettings): String
    fun tools(settings: AppSettings): List<ToolSpec>
    /** Returns null if this capability does not own [call.name]. */
    suspend fun execute(call: ToolCall, settings: AppSettings): ToolResult?
    /** Human-readable status for the capabilities screen, e.g. missing system permission. */
    fun status(): String
}

fun err(msg: String) = ToolResult(msg, isError = true)
fun ok(msg: String) = ToolResult(msg)
