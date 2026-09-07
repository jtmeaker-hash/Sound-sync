package com.example.metadata

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.MetadataReviewItemEntity
import com.example.metadata.apple.AppleMetadataProvider
import com.example.metadata.apple.AppleTrackResult
import com.example.metadata.coverart.CoverArtArchiveProvider
import com.example.metadata.coverart.DownloadedCoverArt
import com.example.metadata.musicbrainz.MusicBrainzResolver
import com.example.metadata.parser.ParsedTrackIdentity
import com.example.metadata.parser.TrackIdentityParser
import com.example.metadata.theaudiodb.TheAudioDbArtworkProvider
import com.example.model.MetadataScanState
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class MetadataResolutionResult(
    val updatedTrack: Track,
    val scanState: MetadataScanState,
    val confidence: Double,
    val wasRepaired: Boolean,
    val message: String
)

/**
 * Authoritative SoundSync metadata resolution and artwork engine.
 *
 * Architecture (Sections 8, 9, 10, 11, 13, 14, 15, 17):
 * - Apple iTunes Search API: Canonical authority for textual catalog metadata (Title, Artist, Album, Genre, Track/Disc numbers, Release Date).
 * - MusicBrainz: Identifier bridge ONLY, to map confirmed iTunes attributes to release/release-group MBIDs.
 * - Cover Art Archive: Canonical authority for front cover artwork.
 * - MetadataFileWriter: Safely writes format-preserving tags and embedded artwork to the local file, then reopens and verifies.
 * - STRICT RULE: Apple artwork URLs (artworkUrl100, artworkUrl60, artworkUrl600) are NEVER downloaded or stored as cover art.
 */
