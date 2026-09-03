package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.SourceFolderEntity
import com.example.data.TrackEntity
import com.example.model.AudioQualityRating
import com.example.model.MusicPlatform
import com.example.model.StorageSourceType
import com.example.model.SyncState
import com.example.model.Track
import com.example.storage.MediaScannerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background Foreground Service using Android DocumentFile API to recursively
 * scan audio storage directories, extract deep acoustic metadata, and index them into Room.
 */
class AudioScanService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    private lateinit var notificationManager: NotificationManager
    private lateinit var database: AppDatabase

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        database = AppDatabase.getDatabase(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("AudioScanService", "onTaskRemoved: User closed app from recents. Cancelling scan and stopping service.")
        cancelScan()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uriString = intent.getStringExtra(EXTRA_TREE_URI)
                if (uriString == null) {
                    val fallbackNotification = buildNotification("Idle", 0, 0, false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, fallbackNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIFICATION_ID, fallbackNotification)
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val label = intent.getStringExtra(EXTRA_SOURCE_LABEL) ?: "Audio Storage"
                val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID) ?: "saf_storage"
                val treeUri = Uri.parse(uriString)
                startRecursiveScan(treeUri, label, sourceId)
            }
            ACTION_PAUSE -> {
                pauseScan()
            }
            ACTION_RESUME -> {
                resumeScan()
            }
            ACTION_CANCEL -> {
                cancelScan()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecursiveScan(treeUri: Uri, label: String, sourceId: String) {
        scanJob?.cancel()
        isPaused.set(false)
        isCancelled.set(false)

        _scanState.value = AudioScanState(
            isScanning = true,
            isPaused = false,
            sourceId = sourceId,
            sourceLabel = label,
            currentDirectory = "Initializing...",
            currentFile = "Reading storage directory structure...",
            filesDiscovered = 0,
            filesIndexed = 0,
            directoriesScanned = 0,
            elapsedTimeMs = 0L,
            isCompleted = false
        )

        val notification = buildNotification("Starting scan on $label...", 0, 0, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        scanJob = serviceScope.launch {
            val startTime = System.currentTimeMillis()
            var totalDiscovered = 0
            var totalIndexed = 0
            var totalSkipped = 0
            var totalFailed = 0
            var directoriesCount = 0
            var totalSizeBytes = 0L

            val trackBatch = mutableListOf<TrackEntity>()
            val BATCH_SIZE = 25

            try {
                val seenFingerprints = database.trackDao().getAllFingerprints().toMutableSet()
                val seenPaths = database.trackDao().getAllFilePaths().toMutableSet()

                val rootDoc = DocumentFile.fromTreeUri(applicationContext, treeUri)
                if (rootDoc == null || !rootDoc.exists() || !rootDoc.canRead()) {
                    Log.e(TAG, "Root document is not readable: $treeUri")
                    _scanState.value = _scanState.value.copy(
                        isScanning = false,
                        errorMessage = "Cannot access root directory. Please check permissions."
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }

                suspend fun traverseDocumentFile(folder: DocumentFile, currentPath: String) {
                    if (isCancelled.get()) return

                    // Check pause state
                    while (isPaused.get()) {
                        if (isCancelled.get()) return
                        delay(300)
                    }

                    directoriesCount++
                    val folderName = folder.name ?: "Folder"
                    val files = folder.listFiles()

                    _scanState.value = _scanState.value.copy(
                        currentDirectory = currentPath,
                        directoriesScanned = directoriesCount
                    )

                    // First pass: identify audio files and subfolders
                    val subDirectories = mutableListOf<DocumentFile>()
                    val audioDocFiles = mutableListOf<DocumentFile>()

                    for (file in files) {
                        if (isCancelled.get()) return
                        if (file.isDirectory) {
                            subDirectories.add(file)
                        } else if (file.isFile) {
                            val name = file.name ?: ""
                            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            if (AUDIO_EXTENSIONS.contains(ext)) {
                                audioDocFiles.add(file)
                            }
                        }
                    }

                    totalDiscovered += audioDocFiles.size
                    _scanState.value = _scanState.value.copy(filesDiscovered = totalDiscovered)

                    // Process audio files in this folder
                    for (audioFile in audioDocFiles) {
                        if (isCancelled.get()) return

                        while (isPaused.get()) {
                            if (isCancelled.get()) return
                            delay(300)
                        }

                        val fileName = audioFile.name ?: "Unknown"
                        val fileSize = audioFile.length()
                        totalSizeBytes += fileSize

                        try {
                            val track = extractTrackMetadata(audioFile, currentPath, sourceId)
                            if (track != null) {
                                val fingerprint = track.contentFingerprint
                                val path = track.filePath

                                // Duplicate protection check
                                if (seenFingerprints.contains(fingerprint) || seenPaths.contains(path)) {
                                    totalSkipped++
                                    _scanState.value = _scanState.value.copy(
                                        filesSkipped = totalSkipped,
                                        currentFile = "Skipped duplicate: $fileName"
                                    )
                                    continue
                                }

                                seenFingerprints.add(fingerprint)
                                seenPaths.add(path)

                                val entity = TrackEntity.fromTrack(track)
                                trackBatch.add(entity)
                                totalIndexed++

                                val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                                val speed = (totalIndexed.toDouble() / (elapsed / 1000.0))

                                _scanState.value = _scanState.value.copy(
                                    currentFile = fileName,
                                    filesIndexed = totalIndexed,
                                    filesSkipped = totalSkipped,
                                    filesFailed = totalFailed,
                                    currentFormat = track.format,
                                    currentBitrate = track.bitrateKbps,
                                    scanSpeedFilesPerSec = String.format(Locale.US, "%.1f", speed).toDoubleOrNull() ?: speed,
                                    elapsedTimeMs = elapsed
                                )

                                // Flush batch if full
                                if (trackBatch.size >= BATCH_SIZE) {
                                    database.trackDao().insertTracks(trackBatch.toList())
                                    trackBatch.clear()

                                    // Update notification periodically
                                    updateNotification(
                                        title = "Indexing $label",
                                        content = "Imported $totalIndexed • Skipped $totalSkipped • ${currentPath.takeLast(30)}",
                                        current = totalIndexed + totalSkipped + totalFailed,
                                        max = totalDiscovered.coerceAtLeast(totalIndexed + totalSkipped),
                                        isPaused = false
                                    )
                                }
                            } else {
                                totalFailed++
                                _scanState.value = _scanState.value.copy(filesFailed = totalFailed)
                            }
                        } catch (e: Exception) {
                            totalFailed++
                            _scanState.value = _scanState.value.copy(filesFailed = totalFailed)
                            Log.w(TAG, "Failed reading audio file $fileName: ${e.message}")
                        }
                    }

                    // Flush remaining tracks from this folder
                    if (trackBatch.isNotEmpty()) {
                        database.trackDao().insertTracks(trackBatch.toList())
                        trackBatch.clear()
                    }

                    // Recursively scan subfolders
                    for (subFolder in subDirectories) {
                        if (isCancelled.get()) return
                        val subName = subFolder.name ?: "Folder"
                        val subPath = if (currentPath.endsWith("/")) "$currentPath$subName" else "$currentPath/$subName"
                        traverseDocumentFile(subFolder, subPath)
                    }
                }

                val rootName = rootDoc.name ?: label
                traverseDocumentFile(rootDoc, "/$rootName")

                // Flush any final leftovers
                if (trackBatch.isNotEmpty()) {
                    database.trackDao().insertTracks(trackBatch.toList())
                    trackBatch.clear()
                }

                if (!isCancelled.get()) {
                    // Update SourceFolder in DB
                    val totalSizeGb = totalSizeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                    val sourceFolder = SourceFolderEntity(
                        id = sourceId,
                        label = label,
                        path = treeUri.toString(),
                        uriString = treeUri.toString(),
                        typeName = StorageSourceType.INTERNAL.name,
                        isOnline = true,
                        trackCount = totalIndexed,
                        freeSpaceGb = 64.0,
                        totalSpaceGb = String.format(Locale.US, "%.2f", totalSizeGb.coerceAtLeast(1.0)).toDoubleOrNull() ?: 1.0,
                        lastScanned = System.currentTimeMillis()
                    )
                    database.sourceFolderDao().insertSourceFolder(sourceFolder)

                    val totalElapsed = System.currentTimeMillis() - startTime
                    val summaryMessage = if (totalIndexed == 0 && totalSkipped > 0) {
                        if (totalFailed > 0) {
                            "All $totalSkipped tracks are already in your library and were skipped ($totalFailed unreadable)."
                        } else {
                            "All $totalSkipped tracks are already in your library and were skipped."
                        }
                    } else {
                        val parts = mutableListOf<String>()
                        parts.add("$totalIndexed ${if (totalIndexed == 1) "track" else "tracks"} imported")
                        if (totalSkipped > 0) {
                            parts.add("$totalSkipped ${if (totalSkipped == 1) "track" else "tracks"} already in library and skipped")
                        }
                        if (totalFailed > 0) {
                            parts.add("$totalFailed ${if (totalFailed == 1) "file" else "files"} could not be read")
                        }
                        parts.joinToString(", ")
                    }

                    _scanState.value = _scanState.value.copy(
                        isScanning = false,
                        isPaused = false,
                        isCompleted = true,
                        totalIndexedInLastRun = totalIndexed,
                        filesIndexed = totalIndexed,
                        filesSkipped = totalSkipped,
                        filesFailed = totalFailed,
                        elapsedTimeMs = totalElapsed,
                        summaryMessage = summaryMessage,
                        currentFile = "Scan completed. $summaryMessage"
                    )

                    showCompletionNotification(label, summaryMessage)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during recursive DocumentFile scan", e)
                _scanState.value = _scanState.value.copy(
                    isScanning = false,
                    errorMessage = "Scan interrupted: ${e.localizedMessage ?: "Unknown error"}"
                )
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun extractTrackMetadata(file: DocumentFile, folderPath: String, sourceId: String): Track? {
        val uri = file.uri
        val name = file.name ?: "Unknown Track"
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val sizeBytes = file.length()
        val sizeMb = sizeBytes.toDouble() / (1024.0 * 1024.0)
        val format = MediaScannerHelper.resolveFormat(name, file.type ?: "")
        val fallbackTitle = name.substringBeforeLast(".")

        var title = fallbackTitle
        var artist = "Unknown Artist"
        var album = "Single"
        var genre = "DJ Library"
        var durationSec = 210
        var bitrateKbps = if (format == "FLAC" || format == "WAV") 1411 else 320
        var bpm = 0.0
        var musicalKey = ""
        var sampleRate = 44100
        var bitDepth = 16

        val embedded = com.example.metadata.AudioEmbeddedMetadataReader.read(this, uri.toString())
        if (embedded.title?.isNotBlank() == true) title = embedded.title
        if (embedded.artist?.isNotBlank() == true) artist = embedded.artist
        if (embedded.album?.isNotBlank() == true) album = embedded.album
        if (embedded.genre?.isNotBlank() == true) genre = embedded.genre
        if (embedded.hasBpm) bpm = embedded.bpm ?: 0.0
        if (embedded.hasKey) musicalKey = embedded.camelotKey ?: embedded.musicalKey.orEmpty()

        val retriever = MediaMetadataRetriever()
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                val mTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val mArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                val mAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val mGenre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                val mDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val mBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                val mSampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                } else null
                val mBitDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
                } else null

                if (!mTitle.isNullOrBlank() && title == name.substringBeforeLast(".")) title = mTitle
                if (!mArtist.isNullOrBlank() && mArtist != "<unknown>" && artist == "Unknown Artist") artist = mArtist
                if (!mAlbum.isNullOrBlank() && mAlbum != "<unknown>" && album == "Single") album = mAlbum
                if (!mGenre.isNullOrBlank() && genre == "DJ Library") genre = mGenre
                if (mDuration != null) {
                    durationSec = (mDuration.toLongOrNull() ?: 0L).let { (it / 1000).toInt().coerceAtLeast(1) }
                }
                if (mBitrate != null) {
                    bitrateKbps = (mBitrate.toIntOrNull() ?: (bitrateKbps * 1000)) / 1000
                }
                if (mSampleRate != null && mSampleRate > 0) sampleRate = mSampleRate
                if (mBitDepth != null && mBitDepth > 0) bitDepth = mBitDepth
            }
        } catch (e: Exception) {
            // Fallback gracefully on heuristics
            Log.v(TAG, "Retriever skipped or fallback for $name: ${e.message}")
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }

        // Infer BPM and Key heuristics from filename if tagged like "128_8A_Artist_Title"
        if (bpm <= 0.0) {
            val cleanName = name.replace("_", " ").replace("-", " ")
            val bpmMatch = Regex("""\b(1[1-3][0-9]|14[0-9]|9[0-9])\s*(?:bpm)?\b""", RegexOption.IGNORE_CASE).find(cleanName)
            if (bpmMatch != null) {
                bpmMatch.groupValues[1].toDoubleOrNull()?.let { bpm = it }
            }
        }

        if (musicalKey.isBlank()) {
            val keyMatch = Regex("""\b([1-9]|1[0-2])([A-B])\b""", RegexOption.IGNORE_CASE).find(name)
            if (keyMatch != null) {
                musicalKey = keyMatch.value.uppercase(Locale.ROOT)
            }
        }

        val qualityRating = when {
            format == "FLAC" && (sampleRate >= 96000 || bitDepth >= 24) -> AudioQualityRating.STUDIO_LOSSLESS
            format == "FLAC" || format == "WAV" || format == "AIFF" -> AudioQualityRating.TRUE_LOSSLESS
            bitrateKbps >= 310 -> AudioQualityRating.TRUE_320
            bitrateKbps >= 240 -> AudioQualityRating.TRUE_256
            bitrateKbps < 160 -> AudioQualityRating.LOW_128
            else -> AudioQualityRating.TRUE_320
        }

        val trackId = "saf_${uri.toString().hashCode().toLong().let { if (it < 0) -it else it }}"
        val fingerprint = com.example.storage.AudioFingerprintUtil.generateDocumentFileFingerprint(this, file, durationSec)

        return Track(
            id = trackId,
            title = title,
            artist = artist,
            album = album,
            albumArtist = embedded.albumArtist.orEmpty(),
            genre = genre,
            subGenre = "Club",
            bpm = bpm,
            musicalKey = musicalKey,
            camelotKey = embedded.camelotKey.orEmpty(),
            durationSeconds = if (durationSec > 0) durationSec else embedded.durationSeconds,
            bitrateKbps = if (bitrateKbps > 0) bitrateKbps else embedded.bitrateKbps,
            format = format,
            fileSizeMb = String.format(Locale.US, "%.2f", sizeMb).toDoubleOrNull() ?: sizeMb,
            filePath = uri.toString(),
            directoryPath = folderPath,
            isOfflineReady = true,
            syncState = SyncState.SYNCED,
            platforms = listOf(MusicPlatform.LOCAL),
            energyRating = 7,
            hotCues = listOf(0, (durationSec * 0.15).toInt(), (durationSec * 0.45).toInt(), (durationSec * 0.75).toInt()),
            isAiTagged = false,
            qualityRating = qualityRating,
            dateAdded = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
            crateId = "crate_all",
            sourceId = sourceId,
            trackNumber = embedded.trackNumber ?: 0,
            discNumber = embedded.discNumber ?: 1,
            releaseDate = embedded.releaseDate,
            releaseYear = embedded.releaseYear,
            recordLabel = embedded.recordLabel,
            barcode = embedded.barcode,
            isrc = embedded.isrc,
            musicBrainzRecordingId = embedded.musicBrainzRecordingId,
            musicBrainzReleaseId = embedded.musicBrainzReleaseId,
            musicBrainzArtistId = embedded.musicBrainzArtistId,
            musicBrainzReleaseGroupId = embedded.musicBrainzReleaseGroupId,
            musicBrainzMatchConfidence = if (embedded.hasEmbeddedMusicBrainz) 1.0 else 0.0,
            musicBrainzLastChecked = if (embedded.hasEmbeddedMusicBrainz) System.currentTimeMillis() else null,
            artworkUrl = embedded.musicBrainzReleaseId?.let { "https://coverartarchive.org/release/$it/front-500" },
            contentFingerprint = fingerprint
        )
    }

    private fun pauseScan() {
        isPaused.set(true)
        _scanState.value = _scanState.value.copy(isPaused = true)
        updateNotification("Scan Paused", "Audio indexing paused by user.", 0, 0, true)
    }

    private fun resumeScan() {
        isPaused.set(false)
        _scanState.value = _scanState.value.copy(isPaused = false)
        updateNotification("Resuming Scan...", "Continuing recursive audio indexing...", 0, 0, false)
    }

    private fun cancelScan() {
        isCancelled.set(true)
        isPaused.set(false)
        scanJob?.cancel()
        _scanState.value = _scanState.value.copy(
            isScanning = false,
            isPaused = false,
            currentFile = "Scan cancelled."
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Library Scanner",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time progress for background music indexing and storage scanning"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        current: Int,
        max: Int,
        isPaused: Boolean
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeAction = if (isPaused) {
            val resumeIntent = Intent(this, AudioScanService::class.java).apply { action = ACTION_RESUME }
            val resumePendingIntent = PendingIntent.getService(
                this, 1, resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play, "Resume", resumePendingIntent
            ).build()
        } else {
            val pauseIntent = Intent(this, AudioScanService::class.java).apply { action = ACTION_PAUSE }
            val pausePendingIntent = PendingIntent.getService(
                this, 2, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause, "Pause", pausePendingIntent
            ).build()
        }

        val cancelIntent = Intent(this, AudioScanService::class.java).apply { action = ACTION_CANCEL }
        val cancelPendingIntent = PendingIntent.getService(
            this, 3, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Stop", cancelPendingIntent
        ).build()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (max > 0) "Indexing $current of $max tracks..." else "Scanning audio directories...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(pauseResumeAction)
            .addAction(cancelAction)

        if (max > 0) {
            builder.setProgress(max, current, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(
        title: String,
        content: String,
        current: Int,
        max: Int,
        isPaused: Boolean
    ) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeAction = if (isPaused) {
            val resumeIntent = Intent(this, AudioScanService::class.java).apply { action = ACTION_RESUME }
            val resumePendingIntent = PendingIntent.getService(
                this, 1, resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play, "Resume", resumePendingIntent
            ).build()
        } else {
            val pauseIntent = Intent(this, AudioScanService::class.java).apply { action = ACTION_PAUSE }
            val pausePendingIntent = PendingIntent.getService(
                this, 2, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause, "Pause", pausePendingIntent
            ).build()
        }

        val cancelIntent = Intent(this, AudioScanService::class.java).apply { action = ACTION_CANCEL }
        val cancelPendingIntent = PendingIntent.getService(
            this, 3, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Stop", cancelPendingIntent
        ).build()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(pauseResumeAction)
            .addAction(cancelAction)

        if (max > 0) {
            builder.setProgress(max, current, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun showCompletionNotification(label: String, summaryMessage: String) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Library Scan Finished")
            .setContentText(summaryMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Finished scanning $label:\n$summaryMessage"))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)

        try {
            notificationManager.notify(NOTIFICATION_ID + 1, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send completion notification", e)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AudioScanService"
        const val CHANNEL_ID = "audio_scan_channel"
        const val NOTIFICATION_ID = 2048

        const val ACTION_START = "com.example.service.action.START_SCAN"
        const val ACTION_PAUSE = "com.example.service.action.PAUSE_SCAN"
        const val ACTION_RESUME = "com.example.service.action.RESUME_SCAN"
        const val ACTION_CANCEL = "com.example.service.action.CANCEL_SCAN"

        const val EXTRA_TREE_URI = "extra_tree_uri"
        const val EXTRA_SOURCE_LABEL = "extra_source_label"
        const val EXTRA_SOURCE_ID = "extra_source_id"

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "aiff", "aif", "wma", "alac", "dsf", "dff"
        )

        private val _scanState = MutableStateFlow(AudioScanState())
        val scanState: StateFlow<AudioScanState> = _scanState.asStateFlow()

        fun startScan(context: Context, treeUri: Uri, label: String, sourceId: String = "saf_folder") {
            val intent = Intent(context, AudioScanService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TREE_URI, treeUri.toString())
                putExtra(EXTRA_SOURCE_LABEL, label)
                putExtra(EXTRA_SOURCE_ID, sourceId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseScan(context: Context) {
            val intent = Intent(context, AudioScanService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resumeScan(context: Context) {
            val intent = Intent(context, AudioScanService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun cancelScan(context: Context) {
            val intent = Intent(context, AudioScanService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
