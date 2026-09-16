package com.andychang.cyanmind.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI `POST /v1/audio/transcriptions`.
 * Default model `gpt-4o-transcribe`; `whisper-1` also works if you prefer it.
 */
class OpenAiSpeechToText(
    private val apiKey: String,
    private val model: String = "gpt-4o-transcribe",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
) : SpeechToText {

    override val id = "openai"

    private companion object {
        const val ZH_PROMPT = "以下是使用者對 AI 眼鏡助理說的話，台灣繁體中文口語，可能夾雜英文單字。"
        const val GENERIC_PROMPT = "Short spoken commands and questions to a voice assistant."
    }

    override suspend fun transcribe(wav: ByteArray, languageHint: String?): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply { languageHint?.let { addFormDataPart("language", it) } }
            // Biases the model toward Taiwanese Traditional Chinese and away from guessing a
            // random language when the first SCO frames are silence/noise.
            .addFormDataPart("prompt", if (languageHint == "zh") ZH_PROMPT else GENERIC_PROMPT)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw SttException("OpenAI STT HTTP ${resp.code}: $text")
            JSONObject(text).optString("text").trim()
        }
    }
}
