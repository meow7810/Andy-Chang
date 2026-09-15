package com.andychang.cyanmind.llm

enum class Role { USER, ASSISTANT }

data class ChatTurn(val role: Role, val text: String)

/**
 * Pluggable LLM backend. Receives the persona system prompt plus as much history as the app
 * decided to send (the app, not the provider, owns memory) and returns the assistant's reply.
 */
interface ChatProvider {
    val id: String
    suspend fun reply(systemPrompt: String, history: List<ChatTurn>): String
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)
