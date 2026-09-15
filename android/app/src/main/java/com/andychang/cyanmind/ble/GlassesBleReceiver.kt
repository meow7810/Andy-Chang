package com.andychang.cyanmind.ble

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.LargeDataHandler

/**
 * Receives the SDK's internal GATT lifecycle broadcasts (sent through LocalBroadcastManager)
 * and forwards them to [GlassesManager].
 */
class GlassesBleReceiver(private val manager: GlassesManager) : QCBluetoothCallbackCloneReceiver() {

    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        Log.i(TAG, "connectStatue device=${device?.address} connected=$connected")
        if (device != null && connected) {
            runCatching { device.name }.getOrNull()?.let { DeviceManager.getInstance().deviceName = it }
            manager.onGattConnected(device)
        } else {
            manager.onGattDisconnected()
        }
    }

    override fun onServiceDiscovered() {
        Log.i(TAG, "onServiceDiscovered -> SDK ready")
        // Per the vendor sample: nothing may be sent before this callback.
        LargeDataHandler.getInstance().initEnable()
        BleOperateManager.getInstance().isReady = true
        manager.onServicesReady()
    }

    override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {
        // The SDK parses these itself and dispatches via LargeDataHandler listeners.
    }

    override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {
        if (uuid != null && data != null) manager.onDeviceInfoRead(uuid, String(data, Charsets.UTF_8))
    }

    companion object { private const val TAG = "GlassesBleReceiver" }
}
