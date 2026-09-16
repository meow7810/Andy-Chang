package com.andychang.clauderi

import android.app.Application
import com.andychang.clauderi.assistant.AssistantEngine
import com.andychang.clauderi.capabilities.CapabilityRegistry
import com.andychang.clauderi.capabilities.PermissionBroker
import com.andychang.clauderi.data.ConversationStore
import com.andychang.clauderi.data.MemoryStore
import com.andychang.clauderi.data.Settings

class ClaudeRiApp : Application() {

    lateinit var settings: Settings; private set
    lateinit var conversation: ConversationStore; private set
    lateinit var memory: MemoryStore; private set
    lateinit var capabilities: CapabilityRegistry; private set
    lateinit var assistant: AssistantEngine; private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        conversation = ConversationStore(this)
        memory = MemoryStore(this)
        capabilities = CapabilityRegistry(this, settings, PermissionBroker(), memory)
        assistant = AssistantEngine(this, settings, conversation, memory, capabilities)
        assistant.start()
    }
}
