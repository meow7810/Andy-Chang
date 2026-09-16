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
        systemPrompt: String, history: List<ChatTurn>, tools: List<ToolSpec>, executor: ToolExecutor,
    ): ChatReply = withContext(Dispatchers.IO) {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (t in history) {
            messages.put(JSONObject().put("role", if (t.role == Role.USER) "user" else "assistant").put("content", t.text))
        }
        val toolsJson = JSONArray().also { arr ->
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
        val used = mutableListOf<String>()

        repeat(MAX_TOOL_ROUNDS) {
            val payload = JSONObject().put("model", model).put("messages", messages)
            if (toolsJson.length() > 0) payload.put("tools", toolsJson)
            val message = post(payload)
            messages.put(message)

            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return@withContext ChatReply(message.optString("content").trim(), used)
            }
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
            }
        }
        ChatReply("（工具呼叫太多次，先停在這裡。）", used)
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
