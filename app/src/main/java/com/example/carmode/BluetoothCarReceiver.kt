package com.example.carmode

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver listening for Bluetooth ACL connection events to automatically trigger
 * Car Mode and apply vehicle profiles when paired cars connect/disconnect.
 */
class BluetoothCarReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BluetoothCarReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action ?: return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        if (device == null) return

        val carManager = CarModeManager.getInstance(context)

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val deviceName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                Log.d(TAG, "Bluetooth ACL connected: ${device.address}")
                carManager.onBluetoothDeviceConnected(device, deviceName)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.d(TAG, "Bluetooth ACL disconnected: ${device.address}")
                carManager.onBluetoothDeviceDisconnected(device)
            }
        }
    }
}