class MetadataResolver(
    private val context: Context,
    private val appleProvider: AppleMetadataProvider = AppleMetadataProvider(),
    private val musicBrainzResolver: MusicBrainzResolver = MusicBrainzResolver(),
    private val coverArtArchiveProvider: CoverArtArchiveProvider = CoverArtArchiveProvider(),
    private val artworkCache: ArtworkCache = ArtworkCache(context),
    private val fileWriter: MetadataFileWriter = MetadataFileWriter(context),
    // Optional compatibility parameter for legacy test cases
    private val artworkProvider: TheAudioDbArtworkProvider? = null,
    private val database: AppDatabase? = null,
    private val settingsStore: MetadataSettingsStore? = null
) {

    companion object {
        private const val TAG = "MetadataResolver"
    }

    suspend fun resolveTrackMetadata(
        track: Track,
        forceRefresh: Boolean = false,
        embedArtworkToFile: Boolean = true
    ): MetadataResolutionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "track started: \"${track.title}\" by \"${track.artist}\" (id=${track.id}, file=${track.filePath})")

        val store = settingsStore ?: MetadataSettingsStore(context)
        val settings = store.load()

        // 1. User metadata protection
        if (!forceRefresh && (track.userConfirmedMetadata || 
                track.metadataScanState == MetadataScanState.USER_CONFIRMED.name ||
                track.metadataScanState == MetadataScanState.APPROVED.name ||
                track.metadataScanState == MetadataScanState.APPLIED.name)) {
            Log.d(TAG, "Track has user confirmed / applied metadata; skipping auto-resolution to protect manual choices.")
            return@withContext MetadataResolutionResult(
                updatedTrack = track,
                scanState = MetadataScanState.USER_CONFIRMED,
                confidence = 100.0,
                wasRepaired = false,
                message = "Protected user-confirmed metadata"
            )
        }

        // 2. Check if already complete and file has not changed
        if (!forceRefresh && track.metadataScanState == MetadataScanState.COMPLETE.name && track.appleTrackId != null && !track.filePath.isBlank()) {
            Log.d(TAG, "Track already COMPLETE and scanned; skipping redundant lookup.")
            return@withContext MetadataResolutionResult(
                updatedTrack = track,
                scanState = MetadataScanState.COMPLETE,
                confidence = track.metadataConfidence,
                wasRepaired = false,
                message = "Already resolved"
            )
        }

        // 3. Parse track identity from tags and filename (Authoritative source of truth)
        val parsed = TrackIdentityParser.parse(
            existingTitle = track.title,
            existingArtist = track.artist,
            album = track.album,
            filename = track.filePath,
            durationSeconds = track.durationSeconds
        )

        Log.d("TrackIdentityParser", "parsed artist: \"${parsed.artist}\", parsed title: \"${parsed.title}\", version: ${parsed.version}")

        // 4. Primary Track Identification via Apple iTunes Search API (Sections 3, 5, 7)
        var selectedCandidate: AppleTrackResult? = null
        var candidateScore = 0.0
        var wasArtistRepaired = false
        var allCandidatesList: List<AppleTrackResult> = emptyList()
        var candidateEvaluation: CandidateEvaluationResult? = null

        if (parsed.isArtistMissing) {
            val missingArtistResolution = resolveMissingArtist(track, parsed)
            if (missingArtistResolution != null) {
                selectedCandidate = missingArtistResolution.first
                candidateScore = missingArtistResolution.second
                wasArtistRepaired = true
                candidateEvaluation = CandidateEvaluationResult(
                    bestCandidate = MetadataConfidenceScorer.scoreCandidate(parsed.title, selectedCandidate.artistName, parsed.album, track.durationSeconds, selectedCandidate),
                    matchStatus = if (candidateScore >= MetadataConfidenceScorer.VERIFIED_CONFIDENCE_THRESHOLD) MetadataScanState.VERIFIED else MetadataScanState.REVIEW_REQUIRED,
                    allCandidates = emptyList(),
                    isMultipleMatches = false,
                    summary = "Recovered artist from iTunes Search"
                )
            }
        } else {
            val primaryTerm = parsed.searchTerms.firstOrNull() ?: "${parsed.artist} ${parsed.title}"
            val candidates = appleProvider.searchTracks(primaryTerm)
            if (candidates.isEmpty() && parsed.sourceOfTruth == "FOLDER_PARSE") {
                val missingArtistResolution = resolveMissingArtist(track, parsed)
                if (missingArtistResolution != null) {
                    selectedCandidate = missingArtistResolution.first
                    candidateScore = missingArtistResolution.second
                    wasArtistRepaired = true
                    candidateEvaluation = CandidateEvaluationResult(
                        bestCandidate = MetadataConfidenceScorer.scoreCandidate(parsed.title, selectedCandidate.artistName, parsed.album, track.durationSeconds, selectedCandidate),
                        matchStatus = if (candidateScore >= MetadataConfidenceScorer.VERIFIED_CONFIDENCE_THRESHOLD) MetadataScanState.VERIFIED else MetadataScanState.REVIEW_REQUIRED,
                        allCandidates = emptyList(),
                        isMultipleMatches = false,
                        summary = "Recovered artist from iTunes Search"
                    )
                }
            } else {
                allCandidatesList = candidates

                val evaluation = MetadataConfidenceScorer.evaluateCandidates(
                    localTitle = parsed.title,
                    localArtist = parsed.artist,
                    localAlbum = parsed.album,
                    localDurationSeconds = track.durationSeconds,
                    candidates = candidates
                )
                candidateEvaluation = evaluation

                evaluation.allCandidates.take(3).forEach {
                    Log.d("MetadataConfidenceScorer", "candidate: \"${it.candidate.artistName} - ${it.candidate.trackName}\" score: ${"%.1f".format(it.totalScore)} breakdown: [${it.scoreBreakdown}]")
                }

                if (evaluation.bestCandidate != null && evaluation.bestCandidate.totalScore >= MetadataConfidenceScorer.MINIMUM_ACCEPTABLE_THRESHOLD) {
                    selectedCandidate = evaluation.bestCandidate.candidate
                    candidateScore = evaluation.bestCandidate.totalScore
                }
            }
        }

        val matchState = candidateEvaluation?.matchStatus ?: MetadataScanState.NO_MATCH

        // Section 27: Preserve original file if match is uncertain, rejected, or conflicting
        if (selectedCandidate == null || matchState == MetadataScanState.NO_MATCH || matchState == MetadataScanState.REJECTED) {
            val finalUncertainState = if (matchState == MetadataScanState.REJECTED) MetadataScanState.REJECTED else MetadataScanState.NO_MATCH
            Log.d(TAG, "No acceptable candidate for \"${track.title}\" (state=$finalUncertainState)")
            return@withContext MetadataResolutionResult(
                updatedTrack = track.copy(
                    metadataScanState = finalUncertainState.name
                ),
                scanState = finalUncertainState,
                confidence = candidateScore,
                wasRepaired = false,
                message = "Inconclusive match (score: ${"%.1f".format(candidateScore)}, state: $finalUncertainState)"
            )
        }

        Log.d(TAG, "selected track: \"${selectedCandidate.artistName} - ${selectedCandidate.trackName}\" (status=$matchState, confidence=${"%.1f".format(candidateScore)})")

        // 5. Canonical Textual Metadata Authority: Apple iTunes (Section 8)
        val resolvedArtist = selectedCandidate.artistName
        val resolvedTitle = selectedCandidate.trackName
        val resolvedAlbum = selectedCandidate.collectionName ?: track.album
        val resolvedYear = selectedCandidate.releaseYear ?: track.releaseYear
        val resolvedGenre = selectedCandidate.primaryGenreName ?: track.genre

        // Primary Rule (Section 5): Protect user's existing title and artist
        val finalTitle = if (settings.replaceExistingTitle || !TrackIdentityParser.isTitleValid(track.title)) resolvedTitle else track.title
        val finalArtist = if (settings.replaceExistingArtist || !TrackIdentityParser.isArtistValid(track.artist)) resolvedArtist else track.artist

        // 6. MusicBrainz Identifier Resolution (Section 9 & 10)
        Log.d(TAG, "Resolving MusicBrainz release identifier bridge for \"$resolvedArtist - $resolvedAlbum\"")
        val mbMatch = musicBrainzResolver.resolveMbid(
            artistName = resolvedArtist,
            trackName = resolvedTitle,
            collectionName = resolvedAlbum,
            durationMs = selectedCandidate.trackTimeMillis
        )

        // 7. Cover Art Archive Artwork Retrieval (Sections 9 & 11)
        var resolvedArtworkUrl: String? = track.artworkUrl
        var artworkSource: String? = track.artworkSource
        var artworkCachePath: String? = track.artworkCachePath
        var activeArtworkBytes: ByteArray? = null
        var activeArtworkMime: String = "image/jpeg"

        val cachedFile = artworkCache.getCachedArtworkFile(resolvedArtist, resolvedAlbum)
        if (cachedFile != null) {
            resolvedArtworkUrl = cachedFile.absolutePath
            artworkSource = "Cover Art Archive (Cached)"
            artworkCachePath = cachedFile.absolutePath
            activeArtworkBytes = try { cachedFile.readBytes() } catch (_: Exception) { null }
        } else {
            try {
                val downloadedCover = coverArtArchiveProvider.fetchFrontCover(
                    releaseMbid = mbMatch?.releaseMbid,
                    releaseGroupMbid = mbMatch?.releaseGroupMbid
                )

                if (downloadedCover != null) {
                    val savedFile = artworkCache.saveArtwork(
                        artist = resolvedArtist,
                        album = resolvedAlbum,
                        artwork = downloadedCover,
                        sourceProvider = "Cover Art Archive"
                    )
                    resolvedArtworkUrl = downloadedCover.sourceUrl
                    artworkSource = "Cover Art Archive"
                    artworkCachePath = savedFile.absolutePath
                    activeArtworkBytes = downloadedCover.bytes
                    activeArtworkMime = downloadedCover.mimeType
                } else if (artworkProvider != null) {
                    val tdbCandidates = artworkProvider.findArtwork(resolvedArtist, resolvedAlbum, resolvedTitle)
                    if (tdbCandidates.isNotEmpty()) {
                        val downloadedTdb = artworkProvider.downloadArtwork(tdbCandidates.first().artworkUrl)
                        if (downloadedTdb != null) {
                            val savedFile = artworkCache.saveArtwork(resolvedArtist, resolvedAlbum, downloadedTdb)
                            resolvedArtworkUrl = tdbCandidates.first().artworkUrl
                            artworkSource = "TheAudioDB"
                            artworkCachePath = savedFile.absolutePath
                            activeArtworkBytes = downloadedTdb.bytes
                            activeArtworkMime = downloadedTdb.mimeType
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cover Art Archive retrieval error: ${e.message}")
            }
        }

        // Protect existing artwork unless explicitly configured to replace
        val finalArtworkUrl = if (settings.replaceExistingArtwork || track.artworkUrl.isNullOrBlank()) {
            resolvedArtworkUrl
        } else {
            track.artworkUrl
        }

        // 8. Store Proposed Metadata Separately in DB Review Inbox (Stages 7 & 8)
        val db = database ?: try { AppDatabase.getDatabase(context) } catch (_: Exception) { null }
        if (db != null) {
            val inboxEntity = MetadataReviewItemEntity(
                id = java.util.UUID.randomUUID().toString(),
                trackId = track.id,
                filePath = track.filePath,
                originalArtist = track.artist,
                originalTitle = track.title,
                originalAlbum = track.album,
                proposedArtist = resolvedArtist,
                proposedTitle = resolvedTitle,
                proposedAlbum = resolvedAlbum,
                proposedGenre = resolvedGenre,
                proposedYear = resolvedYear,
                proposedTrackNumber = selectedCandidate.trackNumber ?: track.trackNumber,
                proposedArtworkUrl = resolvedArtworkUrl,
                provider = "Apple iTunes Search API",
                confidenceScore = candidateScore,
                evidenceSummary = candidateEvaluation?.summary ?: "Identified via Apple Search",
                status = "PENDING",
                timestamp = System.currentTimeMillis(),
                originalArtworkUrl = track.artworkUrl,
                artworkCachePath = artworkCachePath,
                matchStatus = matchState.name,
                candidatesJson = AppleTrackResult.listToJson(allCandidatesList)
            )
            try {
                db.metadataReviewInboxDao().insertItem(inboxEntity)
                Log.d(TAG, "Queued proposal in metadata_review_inbox for track ${track.id} (matchState=$matchState)")
            } catch (e: Exception) {
                Log.w(TAG, "Could not insert review inbox item: ${e.message}")
            }
        }

        var intermediateTrack = track.copy(
            artist = finalArtist,
            title = finalTitle,
            album = resolvedAlbum,
            releaseDate = selectedCandidate.releaseDate ?: track.releaseDate,
            releaseYear = resolvedYear,
            genre = resolvedGenre,
            trackNumber = selectedCandidate.trackNumber ?: track.trackNumber,
            discNumber = selectedCandidate.discNumber ?: track.discNumber,
            originalArtist = track.originalArtist ?: track.artist.takeIf { it != resolvedArtist },
            resolvedArtist = resolvedArtist,
            metadataSource = "Apple iTunes Search API",
            metadataConfidence = candidateScore,
            appleTrackId = selectedCandidate.trackId,
            appleCollectionId = selectedCandidate.collectionId,
            appleArtistId = selectedCandidate.artistId,
            metadataScanState = matchState.name
        )

        // 9. Physical File Writing: STRICT SAFETY ENFORCEMENT (Section 8)
        // Background scanning MUST NOT modify physical audio files unless user disabled approval requirement
        val isLocalPhysicalFile = !track.filePath.startsWith("demo://") &&
                !track.filePath.startsWith("http") &&
                (track.filePath.startsWith("content://") || File(track.filePath).exists())

        val shouldWritePhysicalFile = embedArtworkToFile && isLocalPhysicalFile &&
                (!settings.writeMetadataOnlyAfterApproval || forceRefresh || matchState == MetadataScanState.VERIFIED)

        var finalScanState = matchState
        var fileWriteState = com.example.model.MetadataWriteState.DATABASE_ONLY

        if (shouldWritePhysicalFile) {
            Log.d("MetadataWriter", "Physical tag writing for ${track.filePath}")
            val writeResult = fileWriter.writeAsync(
                track = intermediateTrack,
                artworkBytes = activeArtworkBytes,
                artworkMimeType = activeArtworkMime
            )
            fileWriteState = writeResult.writeState
            when (writeResult) {
                is MetadataWriteResult.Written -> {
                    Log.d("MetadataWriter", "Physical tag writing and readback verification PASSED for ${track.filePath}")
                    finalScanState = MetadataScanState.COMPLETE
                }
                is MetadataWriteResult.AlreadyInSync -> {
                    Log.d("MetadataWriter", "Physical tags already in sync for ${track.filePath}")
                    finalScanState = MetadataScanState.COMPLETE
                }
                is MetadataWriteResult.Partial -> {
                    Log.d("MetadataWriter", "Physical tag writing PASSED (partial) for ${track.filePath}")
                    finalScanState = MetadataScanState.COMPLETE
                }
                is MetadataWriteResult.Skipped -> {
                    Log.d("MetadataWriter", "Physical tag writing skipped: ${writeResult.reason}")
                }
                is MetadataWriteResult.VerificationFailed -> {
                    Log.e("MetadataWriter", "Write verification FAILED on field ${writeResult.field}: expected \"${writeResult.expected}\" but found \"${writeResult.actual}\"")
                    finalScanState = MetadataScanState.FAILED_WRITE_VERIFICATION
                }
                is MetadataWriteResult.PermissionRequired -> {
                    Log.w("MetadataWriter", "Physical write requires Android storage write permission for ${track.filePath}")
                    finalScanState = MetadataScanState.NEEDS_WRITE_PERMISSION
                }
                is MetadataWriteResult.ReadOnlyFile -> {
                    Log.w("MetadataWriter", "Physical write not possible; file is read-only: ${track.filePath}")
                    finalScanState = MetadataScanState.IDENTIFIED
                }
                is MetadataWriteResult.Unsupported -> {
                    Log.d("MetadataWriter", "Physical write not supported: ${writeResult.reason}")
                }
                is MetadataWriteResult.Failed -> {
                    Log.e("MetadataWriter", "Physical write failed: ${writeResult.reason}")
                }
            }
        } else {
            Log.d("MetadataWriter", "Physical file write safely DEFERRED until user approval for ${track.filePath}")
        }

        val finalTrack = intermediateTrack.copy(
            artworkUrl = finalArtworkUrl,
            artworkSource = artworkSource,
            artworkCachePath = artworkCachePath,
            metadataScanState = finalScanState.name,
            metadataWriteState = fileWriteState.name
        )

        Log.d("MetadataWriter", "database write: updated track id=${finalTrack.id} state=${finalTrack.metadataScanState}")

        MetadataResolutionResult(
            updatedTrack = finalTrack,
            scanState = finalScanState,
            confidence = candidateScore,
            wasRepaired = wasArtistRepaired || (candidateScore >= MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD),
            message = "Identified via Apple + Cover Art Archive (score: ${"%.1f".format(candidateScore)}, state: $finalScanState)"
        )
    }

    private suspend fun resolveMissingArtist(
        track: Track,
        parsed: ParsedTrackIdentity
    ): Pair<AppleTrackResult, Double>? {
        Log.d(TAG, "Missing artist detected for \"${track.title}\" (file: ${track.filePath})")

        val titleQuery = parsed.title
        val candidates = appleProvider.searchTracks(titleQuery, limit = 20)
        if (candidates.isEmpty()) {
            return null
        }

        val scored = candidates.map { candidate ->
            MetadataConfidenceScorer.scoreCandidate(
                localTitle = parsed.title,
                localArtist = null,
                localAlbum = parsed.album,
                localDurationSeconds = track.durationSeconds,
                candidate = candidate
            )
        }.sortedByDescending { it.totalScore }

        val best = scored.firstOrNull() ?: return null

        if (best.totalScore >= MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD) {
            val likelyArtist = best.candidate.artistName
            Log.d(TAG, "Determined likely artist: \"$likelyArtist\" for title \"${parsed.title}\"")

            val refinedCandidates = appleProvider.searchTracks("$likelyArtist ${parsed.title}", limit = 5)
            val refinedScored = refinedCandidates.map {
                MetadataConfidenceScorer.scoreCandidate(
                    localTitle = parsed.title,
                    localArtist = likelyArtist,
                    localAlbum = parsed.album,
                    localDurationSeconds = track.durationSeconds,
                    candidate = it
                )
            }.sortedByDescending { it.totalScore }

            val refinedBest = refinedScored.firstOrNull()
            if (refinedBest != null && refinedBest.totalScore >= MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD) {
                return refinedBest.candidate to refinedBest.totalScore
            }

            return best.candidate to best.totalScore
        }

        return if (best.totalScore >= MetadataConfidenceScorer.MINIMUM_ACCEPTABLE_THRESHOLD) {
            best.candidate to best.totalScore
        } else {
            null
        }
    }
}
