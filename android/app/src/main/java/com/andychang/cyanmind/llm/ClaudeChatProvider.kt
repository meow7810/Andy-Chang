package com.andychang.cyanmind.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Claude via the official Anthropic Java SDK (works on Android with core-library desugaring).
 *
 * - The persona system prompt is cached (`cache_control`) so repeated turns are cheap.
 * - Adaptive thinking with `effort=low`: this is a spoken chat companion, not a coding agent;
 *   low effort keeps latency down. Bump to MEDIUM if answers feel shallow.
 * - `max_tokens` is deliberately small because the reply is read aloud by TTS.
 */
class ClaudeChatProvider(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val maxTokens: Long = 800,
) : ChatProvider {

    override val id = "claude"

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    override suspend fun reply(systemPrompt: String, history: List<ChatTurn>): String = withContext(Dispatchers.IO) {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                ),
            )
        for (turn in history) {
            builder.addMessage(
                MessageParam.builder()
                    .role(if (turn.role == Role.USER) MessageParam.Role.USER else MessageParam.Role.ASSISTANT)
                    .content(turn.text)
                    .build(),
            )
        }
        val response = try {
            client.messages().create(builder.build())
        } catch (e: AnthropicServiceException) {
            throw LlmException("Claude API error ${e.statusCode()}: ${e.message}", e)
        }
        if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
            return@withContext "抱歉，這個問題我不方便回答。"
        }
        response.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("")
            .trim()
    }

    companion object { const val DEFAULT_MODEL = "claude-opus-5" }
}
