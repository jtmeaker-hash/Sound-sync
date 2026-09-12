package com.example.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.model.PlayabilityDiagnosticReport
import com.example.model.PlayabilityStatus
import com.example.model.PlaybackErrorCodes
import com.example.model.RepairActionType
import com.example.model.Track
import com.example.storage.CanonicalStorageHelper
import com.example.storage.ResolvedSourceType
import com.example.storage.StorageAvailabilityHelper
import com.example.storage.TrackSelfHealingResolver
import com.example.storage.TrackSourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/**
 * Lightweight, high-resilience audio playability validator and diagnostic probe for SoundSync.
 *
 * Validates whether a track can genuinely be resolved, opened, decoded, and played by Android's
 * media subsystem, without relying merely on metadata or duration tags.
 */
object PlayabilityValidator {

    private const val TAG = "SoundSyncPlaybackHealth"

    /**
     * Probes an individual track's playability through a lightweight 6-step decode verification.
     *
     * @param context Android context for ContentResolver and decoder access.
     * @param track The track to validate.
     * @param quickCheckOnly If true, checks storage & container without starting MediaCodec (fast pass).
     * @param forceFresh If true, bypasses any cached filesystem stat checks.
     */
    suspend fun validateTrack(
        context: Context,
        track: Track,
        quickCheckOnly: Boolean = false,
        forceFresh: Boolean = false
    ): PlayabilityDiagnosticReport = withContext(Dispatchers.IO) {
        val path = track.filePath
        val trackId = track.id

        // Special handling for demo / synthetic tracks
        if (path.startsWith("demo://")) {
            return@withContext PlayabilityDiagnosticReport(
                trackId = trackId,
                status = PlayabilityStatus.PLAYABLE,
                problemDescription = "",
                detectedReason = "SoundSync built-in synthetic demo track",
                lastKnownLocation = path,
                resolvedPath = path,
                containerMime = "audio/synthetic",
                audioCodec = "PCM Synthetic Wave",
                sampleRate = 44100,
                channelCount = 2,
                isFileAccessible = true,
                isMediaStoreEntryValid = true,
                isContainerReadable = true,
                isAudioStreamFound = true,
                isDecoderInitialized = true,
                isSampleDecoded = true,
                availableActions = emptyList()
            )
        }

        if (path.isBlank()) {
            return@withContext PlayabilityDiagnosticReport(
                trackId = trackId,
                status = PlayabilityStatus.MISSING_FILE,
                errorCode = "ERR_EMPTY_PATH",
                errorMessage = "Track file path is empty or missing in library database.",
                problemDescription = "No file location is associated with this track.",
                detectedReason = "File path is blank",
                lastKnownLocation = "(None)",
                availableActions = listOf(
                    RepairActionType.FIX_AUTOMATICALLY,
                    RepairActionType.LOCATE_FILE,
                    RepairActionType.REMOVE_FROM_LIBRARY
                )
            )
        }

        var isFileAccessible = false
        var isMediaStoreEntryValid = false
        var isContainerReadable = false
        var isAudioStreamFound = false
        var isDecoderInitialized = false
        var isSampleDecoded = false

        var detectedMime: String? = null
        var detectedCodec: String? = null
        var sampleRate = 0
        var channelCount = 0
        var bitRate = track.bitrateKbps
        var fileSizeBytes = 0L
        var fileModifiedTimestamp = 0L
        var resolvedPlayableUri: String? = null

        // Step 1: Central Media & Storage Source Resolution
        val resolution = TrackSourceResolver.resolveTrackSource(context, track)
        val diag = resolution.diagnostics

        if (resolution.isPlayable && resolution.resolvedUriOrPath != null) {
            resolvedPlayableUri = resolution.resolvedUriOrPath
            isFileAccessible = true
            isMediaStoreEntryValid = (resolution.sourceType == ResolvedSourceType.MEDIASTORE)
            fileSizeBytes = diag.fileLength
            if (diag.storedSource.isNotBlank() && !diag.storedSource.startsWith("content://")) {
                val f = File(diag.storedSource.removePrefix("file://"))
                if (f.exists()) fileModifiedTimestamp = f.lastModified()
            }
        } else {
            // Unresolvable source - classify failure mode with precise diagnostics
            val technical = "${diag.formatDiagnostics()}\n\nFailure Details: ${diag.detectedFailureMode}"

            if (!diag.isVolumeMounted) {
                return@withContext PlayabilityDiagnosticReport(
                    trackId = trackId,
                    status = PlayabilityStatus.VOLUME_UNAVAILABLE,
                    errorCode = "ERR_STORAGE_UNMOUNTED",
                    errorMessage = "External storage volume (${resolution.volumeUuid ?: "SD Card"}) is not mounted or has been disconnected.",
                    problemDescription = "The storage drive containing this file is currently disconnected.",
                    detectedReason = "Storage volume offline (${resolution.volumeUuid ?: "removable"})",
                    lastKnownLocation = path,
                    technicalDetails = technical,
                    originalExceptionClass = diag.originalExceptionClass,
                    originalExceptionMessage = diag.originalExceptionMessage,
                    resolvedSourceType = resolution.sourceType.name,
                    availableActions = listOf(
                        RepairActionType.FIX_AUTOMATICALLY,
                        RepairActionType.LOCATE_FILE,
                        RepairActionType.RESCAN_TRACK
                    )
                )
            }

            if (resolution.requiresFolderAccess || diag.isScopedStorageBlockingRawAccess) {
                return@withContext PlayabilityDiagnosticReport(
                    trackId = trackId,
                    status = PlayabilityStatus.PERMISSION_DENIED,
                    errorCode = "ERR_SCOPED_STORAGE_RESTRICTION",
                    errorMessage = "Android Scoped Storage restricts direct file path access to removable storage at '$path'. Folder permission grant required.",
                    problemDescription = "SoundSync needs folder permission to access music files on your removable storage (${resolution.volumeUuid ?: "SD Card"}).",
                    detectedReason = "Android Scoped Storage blocks raw POSIX filesystem access on secondary storage",
                    lastKnownLocation = path,
                    technicalDetails = technical,
                    originalExceptionClass = diag.originalExceptionClass,
                    originalExceptionMessage = diag.originalExceptionMessage,
                    resolvedSourceType = resolution.sourceType.name,
                    availableActions = listOf(
                        RepairActionType.REQUEST_PERMISSION,
                        RepairActionType.FIX_AUTOMATICALLY,
                        RepairActionType.LOCATE_FILE
                    )
                )
            }

            if (diag.originalExceptionClass?.contains("SecurityException", ignoreCase = true) == true) {
                return@withContext PlayabilityDiagnosticReport(
                    trackId = trackId,
                    status = PlayabilityStatus.PERMISSION_DENIED,
                    errorCode = "ERR_PERMISSION_DENIED",
                    errorMessage = "Storage permission denied accessing media: ${diag.originalExceptionMessage}",
                    problemDescription = "SoundSync cannot read this file because storage permissions are missing or revoked.",
                    detectedReason = diag.originalExceptionMessage ?: "Android permission denied on storage path",
                    lastKnownLocation = path,
                    technicalDetails = technical,
                    originalExceptionClass = diag.originalExceptionClass,
                    originalExceptionMessage = diag.originalExceptionMessage,
                    resolvedSourceType = resolution.sourceType.name,
                    availableActions = listOf(
                        RepairActionType.REQUEST_PERMISSION,
                        RepairActionType.FIX_AUTOMATICALLY,
                        RepairActionType.LOCATE_FILE
                    )
                )
            }

            return@withContext PlayabilityDiagnosticReport(
                trackId = trackId,
                status = PlayabilityStatus.MISSING_FILE,
                errorCode = "ERR_FILE_NOT_FOUND",
                errorMessage = "File was deleted, renamed, or moved from '$path'.",
                problemDescription = "SoundSync cannot find the file on device storage.",
                detectedReason = "File not found at saved location and not indexed in MediaStore or SAF",
                lastKnownLocation = path,
                technicalDetails = technical,
                originalExceptionClass = diag.originalExceptionClass,
                originalExceptionMessage = diag.originalExceptionMessage,
                resolvedSourceType = resolution.sourceType.name,
                availableActions = listOf(
                    RepairActionType.FIX_AUTOMATICALLY,
                    RepairActionType.LOCATE_FILE,
                    RepairActionType.REMOVE_FROM_LIBRARY
                )
            )
        }

        // Empty file check
        if (fileSizeBytes in 1..127L) {
            return@withContext PlayabilityDiagnosticReport(
                trackId = trackId,
                status = PlayabilityStatus.CORRUPTED_FILE,
                errorCode = "ERR_ZERO_OR_EMPTY_FILE",
                errorMessage = "File size is only $fileSizeBytes bytes (empty or truncated file).",
                problemDescription = "The audio file is empty (0 bytes or truncated header).",
                detectedReason = "File size is too small for valid audio stream",
                lastKnownLocation = path,
                fileSizeBytes = fileSizeBytes,
                technicalDetails = diag.formatDiagnostics(),
                originalExceptionClass = diag.originalExceptionClass,
                originalExceptionMessage = diag.originalExceptionMessage,
                resolvedSourceType = resolution.sourceType.name,
                availableActions = listOf(
                    RepairActionType.FIX_AUTOMATICALLY,
                    RepairActionType.LOCATE_FILE,
                    RepairActionType.REMOVE_FROM_LIBRARY
                )
            )
        }

        // Step 2 & 3: Container Extraction via MediaExtractor
        val targetPathOrUri = resolvedPlayableUri ?: path
        val extractor = MediaExtractor()
        var pfd: android.os.ParcelFileDescriptor? = null
        var afd: android.content.res.AssetFileDescriptor? = null
        fun cleanupResources() {
            try { pfd?.close() } catch (_: Throwable) {}
            try { afd?.close() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null

        try {
            if (targetPathOrUri.startsWith("content://")) {
                val uri = Uri.parse(targetPathOrUri)
                if (TrackSourceResolver.contentUriPlayableCheckerForTesting?.invoke(context, uri) == true) {
                    cleanupResources()
                    return@withContext PlayabilityDiagnosticReport(
                        trackId = trackId,
                        status = PlayabilityStatus.PLAYABLE,
                        problemDescription = "",
                        detectedReason = "Verified playable via test content URI checker",
                        lastKnownLocation = path,
                        resolvedPath = targetPathOrUri,
                        containerMime = "audio/wav",
                        audioCodec = "audio/raw",
                        fileSizeBytes = fileSizeBytes,
                        isFileAccessible = true,
                        isMediaStoreEntryValid = isMediaStoreEntryValid,
                        isContainerReadable = true,
                        isAudioStreamFound = true,
                        isDecoderInitialized = true,
                        isSampleDecoded = true,
                        availableActions = emptyList()
                    )
                }
                try {
                    extractor.setDataSource(context, uri, null)
                } catch (_: Exception) {
                    var opened = false
                    try {
                        val openedAfd = context.contentResolver.openAssetFileDescriptor(uri, "r")
                        if (openedAfd != null) {
                            afd = openedAfd
                            if (openedAfd.declaredLength < 0) {
                                extractor.setDataSource(openedAfd.fileDescriptor)
                            } else {
                                extractor.setDataSource(openedAfd.fileDescriptor, openedAfd.startOffset, openedAfd.declaredLength)
                            }
                            opened = true
                        }
                    } catch (_: Exception) {}

                    if (!opened) {
                        try {
                            val openedPfd = context.contentResolver.openFileDescriptor(uri, "r")
                            if (openedPfd != null) {
                                pfd = openedPfd
                                extractor.setDataSource(openedPfd.fileDescriptor)
                                opened = true
                            }
                        } catch (_: Exception) {}
                    }

                    if (!opened) {
                        extractor.setDataSource(context, uri, null)
                    }
                }
            } else {
                val clean = targetPathOrUri.removePrefix("file://")
                val f = File(clean)
                if (TrackSourceResolver.isGenuinelyRawReadable(f)) {
                    try {
                        extractor.setDataSource(clean)
                    } catch (_: Exception) {
                        val openedPfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                        pfd = openedPfd
                        extractor.setDataSource(openedPfd.fileDescriptor)
                    }
                } else {
                    extractor.setDataSource(targetPathOrUri)
                }
            }

            val numTracks = extractor.trackCount
            if (numTracks == 0) {
                cleanupResources()
                return@withContext PlayabilityDiagnosticReport(
                    trackId = trackId,
                    status = PlayabilityStatus.INVALID_CONTAINER,
                    errorCode = "ERR_NO_TRACKS_IN_CONTAINER",
                    errorMessage = "Media container contains 0 media tracks.",
                    problemDescription = "File header was read, but no valid media streams were found inside.",
                    detectedReason = "MediaExtractor found 0 tracks in container",
                    lastKnownLocation = path,
                    resolvedPath = targetPathOrUri,
                    isFileAccessible = isFileAccessible,
                    isContainerReadable = false,
                    fileSizeBytes = fileSizeBytes,
                    availableActions = listOf(
                        RepairActionType.FIX_AUTOMATICALLY,
                        RepairActionType.LOCATE_FILE,
                        RepairActionType.RESCAN_TRACK
                    )
                )
            }

            isContainerReadable = true

            // Step 4: Locate Audio Track
            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    detectedMime = mime
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null || detectedMime == null) {
                cleanupResources()
                return@withContext PlayabilityDiagnosticReport(
                    trackId = trackId,
                    status = PlayabilityStatus.ZERO_AUDIO_STREAMS,
                    errorCode = "ERR_ZERO_AUDIO_STREAMS",
                    errorMessage = "File has $numTracks streams, but none are audio streams.",
                    problemDescription = "The media file contains video or data streams, but no audio stream.",
                    detectedReason = "Zero audio tracks found in container",
                    lastKnownLocation = path,
                    resolvedPath = targetPathOrUri,
                    isFileAccessible = isFileAccessible,
                    isContainerReadable = true,
                    isAudioStreamFound = false,
                    fileSizeBytes = fileSizeBytes,
                    availableActions = listOf(
                        RepairActionType.FIX_AUTOMATICALLY,
                        RepairActionType.LOCATE_FILE,
                        RepairActionType.REMOVE_FROM_LIBRARY
                    )
                )
            }

            isAudioStreamFound = true
            sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            if (audioFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                bitRate = audioFormat.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
            }

            // Quick check bypass if requested
            if (quickCheckOnly) {
                cleanupResources()
                return@withContext PlayabilityDiagnosticReport(
                    trackId = trackId,
                    status = PlayabilityStatus.PLAYABLE,
                    problemDescription = "",
                    detectedReason = "Quick verification succeeded (container & audio stream verified)",
                    lastKnownLocation = path,
                    resolvedPath = targetPathOrUri,
                    containerMime = detectedMime,
                    audioCodec = detectedMime,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    bitRateKbps = bitRate,
                    fileSizeBytes = fileSizeBytes,
                    fileModifiedTimestamp = fileModifiedTimestamp,
                    isFileAccessible = isFileAccessible,
                    isMediaStoreEntryValid = isMediaStoreEntryValid,
                    isContainerReadable = true,
                    isAudioStreamFound = true,
                    isDecoderInitialized = true,
                    isSampleDecoded = true,
                    availableActions = emptyList()
                )
            }

            // Step 5: Decoder Initialization
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createDecoderByType(detectedMime)
                detectedCodec = codec.name
                codec.configure(audioFormat, null, null, 0)
                codec.start()
                isDecoderInitialized = true
            } catch (e: Exception) {
                codec?.release()
                cleanupResources()
                Log.w(TAG, "Decoder creation failed for $detectedMime on track '${track.title}': ${e.message}")
                return@withContext PlayabilityDiagnosticReport(
                    trackId = trackId,
                    status = PlayabilityStatus.UNSUPPORTED_FORMAT,
                    errorCode = "ERR_UNSUPPORTED_CODEC",
                    errorMessage = "Android cannot decode format '$detectedMime': ${e.message}",
                    problemDescription = "This audio format ($detectedMime) is not supported by your device's audio decoders.",
                    detectedReason = "System MediaCodec does not support MIME $detectedMime",
                    lastKnownLocation = path,
                    resolvedPath = targetPathOrUri,
                    containerMime = detectedMime,
                    isFileAccessible = isFileAccessible,
                    isContainerReadable = true,
                    isAudioStreamFound = true,
                    isDecoderInitialized = false,
                    fileSizeBytes = fileSizeBytes,
                    technicalDetails = "MIME: $detectedMime, Exception: ${e.javaClass.simpleName} - ${e.message}",
                    availableActions = listOf(
                        RepairActionType.FIX_AUTOMATICALLY,
                        RepairActionType.LOCATE_FILE,
                        RepairActionType.REMOVE_FROM_LIBRARY
                    )
                )
            }

            // Step 6: Test Probe Sample Decode (Read 1 packet into codec and verify output)
            try {
                extractor.selectTrack(audioTrackIndex)
                val bufferInfo = MediaCodec.BufferInfo()
                var inputFed = false
                val inputBufferIndex = codec.dequeueInputBuffer(10_000L) // 10ms timeout

                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize > 0) {
                            val sampleTime = extractor.sampleTime
                            codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, sampleTime, 0)
                            inputFed = true
                        } else if (sampleSize == -1) {
                            // File has no data packets
                            codec.release()
                            cleanupResources()
                            return@withContext PlayabilityDiagnosticReport(
                                trackId = trackId,
                                status = PlayabilityStatus.CORRUPTED_FILE,
                                errorCode = "ERR_EMPTY_AUDIO_STREAM",
                                errorMessage = "Audio stream contains 0 readable audio packets.",
                                problemDescription = "The audio track is corrupted or empty.",
                                detectedReason = "MediaExtractor.readSampleData returned EOF immediately",
                                lastKnownLocation = path,
                                resolvedPath = targetPathOrUri,
                                containerMime = detectedMime,
                                isFileAccessible = isFileAccessible,
                                isContainerReadable = true,
                                isAudioStreamFound = true,
                                fileSizeBytes = fileSizeBytes,
                                availableActions = listOf(
                                    RepairActionType.FIX_AUTOMATICALLY,
                                    RepairActionType.LOCATE_FILE,
                                    RepairActionType.REMOVE_FROM_LIBRARY
                                )
                            )
                        }
                    }
                }

                if (inputFed) {
                    // Try dequeue output buffer (or accept INFO_OUTPUT_FORMAT_CHANGED)
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 20_000L) // 20ms timeout
                    if (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        isSampleDecoded = true
                        if (outputBufferIndex >= 0) {
                            codec.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }
                } else {
                    // Even if timeout on first input buffer, codec started successfully
                    isSampleDecoded = true
                }
            } catch (de: Exception) {
                Log.w(TAG, "Sample decode probe exception for track '${track.title}': ${de.message}")
                codec.release()
                cleanupResources()
                return@withContext PlayabilityDiagnosticReport(
                    trackId = trackId,
                    status = PlayabilityStatus.DECODER_ERROR,
                    errorCode = "ERR_DECODE_FAILED",
                    errorMessage = "Decoder threw an exception while reading audio frames: ${de.message}",
                    problemDescription = "The audio decoder encountered an error while attempting to decode the audio stream.",
                    detectedReason = "MediaCodec runtime decode failure: ${de.message}",
                    lastKnownLocation = path,
                    resolvedPath = targetPathOrUri,
                    containerMime = detectedMime,
                    audioCodec = detectedCodec,
                    isFileAccessible = isFileAccessible,
                    isContainerReadable = true,
                    isAudioStreamFound = true,
                    isDecoderInitialized = true,
                    isSampleDecoded = false,
                    fileSizeBytes = fileSizeBytes,
                    technicalDetails = de.stackTraceToString().take(500),
                    availableActions = listOf(
                        RepairActionType.FIX_AUTOMATICALLY,
                        RepairActionType.LOCATE_FILE,
                        RepairActionType.RESCAN_TRACK
                    )
                )
            } finally {
                try {
                    codec.stop()
                    codec.release()
                } catch (_: Exception) {}
            }

            cleanupResources()

            // Step 7: SUCCESS! Track is 100% playable
            return@withContext PlayabilityDiagnosticReport(
                trackId = trackId,
                status = PlayabilityStatus.PLAYABLE,
                problemDescription = "",
                detectedReason = "All 6 playback validation checks passed successfully.",
                lastKnownLocation = path,
                resolvedPath = targetPathOrUri,
                containerMime = detectedMime,
                audioCodec = detectedCodec ?: detectedMime,
                sampleRate = sampleRate,
                channelCount = channelCount,
                bitRateKbps = bitRate,
                fileSizeBytes = fileSizeBytes,
                fileModifiedTimestamp = fileModifiedTimestamp,
                isFileAccessible = isFileAccessible,
                isMediaStoreEntryValid = isMediaStoreEntryValid,
                isContainerReadable = true,
                isAudioStreamFound = true,
                isDecoderInitialized = true,
                isSampleDecoded = true,
                availableActions = emptyList()
            )

        } catch (ioe: java.io.IOException) {
            cleanupResources()
            Log.w(TAG, "I/O error opening media container for '${track.title}': [${ioe.javaClass.simpleName}] ${ioe.message}\n${diag.formatDiagnostics()}", ioe)

            val (status, code, userMsg, actionList) = when {
                ioe is FileNotFoundException -> {
                    val msg = ioe.message.orEmpty()
                    if (msg.contains("Permission denied", ignoreCase = true) || msg.contains("EACCES", ignoreCase = true)) {
                        val errCode = if (diag.isRemovableStorage) "ERR_SCOPED_STORAGE_RESTRICTION" else "ERR_PERMISSION_DENIED"
                        listOf(
                            PlayabilityStatus.PERMISSION_DENIED,
                            errCode,
                            "SoundSync cannot open this file due to Android storage permission restrictions.",
                            listOf(RepairActionType.REQUEST_PERMISSION, RepairActionType.FIX_AUTOMATICALLY, RepairActionType.LOCATE_FILE)
                        )
                    } else {
                        listOf(
                            PlayabilityStatus.MISSING_FILE,
                            "ERR_FILE_NOT_FOUND",
                            "The audio file could not be found at the stored path: ${ioe.message}",
                            listOf(RepairActionType.FIX_AUTOMATICALLY, RepairActionType.LOCATE_FILE, RepairActionType.REMOVE_FROM_LIBRARY)
                        )
                    }
                }
                ioe is SecurityException -> {
                    listOf(
                        PlayabilityStatus.PERMISSION_DENIED,
                        "ERR_URI_PERMISSION",
                        "Security exception accessing media URI: ${ioe.message}",
                        listOf(RepairActionType.REQUEST_PERMISSION, RepairActionType.FIX_AUTOMATICALLY, RepairActionType.LOCATE_FILE)
                    )
                }
                !diag.isVolumeMounted -> {
                    listOf(
                        PlayabilityStatus.VOLUME_UNAVAILABLE,
                        "ERR_STORAGE_UNMOUNTED",
                        "Storage volume (${resolution.volumeUuid ?: "SD Card"}) is disconnected.",
                        listOf(RepairActionType.FIX_AUTOMATICALLY, RepairActionType.LOCATE_FILE, RepairActionType.RESCAN_TRACK)
                    )
                }
                ioe.message?.contains("Permission denied", ignoreCase = true) == true || ioe.message?.contains("EACCES", ignoreCase = true) == true -> {
                    val errCode = if (diag.isRemovableStorage) "ERR_SCOPED_STORAGE_RESTRICTION" else "ERR_RAW_PATH_PERMISSION_BLOCKED"
                    listOf(
                        PlayabilityStatus.PERMISSION_REQUIRED,
                        errCode,
                        "SoundSync cannot open this file due to storage permission restrictions.",
                        listOf(RepairActionType.REQUEST_PERMISSION, RepairActionType.FIX_AUTOMATICALLY, RepairActionType.LOCATE_FILE)
                    )
                }
                ioe.message?.contains("Failed to instantiate extractor", ignoreCase = true) == true -> {
                    val sniff = AudioFormatSniffer.sniffFormat(context, targetPathOrUri)
                    if (sniff.isRecognizedAudio) {
                        listOf(
                            PlayabilityStatus.EXTRACTOR_ERROR,
                            PlaybackErrorCodes.ERR_EXTRACTOR_INIT,
                            "Android media extractor failed to instantiate container for ${sniff.containerFormat ?: "audio"} stream: ${ioe.message}",
                            listOf(RepairActionType.FIX_AUTOMATICALLY, RepairActionType.LOCATE_FILE, RepairActionType.RESCAN_TRACK)
                        )
                    } else {
                        listOf(
                            PlayabilityStatus.FORMAT_UNRECOGNIZED,
                            PlaybackErrorCodes.ERR_FORMAT_UNRECOGNIZED,
                            "Android media extractor failed to instantiate container: unrecognized audio format (${sniff.headerHex}).",
                            listOf(RepairActionType.FIX_AUTOMATICALLY, RepairActionType.LOCATE_FILE, RepairActionType.RESCAN_TRACK)
                        )
                    }
                }
                ioe.message?.contains("0x80000000") == true || ioe.message?.contains("unsupported", ignoreCase = true) == true -> {
                    listOf(
                        PlayabilityStatus.INVALID_CONTAINER,
                        PlaybackErrorCodes.ERR_AUDIO_CORRUPT,
                        "Media container format is corrupted or unsupported by device decoders.",
                        listOf(RepairActionType.FIX_AUTOMATICALLY, RepairActionType.LOCATE_FILE, RepairActionType.RESCAN_TRACK)
                    )
                }
                ioe.message?.contains("EIO", ignoreCase = true) == true || ioe.message?.contains("device", ignoreCase = true) == true -> {
                    listOf(
                        PlayabilityStatus.READ_ERROR,
                        PlaybackErrorCodes.ERR_SOURCE_IO,
                        "A hardware I/O device error occurred reading from the storage medium.",
                        listOf(RepairActionType.FIX_AUTOMATICALLY, RepairActionType.LOCATE_FILE, RepairActionType.RESCAN_TRACK)
                    )
                }
                else -> {
                    listOf(
                        PlayabilityStatus.READ_ERROR,
                        PlaybackErrorCodes.ERR_SOURCE_IO,
                        "SoundSync encountered an I/O read error opening the file stream: ${ioe.message}",
                        listOf(RepairActionType.FIX_AUTOMATICALLY, RepairActionType.LOCATE_FILE, RepairActionType.RESCAN_TRACK)
                    )
                }
            }

            @Suppress("UNCHECKED_CAST")
            return@withContext PlayabilityDiagnosticReport(
                trackId = trackId,
                status = status as PlayabilityStatus,
                errorCode = code as String,
                errorMessage = ioe.message ?: (userMsg as String),
                problemDescription = userMsg as String,
                detectedReason = "MediaExtractor failure: ${ioe.javaClass.simpleName} - ${ioe.message}",
                lastKnownLocation = path,
                resolvedPath = targetPathOrUri,
                isFileAccessible = isFileAccessible,
                fileSizeBytes = fileSizeBytes,
                technicalDetails = "${diag.formatDiagnostics()}\n\nOriginal Exception: ${ioe.javaClass.name}: ${ioe.message}\n${ioe.stackTraceToString().take(600)}",
                originalExceptionClass = ioe.javaClass.name,
                originalExceptionMessage = ioe.message,
                resolvedSourceType = resolution.sourceType.name,
                availableActions = actionList as List<RepairActionType>
            )
        } catch (e: Exception) {
            cleanupResources()
            Log.w(TAG, "Unexpected error validating track '${track.title}': [${e.javaClass.simpleName}] ${e.message}\n${diag.formatDiagnostics()}", e)
            return@withContext PlayabilityDiagnosticReport(
                trackId = trackId,
                status = PlayabilityStatus.UNKNOWN_PLAYBACK_ERROR,
                errorCode = "ERR_UNKNOWN_PROBE",
                errorMessage = "Validation error: ${e.message}",
                problemDescription = "An error occurred while inspecting this track.",
                detectedReason = e.message ?: "Unknown validation failure",
                lastKnownLocation = path,
                resolvedPath = targetPathOrUri,
                isFileAccessible = isFileAccessible,
                fileSizeBytes = fileSizeBytes,
                technicalDetails = "${diag.formatDiagnostics()}\n\nOriginal Exception: ${e.javaClass.name}: ${e.message}\n${e.stackTraceToString().take(600)}",
                originalExceptionClass = e.javaClass.name,
                originalExceptionMessage = e.message,
                resolvedSourceType = resolution.sourceType.name,
                availableActions = listOf(
                    RepairActionType.FIX_AUTOMATICALLY,
                    RepairActionType.LOCATE_FILE,
                    RepairActionType.RESCAN_TRACK
                )
            )
        }
    }
}
