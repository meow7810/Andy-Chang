package com.andychang.clauderi.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Any OpenAI-compatible Chat Completions endpoint with function calling
 * (`POST {baseUrl}/chat/completions`). OpenAI, DeepSeek and Qwen (DashScope compatible mode)
 * all speak this format, so one class covers all three.
 */
class OpenAiChatProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = OPENAI_BASE_URL,
    override val id: String = "openai",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
) : ChatProvider {

    override suspend fun reply(
        systemPrompt: suspend () -> SystemPrompt, history: List<ChatTurn>, tools: suspend () -> List<ToolSpec>, executor: ToolExecutor,
    ): ChatReply = withContext(Dispatchers.IO) {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", ""))
        for (t in history) {
            messages.put(JSONObject().put("role", if (t.role == Role.USER) "user" else "assistant").put("content", t.text))
        }
        val used = mutableListOf<String>()
        var usage = Usage.ZERO

        repeat(MAX_TOOL_ROUNDS) {
            messages.getJSONObject(0).put("content", systemPrompt().full)
            val toolsJson = toolsJson(tools().filter { !it.server })
            val payload = JSONObject().put("model", model).put("messages", messages)
            if (toolsJson.length() > 0) payload.put("tools", toolsJson)
            val full = post(payload)
            usage += usageOf(full)
            val message = full.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            messages.put(message)

            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val content = if (message.isNull("content")) "" else message.optString("content")
                return@withContext ChatReply(content.trim(), used, usage)
            }
            val imageFollowUps = mutableListOf<JSONObject>()
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val fn = c.getJSONObject("function")
                val name = fn.getString("name")
                used += name
                val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrElse { JSONObject() }
                val r = executor.execute(ToolCall(c.getString("id"), name, args))
                messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", c.getString("id"))
                        .put("content", if (r.isError) "ERROR: ${r.text}" else r.text),
                )
                r.imageJpeg?.let { jpeg ->
                    // OpenAI-style tool messages cannot carry images; send it as a user turn after all tool results.
                    val dataUrl = "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
                    imageFollowUps += JSONObject().put("role", "user").put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "text").put("text", "（這是剛拍的照片）"))
                            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl))),
                    )
                }
            }
            imageFollowUps.forEach { messages.put(it) }
        }
        // Round cap reached: one last call without tools so the model wraps up in its own voice.
        messages.put(JSONObject().put("role", "user").put("content", "（系統：這一輪的工具呼叫次數已達上限，先不要再呼叫工具。用你的口吻告訴使用者目前進度、還剩什麼，問要不要繼續。）"))
        val lastFull = post(JSONObject().put("model", model).put("messages", messages))
        usage += usageOf(lastFull)
        val last = lastFull.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val content = if (last.isNull("content")) "" else last.optString("content")
        ChatReply(content.trim(), used, usage)
    }

    /** OpenAI-style usage: prompt_tokens includes cached tokens, so split them out. */
    private fun usageOf(full: JSONObject): Usage {
        val u = full.optJSONObject("usage") ?: return Usage.ZERO
        val prompt = u.optLong("prompt_tokens")
        val cached = u.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens") ?: 0L
        return Usage((prompt - cached).coerceAtLeast(0), u.optLong("completion_tokens"), cached, 0)
    }

    private fun toolsJson(tools: List<ToolSpec>): JSONArray = JSONArray().also { arr ->
        tools.forEach { spec ->
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", spec.name).put("description", spec.description).put(
                        "parameters",
                        JSONObject().put("type", "object")
                            .put("properties", spec.schemaProperties())
                            .put("required", JSONArray(spec.requiredNames())),
                    ),
                ),
            )
        }
    }

    /**
     * One request with the same retry policy the Claude SDK applies: overload and rate-limit
     * answers (429, 500, 502, 503, 504) and connection failures are retried a few times with
     * backoff; anything else (401, 404, 400) is reported at once.
     */
    private suspend fun post(payload: JSONObject): JSONObject {
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        var last: LlmException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            coroutineContext.ensureActive()   // a cancelled or timed-out turn stops here, not after four more tries
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            try {
                val (code, text) = client.newCall(req).await()
                if (code in 200..299) return JSONObject(text)
                val e = LlmException("$id HTTP $code: ${text.take(600)}")
                if (code !in RETRYABLE) throw e
                last = e
            } catch (e: java.io.IOException) {
                last = LlmException("$id 連線失敗：${e.message}", e)
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(BACKOFF_MS shl attempt)
        }
        throw last ?: LlmException("$id 沒有回應")
    }

    /**
     * Cancellable HTTP: the blocking execute() cannot be interrupted, so a turn that hit its
     * ceiling kept the socket (and the "思考中" spinner) alive until the read timeout. Enqueue
     * instead, and cancel the call when the coroutine is cancelled.
     */
    private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use { r -> runCatching { r.code to r.body?.string().orEmpty() }.fold({ cont.resume(it) }, { cont.resumeWithException(it) }) }
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val BACKOFF_MS = 1500L
        private val RETRYABLE = setOf(429, 500, 502, 503, 504)
        const val DEFAULT_MODEL = "gpt-4o"
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        /** DashScope international endpoint; mainland-China accounts use dashscope.aliyuncs.com instead. */
        const val QWEN_BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        /** Gemini's OpenAI-compatible endpoint; keys from AI Studio, billed to the key's Cloud project. */
        const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    }
}
