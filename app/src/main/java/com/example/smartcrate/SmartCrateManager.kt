package com.example.smartcrate

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

/**
 * Manages Smart Crate persistence, CRUD operations, and default starter crates.
 */
class SmartCrateManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "SmartCrateManager"
        private const val CRATES_FILENAME = "smart_crates.json"

        @Volatile
        private var instance: SmartCrateManager? = null

        fun getInstance(context: Context): SmartCrateManager {
            return instance ?: synchronized(this) {
                instance ?: SmartCrateManager(context.applicationContext).also {
                    instance = it
                    it.restoreFromDisk()
                }
            }
        }

        val DEFAULT_SMART_CRATES: List<SmartCrate> by lazy {
            listOf(
                SmartCrate(
                    id = "default_lossless_hi_res",
                    name = "Lossless & Hi-Res Masters",
                    matchMode = SmartMatchMode.MATCH_ALL,
                    rules = listOf(
                        SmartRule(field = SmartField.IS_LOSSLESS, operator = SmartOperator.EQUALS, value = "true")
                    ),
                    sortField = SmartSortField.BITRATE,
                    sortAscending = false
                ),
                SmartCrate(
                    id = "default_peak_energy_128",
                    name = "Peak Time (124 - 130 BPM)",
                    matchMode = SmartMatchMode.MATCH_ALL,
                    rules = listOf(
                        SmartRule(
                            field = SmartField.BPM,
                            operator = SmartOperator.BETWEEN,
                            value = "124.0",
                            secondaryValue = "130.0"
                        )
                    ),
                    sortField = SmartSortField.BPM,
                    sortAscending = true
                ),
                SmartCrate(
                    id = "default_recent_additions",
                    name = "Recent Additions",
                    matchMode = SmartMatchMode.MATCH_ALL,
                    rules = emptyList(),
                    sortField = SmartSortField.DATE_ADDED,
                    sortAscending = false,
                    maxTrackLimit = 50
                )
            )
        }
    }

    private val cratesFile = File(context.filesDir, CRATES_FILENAME)

    private val _crates = MutableStateFlow<List<SmartCrate>>(DEFAULT_SMART_CRATES)
    val crates: StateFlow<List<SmartCrate>> = _crates.asStateFlow()

    fun saveCrate(crate: SmartCrate) {
        val list = _crates.value.toMutableList()
        val idx = list.indexOfFirst { it.id == crate.id }
        if (idx >= 0) {
            list[idx] = crate.copy(updatedAt = System.currentTimeMillis())
        } else {
            list.add(crate)
        }
        _crates.value = list
        saveToDiskAsync()
        Log.i(TAG, "Saved Smart Crate: '${crate.name}'")
    }

    fun deleteCrate(crateId: String): Boolean {
        val list = _crates.value.toMutableList()
        val removed = list.removeAll { it.id == crateId }
        if (removed) {
            _crates.value = list
            saveToDiskAsync()
            Log.i(TAG, "Deleted Smart Crate id: $crateId")
        }
        return removed
    }

    private fun saveToDiskAsync() {
        scope.launch {
            saveToDisk()
        }
    }

    suspend fun saveToDisk() = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject().apply {
                put("version", 1)
                val arr = JSONArray()
                _crates.value.forEach { arr.put(it.toJson()) }
                put("crates", arr)
            }
            val tempFile = File(context.filesDir, "$CRATES_FILENAME.tmp")
            tempFile.writeText(root.toString(2), StandardCharsets.UTF_8)
            if (cratesFile.exists()) cratesFile.delete()
            tempFile.renameTo(cratesFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving smart crates to disk: ${e.message}")
        }
    }

    fun restoreFromDisk() {
        if (!cratesFile.exists() || cratesFile.length() == 0L) return
        try {
            val text = cratesFile.readText(StandardCharsets.UTF_8)
            val root = JSONObject(text)
            val arr = root.optJSONArray("crates")
            if (arr != null) {
                val list = mutableListOf<SmartCrate>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(SmartCrate.fromJson(it)) }
                }
                if (list.isNotEmpty()) {
                    _crates.value = list
                }
            }
            Log.i(TAG, "Restored ${_crates.value.size} smart crates from disk.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed restoring smart crates from disk: ${e.message}")
        }
    }
}
