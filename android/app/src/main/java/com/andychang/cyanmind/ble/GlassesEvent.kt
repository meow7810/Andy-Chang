package com.andychang.cyanmind.ble

/** Events pushed by the glasses over the BLE notify channel (opcode 0x73 "data update"). */
sealed class GlassesEvent {
    /** User said "Hey Cyan" (or pressed the AI button); the glasses' mic has been opened. */
    data object WakeWord : GlassesEvent()
    /** Battery report. */
    data class Battery(val percent: Int, val charging: Boolean) : GlassesEvent()
    /** User tapped to pause / stop the AI voice playback. */
    data object PausePlayback : GlassesEvent()
    /** Glasses asked the app to unbind. */
    data object Unbind : GlassesEvent()
    /** Glasses storage is nearly full. */
    data object StorageLow : GlassesEvent()
    /** Any event the app does not decode yet; raw payload kept for debugging. */
    data class Raw(val subType: Int, val payload: ByteArray) : GlassesEvent()
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, READY }

/**
 * AI playback state values understood by the glasses firmware.
 * They only drive the indicator LED / earcon on the glasses; audio itself goes over A2DP.
 * Mirrors QGAISpeakMode in the iOS QCSDK headers.
 */
object AiState {
    const val SPEAK_START = 0x01
    const val SPEAK_HOLD = 0x02
    const val SPEAK_STOP = 0x03
    const val THINKING_START = 0x04
    const val THINKING_HOLD = 0x05
    const val THINKING_STOP = 0x06
    const val NO_NETWORK = 0xF1
}

data class ScannedGlasses(val name: String, val address: String, val rssi: Int)
