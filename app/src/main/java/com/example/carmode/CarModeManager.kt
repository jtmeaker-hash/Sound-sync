package com.example.carmode

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.audio.DjAudioEngine
import com.example.data.AppDatabase
import com.example.data.PlaylistEntity
import com.example.data.PlaylistTrackEntity
import com.example.model.Playlist
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Authoritative controller for SoundSync Car Mode and connected vehicle management.
 */
class CarModeManager private constructor(
    private val context: Context
) {

    private val prefs: SharedPreferences = context.getSharedPreferences("soundsync_carmode_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = AppDatabase.getDatabase(context)

    private var audioEngineRef: DjAudioEngine? = null

    // Backup of user's normal non-car audio settings to restore upon car disconnect
    private var preCarEqEnabled: Boolean = true
    private var preCarEqLow: Float = 1.0f
    private var preCarEqMid: Float = 1.0f
    private var preCarEqHigh: Float = 1.0f
    private var preCarHaasEnabled: Boolean = false
    private var preCarHaasAmount: Float = 0.5f
    private var preCarHaasDelayMs: Float = 18f
    private var preCarCrossfadeSec: Int = 4

    private val _isCarModeActive = MutableStateFlow(false)
    val isCarModeActive: StateFlow<Boolean> = _isCarModeActive.asStateFlow()

    private val _currentCarDevice = MutableStateFlow<String?>(null)
    val currentCarDevice: StateFlow<String?> = _currentCarDevice.asStateFlow()

    private val _currentProfile = MutableStateFlow<CarAudioProfile?>(null)
    val currentProfile: StateFlow<CarAudioProfile?> = _currentProfile.asStateFlow()

    private val _configuredCarAddresses = MutableStateFlow<Set<String>>(emptySet())
    val configuredCarAddresses: StateFlow<Set<String>> = _configuredCarAddresses.asStateFlow()

    private val _configuredCarNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val configuredCarNames: StateFlow<Map<String, String>> = _configuredCarNames.asStateFlow()

    private val _keepScreenAwake = MutableStateFlow(prefs.getBoolean("carmode_keep_awake", true))
    val keepScreenAwake: StateFlow<Boolean> = _keepScreenAwake.asStateFlow()

    private val _isNightMode = MutableStateFlow(prefs.getBoolean("carmode_night_mode", false))
    val isNightMode: StateFlow<Boolean> = _isNightMode.asStateFlow()

    private val _displayMode = MutableStateFlow(CarDisplayMode.ARTWORK)
    val displayMode: StateFlow<CarDisplayMode> = _displayMode.asStateFlow()

    private val _smartDrivingShuffle = MutableStateFlow(prefs.getBoolean("carmode_smart_shuffle", true))
    val smartDrivingShuffle: StateFlow<Boolean> = _smartDrivingShuffle.asStateFlow()

    private val _activeSession = MutableStateFlow<DrivingSession?>(null)
    val activeSession: StateFlow<DrivingSession?> = _activeSession.asStateFlow()

    private val _pastSessions = MutableStateFlow<List<DrivingSession>>(emptyList())
    val pastSessions: StateFlow<List<DrivingSession>> = _pastSessions.asStateFlow()

    init {
        loadConfiguredCars()
        loadPastSessions()
    }

    fun attachAudioEngine(engine: DjAudioEngine) {
        this.audioEngineRef = engine
    }

    fun setKeepScreenAwake(enabled: Boolean) {
        _keepScreenAwake.value = enabled
        prefs.edit().putBoolean("carmode_keep_awake", enabled).apply()
    }

    fun setNightMode(enabled: Boolean) {
        _isNightMode.value = enabled
        prefs.edit().putBoolean("carmode_night_mode", enabled).apply()
    }

    fun setDisplayMode(mode: CarDisplayMode) {
        _displayMode.value = mode
    }

    fun setSmartDrivingShuffle(enabled: Boolean) {
        _smartDrivingShuffle.value = enabled
        prefs.edit().putBoolean("carmode_smart_shuffle", enabled).apply()
    }

    fun enterCarMode(carName: String = "Vehicle", carAddress: String? = null, manual: Boolean = true) {
        if (_isCarModeActive.value) return

        val engine = audioEngineRef
        if (engine != null) {
            preCarEqEnabled = engine.eqEnabled.value
            preCarEqLow = engine.eqLow.value
            preCarEqMid = engine.eqMid.value
            preCarEqHigh = engine.eqHigh.value
            preCarHaasEnabled = engine.haasEnabled.value
            preCarHaasAmount = engine.haasAmount.value
            preCarHaasDelayMs = engine.haasDelayMs.value
            preCarCrossfadeSec = engine.crossfadeSeconds.value
        }

        val profile = if (carAddress != null) getCarProfile(carAddress) ?: CarAudioProfile(carAddress, carName) else null
        _currentProfile.value = profile
        _currentCarDevice.value = carName

        if (profile != null) {
            applyAudioProfile(profile)
            _displayMode.value = profile.preferredDisplayMode
        }

        _isCarModeActive.value = true

        // Start new driving session
        _activeSession.value = DrivingSession(
            carName = carName,
            startedAt = System.currentTimeMillis()
        )
        Log.d(TAG, "Entered Car Mode with vehicle '$carName'.")
    }

    fun exitCarMode() {
        if (!_isCarModeActive.value) return

        restoreAudioSettings()
        endDrivingSession()

        _isCarModeActive.value = false
        _currentCarDevice.value = null
        _currentProfile.value = null
        Log.d(TAG, "Exited Car Mode.")
    }

    /**
     * Called when a Bluetooth ACL connection event occurs.
     */
    fun onBluetoothDeviceConnected(device: BluetoothDevice, deviceName: String?) {
        val address = device.address ?: return
        val name = deviceName ?: device.name ?: "Bluetooth Device"

        if (_configuredCarAddresses.value.contains(address)) {
            Log.d(TAG, "Connected to configured Car Bluetooth: $name ($address)")
            val profile = getCarProfile(address) ?: CarAudioProfile(address, name)
            if (profile.autoLaunch) {
                enterCarMode(carName = name, carAddress = address)
                if (profile.resumeOnConnect) {
                    audioEngineRef?.play()
                }
            }
        }
    }

    /**
     * Called when a Bluetooth ACL disconnection event occurs.
     */
    fun onBluetoothDeviceDisconnected(device: BluetoothDevice) {
        val address = device.address ?: return
        if (_configuredCarAddresses.value.contains(address) || _isCarModeActive.value) {
            Log.d(TAG, "Disconnected from Car Bluetooth: $address")
            val profile = getCarProfile(address)
            if (profile?.pauseOnDisconnect != false) {
                audioEngineRef?.pause()
            }
            exitCarMode()
        }
    }

    fun addConfiguredCar(address: String, name: String) {
        val addresses = _configuredCarAddresses.value.toMutableSet().apply { add(address) }
        val names = _configuredCarNames.value.toMutableMap().apply { put(address, name) }
        _configuredCarAddresses.value = addresses
        _configuredCarNames.value = names
        saveConfiguredCars(addresses, names)

        // Save default profile if none exists
        if (getCarProfile(address) == null) {
            saveCarProfile(CarAudioProfile(address, name))
        }
    }

    fun removeConfiguredCar(address: String) {
        val addresses = _configuredCarAddresses.value.toMutableSet().apply { remove(address) }
        val names = _configuredCarNames.value.toMutableMap().apply { remove(address) }
        _configuredCarAddresses.value = addresses
        _configuredCarNames.value = names
        saveConfiguredCars(addresses, names)
    }

    fun saveCarProfile(profile: CarAudioProfile) {
        val key = "car_profile_${profile.deviceAddress.replace(":", "_")}"
        val json = JSONObject().apply {
            put("deviceAddress", profile.deviceAddress)
            put("deviceName", profile.deviceName)
            put("eqPreset", profile.eqPreset)
            put("customEqLow", profile.customEqLow.toDouble())
            put("customEqMid", profile.customEqMid.toDouble())
            put("customEqHigh", profile.customEqHigh.toDouble())
            put("haasEnabled", profile.haasEnabled)
            put("haasAmount", profile.haasAmount.toDouble())
            put("haasDelayMs", profile.haasDelayMs.toDouble())
            put("crossfadeDurationSec", profile.crossfadeDurationSec)
            put("replayGainEnabled", profile.replayGainEnabled)
            put("preferredDisplayMode", profile.preferredDisplayMode.name)
            put("autoLaunch", profile.autoLaunch)
            put("resumeOnConnect", profile.resumeOnConnect)
            put("pauseOnDisconnect", profile.pauseOnDisconnect)
        }
        prefs.edit().putString(key, json.toString()).apply()

        if (_currentProfile.value?.deviceAddress == profile.deviceAddress) {
            _currentProfile.value = profile
            if (_isCarModeActive.value) {
                applyAudioProfile(profile)
            }
        }
    }

    fun getCarProfile(deviceAddress: String): CarAudioProfile? {
        val key = "car_profile_${deviceAddress.replace(":", "_")}"
        val jsonStr = prefs.getString(key, null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            CarAudioProfile(
                deviceAddress = json.getString("deviceAddress"),
                deviceName = json.optString("deviceName", "Car"),
                eqPreset = json.optString("eqPreset", "Car Flat"),
                customEqLow = json.optDouble("customEqLow", 1.0).toFloat(),
                customEqMid = json.optDouble("customEqMid", 1.0).toFloat(),
                customEqHigh = json.optDouble("customEqHigh", 1.0).toFloat(),
                haasEnabled = json.optBoolean("haasEnabled", false),
                haasAmount = json.optDouble("haasAmount", 0.5).toFloat(),
                haasDelayMs = json.optDouble("haasDelayMs", 18.0).toFloat(),
                crossfadeDurationSec = json.optInt("crossfadeDurationSec", 4),
                replayGainEnabled = json.optBoolean("replayGainEnabled", true),
                preferredDisplayMode = try {
                    CarDisplayMode.valueOf(json.optString("preferredDisplayMode", "ARTWORK"))
                } catch (_: Exception) { CarDisplayMode.ARTWORK },
                autoLaunch = json.optBoolean("autoLaunch", true),
                resumeOnConnect = json.optBoolean("resumeOnConnect", true),
                pauseOnDisconnect = json.optBoolean("pauseOnDisconnect", true)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun applyAudioProfile(profile: CarAudioProfile) {
        val engine = audioEngineRef ?: return
        when (profile.eqPreset) {
            "Bass Reduction" -> {
                engine.setEqEnabled(true)
                engine.setEq(low = 0.7f, mid = 1.0f, high = 1.1f)
            }
            "Road Noise Compensation" -> {
                engine.setEqEnabled(true)
                engine.setEq(low = 1.3f, mid = 0.95f, high = 1.25f)
            }
            "Custom Car EQ" -> {
                engine.setEqEnabled(true)
                engine.setEq(
                    low = profile.customEqLow,
                    mid = profile.customEqMid,
                    high = profile.customEqHigh
                )
            }
            else -> { // "Car Flat"
                engine.setEqEnabled(true)
                engine.setEq(low = 1.0f, mid = 1.0f, high = 1.0f)
            }
        }
        engine.setHaasEnabled(profile.haasEnabled)
        engine.setHaasAmount(profile.haasAmount)
        engine.setHaasDelayMs(profile.haasDelayMs)
        engine.setCrossfadeSeconds(profile.crossfadeDurationSec)
    }

    private fun restoreAudioSettings() {
        val engine = audioEngineRef ?: return
        engine.setEqEnabled(preCarEqEnabled)
        engine.setEq(low = preCarEqLow, mid = preCarEqMid, high = preCarEqHigh)
        engine.setHaasEnabled(preCarHaasEnabled)
        engine.setHaasAmount(preCarHaasAmount)
        engine.setHaasDelayMs(preCarHaasDelayMs)
        engine.setCrossfadeSeconds(preCarCrossfadeSec)
    }

    fun recordTrackPlayedInSession(track: Track) {
        val session = _activeSession.value ?: return
        val titles = session.trackTitles.toMutableList().apply { add(track.title) }
        val artists = session.artistNames.toMutableList().apply { add(track.artist) }
        _activeSession.value = session.copy(
            tracksPlayedCount = session.tracksPlayedCount + 1,
            trackTitles = titles,
            artistNames = artists
        )
    }

    fun recordTrackSkippedInSession(track: Track) {
        val session = _activeSession.value ?: return
        _activeSession.value = session.copy(
            tracksSkippedCount = session.tracksSkippedCount + 1
        )
    }

    private fun endDrivingSession() {
        val session = _activeSession.value ?: return
        val ended = session.copy(
            endedAt = System.currentTimeMillis(),
            totalDurationMs = (System.currentTimeMillis() - session.startedAt).coerceAtLeast(0L)
        )
        _activeSession.value = null
        if (ended.tracksPlayedCount > 0 || ended.totalDurationMs > 30000L) {
            val list = _pastSessions.value.toMutableList().apply { add(0, ended) }
            _pastSessions.value = list
            savePastSessions(list)
        }
    }

    /**
     * "Save for Later" action: adds track to a dedicated "Car Finds" playlist.
     */
    fun saveTrackForLater(track: Track, onComplete: (String) -> Unit = {}) {
        scope.launch {
            try {
                val playlistDao = db.playlistDao()
                val allPlaylists = playlistDao.getAllPlaylistsSync()
                val carFinds = allPlaylists.firstOrNull { it.name.equals("Car Finds", ignoreCase = true) }
                    ?: run {
                        val newId = "playlist_car_finds_${System.currentTimeMillis()}"
                        val p = PlaylistEntity(
                            id = newId,
                            name = "Car Finds",
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            isRockboxCompatible = true,
                            isImported = false
                        )
                        playlistDao.insertPlaylist(p)
                        p
                    }

                val currentTracks = playlistDao.getTracksForPlaylistSync(carFinds.id)
                val alreadyAdded = currentTracks.any { it.trackId == track.id }
                if (!alreadyAdded) {
                    val position = currentTracks.size
                    playlistDao.insertPlaylistTracks(
                        listOf(
                            PlaylistTrackEntity(
                                playlistId = carFinds.id,
                                trackId = track.id,
                                position = position,
                                dateAdded = System.currentTimeMillis()
                            )
                        )
                    )
                    onComplete("Saved to 'Car Finds' playlist")
                } else {
                    onComplete("Already in 'Car Finds'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save track for later: ${e.message}")
                onComplete("Failed to save track")
            }
        }
    }

    private fun loadConfiguredCars() {
        val jsonStr = prefs.getString("configured_cars", null) ?: return
        try {
            val json = JSONObject(jsonStr)
            val addresses = mutableSetOf<String>()
            val names = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val addr = keys.next()
                val name = json.getString(addr)
                addresses.add(addr)
                names[addr] = name
            }
            _configuredCarAddresses.value = addresses
            _configuredCarNames.value = names
        } catch (_: Exception) {}
    }

    private fun saveConfiguredCars(addresses: Set<String>, names: Map<String, String>) {
        val json = JSONObject()
        addresses.forEach { addr ->
            json.put(addr, names[addr] ?: "Vehicle")
        }
        prefs.edit().putString("configured_cars", json.toString()).apply()
    }

    private fun loadPastSessions() {
        val jsonStr = prefs.getString("past_driving_sessions", null) ?: return
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<DrivingSession>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    DrivingSession(
                        id = obj.optString("id"),
                        carName = obj.optString("carName", "Car"),
                        startedAt = obj.optLong("startedAt"),
                        endedAt = obj.optLong("endedAt"),
                        totalDurationMs = obj.optLong("totalDurationMs"),
                        tracksPlayedCount = obj.optInt("tracksPlayedCount"),
                        tracksSkippedCount = obj.optInt("tracksSkippedCount")
                    )
                )
            }
            _pastSessions.value = list
        } catch (_: Exception) {}
    }

    private fun savePastSessions(list: List<DrivingSession>) {
        val arr = JSONArray()
        list.take(30).forEach { session ->
            val obj = JSONObject().apply {
                put("id", session.id)
                put("carName", session.carName)
                put("startedAt", session.startedAt)
                put("endedAt", session.endedAt)
                put("totalDurationMs", session.totalDurationMs)
                put("tracksPlayedCount", session.tracksPlayedCount)
                put("tracksSkippedCount", session.tracksSkippedCount)
            }
            arr.put(obj)
        }
        prefs.edit().putString("past_driving_sessions", arr.toString()).apply()
    }

    companion object {
        private const val TAG = "CarModeManager"

        @Volatile
        private var INSTANCE: CarModeManager? = null

        fun getInstance(context: Context): CarModeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CarModeManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
