package com.andychang.cyanmind.llm

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
 * Any "OpenAI-compatible" Chat Completions endpoint (`POST {baseUrl}/chat/completions`).
 * OpenAI, DeepSeek and Qwen (DashScope compatible mode) all speak this exact format,
 * so one class covers all three; only [baseUrl], [model] and [id] differ.
 */
class OpenAiChatProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = OPENAI_BASE_URL,
    override val id: String = "openai",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build(),
) : ChatProvider {

    override suspend fun reply(systemPrompt: String, history: List<ChatTurn>): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (t in history) {
            messages.put(JSONObject().put("role", if (t.role == Role.USER) "user" else "assistant").put("content", t.text))
        }
        val payload = JSONObject().put("model", model).put("messages", messages)
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw LlmException("OpenAI HTTP ${resp.code}: $text")
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content").trim()
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-4o"
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        const val DEEPSEEK_DEFAULT_MODEL = "deepseek-chat"
        /** DashScope international endpoint; mainland-China accounts use dashscope.aliyuncs.com instead. */
        const val QWEN_BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        const val QWEN_DEFAULT_MODEL = "qwen-plus"
    }
}
