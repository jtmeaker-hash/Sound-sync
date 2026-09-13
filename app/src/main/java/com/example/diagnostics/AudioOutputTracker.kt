package com.example.diagnostics

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks live audio routing, Bluetooth state, connected peripherals,
 * and audio interruption/focus events for Developer Diagnostics.
 */
class AudioOutputTracker private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AudioOutputTracker"

        @Volatile
        private var instance: AudioOutputTracker? = null

        fun getInstance(context: Context): AudioOutputTracker {
            return instance ?: synchronized(this) {
                instance ?: AudioOutputTracker(context.applicationContext).also {
                    it.initialize()
                    instance = it
                }
            }
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val bluetoothAdapter = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (_: Exception) {
        null
    }

    private var lastConnectTime: Long? = null
    private var lastConnectDevice: String? = null
    private var lastDisconnectTime: Long? = null
    private var lastDisconnectDevice: String? = null
    private var autoPauseFired: Boolean = false
    private var lastAudioFocusTime: Long? = null
    private var lastAudioFocusEvent: String? = null
    private var lastNoisyTime: Long? = null

    private val _diagnosticsFlow = MutableStateFlow(buildSnapshot())
    val diagnosticsFlow: StateFlow<AudioOutputDiagnosticsSnapshot> = _diagnosticsFlow.asStateFlow()

    private var isInitialized = false

    private fun initialize() {
        if (isInitialized) return
        isInitialized = true

        // Register AudioDeviceCallback on Android 23+ (Marshmallow+)
        try {
            audioManager?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    val deviceNames = addedDevices?.map { it.productName?.toString() ?: getDeviceTypeName(it.type) } ?: emptyList()
                    if (deviceNames.isNotEmpty()) {
                        recordConnect(deviceNames.joinToString(", "))
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    val deviceNames = removedDevices?.map { it.productName?.toString() ?: getDeviceTypeName(it.type) } ?: emptyList()
                    if (deviceNames.isNotEmpty()) {
                        recordDisconnect(deviceNames.joinToString(", "))
                    }
                }
            }, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register AudioDeviceCallback", e)
        }

        // Register broadcast receiver for ACTION_AUDIO_BECOMING_NOISY
        try {
            val noisyReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                        recordNoisyEvent()
                    }
                }
            }
            context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register noisy receiver", e)
        }

        refresh()
    }

    fun refresh() {
        _diagnosticsFlow.value = buildSnapshot()
    }

    fun recordConnect(deviceName: String) {
        lastConnectTime = System.currentTimeMillis()
        lastConnectDevice = deviceName
        DiagnosticLogger.getInstance().info(
            DiagnosticSubsystem.AUDIO_OUTPUT,
            "AUDIO_DEVICE_CONNECTED",
            "Audio output device connected: $deviceName"
        )
        refresh()
    }

    fun recordDisconnect(deviceName: String, didAutoPause: Boolean = true) {
        lastDisconnectTime = System.currentTimeMillis()
        lastDisconnectDevice = deviceName
        autoPauseFired = didAutoPause
        DiagnosticLogger.getInstance().info(
            DiagnosticSubsystem.AUDIO_OUTPUT,
            "AUDIO_DEVICE_DISCONNECTED",
            "Audio output device disconnected: $deviceName (auto-pause=$didAutoPause)"
        )
        if (didAutoPause) {
            runCatching {
                com.example.audio.DjAudioEngine.getInstance(context).pause()
            }
        }
        refresh()
    }

    fun recordAudioFocusEvent(focusEventName: String) {
        lastAudioFocusTime = System.currentTimeMillis()
        lastAudioFocusEvent = focusEventName
        DiagnosticLogger.getInstance().info(
            DiagnosticSubsystem.AUDIO_OUTPUT,
            "AUDIO_FOCUS_CHANGE",
            "Audio focus changed: $focusEventName"
        )
        refresh()
    }

    fun recordNoisyEvent() {
        lastNoisyTime = System.currentTimeMillis()
        autoPauseFired = true
        DiagnosticLogger.getInstance().warn(
            DiagnosticSubsystem.AUDIO_OUTPUT,
            "BECOMING_NOISY",
            "Audio becoming noisy intent received: head/earphones disconnected"
        )
        runCatching {
            com.example.audio.DjAudioEngine.getInstance(context).pause()
        }
        refresh()
    }

    fun buildSnapshot(): AudioOutputDiagnosticsSnapshot {
        val btState = when {
            bluetoothAdapter == null -> "UNAVAILABLE"
            bluetoothAdapter.isEnabled -> "ENABLED"
            else -> "DISABLED"
        }

        val devices = mutableListOf<String>()
        var activeRoute = "BUILTIN_SPEAKER"

        if (audioManager != null) {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (info in outputs) {
                val typeName = getDeviceTypeName(info.type)
                val label = info.productName?.takeIf { it.isNotBlank() }?.let { "$it ($typeName)" } ?: typeName
                devices.add(label)

                when (info.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                        activeRoute = "BLUETOOTH ($label)"
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                        if (!activeRoute.startsWith("BLUETOOTH")) {
                            activeRoute = "WIRED_HEADPHONES"
                        }
                    }
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> {
                        if (!activeRoute.startsWith("BLUETOOTH")) {
                            activeRoute = "USB_AUDIO ($label)"
                        }
                    }
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
                        if (activeRoute == "BUILTIN_SPEAKER") {
                            activeRoute = "BUILTIN_SPEAKER"
                        }
                    }
                }
            }
        }

        val dateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

        val lastConnectStr = if (lastConnectTime != null) {
            "${dateFormat.format(Date(lastConnectTime!!))} · $lastConnectDevice"
        } else "None recorded"

        val lastDisconnectStr = if (lastDisconnectTime != null) {
            "${dateFormat.format(Date(lastDisconnectTime!!))} · $lastDisconnectDevice"
        } else "None recorded"

        val lastFocusStr = if (lastAudioFocusTime != null) {
            "${dateFormat.format(Date(lastAudioFocusTime!!))} · $lastAudioFocusEvent"
        } else "None recorded"

        val lastNoisyStr = if (lastNoisyTime != null) {
            dateFormat.format(Date(lastNoisyTime!!))
        } else "None"

        return AudioOutputDiagnosticsSnapshot(
            bluetoothState = btState,
            connectedDevices = if (devices.isEmpty()) listOf("Built-in Audio") else devices.distinct(),
            activeRoute = activeRoute,
            lastConnectEvent = lastConnectStr,
            lastDisconnectEvent = lastDisconnectStr,
            autoPauseOnDisconnectFired = autoPauseFired,
            lastAudioFocusEvent = lastFocusStr,
            lastNoisyEvent = lastNoisyStr
        )
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Out"
            AudioDeviceInfo.TYPE_AUX_LINE -> "AUX Line"
            else -> "Audio Device (type $type)"
        }
    }
}
