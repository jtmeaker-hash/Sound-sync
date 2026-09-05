package com.example.backup

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.SongFindEntity
import com.example.data.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupFileInfo(
    val file: File?,
    val uri: Uri?,
    val displayName: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val version: Int = 1,
    val trackCount: Int = 0,
    val songFindCount: Int = 0
)

/**
 * Manages persistent JSON backups of SoundSync data (Song Finds, beatgrid/BPM/key analysis, repaired artist metadata).
 *
 * Designed to survive full app uninstall/reinstall by persisting to shared Documents or user-selected SAF directories.
 */
class SoundSyncBackupManager(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var autoBackupJob: Job? = null

    private val _summaryFlow = MutableStateFlow(loadSummary())
    val summaryFlow: StateFlow<BackupSummary> = _summaryFlow.asStateFlow()

    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    companion object {
        private const val TAG = "SoundSyncBackupManager"
        private const val PREFS_NAME = "soundsync_backup_prefs"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_CUSTOM_TREE_URI = "custom_backup_tree_uri"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val KEY_LAST_BACKUP_TRACKS = "last_backup_tracks"
        private const val KEY_LAST_BACKUP_FINDS = "last_backup_finds"
        private const val KEY_LAST_BACKUP_LOC = "last_backup_loc"

        const val BACKUP_FILENAME = "soundsync_backup.json"
        const val BACKUP_SUBFOLDER = "SoundSync/backups"

        @Volatile
        private var INSTANCE: SoundSyncBackupManager? = null

        fun getInstance(context: Context): SoundSyncBackupManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SoundSyncBackupManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    fun isAutoBackupEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_BACKUP_ENABLED, true)
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled).apply()
        _summaryFlow.value = loadSummary()
    }

    fun getCustomBackupTreeUri(): Uri? {
        val uriStr = prefs.getString(KEY_CUSTOM_TREE_URI, null) ?: return null
        return try {
            Uri.parse(uriStr)
        } catch (_: Exception) {
            null
        }
    }

    fun setCustomBackupTreeUri(uri: Uri?) {
        prefs.edit().putString(KEY_CUSTOM_TREE_URI, uri?.toString()).apply()
        _summaryFlow.value = loadSummary()
    }

    /**
     * Debounced notification triggered whenever tracks, analysis, or Song Finds change.
     * Backs up automatically after 5 seconds of inactivity if auto-backup is enabled.
     */
    fun notifyDataChanged() {
        if (!isAutoBackupEnabled()) return

        autoBackupJob?.cancel()
        autoBackupJob = scope.launch {
            delay(5000)
            Log.d(TAG, "Triggering automatic debounced backup...")
            createBackup()
        }
    }

    /**
     * Creates a complete backup of all Song Finds and Track analysis & metadata.
     * Writes atomically to persistent storage.
     */
    suspend fun createBackup(targetUri: Uri? = null): Result<BackupSummary> = withContext(Dispatchers.IO) {
        _isBackingUp.value = true
        try {
            val tracks = database.trackDao().getAllTracksSync()
            val songFinds = database.songFindDao().getAllSongFindsSync()

            val backup = SoundSyncBackup(
                backupVersion = SoundSyncBackup.CURRENT_BACKUP_VERSION,
                appVersion = "1.0.0",
                createdAt = prefs.getLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis()),
                updatedAt = System.currentTimeMillis(),
                songFinds = songFinds.map { SongFindBackupItem.fromEntity(it) },
                tracks = tracks.map { TrackBackupItem.fromEntity(it) }
            )

            val jsonString = serializeBackup(backup)
            var savedPath = "Unknown"

            if (targetUri != null) {
                // User-selected destination URI
                writeJsonToUri(targetUri, jsonString)
                savedPath = targetUri.toString()
            } else {
                // Check if user set a custom SAF tree folder
                val customTreeUri = getCustomBackupTreeUri()
                var wroteSaf = false
                if (customTreeUri != null) {
                    try {
                        wroteSaf = writeToSafDirectory(customTreeUri, jsonString)
                        if (wroteSaf) savedPath = "SAF: $customTreeUri"
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed writing to custom SAF backup directory, falling back", e)
                    }
                }

                if (!wroteSaf) {
                    // Standard persistent storage: Documents/SoundSync/backups/
                    val file = writeToDefaultDirectory(jsonString)
                    savedPath = file.absolutePath
                }
            }

            // Update preferences
            prefs.edit()
                .putLong(KEY_LAST_BACKUP_TIME, backup.updatedAt)
                .putInt(KEY_LAST_BACKUP_TRACKS, backup.tracks.size)
                .putInt(KEY_LAST_BACKUP_FINDS, backup.songFinds.size)
                .putString(KEY_LAST_BACKUP_LOC, savedPath)
                .apply()

            val summary = loadSummary()
            _summaryFlow.value = summary
            Log.i(TAG, "Backup created successfully: ${backup.tracks.size} tracks, ${backup.songFinds.size} song finds.")
            Result.success(summary)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup", e)
            Result.failure(e)
        } finally {
            _isBackingUp.value = false
        }
    }

    /**
     * Restores backup from a user-specified Uri or from the latest detected persistent backup file.
     * Executes non-destructively in a single database transaction.
     */
    suspend fun restoreBackup(sourceUri: Uri? = null): RestoreResult = withContext(Dispatchers.IO) {
        _isRestoring.value = true
        try {
            val jsonString = if (sourceUri != null) {
                readJsonFromUri(sourceUri)
            } else {
                val latest = findLatestBackup()
                if (latest == null) {
                    return@withContext RestoreResult.Error("No backup found on device to restore.")
                }
                if (latest.uri != null) {
                    readJsonFromUri(latest.uri)
                } else if (latest.file != null && latest.file.exists()) {
                    latest.file.readText(StandardCharsets.UTF_8)
                } else {
                    return@withContext RestoreResult.Error("Backup file is inaccessible.")
                }
            }

            val validation = validateBackup(jsonString)
            if (validation is ValidationResult.Invalid) {
                return@withContext RestoreResult.Error("Invalid backup format: ${validation.reason}")
            }

            val backup = (validation as ValidationResult.Valid).backup

            // Perform transactional non-destructive merge
            var restoredTracks = 0
            var matchedTracks = 0
            var restoredFinds = 0

            database.withTransaction {
                // 1. Restore and merge Song Finds
                val currentFinds = database.songFindDao().getAllSongFindsSync()
                val currentUrls = currentFinds.map { it.url.trim().lowercase(Locale.ROOT) }.toSet()
                val currentIds = currentFinds.map { it.id }.toSet()

                val newFindsToInsert = mutableListOf<SongFindEntity>()
                for (backupFind in backup.songFinds) {
                    val urlNorm = backupFind.url.trim().lowercase(Locale.ROOT)
                    if (!currentUrls.contains(urlNorm) && !currentIds.contains(backupFind.id)) {
                        newFindsToInsert.add(backupFind.toEntity())
                    }
                }
                if (newFindsToInsert.isNotEmpty()) {
                    database.songFindDao().insertSongFinds(newFindsToInsert)
                    restoredFinds = newFindsToInsert.size
                }

                // 2. Restore and merge Tracks
                val currentTracks = database.trackDao().getAllTracksSync()
                val matchResults = TrackMatcher.matchTracks(backup.tracks, currentTracks)

                val tracksToUpdate = mutableListOf<TrackEntity>()
                val tracksToInsert = mutableListOf<TrackEntity>()

                for (result in matchResults) {
                    if (result.matchedEntity != null) {
                        // Merge into existing entity
                        val merged = TrackMatcher.mergeTrack(
                            backupTrack = result.backupTrack,
                            existingEntity = result.matchedEntity,
                            isFileModified = result.isFileModified
                        )
                        tracksToUpdate.add(merged)
                        matchedTracks++
                    } else {
                        // Unmatched track (not on current device yet or scanned under different root)
                        // Restore as an offline track so all historical analysis and tags are preserved!
                        val restoredEntity = result.backupTrack.toEntity().copy(
                            isOfflineReady = false
                        )
                        tracksToInsert.add(restoredEntity)
                        restoredTracks++
                    }
                }

                if (tracksToUpdate.isNotEmpty()) {
                    database.trackDao().updateTracks(tracksToUpdate)
                }
                if (tracksToInsert.isNotEmpty()) {
                    database.trackDao().insertTracks(tracksToInsert)
                }
            }

            _summaryFlow.value = loadSummary()
            val message = "Restored $matchedTracks matched tracks, $restoredTracks new track records, and $restoredFinds Song Finds."
            Log.i(TAG, message)
            RestoreResult.Success(
                tracksRestored = restoredTracks,
                tracksMatched = matchedTracks,
                songFindsRestored = restoredFinds,
                message = message
            )
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            RestoreResult.Error("Restore failed: ${e.message ?: "Unknown error"}", e)
        } finally {
            _isRestoring.value = false
        }
    }

    /**
     * Validates a JSON string against the SoundSync backup schema.
     */
    fun validateBackup(jsonString: String): ValidationResult {
        if (jsonString.isBlank()) {
            return ValidationResult.Invalid("Backup file is empty.")
        }
        return try {
            val root = JSONObject(jsonString)
            val version = root.optInt("backupVersion", -1)
            if (version < 1) {
                return ValidationResult.Invalid("Unsupported backup version or missing header.")
            }
            if (version > SoundSyncBackup.CURRENT_BACKUP_VERSION) {
                return ValidationResult.Invalid("Backup was generated by a newer version ($version) of SoundSync.")
            }

            val appVersion = root.optString("appVersion", "1.0.0")
            val createdAt = root.optLong("createdAt", 0L)
            val updatedAt = root.optLong("updatedAt", 0L)

            val findsArray = root.optJSONArray("songFinds") ?: JSONArray()
            val songFinds = mutableListOf<SongFindBackupItem>()
            for (i in 0 until findsArray.length()) {
                songFinds.add(SongFindBackupItem.fromJson(findsArray.getJSONObject(i)))
            }

            val tracksArray = root.optJSONArray("tracks") ?: JSONArray()
            val tracks = mutableListOf<TrackBackupItem>()
            for (i in 0 until tracksArray.length()) {
                tracks.add(TrackBackupItem.fromJson(tracksArray.getJSONObject(i)))
            }

            ValidationResult.Valid(
                SoundSyncBackup(
                    backupVersion = version,
                    appVersion = appVersion,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    songFinds = songFinds,
                    tracks = tracks
                )
            )
        } catch (e: Exception) {
            ValidationResult.Invalid("Malformed JSON: ${e.message}")
        }
    }

    /**
     * Detects existing backups across all persistent locations.
     */
    fun findAvailableBackups(): List<BackupFileInfo> {
        val backups = mutableListOf<BackupFileInfo>()

        // 1. Custom SAF Directory if set
        val customTreeUri = getCustomBackupTreeUri()
        if (customTreeUri != null) {
            try {
                val doc = DocumentFile.fromTreeUri(context, customTreeUri)
                if (doc != null && doc.canRead()) {
                    doc.listFiles().forEach { file ->
                        if (file.isFile && (file.name?.endsWith(".json", ignoreCase = true) == true)) {
                            backups.add(
                                BackupFileInfo(
                                    file = null,
                                    uri = file.uri,
                                    displayName = file.name ?: "SAF Backup",
                                    lastModified = file.lastModified(),
                                    sizeBytes = file.length()
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error listing SAF backups", e)
            }
        }

        // 2. Documents/SoundSync/backups directory
        try {
            val docsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), BACKUP_SUBFOLDER)
            if (docsDir.exists() && docsDir.isDirectory) {
                docsDir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }?.forEach { f ->
                    backups.add(
                        BackupFileInfo(
                            file = f,
                            uri = null,
                            displayName = f.name,
                            lastModified = f.lastModified(),
                            sizeBytes = f.length()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error listing public Documents backups", e)
        }

        // 3. Fallback app external files dir
        try {
            val appExtDir = File(context.getExternalFilesDir(null), "backups")
            if (appExtDir.exists() && appExtDir.isDirectory) {
                appExtDir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }?.forEach { f ->
                    if (backups.none { it.displayName == f.name && it.lastModified == f.lastModified() }) {
                        backups.add(
                            BackupFileInfo(
                                file = f,
                                uri = null,
                                displayName = f.name,
                                lastModified = f.lastModified(),
                                sizeBytes = f.length()
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        return backups.sortedByDescending { it.lastModified }
    }

    fun findLatestBackup(): BackupFileInfo? {
        return findAvailableBackups().firstOrNull()
    }

    /**
     * Checks if there is an existing persistent backup that can be restored on a fresh install.
     */
    fun hasExistingBackup(): Boolean {
        return findAvailableBackups().isNotEmpty()
    }

    private fun serializeBackup(backup: SoundSyncBackup): String {
        val root = JSONObject().apply {
            put("backupVersion", backup.backupVersion)
            put("appVersion", backup.appVersion)
            put("createdAt", backup.createdAt)
            put("updatedAt", backup.updatedAt)

            val songFindsArray = JSONArray()
            backup.songFinds.forEach { songFindsArray.put(it.toJson()) }
            put("songFinds", songFindsArray)

            val tracksArray = JSONArray()
            backup.tracks.forEach { tracksArray.put(it.toJson()) }
            put("tracks", tracksArray)
        }
        return root.toString(2)
    }

    private fun writeToDefaultDirectory(jsonString: String): File {
        val baseDir = try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), BACKUP_SUBFOLDER)
            if (!dir.exists()) dir.mkdirs()
            if (dir.canWrite()) dir else getFallbackDirectory()
        } catch (_: Exception) {
            getFallbackDirectory()
        }

        val canonicalFile = File(baseDir, BACKUP_FILENAME)
        val tempFile = File(baseDir, "$BACKUP_FILENAME.tmp")

        // Atomic write via temp file
        tempFile.writeText(jsonString, StandardCharsets.UTF_8)
        if (canonicalFile.exists()) {
            canonicalFile.delete()
        }
        tempFile.renameTo(canonicalFile)

        // Also write a timestamped version for versioned history (keep last 3)
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
            val archiveFile = File(baseDir, "soundsync_backup_$timestamp.json")
            archiveFile.writeText(jsonString, StandardCharsets.UTF_8)
            pruneOldBackups(baseDir, 5)
        } catch (e: Exception) {
            Log.w(TAG, "Failed creating timestamped backup copy", e)
        }

        return canonicalFile
    }

    private fun getFallbackDirectory(): File {
        val ext = File(context.getExternalFilesDir(null), "backups")
        if (!ext.exists()) ext.mkdirs()
        return ext
    }

    private fun pruneOldBackups(dir: File, maxCount: Int) {
        val timestamped = dir.listFiles { f ->
            f.isFile && f.name.startsWith("soundsync_backup_20") && f.name.endsWith(".json")
        }?.sortedByDescending { it.lastModified() } ?: return

        if (timestamped.size > maxCount) {
            timestamped.drop(maxCount).forEach { it.delete() }
        }
    }

    private fun writeToSafDirectory(treeUri: Uri, jsonString: String): Boolean {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        if (!rootDoc.canWrite()) return false

        val existingDoc = rootDoc.findFile(BACKUP_FILENAME)
        existingDoc?.delete()

        val newFile = rootDoc.createFile("application/json", BACKUP_FILENAME) ?: return false
        context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
            OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }
        }
        return true
    }

    private fun writeJsonToUri(uri: Uri, jsonString: String) {
        context.contentResolver.openOutputStream(uri)?.use { os ->
            OutputStreamWriter(os, StandardCharsets.UTF_8).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }
        } ?: throw IllegalStateException("Unable to open output stream for $uri")
    }

    private fun readJsonFromUri(uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                return reader.readText()
            }
        } ?: throw IllegalStateException("Unable to open input stream for $uri")
    }

    fun loadSummary(): BackupSummary {
        val lastTime = prefs.getLong(KEY_LAST_BACKUP_TIME, 0L).takeIf { it > 0 }
        val tracksCount = prefs.getInt(KEY_LAST_BACKUP_TRACKS, 0)
        val findsCount = prefs.getInt(KEY_LAST_BACKUP_FINDS, 0)
        val loc = prefs.getString(KEY_LAST_BACKUP_LOC, "Documents/SoundSync/backups") ?: "Documents/SoundSync/backups"
        val enabled = isAutoBackupEnabled()

        val status = if (lastTime == null) {
            "No backup created yet"
        } else {
            val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(lastTime))
            "Up to date ($dateStr)"
        }

        return BackupSummary(
            lastBackupTimestamp = lastTime,
            status = status,
            songFindCount = findsCount,
            trackCount = tracksCount,
            backupLocation = loc,
            isAutoBackupEnabled = enabled
        )
    }
}
