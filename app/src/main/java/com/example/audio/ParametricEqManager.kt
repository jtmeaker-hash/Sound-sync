package com.example.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages parametric EQ presets, persistence, and global active equalizer parameters.
 * Thread-safe with atomic version counter for lock-free audio thread polling.
 */
class ParametricEqManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "ParametricEqManager"
        private const val PRESETS_FILENAME = "parametric_eq_presets.json"

        @Volatile
        private var instance: ParametricEqManager? = null

        fun getInstance(context: Context): ParametricEqManager {
            return instance ?: synchronized(this) {
                instance ?: ParametricEqManager(context.applicationContext).also {
                    instance = it
                    it.restoreFromDisk()
                }
            }
        }

        val BUILT_IN_PRESETS: List<EqPreset> by lazy {
            listOf(
                EqPreset(
                    id = "preset_flat",
                    name = "Flat",
                    isBuiltIn = true,
                    preampDb = 0.0,
                    bands = ParametricEq.DEFAULT_8_BANDS.map { it.copy(gainDb = 0.0) }
                ),
                EqPreset(
                    id = "preset_club_bass",
                    name = "Club / Bass Boost",
                    isBuiltIn = true,
                    preampDb = -2.5,
                    bands = listOf(
                        EqBand(0, "Sub-Bass", EqFilterType.LOW_SHELF, 32.0, 5.5, 0.71, true),
                        EqBand(1, "Bass", EqFilterType.PEAKING, 64.0, 4.5, 1.0, true),
                        EqBand(2, "Low-Mid", EqFilterType.PEAKING, 160.0, 2.0, 1.2, true),
                        EqBand(3, "Mid", EqFilterType.PEAKING, 500.0, -1.0, 1.4, true),
                        EqBand(4, "High-Mid", EqFilterType.PEAKING, 1200.0, 0.5, 1.4, true),
                        EqBand(5, "Presence", EqFilterType.PEAKING, 3000.0, 2.0, 1.2, true),
                        EqBand(6, "Brilliance", EqFilterType.PEAKING, 8000.0, 3.5, 1.0, true),
                        EqBand(7, "Air", EqFilterType.HIGH_SHELF, 16000.0, 4.0, 0.71, true)
                    )
                ),
                EqPreset(
                    id = "preset_electronic",
                    name = "Electronic / Dance",
                    isBuiltIn = true,
                    preampDb = -2.0,
                    bands = listOf(
                        EqBand(0, "Sub-Bass", EqFilterType.LOW_SHELF, 32.0, 4.5, 0.71, true),
                        EqBand(1, "Bass", EqFilterType.PEAKING, 64.0, 3.5, 1.0, true),
                        EqBand(2, "Low-Mid", EqFilterType.PEAKING, 160.0, 1.0, 1.2, true),
                        EqBand(3, "Mid", EqFilterType.PEAKING, 500.0, 0.5, 1.4, true),
                        EqBand(4, "High-Mid", EqFilterType.PEAKING, 1200.0, 1.5, 1.4, true),
                        EqBand(5, "Presence", EqFilterType.PEAKING, 3000.0, 3.0, 1.2, true),
                        EqBand(6, "Brilliance", EqFilterType.PEAKING, 8000.0, 4.0, 1.0, true),
                        EqBand(7, "Air", EqFilterType.HIGH_SHELF, 16000.0, 4.5, 0.71, true)
                    )
                ),
                EqPreset(
                    id = "preset_hip_hop",
                    name = "Hip-Hop",
                    isBuiltIn = true,
                    preampDb = -3.0,
                    bands = listOf(
                        EqBand(0, "Sub-Bass", EqFilterType.LOW_SHELF, 32.0, 6.0, 0.71, true),
                        EqBand(1, "Bass", EqFilterType.PEAKING, 64.0, 5.0, 1.0, true),
                        EqBand(2, "Low-Mid", EqFilterType.PEAKING, 160.0, 2.0, 1.2, true),
                        EqBand(3, "Mid", EqFilterType.PEAKING, 500.0, -1.5, 1.4, true),
                        EqBand(4, "High-Mid", EqFilterType.PEAKING, 1200.0, 1.0, 1.4, true),
                        EqBand(5, "Presence", EqFilterType.PEAKING, 3000.0, 2.0, 1.2, true),
                        EqBand(6, "Brilliance", EqFilterType.PEAKING, 8000.0, 2.5, 1.0, true),
                        EqBand(7, "Air", EqFilterType.HIGH_SHELF, 16000.0, 3.0, 0.71, true)
                    )
                ),
                EqPreset(
                    id = "preset_vocal",
                    name = "Vocal Clarity",
                    isBuiltIn = true,
                    preampDb = -1.0,
                    bands = listOf(
                        EqBand(0, "Sub-Bass", EqFilterType.LOW_SHELF, 32.0, -4.0, 0.71, true),
                        EqBand(1, "Bass", EqFilterType.PEAKING, 64.0, -2.0, 1.0, true),
                        EqBand(2, "Low-Mid", EqFilterType.PEAKING, 160.0, -1.0, 1.2, true),
                        EqBand(3, "Mid", EqFilterType.PEAKING, 500.0, 1.5, 1.4, true),
                        EqBand(4, "High-Mid", EqFilterType.PEAKING, 1200.0, 3.5, 1.4, true),
                        EqBand(5, "Presence", EqFilterType.PEAKING, 3000.0, 4.0, 1.2, true),
                        EqBand(6, "Brilliance", EqFilterType.PEAKING, 8000.0, 2.5, 1.0, true),
                        EqBand(7, "Air", EqFilterType.HIGH_SHELF, 16000.0, 1.5, 0.71, true)
                    )
                ),
                EqPreset(
                    id = "preset_acoustic",
                    name = "Acoustic / Warm",
                    isBuiltIn = true,
                    preampDb = 0.0,
                    bands = listOf(
                        EqBand(0, "Sub-Bass", EqFilterType.LOW_SHELF, 32.0, 1.0, 0.71, true),
                        EqBand(1, "Bass", EqFilterType.PEAKING, 64.0, 2.0, 1.0, true),
                        EqBand(2, "Low-Mid", EqFilterType.PEAKING, 160.0, 2.5, 1.2, true),
                        EqBand(3, "Mid", EqFilterType.PEAKING, 500.0, 1.5, 1.4, true),
                        EqBand(4, "High-Mid", EqFilterType.PEAKING, 1200.0, 1.0, 1.4, true),
                        EqBand(5, "Presence", EqFilterType.PEAKING, 3000.0, 1.5, 1.2, true),
                        EqBand(6, "Brilliance", EqFilterType.PEAKING, 8000.0, 2.0, 1.0, true),
                        EqBand(7, "Air", EqFilterType.HIGH_SHELF, 16000.0, 2.5, 0.71, true)
                    )
                ),
                EqPreset(
                    id = "preset_rock",
                    name = "Rock / Punch",
                    isBuiltIn = true,
                    preampDb = -2.0,
                    bands = listOf(
                        EqBand(0, "Sub-Bass", EqFilterType.LOW_SHELF, 32.0, 3.0, 0.71, true),
                        EqBand(1, "Bass", EqFilterType.PEAKING, 64.0, 4.0, 1.0, true),
                        EqBand(2, "Low-Mid", EqFilterType.PEAKING, 160.0, 2.0, 1.2, true),
                        EqBand(3, "Mid", EqFilterType.PEAKING, 500.0, -1.0, 1.4, true),
                        EqBand(4, "High-Mid", EqFilterType.PEAKING, 1200.0, 1.0, 1.4, true),
                        EqBand(5, "Presence", EqFilterType.PEAKING, 3000.0, 3.0, 1.2, true),
                        EqBand(6, "Brilliance", EqFilterType.PEAKING, 8000.0, 4.0, 1.0, true),
                        EqBand(7, "Air", EqFilterType.HIGH_SHELF, 16000.0, 3.5, 0.71, true)
                    )
                ),
                EqPreset(
                    id = "preset_treble_air",
                    name = "Treble Air",
                    isBuiltIn = true,
                    preampDb = -1.5,
                    bands = listOf(
                        EqBand(0, "Sub-Bass", EqFilterType.LOW_SHELF, 32.0, 0.0, 0.71, true),
                        EqBand(1, "Bass", EqFilterType.PEAKING, 64.0, 0.0, 1.0, true),
                        EqBand(2, "Low-Mid", EqFilterType.PEAKING, 160.0, 0.0, 1.2, true),
                        EqBand(3, "Mid", EqFilterType.PEAKING, 500.0, 0.5, 1.4, true),
                        EqBand(4, "High-Mid", EqFilterType.PEAKING, 1200.0, 1.5, 1.4, true),
                        EqBand(5, "Presence", EqFilterType.PEAKING, 3000.0, 3.5, 1.2, true),
                        EqBand(6, "Brilliance", EqFilterType.PEAKING, 8000.0, 5.0, 1.0, true),
                        EqBand(7, "Air", EqFilterType.HIGH_SHELF, 16000.0, 6.5, 0.71, true)
                    )
                )
            )
        }
    }

    private val presetsFile = File(context.filesDir, PRESETS_FILENAME)

    // Version incremented on any parameter change to notify audio engine thread
    val version = AtomicInteger(1)

    private val _presets = MutableStateFlow<List<EqPreset>>(BUILT_IN_PRESETS)
    val presets: StateFlow<List<EqPreset>> = _presets.asStateFlow()

    private val _activePresetId = MutableStateFlow("preset_flat")
    val activePresetId: StateFlow<String> = _activePresetId.asStateFlow()

    private val _currentBands = MutableStateFlow<List<EqBand>>(ParametricEq.DEFAULT_8_BANDS.map { it.copy() })
    val currentBands: StateFlow<List<EqBand>> = _currentBands.asStateFlow()

    private val _preampDb = MutableStateFlow(0.0)
    val preampDb: StateFlow<Double> = _preampDb.asStateFlow()

    private val _isEqEnabled = MutableStateFlow(true)
    val isEqEnabled: StateFlow<Boolean> = _isEqEnabled.asStateFlow()

    private val _autoHeadroomEnabled = MutableStateFlow(false)
    val autoHeadroomEnabled: StateFlow<Boolean> = _autoHeadroomEnabled.asStateFlow()

    fun setEqEnabled(enabled: Boolean) {
        _isEqEnabled.value = enabled
        version.incrementAndGet()
        saveToDiskAsync()
    }

    fun setAutoHeadroomEnabled(enabled: Boolean) {
        _autoHeadroomEnabled.value = enabled
        version.incrementAndGet()
        saveToDiskAsync()
    }

    fun applyPreset(presetId: String) {
        val preset = _presets.value.find { it.id == presetId } ?: return
        _activePresetId.value = preset.id
        _preampDb.value = preset.preampDb
        _currentBands.value = preset.bands.map { it.copy() }
        version.incrementAndGet()
        saveToDiskAsync()
        Log.i(TAG, "Applied EQ preset: '${preset.name}'")
    }

    fun updateBand(index: Int, freqHz: Double, gainDb: Double, q: Double, isEnabled: Boolean, type: EqFilterType? = null) {
        val updated = _currentBands.value.toMutableList()
        if (index in updated.indices) {
            val b = updated[index].copy(
                frequencyHz = freqHz.coerceIn(ParametricEq.MIN_FREQ_HZ, ParametricEq.MAX_FREQ_HZ),
                gainDb = gainDb.coerceIn(ParametricEq.MIN_GAIN_DB, ParametricEq.MAX_GAIN_DB),
                q = q.coerceIn(ParametricEq.MIN_Q, ParametricEq.MAX_Q),
                isEnabled = isEnabled,
                type = type ?: updated[index].type
            )
            updated[index] = b
            _currentBands.value = updated
            _activePresetId.value = "custom_user_tweaked"
            version.incrementAndGet()
            saveToDiskAsync()
        }
    }

    fun updateBandType(index: Int, type: EqFilterType) {
        val updated = _currentBands.value.toMutableList()
        if (index in updated.indices) {
            updated[index] = updated[index].copy(type = type)
            _currentBands.value = updated
            _activePresetId.value = "custom_user_tweaked"
            version.incrementAndGet()
            saveToDiskAsync()
        }
    }

    fun resetBand(index: Int) {
        val updated = _currentBands.value.toMutableList()
        if (index in updated.indices) {
            val defaultBand = ParametricEq.DEFAULT_8_BANDS.getOrNull(index)
            updated[index] = updated[index].copy(
                gainDb = 0.0,
                isEnabled = true,
                q = defaultBand?.q ?: 1.0,
                frequencyHz = defaultBand?.frequencyHz ?: updated[index].frequencyHz,
                type = defaultBand?.type ?: updated[index].type
            )
            _currentBands.value = updated
            _activePresetId.value = "custom_user_tweaked"
            version.incrementAndGet()
            saveToDiskAsync()
        }
    }

    fun setPreamp(gainDb: Double) {
        _preampDb.value = gainDb.coerceIn(ParametricEq.MIN_GAIN_DB, ParametricEq.MAX_GAIN_DB)
        _activePresetId.value = "custom_user_tweaked"
        version.incrementAndGet()
        saveToDiskAsync()
    }

    fun resetToFlat() {
        applyPreset("preset_flat")
    }

    fun saveCustomPreset(name: String): EqPreset {
        val newId = "preset_custom_${UUID.randomUUID()}"
        val preset = EqPreset(
            id = newId,
            name = name.trim().ifBlank { "User Preset" },
            isBuiltIn = false,
            preampDb = _preampDb.value,
            bands = _currentBands.value.map { it.copy() }
        )
        val list = _presets.value.toMutableList()
        list.add(preset)
        _presets.value = list
        _activePresetId.value = newId
        version.incrementAndGet()
        saveToDiskAsync()
        Log.i(TAG, "Saved custom preset: '$name'")
        return preset
    }

    fun deleteCustomPreset(presetId: String): Boolean {
        val preset = _presets.value.find { it.id == presetId } ?: return false
        if (preset.isBuiltIn) return false

        val list = _presets.value.toMutableList()
        list.remove(preset)
        _presets.value = list
        if (_activePresetId.value == presetId) {
            applyPreset("preset_flat")
        }
        version.incrementAndGet()
        saveToDiskAsync()
        return true
    }

    fun renameCustomPreset(presetId: String, newName: String): Boolean {
        val list = _presets.value.toMutableList()
        val idx = list.indexOfFirst { it.id == presetId }
        if (idx >= 0 && !list[idx].isBuiltIn) {
            list[idx] = list[idx].copy(name = newName.trim().ifBlank { "Preset" })
            _presets.value = list
            version.incrementAndGet()
            saveToDiskAsync()
            return true
        }
        return false
    }

    private fun saveToDiskAsync() {
        scope.launch {
            saveToDisk()
        }
    }

    suspend fun saveToDisk() = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject().apply {
                put("version", 2)
                put("activePresetId", _activePresetId.value)
                put("preampDb", _preampDb.value)
                put("isEqEnabled", _isEqEnabled.value)
                put("autoHeadroomEnabled", _autoHeadroomEnabled.value)

                val bandsArr = JSONArray()
                _currentBands.value.forEach { b ->
                    bandsArr.put(JSONObject().apply {
                        put("id", b.id)
                        put("name", b.name)
                        put("type", b.type.name)
                        put("frequencyHz", b.frequencyHz)
                        put("gainDb", b.gainDb)
                        put("q", b.q)
                        put("isEnabled", b.isEnabled)
                    })
                }
                put("currentBands", bandsArr)

                val customPresetsArr = JSONArray()
                _presets.value.filter { !it.isBuiltIn }.forEach { p ->
                    customPresetsArr.put(JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("preampDb", p.preampDb)
                        val bArr = JSONArray()
                        p.bands.forEach { b ->
                            bArr.put(JSONObject().apply {
                                put("id", b.id)
                                put("name", b.name)
                                put("type", b.type.name)
                                put("frequencyHz", b.frequencyHz)
                                put("gainDb", b.gainDb)
                                put("q", b.q)
                                put("isEnabled", b.isEnabled)
                            })
                        }
                        put("bands", bArr)
                    })
                }
                put("customPresets", customPresetsArr)
            }

            val tempFile = File(context.filesDir, "$PRESETS_FILENAME.tmp")
            tempFile.writeText(root.toString(2), StandardCharsets.UTF_8)
            if (presetsFile.exists()) presetsFile.delete()
            tempFile.renameTo(presetsFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving EQ settings to disk: ${e.message}")
        }
    }

    fun restoreFromDisk() {
        if (!presetsFile.exists() || presetsFile.length() == 0L) return
        try {
            val text = presetsFile.readText(StandardCharsets.UTF_8)
            val root = JSONObject(text)
            _activePresetId.value = root.optString("activePresetId", "preset_flat")
            _preampDb.value = root.optDouble("preampDb", 0.0)
            _isEqEnabled.value = root.optBoolean("isEqEnabled", true)
            _autoHeadroomEnabled.value = root.optBoolean("autoHeadroomEnabled", false)

            val customList = mutableListOf<EqPreset>()
            val customPresetsArr = root.optJSONArray("customPresets")
            if (customPresetsArr != null) {
                for (i in 0 until customPresetsArr.length()) {
                    val pObj = customPresetsArr.optJSONObject(i) ?: continue
                    val bList = mutableListOf<EqBand>()
                    val bArr = pObj.optJSONArray("bands")
                    if (bArr != null) {
                        for (j in 0 until bArr.length()) {
                            val bObj = bArr.optJSONObject(j) ?: continue
                            bList.add(
                                EqBand(
                                    id = bObj.optInt("id", j),
                                    name = bObj.optString("name", "Band $j"),
                                    type = runCatching { EqFilterType.valueOf(bObj.optString("type")) }.getOrDefault(EqFilterType.PEAKING),
                                    frequencyHz = bObj.optDouble("frequencyHz", 1000.0),
                                    gainDb = bObj.optDouble("gainDb", 0.0),
                                    q = bObj.optDouble("q", 1.0),
                                    isEnabled = bObj.optBoolean("isEnabled", true)
                                )
                            )
                        }
                    }
                    customList.add(
                        EqPreset(
                            id = pObj.optString("id"),
                            name = pObj.optString("name", "Custom"),
                            isBuiltIn = false,
                            preampDb = pObj.optDouble("preampDb", 0.0),
                            bands = bList
                        )
                    )
                }
            }
            _presets.value = BUILT_IN_PRESETS + customList

            val currentBandsArr = root.optJSONArray("currentBands")
            if (currentBandsArr != null && currentBandsArr.length() > 0) {
                val bList = mutableListOf<EqBand>()
                for (i in 0 until currentBandsArr.length()) {
                    val bObj = currentBandsArr.optJSONObject(i) ?: continue
                    bList.add(
                        EqBand(
                            id = bObj.optInt("id", i),
                            name = bObj.optString("name", "Band $i"),
                            type = runCatching { EqFilterType.valueOf(bObj.optString("type")) }.getOrDefault(EqFilterType.PEAKING),
                            frequencyHz = bObj.optDouble("frequencyHz", 1000.0),
                            gainDb = bObj.optDouble("gainDb", 0.0),
                            q = bObj.optDouble("q", 1.0),
                            isEnabled = bObj.optBoolean("isEnabled", true)
                        )
                    )
                }
                // If legacy save had fewer than 8 bands, append missing default bands
                if (bList.size < ParametricEq.DEFAULT_8_BANDS.size) {
                    val defaults = ParametricEq.DEFAULT_8_BANDS
                    for (k in bList.size until defaults.size) {
                        bList.add(defaults[k].copy())
                    }
                }
                _currentBands.value = bList
            }
            version.incrementAndGet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed restoring EQ settings from disk: ${e.message}")
        }
    }
}
