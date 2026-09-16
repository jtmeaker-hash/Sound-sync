package com.example.doctor

import android.content.Context
import android.util.Log
import com.example.analysis.DuplicateDetector
import com.example.audio.BitrateProbe
import com.example.brain.BrainCategory
import com.example.brain.BrainProcessingState
import com.example.brain.BrainSubStatus
import com.example.brain.LibraryBrain
import com.example.data.AppDatabase
import com.example.data.TrackBrainDao
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.AnalysisState
import com.example.model.AudioQualityRating
import com.example.model.PlayabilityStatus
import com.example.model.Track
import com.example.storage.CanonicalStorageHelper
import com.example.storage.TrackSelfHealingResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * SoundSync Library Doctor Auditor:
 * Comprehensive audit engine evaluating library health across 12 distinct categories
 * without blocking UI or disrupting active audio playback.
 */
class LibraryDoctorAuditor(
    private val context: Context,
    database: AppDatabase = AppDatabase.getDatabase(context),
    private val preferences: LibraryDoctorPreferences = LibraryDoctorPreferences.getInstance(context),
    trackDaoOverride: TrackDao? = null,
    brainDaoOverride: TrackBrainDao? = null
) {
    companion object {
        private const val TAG = "LibraryDoctorAuditor"
    }

    private val trackDao: TrackDao = trackDaoOverride ?: database.trackDao()
    private val brainDao: TrackBrainDao = brainDaoOverride ?: database.trackBrainDao()

    private val _auditReportFlow = MutableStateFlow(DoctorAuditReport())
    val auditReportFlow: StateFlow<DoctorAuditReport> = _auditReportFlow.asStateFlow()

    suspend fun runAudit(): DoctorAuditReport = withContext(Dispatchers.IO) {
        _auditReportFlow.value = _auditReportFlow.value.copy(
            isAuditing = true,
            auditProgress = 0.05f,
            statusMessage = "Loading library tracks..."
        )

        Log.i(TAG, "Beginning SoundSync Library Doctor audit...")

        val allEntities = trackDao.getAllTracksList()
        val totalTracks = allEntities.size

        if (totalTracks == 0) {
            val emptyReport = DoctorAuditReport(
                summary = DoctorHealthSummary(healthScore = 100, totalTracks = 0),
                issues = emptyList(),
                isAuditing = false,
                auditProgress = 1.0f,
                statusMessage = "Library is empty",
                lastAuditTime = System.currentTimeMillis()
            )
            _auditReportFlow.value = emptyReport
            return@withContext emptyReport
        }

        val issues = mutableListOf<DoctorIssue>()
        val brainStatuses = brainDao.getAllStatuses().associateBy { it.trackId }

        var missingArtworkCount = 0
        var missingFilesCount = 0
        var failedAnalysisCount = 0
        var needsReviewCount = 0
        var completeTracksCount = 0

        // 1. Audit tracks iteratively
        for ((index, entity) in allEntities.withIndex()) {
            if (index % 50 == 0) {
                val progress = 0.1f + 0.7f * (index.toFloat() / totalTracks)
                _auditReportFlow.value = _auditReportFlow.value.copy(
                    auditProgress = progress,
                    statusMessage = "Auditing track ${index + 1} of $totalTracks..."
                )
            }

            val track = entity.toTrack()
            val brainStatus = brainStatuses[track.id]
            val normPath = track.filePath.trim()
            var trackHasIssue = false

            // --- CATEGORY 4 & 11: BROKEN FILE PATHS & MISSING FILES ---
            val isContentUri = normPath.startsWith("content://")
            val isFileReadable = CanonicalStorageHelper.isReferenceReadable(context, normPath)

            if (!isFileReadable) {
                val healed = TrackSelfHealingResolver.healTrack(context, track, trackDao)
                if (healed == null) {
                    missingFilesCount++
                    trackHasIssue = true
                    val issueId = "missing_file_${track.id}"
                    val reviewStatus = preferences.getIssueStatus(issueId)
                    issues.add(
                        DoctorIssue(
                            id = issueId,
                            category = if (isContentUri) DoctorCategory.BROKEN_FILE_PATHS else DoctorCategory.MISSING_FILES,
                            trackId = track.id,
                            trackTitle = track.title,
                            artist = track.artist,
                            album = track.album,
                            filePath = track.filePath,
                            problem = if (isContentUri) "Invalid or revoked content URI" else "Physical audio file missing from storage",
                            evidence = "System cannot access or read audio file reference: $normPath",
                            recommendedAction = if (isContentUri) "Relink directory permissions or locate file" else "Restore file to storage or remove stale entry",
                            confidence = 1.0f,
                            currentValue = normPath,
                            proposedValue = null,
                            isSafeAutoRepair = false,
                            severity = DoctorIssueSeverity.CRITICAL,
                            reviewStatus = reviewStatus
                        )
                    )
                }
            }

            // --- CATEGORY 1: MISSING ARTWORK ---
            val hasArt = com.example.metadata.artwork.CanonicalArtworkDetector.hasArtwork(context, track)

            if (!hasArt) {
                missingArtworkCount++
                trackHasIssue = true
                val issueId = "missing_art_${track.id}"
                val reviewStatus = preferences.getIssueStatus(issueId)
                issues.add(
                    DoctorIssue(
                        id = issueId,
                        category = DoctorCategory.MISSING_ARTWORK,
                        trackId = track.id,
                        trackTitle = track.title,
                        artist = track.artist,
                        album = track.album,
                        filePath = track.filePath,
                        problem = "Front cover artwork not available or broken",
                        evidence = "No valid embedded, cached, local file, or provider artwork found",
                        recommendedAction = "Fetch artwork via Library Brain and online metadata providers",
                        confidence = 0.95f,
                        currentValue = track.artworkCachePath ?: track.artworkUrl ?: "None",
                        proposedValue = "Fetch via Library Brain",
                        isSafeAutoRepair = true,
                        severity = DoctorIssueSeverity.INFO,
                        reviewStatus = reviewStatus
                    )
                )
            }

            // --- CATEGORY 2: MISSING / UNKNOWN ARTIST ---
            val isArtistBlank = track.artist.isBlank()
            val isUnknownArtist = track.artist.equals("Unknown Artist", ignoreCase = true)
            val embeddedArtistMatch = if (track.title.contains(" - ")) {
                val parts = track.title.split(" - ", limit = 2)
                if (parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0].trim() to parts[1].trim() else null
            } else null

            if (isArtistBlank || isUnknownArtist) {
                trackHasIssue = true
                val issueId = "missing_artist_${track.id}"
                val reviewStatus = preferences.getIssueStatus(issueId)

                if (embeddedArtistMatch != null) {
                    issues.add(
                        DoctorIssue(
                            id = issueId,
                            category = DoctorCategory.MISSING_ARTIST,
                            trackId = track.id,
                            trackTitle = track.title,
                            artist = track.artist,
                            album = track.album,
                            filePath = track.filePath,
                            problem = "Artist appears embedded in title text",
                            evidence = "Title contains separator '${track.title}', suggesting Artist: '${embeddedArtistMatch.first}', Title: '${embeddedArtistMatch.second}'",
                            recommendedAction = "Split title into Artist '${embeddedArtistMatch.first}' and Title '${embeddedArtistMatch.second}'",
                            confidence = 0.90f,
                            currentValue = "Artist: '${track.artist}', Title: '${track.title}'",
                            proposedValue = "Artist: '${embeddedArtistMatch.first}', Title: '${embeddedArtistMatch.second}'",
                            isSafeAutoRepair = true,
                            severity = DoctorIssueSeverity.WARNING,
                            reviewStatus = reviewStatus
                        )
                    )
                } else {
                    issues.add(
                        DoctorIssue(
                            id = issueId,
                            category = DoctorCategory.MISSING_ARTIST,
                            trackId = track.id,
                            trackTitle = track.title,
                            artist = track.artist,
                            album = track.album,
                            filePath = track.filePath,
                            problem = "Missing or uncredited artist",
                            evidence = "Artist tag is '${track.artist.ifBlank { "<empty>" }}'",
                            recommendedAction = "Query MusicBrainz / Apple iTunes via Library Brain",
                            confidence = 0.85f,
                            currentValue = track.artist,
                            proposedValue = "Lookup via Library Brain",
                            isSafeAutoRepair = true,
                            severity = DoctorIssueSeverity.WARNING,
                            reviewStatus = reviewStatus
                        )
                    )
                }
            }

            // --- CATEGORY 5: CORRUPTED AUDIO ---
            if (track.playability == PlayabilityStatus.CORRUPTED_FILE || track.playability == PlayabilityStatus.DECODER_ERROR || track.durationSeconds <= 1) {
                trackHasIssue = true
                val issueId = "corrupt_${track.id}"
                val reviewStatus = preferences.getIssueStatus(issueId)
                issues.add(
                    DoctorIssue(
                        id = issueId,
                        category = DoctorCategory.CORRUPTED_AUDIO,
                        trackId = track.id,
                        trackTitle = track.title,
                        artist = track.artist,
                        album = track.album,
                        filePath = track.filePath,
                        problem = "Audio decoder failed or invalid duration",
                        evidence = "Duration is ${track.durationSeconds}s, playability state is ${track.playability.name}",
                        recommendedAction = "Inspect audio container headers or re-encode",
                        confidence = 0.90f,
                        currentValue = "${track.durationSeconds}s",
                        proposedValue = null,
                        isSafeAutoRepair = false,
                        severity = DoctorIssueSeverity.CRITICAL,
                        reviewStatus = reviewStatus
                    )
                )
            }

            // --- CATEGORY 6: SUSPICIOUS BPM ---
            if (track.bpm > 0.0) {
                val isOutlier = track.bpm < 40.0 || track.bpm > 250.0
                val isLowConfidence = track.bpmConfidence in 0.01..0.35
                if (isOutlier || isLowConfidence) {
                    trackHasIssue = true
                    needsReviewCount++
                    val issueId = "suspicious_bpm_${track.id}"
                    val reviewStatus = preferences.getIssueStatus(issueId)
                    val proposed = if (track.bpm > 160.0) "${track.bpm / 2.0} BPM (Half-time)" else if (track.bpm < 75.0) "${track.bpm * 2.0} BPM (Double-time)" else "Re-analyse via DSP"
                    issues.add(
                        DoctorIssue(
                            id = issueId,
                            category = DoctorCategory.SUSPICIOUS_BPM,
                            trackId = track.id,
                            trackTitle = track.title,
                            artist = track.artist,
                            album = track.album,
                            filePath = track.filePath,
                            problem = if (isOutlier) "BPM (${String.format("%.1f", track.bpm)}) is outside normal musical range" else "Low confidence tempo estimate (${(track.bpmConfidence * 100).toInt()}%)",
                            evidence = "BPM: ${track.bpm}, Confidence: ${track.bpmConfidence}, Version: ${track.bpmAnalysisVersion}",
                            recommendedAction = "Verify tempo, test half/double-time, or trigger Brain DSP re-analysis",
                            confidence = 0.70f,
                            currentValue = "${String.format("%.1f", track.bpm)} BPM",
                            proposedValue = proposed,
                            isSafeAutoRepair = false,
                            severity = DoctorIssueSeverity.WARNING,
                            reviewStatus = reviewStatus
                        )
                    )
                }
            }

            // --- CATEGORY 7: SUSPICIOUS KEY ---
            if (track.musicalKey.isNotBlank()) {
                val isKeyLowConfidence = track.keyConfidence in 0.01..0.30
                val isValidKeyFormat = track.camelotKey.matches(Regex("^(1[0-2]|[1-9])[AB]$")) ||
                        track.musicalKey.matches(Regex("^[A-G][#b]?(m|min|maj)?$", RegexOption.IGNORE_CASE))
                if (isKeyLowConfidence || !isValidKeyFormat) {
                    trackHasIssue = true
                    needsReviewCount++
                    val issueId = "suspicious_key_${track.id}"
                    val reviewStatus = preferences.getIssueStatus(issueId)
                    issues.add(
                        DoctorIssue(
                            id = issueId,
                            category = DoctorCategory.SUSPICIOUS_KEY,
                            trackId = track.id,
                            trackTitle = track.title,
                            artist = track.artist,
                            album = track.album,
                            filePath = track.filePath,
                            problem = if (!isValidKeyFormat) "Non-standard musical key notation: '${track.musicalKey}'" else "Low confidence harmonic key estimate (${(track.keyConfidence * 100).toInt()}%)",
                            evidence = "Musical key: ${track.musicalKey}, Camelot: ${track.camelotKey}, Confidence: ${track.keyConfidence}",
                            recommendedAction = "Re-run chromagram analysis in Library Brain or edit key manually",
                            confidence = 0.75f,
                            currentValue = "${track.musicalKey} (${track.camelotKey})",
                            proposedValue = "Re-analyse via Brain",
                            isSafeAutoRepair = true,
                            severity = DoctorIssueSeverity.INFO,
                            reviewStatus = reviewStatus
                        )
                    )
                }
            }

            // --- CATEGORY 8: LOW QUALITY AUDIO ---
            if (track.qualityRating == AudioQualityRating.LOW_128 || (track.bitrateKbps in 1..128)) {
                val issueId = "low_quality_${track.id}"
                val reviewStatus = preferences.getIssueStatus(issueId)
                val kbps = if (track.bitrateKbps > 0) track.bitrateKbps else 128
                issues.add(
                    DoctorIssue(
                        id = issueId,
                        category = DoctorCategory.LOW_QUALITY_AUDIO,
                        trackId = track.id,
                        trackTitle = track.title,
                        artist = track.artist,
                        album = track.album,
                        filePath = track.filePath,
                        problem = "Low bitrate lossy audio ($kbps kbps)",
                        evidence = "Format is ${track.format}, Bitrate: $kbps kbps, quality classification: ${track.qualityRating.name}",
                        recommendedAction = "Inspect track quality; consider replacing with higher bitrate release",
                        confidence = 0.85f,
                        currentValue = "$kbps kbps (${track.format})",
                        proposedValue = null,
                        isSafeAutoRepair = false,
                        severity = DoctorIssueSeverity.INFO,
                        reviewStatus = reviewStatus
                    )
                )
            }

            // --- CATEGORY 10: INCOMPLETE ANALYSIS ---
            val lacksBpm = track.bpm <= 0.0
            val lacksKey = track.musicalKey.isBlank() && track.camelotKey.isBlank()
            val lacksBrainComplete = brainStatus?.overallStatus != BrainProcessingState.COMPLETE.name
            if (lacksBpm || lacksKey || lacksBrainComplete) {
                trackHasIssue = true
                val issueId = "incomplete_${track.id}"
                val reviewStatus = preferences.getIssueStatus(issueId)
                val missingParts = buildList {
                    if (lacksBpm) add("BPM")
                    if (lacksKey) add("Key")
                    if (brainStatus?.waveformStatus != BrainSubStatus.COMPLETE.name) add("Waveform")
                    if (brainStatus?.metadataStatus != BrainSubStatus.COMPLETE.name) add("Metadata")
                }
                issues.add(
                    DoctorIssue(
                        id = issueId,
                        category = DoctorCategory.INCOMPLETE_ANALYSIS,
                        trackId = track.id,
                        trackTitle = track.title,
                        artist = track.artist,
                        album = track.album,
                        filePath = track.filePath,
                        problem = "Missing analysis components: ${missingParts.joinToString(", ")}",
                        evidence = "Library Brain status: ${brainStatus?.overallStatus ?: "PENDING"}, incomplete modules: $missingParts",
                        recommendedAction = "Queue complete multi-stage analysis in Library Brain",
                        confidence = 1.0f,
                        currentValue = "Incomplete",
                        proposedValue = "Queue in Brain",
                        isSafeAutoRepair = true,
                        severity = DoctorIssueSeverity.INFO,
                        reviewStatus = reviewStatus
                    )
                )
            }

            // --- CATEGORY 12: FAILED BACKGROUND JOBS ---
            if (brainStatus?.overallStatus == BrainProcessingState.FAILED.name || (brainStatus?.retryCount ?: 0) >= 3) {
                failedAnalysisCount++
                trackHasIssue = true
                val issueId = "failed_job_${track.id}"
                val reviewStatus = preferences.getIssueStatus(issueId)
                issues.add(
                    DoctorIssue(
                        id = issueId,
                        category = DoctorCategory.FAILED_BACKGROUND_JOBS,
                        trackId = track.id,
                        trackTitle = track.title,
                        artist = track.artist,
                        album = track.album,
                        filePath = track.filePath,
                        problem = "Background processing failed after multiple attempts",
                        evidence = "Error code: ${brainStatus?.errorCode ?: "UNKNOWN"}, message: ${brainStatus?.errorMessage ?: "None"}, attempts: ${brainStatus?.retryCount}",
                        recommendedAction = "Reset error state and retry processing in Library Brain",
                        confidence = 1.0f,
                        currentValue = "Failed (${brainStatus?.retryCount} retries)",
                        proposedValue = "Retry in Brain",
                        isSafeAutoRepair = true,
                        severity = DoctorIssueSeverity.ERROR,
                        reviewStatus = reviewStatus
                    )
                )
            }

            if (!trackHasIssue) {
                completeTracksCount++
            }
        }

        // --- CATEGORY 3: DUPLICATE TRACKS (CONSERVATIVE DETECTION) ---
        _auditReportFlow.value = _auditReportFlow.value.copy(
            auditProgress = 0.85f,
            statusMessage = "Analyzing duplicates..."
        )

        // Group 1: By contentFingerprint (if non-blank)
        val fingerprintGroups = allEntities.filter { it.contentFingerprint.isNotBlank() }
            .groupBy { it.contentFingerprint }
            .filter { it.value.size > 1 }

        for ((fp, group) in fingerprintGroups) {
            val primary = group.first()
            for (dup in group.drop(1)) {
                val issueId = "dup_fp_${primary.id}_${dup.id}"
                val reviewStatus = preferences.getIssueStatus(issueId)
                issues.add(
                    DoctorIssue(
                        id = issueId,
                        category = DoctorCategory.DUPLICATE_TRACKS,
                        trackId = dup.id,
                        trackTitle = dup.title,
                        artist = dup.artist,
                        album = dup.album,
                        filePath = dup.filePath,
                        problem = "Exact audio fingerprint duplicate of '${primary.title}'",
                        evidence = "Matching AcoustID/content fingerprint ($fp), Duration: ${dup.durationSeconds}s vs ${primary.durationSeconds}s",
                        recommendedAction = "Compare file paths and keep primary copy",
                        confidence = 0.98f,
                        currentValue = dup.filePath,
                        proposedValue = primary.filePath,
                        isSafeAutoRepair = false,
                        severity = DoctorIssueSeverity.WARNING,
                        reviewStatus = reviewStatus,
                        secondaryTrackId = primary.id
                    )
                )
            }
        }

        // Group 2: Conservative normalized Title + Artist + Duration (+/- 1 sec)
        val normalizedMetaGroups = allEntities
            .filter { it.artist.isNotBlank() && it.artist != "Unknown Artist" && it.title.isNotBlank() && it.title != "Untitled" }
            .groupBy { "${it.artist.trim().lowercase()}:::${it.title.trim().lowercase()}" }
            .filter { it.value.size > 1 }

        for ((_, group) in normalizedMetaGroups) {
            val primary = group.first()
            for (dup in group.drop(1)) {
                if (abs(dup.durationSeconds - primary.durationSeconds) <= 2 && dup.filePath != primary.filePath) {
                    val issueId = "dup_meta_${primary.id}_${dup.id}"
                    if (issues.none { it.id == issueId }) {
                        val isRemixOrLive = dup.title.contains(Regex("(?i)(remix|live|mix|edit|dub|vip|acoustic|instrumental)"))
                        val reviewStatus = preferences.getIssueStatus(issueId)
                        issues.add(
                            DoctorIssue(
                                id = issueId,
                                category = DoctorCategory.DUPLICATE_TRACKS,
                                trackId = dup.id,
                                trackTitle = dup.title,
                                artist = dup.artist,
                                album = dup.album,
                                filePath = dup.filePath,
                                problem = if (isRemixOrLive) "Possible alternate version/remix: '${dup.title}'" else "Probable duplicate recording of '${primary.title}'",
                                evidence = "Matching Artist ('${dup.artist}') and Title ('${dup.title}'), Duration delta: ${abs(dup.durationSeconds - primary.durationSeconds)}s",
                                recommendedAction = if (isRemixOrLive) "Keep both (different mix/live version)" else "Review paths and select track to retain",
                                confidence = if (isRemixOrLive) 0.50f else 0.88f,
                                currentValue = dup.filePath,
                                proposedValue = primary.filePath,
                                isSafeAutoRepair = false,
                                severity = DoctorIssueSeverity.WARNING,
                                reviewStatus = reviewStatus,
                                secondaryTrackId = primary.id
                            )
                        )
                    }
                }
            }
        }

        // --- CATEGORY 9: INCONSISTENT ALBUM NAMES ---
        _auditReportFlow.value = _auditReportFlow.value.copy(
            auditProgress = 0.92f,
            statusMessage = "Analyzing album consistency..."
        )

        val artistGroups = allEntities
            .filter { it.artist.isNotBlank() && it.artist != "Unknown Artist" && it.album.isNotBlank() && it.album != "Single" }
            .groupBy { it.artist.trim().lowercase() }

        for ((_, tracksForArtist) in artistGroups) {
            val albumCanonicalGroups = tracksForArtist.groupBy {
                it.album.trim().lowercase()
                    .replace(Regex("\\s*\\(\\s*(19|20)\\d{2}\\s*\\)"), "") // Strip (2001), (1999)
                    .replace(Regex("\\s*\\[\\s*(19|20)\\d{2}\\s*\\]"), "") // Strip [2001]
                    .replace(Regex("[^a-z0-9]"), "")
            }

            for ((_, matchingAlbums) in albumCanonicalGroups) {
                val distinctRawAlbumNames = matchingAlbums.map { it.album }.distinct()
                if (distinctRawAlbumNames.size > 1) {
                    val trimmedCounts = matchingAlbums.groupingBy { it.album.trim() }.eachCount()
                    val mostCommonName = trimmedCounts.maxByOrNull { it.value }?.key ?: matchingAlbums.first().album.trim()

                    for (track in matchingAlbums) {
                        if (track.album != mostCommonName) {
                            val issueId = "inconsistent_album_${track.id}"
                            val reviewStatus = preferences.getIssueStatus(issueId)
                            issues.add(
                                DoctorIssue(
                                    id = issueId,
                                    category = DoctorCategory.INCONSISTENT_ALBUMS,
                                    trackId = track.id,
                                    trackTitle = track.title,
                                    artist = track.artist,
                                    album = track.album,
                                    filePath = track.filePath,
                                    problem = "Inconsistent album spelling: '${track.album}'",
                                    evidence = "Other tracks by '${track.artist}' use '$mostCommonName'. Variations: ${distinctRawAlbumNames.joinToString(", ")}",
                                    recommendedAction = "Standardize album title to '$mostCommonName'",
                                    confidence = 0.90f,
                                    currentValue = track.album,
                                    proposedValue = mostCommonName,
                                    isSafeAutoRepair = false,
                                    severity = DoctorIssueSeverity.INFO,
                                    reviewStatus = reviewStatus
                                )
                            )
                        }
                    }
                }
            }
        }

        // --- HEALTH SCORE CALCULATION ---
        val unresolvedIssues = issues.filter { it.reviewStatus == DoctorReviewStatus.OPEN }
        val categoryCounts = issues.groupBy { it.category }.mapValues { it.value.size }
        val safeAutoRepairCount = unresolvedIssues.count { it.isSafeAutoRepair }

        // Deductions calculation
        val criticalCount = unresolvedIssues.count { it.severity == DoctorIssueSeverity.CRITICAL }
        val errorCount = unresolvedIssues.count { it.severity == DoctorIssueSeverity.ERROR }
        val warningCount = unresolvedIssues.count { it.severity == DoctorIssueSeverity.WARNING }
        val infoCount = unresolvedIssues.count { it.severity == DoctorIssueSeverity.INFO }

        val penalty = (criticalCount * 6) + (errorCount * 4) + (warningCount * 2) + (infoCount * 1)
        val rawScore = 100 - (penalty * 100.0 / (totalTracks * 3).coerceAtLeast(20)).toInt()
        val healthScore = rawScore.coerceIn(10, 100)

        val summary = DoctorHealthSummary(
            healthScore = healthScore,
            totalTracks = totalTracks,
            completeTracks = completeTracksCount,
            needsReviewCount = needsReviewCount,
            missingFilesCount = missingFilesCount,
            failedAnalysisCount = failedAnalysisCount,
            missingArtworkCount = missingArtworkCount,
            totalIssues = issues.size,
            safeAutoRepairCount = safeAutoRepairCount,
            categoryCounts = categoryCounts
        )

        val report = DoctorAuditReport(
            summary = summary,
            issues = issues,
            isAuditing = false,
            auditProgress = 1.0f,
            statusMessage = "Audit complete: ${issues.size} issues identified",
            lastAuditTime = System.currentTimeMillis()
        )

        _auditReportFlow.value = report
        Log.i(TAG, "Library Doctor audit finished. Health: $healthScore%, Total Issues: ${issues.size}, Safe Repairable: $safeAutoRepairCount")
        report
    }
}
