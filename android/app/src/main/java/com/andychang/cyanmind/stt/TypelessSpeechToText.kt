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
 * Typeless External Transcript API, `POST {base}/v1/transcribe`.
 *
 * Wire format reverse-engineered from the official `typeless-sdk` Python package (0.1.0):
 *   header  Authorization: Token <key>
 *   form    model=<model>, language=<zh|en|...>, audio=<file>
 *   reply   { request_id, result: { transcript, detected_language, duration_seconds }, usage }
 *
 * A streaming WebSocket variant exists at `/v1/transcribe/stream` (pcm16, 16 kHz) and can be
 * added here later for lower latency.
 */
class TypelessSpeechToText(
    private val apiKey: String,
    private val model: String = "typeless-1.0-max",
    private val baseUrl: String = "https://api.typelessapi.com",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build(),
) : SpeechToText {

    override val id = "typeless"

    override suspend fun transcribe(wav: ByteArray, languageHint: String?): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .apply { languageHint?.let { addFormDataPart("language", it) } }
            .addFormDataPart("audio", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/transcribe")
            .header("Authorization", "Token $apiKey")
            .post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw SttException("Typeless STT HTTP ${resp.code}: $text")
            JSONObject(text).optJSONObject("result")?.optString("transcript")?.trim()
                ?: throw SttException("Typeless STT: missing result.transcript in $text")
        }
    }
}
