package com.andychang.cyanmind.ble

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thin, coroutine-friendly wrapper around the vendor QCSDK Android AAR.
 *
 * Responsibilities:
 *  - scan / connect / reconnect to the glasses over BLE
 *  - surface firmware events (wake word, battery, pause) as a [SharedFlow]
 *  - push AI status back so the glasses' LED / prompt tones match what the phone is doing
 *  - keep the AI heartbeat alive while a voice session is running
 *
 * Voice audio does NOT go through here: the glasses expose a normal Bluetooth HFP microphone
 * and A2DP speaker, see [com.andychang.cyanmind.audio.HfpMicRecorder] and
 * [com.andychang.cyanmind.audio.Speaker].
 */
class GlassesManager(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery.asStateFlow()

    private val _firmware = MutableStateFlow<String?>(null)
    val firmware: StateFlow<String?> = _firmware.asStateFlow()

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<GlassesEvent> = _events.asSharedFlow()

    private val _scanResults = MutableStateFlow<List<ScannedGlasses>>(emptyList())
    val scanResults: StateFlow<List<ScannedGlasses>> = _scanResults.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var heartbeatJob: Job? = null
    private val receiver = GlassesBleReceiver(this)

    init {
        // Same bootstrap sequence as the vendor sample's MyApplication.
        LargeDataHandler.getInstance()
        BleOperateManager.getInstance(app)
        BleOperateManager.getInstance().setApplication(app)
        BleOperateManager.getInstance().init()
        BleBaseControl.getInstance(app).setmContext(app)
        LocalBroadcastManager.getInstance(app).registerReceiver(receiver, BleAction.getIntentFilter())
    }

    // ------------------------------------------------------------------ scanning / connecting

    fun startScan(context: Context) {
        if (_scanning.value) return
        _scanResults.value = emptyList()
        _scanning.value = true
        BleScannerHelper.getInstance().reSetCallback()
        BleScannerHelper.getInstance().scanDevice(context, null, object : ScanWrapperCallback {
            override fun onStart() {}
            override fun onStop() { _scanning.value = false }
            override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
                val name = runCatching { device?.name }.getOrNull() ?: return
                val address = device?.address ?: return
                _scanResults.update { list ->
                    if (list.any { it.address == address }) list
                    else (list + ScannedGlasses(name, address, rssi)).sortedByDescending { it.rssi }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
                _scanning.value = false
            }
            override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {}
            override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>?) {}
        })
        scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_scanning.value) stopScan(context)
        }
    }

    fun stopScan(context: Context) {
        runCatching { BleScannerHelper.getInstance().stopScan(context) }
        _scanning.value = false
    }

    fun connect(address: String, name: String? = null) {
        DeviceManager.getInstance().deviceAddress = address
        name?.let { DeviceManager.getInstance().deviceName = it }
        _deviceName.value = name
        _state.value = ConnectionState.CONNECTING
        BleOperateManager.getInstance().reConnectMac = address
        BleOperateManager.getInstance().connectDirectly(address)
    }

    fun reconnectLast() {
        val address = DeviceManager.getInstance().deviceAddress
        if (!address.isNullOrBlank()) connect(address, DeviceManager.getInstance().deviceName)
    }

    fun disconnect() {
        stopHeartbeat()
        runCatching { BleOperateManager.getInstance().disconnect() }
        _state.value = ConnectionState.DISCONNECTED
    }

    fun forget() {
        stopHeartbeat()
        runCatching { BleOperateManager.getInstance().unBindDevice() }
        DeviceManager.getInstance().reSet()
        _deviceName.value = null
        _state.value = ConnectionState.DISCONNECTED
    }

    val isReady: Boolean get() = _state.value == ConnectionState.READY

    // ------------------------------------------------------------------ callbacks from receiver

    internal fun onGattConnected(device: BluetoothDevice) {
        _deviceName.value = runCatching { device.name }.getOrNull() ?: _deviceName.value
        _state.value = ConnectionState.CONNECTED
    }

    internal fun onGattDisconnected() {
        stopHeartbeat()
        _state.value = ConnectionState.DISCONNECTED
    }

    internal fun onServicesReady() {
        _state.value = ConnectionState.READY
        val handler = LargeDataHandler.getInstance()
        handler.addOutDeviceListener(NOTIFY_LISTENER_ID, notifyListener)
        handler.syncTime { _, _ -> }
        handler.addBatteryCallBack("cyanmind") { _, rsp ->
            if (rsp != null) _battery.value = rsp.battery
        }
        handler.syncBattery()
        handler.syncDeviceInfo { _, rsp ->
            if (rsp != null) _firmware.value = "BT ${rsp.firmwareVersion} / WiFi ${rsp.wifiFirmwareVersion}"
        }
        // Make sure the on-device "Hey Cyan" wake-word detector is on so it reports to us.
        handler.aiVoiceWake(true, true) { _, rsp -> Log.i(TAG, "voice wake enabled=${rsp?.isOpen}") }
    }

    internal fun onDeviceInfoRead(uuid: String, value: String) {
        Log.d(TAG, "device info $uuid = $value")
    }

    // ------------------------------------------------------------------ firmware notifications

    /**
     * Decodes the 0x73 "data update" frames. Layout (from the vendor sample):
     *   loadData[6] = sub type, loadData[7..] = payload.
     */
    private val notifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val d = response.loadData ?: return
            if (d.size < 7) return
            val sub = d[6].toInt() and 0xFF
            val event: GlassesEvent = when (sub) {
                0x05 -> {
                    val pct = d.getOrNull(7)?.toInt()?.and(0xFF) ?: -1
                    _battery.value = pct
                    GlassesEvent.Battery(pct, (d.getOrNull(8)?.toInt() ?: 0) == 1)
                }
                0x03 -> if ((d.getOrNull(7)?.toInt() ?: 0) == 1) GlassesEvent.WakeWord else GlassesEvent.Raw(sub, d)
                0x0c -> GlassesEvent.PausePlayback
                0x0d -> GlassesEvent.Unbind
                0x0e -> GlassesEvent.StorageLow
                else -> GlassesEvent.Raw(sub, d)
            }
            Log.d(TAG, "glasses event: $event")
            _events.tryEmit(event)
        }
    }

    // ------------------------------------------------------------------ AI status back to glasses

    /** Tell the glasses what the assistant is doing so the LED / prompt tone matches. */
    fun setAiState(state: Int) {
        if (!isReady) return
        runCatching {
            LargeDataHandler.getInstance().aiVoicePlay(state) { _, rsp -> Log.d(TAG, "aiVoicePlay($state) -> ${rsp?.status}") }
        }.onFailure { Log.w(TAG, "aiVoicePlay failed", it) }
    }

    /**
     * The firmware expects a periodic AI heartbeat while a voice session is active,
     * otherwise it drops back to idle and may close the mic. Call [startHeartbeat] when a
     * session begins and [stopHeartbeat] when it ends.
     */
    fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive && isReady) {
                runCatching { LargeDataHandler.getInstance().syncHeartBeat(1) }
                delay(HEARTBEAT_MS)
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    companion object {
        private const val TAG = "GlassesManager"
        private const val NOTIFY_LISTENER_ID = 100
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val HEARTBEAT_MS = 5_000L
    }
}
