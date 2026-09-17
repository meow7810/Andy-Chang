package com.andychang.clauderi.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import android.util.Base64
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebSearchTool20260209
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Claude via the official Anthropic Java SDK, with a manual tool-use loop.
 *
 * - Tools come first in the cached prefix, then the system prompt (cache_control on it), so
 *   repeated turns are cheap as long as the enabled capability set does not change.
 * - Adaptive thinking, effort MEDIUM: this is a phone assistant, latency matters.
 * - The whole assistant message (including thinking blocks) is echoed back verbatim on each
 *   tool round, as the API requires.
 */
class ClaudeChatProvider(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val maxTokens: Long = 2048,
) : ChatProvider {

    override val id = "claude"

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    override suspend fun reply(
        systemPrompt: suspend () -> SystemPrompt, history: List<ChatTurn>, tools: suspend () -> List<ToolSpec>, executor: ToolExecutor,
    ): ChatReply = withContext(Dispatchers.IO) {
        val messages = history.map { turn ->
            MessageParam.builder()
                .role(if (turn.role == Role.USER) MessageParam.Role.USER else MessageParam.Role.ASSISTANT)
                .content(turn.text)
                .build()
        }.toMutableList()
        val used = mutableListOf<String>()
        var usage = Usage.ZERO

        repeat(MAX_TOOL_ROUNDS) {
            val specs = tools()
            val sdkTools = specs.filter { !it.server }.map { toSdkTool(it) }
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .thinking(ThinkingConfigAdaptive.builder().build())
                // Phone-assistant turns are short; LOW keeps thinking tokens (billed as output) down.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .systemOfTextBlockParams(systemBlocks(systemPrompt()))
                .messages(messages)
            sdkTools.forEach { builder.addTool(it) }
            if (specs.any { it.server && it.name == "web_search" }) {
                builder.addTool(
                    WebSearchTool20260209.builder()
                        .maxUses(5L)
                        .userLocation(UserLocation.builder().city("Taipei").country("TW").timezone("Asia/Taipei").build())
                        .build(),
                )
            }

            val response = try {
                client.messages().create(builder.build())
            } catch (e: AnthropicServiceException) {
                throw LlmException("Claude API error ${e.statusCode()}: ${e.message}", e)
            }
            usage += usageOf(response)
            val stop = response.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) return@withContext ChatReply("抱歉，這個問題我不方便回答。", used, usage)

            messages += response.toParam()
            val text = response.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("").trim()
            if (response.content().any { it.serverToolUse().isPresent } && "web_search" !in used) used += "web_search"

            // Server-side tools (web search) can hand the turn back mid-way; just ask it to continue.
            if (stop == StopReason.PAUSE_TURN) return@repeat
            if (stop != StopReason.TOOL_USE) return@withContext ChatReply(text, used, usage)

            val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
                used += use.name()
                val args = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    JSONObject(use._input().convert(Map::class.java) as Map<String, Any?>)
                }.getOrElse { JSONObject() }
                val r = executor.execute(ToolCall(use.id(), use.name(), args))
                val builder = ToolResultBlockParam.builder().toolUseId(use.id()).isError(r.isError)
                if (r.imageJpeg == null) {
                    builder.content(r.text)
                } else {
                    val image = ImageBlockParam.builder().source(
                        Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .data(Base64.encodeToString(r.imageJpeg, Base64.NO_WRAP))
                            .build(),
                    ).build()
                    builder.content(
                        ToolResultBlockParam.Content.ofBlocks(
                            listOf(
                                ToolResultBlockParam.Content.Block.ofImage(image),
                                ToolResultBlockParam.Content.Block.ofText(r.text),
                            ),
                        ),
                    )
                }
                ContentBlockParam.ofToolResult(builder.build())
            }
            messages += MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build()
        }
        // Round cap reached: one last call with no tools, so the model wraps up in its own voice
        // (progress so far, what is left) instead of a canned line.
        val closing = MessageCreateParams.builder()
            .model(model).maxTokens(maxTokens)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .systemOfTextBlockParams(systemBlocks(systemPrompt()))
            .messages(messages + MessageParam.builder().role(MessageParam.Role.USER)
                .content("（系統：這一輪的工具呼叫次數已達上限，先不要再呼叫工具。用你的口吻告訴使用者目前進度、還剩什麼，問要不要繼續。）").build())
            .build()
        val last = try { client.messages().create(closing) } catch (e: AnthropicServiceException) {
            throw LlmException("Claude API error ${e.statusCode()}: ${e.message}", e)
        }
        usage += usageOf(last)
        ChatReply(last.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("").trim(), used, usage)
    }

    private fun usageOf(m: Message): Usage {
        val u = m.usage()
        return Usage(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L))
    }

    /** Stable text first with the cache breakpoint; volatile text (memory, clock) after it, uncached. */
    private fun systemBlocks(sp: SystemPrompt): List<TextBlockParam> {
        val blocks = mutableListOf(
            TextBlockParam.builder().text(sp.stable).cacheControl(CacheControlEphemeral.builder().build()).build(),
        )
        if (sp.volatile.isNotBlank()) blocks += TextBlockParam.builder().text(sp.volatile).build()
        return blocks
    }

    private fun toSdkTool(spec: ToolSpec): Tool {
        val props = Tool.InputSchema.Properties.builder()
        val schema = spec.schemaProperties()
        schema.keys().forEach { key -> props.putAdditionalProperty(key, JsonValue.from(jsonToMap(schema.getJSONObject(key)))) }
        return Tool.builder()
            .name(spec.name)
            .description(spec.description)
            .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(spec.requiredNames()).build())
            .build()
    }

    private fun jsonToMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k ->
        when (val v = o.get(k)) {
            is JSONObject -> jsonToMap(v)
            is org.json.JSONArray -> List(v.length()) { v.get(it) }
            else -> v
        }
    }

    companion object { const val DEFAULT_MODEL = "claude-sonnet-5" }
}
