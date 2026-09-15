package com.andychang.cyanmind.stt

/**
 * Pluggable speech-to-text backend.
 * Implementations receive a complete WAV (16 kHz mono PCM16) and return the transcript.
 */
interface SpeechToText {
    val id: String
    suspend fun transcribe(wav: ByteArray, languageHint: String?): String
}

class SttException(message: String, cause: Throwable? = null) : Exception(message, cause)
