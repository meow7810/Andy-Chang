package com.andychang.cyanmind

import android.app.Application
import com.andychang.cyanmind.assistant.AssistantEngine
import com.andychang.cyanmind.ble.GlassesManager
import com.andychang.cyanmind.data.ConversationStore
import com.andychang.cyanmind.data.Settings

/**
 * Application entry point. Wires the singletons together:
 *
 *  GlassesManager  – BLE control channel to the glasses (wake events, AI state LED, heartbeat)
 *  Settings        – API keys / provider selection / persona (DataStore)
 *  ConversationStore – on-device chat history (unlimited, file-backed)
 *  AssistantEngine – the pipeline: mic -> STT -> LLM -> TTS -> glasses state
 */
class CyanApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var conversation: ConversationStore
        private set
    lateinit var glasses: GlassesManager
        private set
    lateinit var assistant: AssistantEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        conversation = ConversationStore(this)
        glasses = GlassesManager(this)
        assistant = AssistantEngine(this, settings, conversation, glasses)
        assistant.start()
    }

    companion object {
        lateinit var instance: CyanApp
            private set
    }
}
