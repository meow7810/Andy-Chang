package com.andychang.clauderi.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.batches.BatchCreateParams
import com.anthropic.models.messages.batches.MessageBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The model call behind memory compaction. Kept separate from [ChatProvider] because it has
 * different economics: nobody is waiting for it, so it can use a cheaper model and, on Claude,
 * the Batch API (half price, results within 24 h).
 */
interface MemorySummarizer {
    /** Synchronous summary, full price. */
    suspend fun summarize(prompt: String): String
    /** Submit for asynchronous processing; returns a job id, or null if this backend has no batch mode. */
    suspend fun submit(prompt: String): String? = null
    /** Poll a job: the summary once finished, null while still running. Throws if the job failed or is unsupported here. */
    suspend fun poll(jobId: String): String? = throw LlmException("this backend has no batch jobs; dropping $jobId")
}

/** Any chat backend can summarise directly (no batch). */
class DirectSummarizer(private val provider: ChatProvider) : MemorySummarizer {
    override suspend fun summarize(prompt: String): String = provider.reply(
        systemPrompt = { SystemPrompt(SYSTEM, "") },
        history = listOf(ChatTurn(Role.USER, prompt)),
        tools = { emptyList() },
        executor = ToolExecutor { ToolResult("no tools", isError = true) },
    ).text

    companion object { const val SYSTEM = "你是精確、簡潔的記憶整理員。" }
}

/**
 * Claude with a cheap model and optional Batch API. Batch requests are billed at 50% and usually
 * finish in minutes, worst case 24 h; the app polls on later turns.
 */
class ClaudeMemorySummarizer(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val useBatch: Boolean = true,
) : MemorySummarizer {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    override suspend fun summarize(prompt: String): String = withContext(Dispatchers.IO) {
        val resp = client.messages().create(
            com.anthropic.models.messages.MessageCreateParams.builder()
                .model(model).maxTokens(2048L).system(DirectSummarizer.SYSTEM).addUserMessage(prompt).build(),
        )
        resp.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
    }

    override suspend fun submit(prompt: String): String? {
        if (!useBatch) return null
        return withContext(Dispatchers.IO) {
            val params = BatchCreateParams.builder().addRequest(
                BatchCreateParams.Request.builder()
                    .customId(CUSTOM_ID)
                    .params(
                        BatchCreateParams.Request.Params.builder()
                            .model(model).maxTokens(2048L).system(DirectSummarizer.SYSTEM).addUserMessage(prompt).build(),
                    )
                    .build(),
            ).build()
            client.messages().batches().create(params).id()
        }
    }

    override suspend fun poll(jobId: String): String? = withContext(Dispatchers.IO) {
        val batch = client.messages().batches().retrieve(jobId)
        if (batch.processingStatus() != MessageBatch.ProcessingStatus.ENDED) return@withContext null
        client.messages().batches().resultsStreaming(jobId).use { stream ->
            val item = stream.stream().filter { it.customId() == CUSTOM_ID }.findFirst().orElse(null)
                ?: throw LlmException("batch $jobId ended without our result")
            val ok = item.result().succeeded().orElse(null)
                ?: throw LlmException("batch $jobId request did not succeed")
            ok.message().content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-4-5"
        private const val CUSTOM_ID = "memory"
    }
}
