package com.andychang.clauderi.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build(),
) : ChatProvider {

    override suspend fun reply(
        systemPrompt: suspend () -> SystemPrompt, history: List<ChatTurn>, tools: suspend () -> List<ToolSpec>, executor: ToolExecutor,
    ): ChatReply = withContext(Dispatchers.IO) {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", ""))
        for (t in history) {
            messages.put(JSONObject().put("role", if (t.role == Role.USER) "user" else "assistant").put("content", t.text))
        }
        val used = mutableListOf<String>()

        repeat(MAX_TOOL_ROUNDS) {
            messages.getJSONObject(0).put("content", systemPrompt().full)
            val toolsJson = toolsJson(tools())
            val payload = JSONObject().put("model", model).put("messages", messages)
            if (toolsJson.length() > 0) payload.put("tools", toolsJson)
            val message = post(payload)
            messages.put(message)

            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val content = if (message.isNull("content")) "" else message.optString("content")
                return@withContext ChatReply(content.trim(), used)
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
        val last = post(JSONObject().put("model", model).put("messages", messages))
        val content = if (last.isNull("content")) "" else last.optString("content")
        ChatReply(content.trim(), used)
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

    private fun post(payload: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException("$id HTTP ${resp.code}: $text")
            return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-4o"
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        /** DashScope international endpoint; mainland-China accounts use dashscope.aliyuncs.com instead. */
        const val QWEN_BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    }
}
