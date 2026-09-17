package com.andychang.clauderi

import android.app.Application
import com.andychang.clauderi.assistant.AssistantEngine
import com.andychang.clauderi.capabilities.CapabilityRegistry
import com.andychang.clauderi.capabilities.ListeningWatcher
import com.andychang.clauderi.capabilities.PermissionBroker
import com.andychang.clauderi.capabilities.PhotoWatcher
import com.andychang.clauderi.data.ConversationStore
import com.andychang.clauderi.data.ListeningLog
import com.andychang.clauderi.data.MemoryStore
import com.andychang.clauderi.data.Settings
import com.andychang.clauderi.data.Traditionalizer
import com.andychang.clauderi.data.UsageLedger

class ClaudeRiApp : Application() {

    lateinit var settings: Settings; private set
    lateinit var conversation: ConversationStore; private set
    lateinit var memory: MemoryStore; private set
    lateinit var listening: ListeningLog; private set
    lateinit var usage: UsageLedger; private set
    lateinit var capabilities: CapabilityRegistry; private set
    lateinit var assistant: AssistantEngine; private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        conversation = ConversationStore(this)
        memory = MemoryStore(this)
        listening = ListeningLog(this)
        usage = UsageLedger(this)
        ListeningWatcher.log = listening
        capabilities = CapabilityRegistry(this, settings, PermissionBroker(), memory, listening)
        assistant = AssistantEngine(this, settings, conversation, memory, capabilities, usage)
        assistant.start()
        PhotoWatcher.onPhoto = { jpeg, origin -> assistant.onPhoto(jpeg, origin) }
        PhotoWatcher.start(this)
        Thread { Traditionalizer.preload(this) }.start()   // ~1 MB of OpenCC tables, off the main thread
    }
}
