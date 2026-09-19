package com.andychang.clauderi.capabilities

import com.andychang.clauderi.data.CapabilityId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** What the user decided on an in-chat capability request. */
enum class RequestOutcome { GRANTED, GRANTED_SYSTEM_PENDING, DENIED, TIMEOUT }

data class CapabilityRequest(
    val capability: CapabilityId,
    val reason: String,
    internal val answer: CompletableDeferred<RequestOutcome> = CompletableDeferred(),
)

/**
 * Lets the model ask for a capability *inside the conversation*, the way a coding agent asks
 * before running a command. The tool call suspends until the user taps 允許 / 拒絕 on the card
 * the chat screen shows. The user, not the model, still flips the switch: the model only gets
 * to ask, and only when the setting "允許 AI 在對話中請求能力" is on.
 */
class PermissionBroker {

    private val _pending = MutableStateFlow<CapabilityRequest?>(null)
    val pending: StateFlow<CapabilityRequest?> = _pending.asStateFlow()

    /** Called from the tool executor; blocks the tool loop until the user answers (or 2 min). */
    suspend fun ask(capability: CapabilityId, reason: String): RequestOutcome {
        val req = CapabilityRequest(capability, reason)
        _pending.value = req
        return try {
            withTimeoutOrNull(120_000) { req.answer.await() } ?: RequestOutcome.TIMEOUT
        } finally {
            if (_pending.value === req) _pending.value = null
        }
    }

    /** Called from the UI once the user has answered and any system dialog has come back. */
    fun resolve(outcome: RequestOutcome) {
        _pending.value?.answer?.complete(outcome)
        _pending.value = null
    }
}
