package com.andychang.clauderi.stt

/** Pluggable speech-to-text. Receives a complete WAV (16 kHz mono PCM16) and returns the transcript. */
interface SpeechToText {
    val id: String
    suspend fun transcribe(wav: ByteArray, languageHint: String?): String
}

class SttException(message: String, cause: Throwable? = null) : Exception(message, cause)
