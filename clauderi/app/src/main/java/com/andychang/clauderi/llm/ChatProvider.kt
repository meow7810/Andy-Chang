package com.andychang.clauderi.llm

import org.json.JSONObject

enum class Role { USER, ASSISTANT }

data class ChatTurn(val role: Role, val text: String)

/** One parameter of a tool. Flat parameters are all v1 capabilities need. */
data class ToolParam(
    val name: String,
    val type: String,               // "string" | "integer" | "number" | "boolean"
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null,
)

/** Provider-neutral tool definition; each provider converts it to its own wire format. */
data class ToolSpec(val name: String, val description: String, val params: List<ToolParam> = emptyList()) {
    /** JSON-Schema `properties` object, shared by the Claude and OpenAI formats. */
    fun schemaProperties(): JSONObject = JSONObject().also { props ->
        params.forEach { p ->
            val o = JSONObject().put("type", p.type).put("description", p.description)
            p.enum?.let { o.put("enum", org.json.JSONArray(it)) }
            props.put(p.name, o)
        }
    }
    fun requiredNames(): List<String> = params.filter { it.required }.map { it.name }
}

data class ToolCall(val id: String, val name: String, val args: JSONObject) {
    fun str(key: String): String? = if (args.has(key) && !args.isNull(key)) args.get(key).toString() else null
    fun int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()
    fun bool(key: String): Boolean? = str(key)?.toBooleanStrictOrNull()
}

/** [imageJpeg] lets a tool hand the model a picture (Claude: image block inside the tool_result). */
data class ToolResult(val text: String, val isError: Boolean = false, val imageJpeg: ByteArray? = null)

fun interface ToolExecutor {
    suspend fun execute(call: ToolCall): ToolResult
}

/**
 * System prompt in two parts so providers can cache the stable one.
 * [stable]: persona + capability descriptions (changes only when settings change).
 * [volatile]: memory, current time, anything that changes turn to turn.
 */
data class SystemPrompt(val stable: String, val volatile: String) {
    val full: String get() = if (volatile.isBlank()) stable else stable + "\n\n" + volatile
}

data class ChatReply(val text: String, val toolsUsed: List<String>)

/**
 * Pluggable LLM backend. The app owns memory (it decides how much history to send) and owns
 * the tool list (only capabilities the user switched on are passed in). The provider runs the
 * model <-> tool loop until the model produces a final text answer.
 */
interface ChatProvider {
    val id: String
    /**
     * [systemPrompt] and [tools] are re-evaluated before every round of the tool loop, so a
     * capability the user grants mid-turn (via request_capability) is available on the next round.
     */
    suspend fun reply(
        systemPrompt: suspend () -> SystemPrompt,
        history: List<ChatTurn>,
        tools: suspend () -> List<ToolSpec>,
        executor: ToolExecutor,
    ): ChatReply
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

const val MAX_TOOL_ROUNDS = 8
