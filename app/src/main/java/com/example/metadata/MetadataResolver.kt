package com.example.metadata

import android.content.Context
import android.util.Log
import com.example.metadata.apple.AppleMetadataProvider
import com.example.metadata.apple.AppleTrackResult
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
 * Unified metadata resolution and artwork engine for SoundSync.
 *
 * Uses:
 * - Apple iTunes Search API for primary track identification and textual catalog metadata.
 * - TheAudioDB v1 API for dedicated, verified cover artwork.
 *
 * Enforces:
 * - User metadata protection (manual edits and user confirmations are NEVER overwritten).
 * - Apple artwork is NEVER used for permanent library artwork.
 * - Multi-stage resolution for tracks with missing artists.
 * - Robust duration gating and version match scoring (threshold >= 85.0).
 * - Safe atomic local artwork embedding without re-encoding audio.
 */
class MetadataResolver(
    private val context: Context,
    private val appleProvider: AppleMetadataProvider = AppleMetadataProvider(),
    private val artworkProvider: TheAudioDbArtworkProvider = TheAudioDbArtworkProvider(),
    private val artworkCache: ArtworkCache = ArtworkCache(context)
) {

    companion object {
        private const val TAG = "MetadataResolver"
    }

    suspend fun resolveTrackMetadata(
        track: Track,
        forceRefresh: Boolean = false,
        embedArtworkToFile: Boolean = false
    ): MetadataResolutionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "track started: \"${track.title}\" by \"${track.artist}\" (id=${track.id})")

        // 1. Check user metadata protection (Phase 21)
        if (!forceRefresh && (track.userConfirmedMetadata || track.metadataScanState == MetadataScanState.USER_CONFIRMED.name)) {
            Log.d(TAG, "Track has USER_CONFIRMED metadata; skipping auto-resolution to protect manual choices.")
            return@withContext MetadataResolutionResult(
                updatedTrack = track,
                scanState = MetadataScanState.USER_CONFIRMED,
                confidence = 100.0,
                wasRepaired = false,
                message = "Protected user-confirmed metadata"
            )
        }

        // 2. Check if already complete and file has not changed (Phase 20)
        if (!forceRefresh && track.metadataScanState == MetadataScanState.COMPLETE.name && track.appleTrackId != null) {
            Log.d(TAG, "Track already COMPLETE and scanned; skipping redundant lookup.")
            return@withContext MetadataResolutionResult(
                updatedTrack = track,
                scanState = MetadataScanState.COMPLETE,
                confidence = track.metadataConfidence,
                wasRepaired = false,
                message = "Already resolved"
            )
        }

        // 3. Parse track identity from tags and filename (Phase 7)
        val parsed = TrackIdentityParser.parse(
            existingTitle = track.title,
            existingArtist = track.artist,
            album = track.album,
            filename = track.filePath,
            durationSeconds = track.durationSeconds
        )

        Log.d("TrackIdentityParser", "parsed artist: \"${parsed.artist}\", parsed title: \"${parsed.title}\", version: ${parsed.version}")

        // 4. Primary Track Identification via Apple (Phases 3, 4, 8)
        var selectedCandidate: AppleTrackResult? = null
        var candidateScore = 0.0
        var wasArtistRepaired = false

        if (parsed.isArtistMissing) {
            // Missing Artist Resolution (Phase 8)
            val missingArtistResolution = resolveMissingArtist(track, parsed)
            if (missingArtistResolution != null) {
                selectedCandidate = missingArtistResolution.first
                candidateScore = missingArtistResolution.second
                wasArtistRepaired = true
            }
        } else {
            // Standard Identification with known artist
            val primaryTerm = parsed.searchTerms.firstOrNull() ?: "${parsed.artist} ${parsed.title}"
            val candidates = appleProvider.searchTracks(primaryTerm)
            val scored = candidates.map { candidate ->
                MetadataConfidenceScorer.scoreCandidate(
                    localTitle = parsed.title,
                    localArtist = parsed.artist,
                    localAlbum = parsed.album,
                    localDurationSeconds = track.durationSeconds,
                    candidate = candidate
                )
            }.sortedByDescending { it.totalScore }

            scored.take(3).forEach {
                Log.d("MetadataConfidenceScorer", "candidate: \"${it.candidate.artistName} - ${it.candidate.trackName}\" score: ${"%.1f".format(it.totalScore)} breakdown: [${it.scoreBreakdown}]")
            }

            val best = scored.firstOrNull()
            if (best != null && best.totalScore >= MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD) {
                selectedCandidate = best.candidate
                candidateScore = best.totalScore
            } else if (best != null && best.totalScore >= MetadataConfidenceScorer.MINIMUM_ACCEPTABLE_THRESHOLD) {
                // Secondary check: search again with title alone if version is specified
                if (parsed.version != null && parsed.searchTerms.size > 1) {
                    val fallbackCandidates = appleProvider.searchTracks(parsed.searchTerms[1])
                    val fallbackScored = fallbackCandidates.map {
                        MetadataConfidenceScorer.scoreCandidate(
                            localTitle = parsed.title,
                            localArtist = parsed.artist,
                            localAlbum = parsed.album,
                            localDurationSeconds = track.durationSeconds,
                            candidate = it
                        )
                    }.sortedByDescending { it.totalScore }

                    val fallbackBest = fallbackScored.firstOrNull()
                    if (fallbackBest != null && fallbackBest.totalScore > best.totalScore && fallbackBest.totalScore >= MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD) {
                        selectedCandidate = fallbackBest.candidate
                        candidateScore = fallbackBest.totalScore
                    } else {
                        selectedCandidate = best.candidate
                        candidateScore = best.totalScore
                    }
                } else {
                    selectedCandidate = best.candidate
                    candidateScore = best.totalScore
                }
            }
        }

        if (selectedCandidate == null || candidateScore < MetadataConfidenceScorer.MINIMUM_ACCEPTABLE_THRESHOLD) {
            Log.d(TAG, "No candidate met minimum confidence threshold for \"${track.title}\"")
            return@withContext MetadataResolutionResult(
                updatedTrack = track.copy(
                    metadataScanState = MetadataScanState.LOW_CONFIDENCE.name
                ),
                scanState = MetadataScanState.LOW_CONFIDENCE,
                confidence = candidateScore,
                wasRepaired = false,
                message = "Inconclusive match (score: ${"%.1f".format(candidateScore)})"
            )
        }

        Log.d(TAG, "selected track: \"${selectedCandidate.artistName} - ${selectedCandidate.trackName}\" (confidence=${"%.1f".format(candidateScore)})")

        // 5. Build Enriched Track Metadata from Apple (Phases 5 & 6)
        // Technical properties (BPM, key, local duration, format, bitrate) are STRICTLY preserved!
        val resolvedArtist = selectedCandidate.artistName
        val resolvedTitle = selectedCandidate.trackName
        val resolvedAlbum = selectedCandidate.collectionName ?: track.album
        val resolvedYear = selectedCandidate.releaseYear ?: track.releaseYear
        val resolvedGenre = selectedCandidate.primaryGenreName ?: track.genre

        var intermediateTrack = track.copy(
            artist = resolvedArtist,
            title = resolvedTitle,
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
            metadataScanState = MetadataScanState.IDENTIFIED.name
        )

        // 6. Artwork Resolution via TheAudioDB (Phases 11, 13, 14, 15)
        // Apple artwork is strictly ignored! TheAudioDB is the sole artwork provider.
        var resolvedArtworkUrl: String? = track.artworkUrl
        var artworkSource: String? = track.artworkSource
        var artworkCachePath: String? = track.artworkCachePath

        val cachedFile = artworkCache.getCachedArtworkFile(resolvedArtist, resolvedAlbum)
        if (cachedFile != null) {
            resolvedArtworkUrl = cachedFile.absolutePath
            artworkSource = "TheAudioDB (Cached)"
            artworkCachePath = cachedFile.absolutePath
            Log.d("ArtworkResolver", "Using locally cached TheAudioDB artwork: ${cachedFile.absolutePath}")
        } else {
            try {
                Log.d("TheAudioDbArtworkProvider", "artist: $resolvedArtist, album: $resolvedAlbum")
                val artworkCandidates = artworkProvider.findArtwork(
                    artist = resolvedArtist,
                    album = resolvedAlbum,
                    track = resolvedTitle
                )

                if (artworkCandidates.isNotEmpty()) {
                    val chosenArtwork = artworkCandidates.first()
                    Log.d("ArtworkResolver", "selected image: ${chosenArtwork.artworkUrl}")

                    val downloaded = artworkProvider.downloadArtwork(chosenArtwork.artworkUrl)
                    if (downloaded != null) {
                        val savedFile = artworkCache.saveArtwork(resolvedArtist, resolvedAlbum, downloaded)
                        resolvedArtworkUrl = chosenArtwork.artworkUrl
                        artworkSource = "TheAudioDB"
                        artworkCachePath = savedFile.absolutePath

                        // 7. Local File Embedding (Phase 16)
                        if (embedArtworkToFile && !track.filePath.startsWith("content://") && File(track.filePath).exists()) {
                            val embedded = ArtworkEmbeddingHelper.embedArtwork(
                                audioFile = File(track.filePath),
                                artworkBytes = downloaded.bytes,
                                mimeType = downloaded.mimeType
                            )
                            Log.d("MetadataWriter", "embedded artwork write: ${if (embedded) "SUCCESS" else "SKIPPED/UNSUPPORTED"}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TheAudioDB lookup failed gracefully without affecting textual metadata: ${e.message}")
            }
        }

        val finalScanState = if (candidateScore >= MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD) {
            MetadataScanState.COMPLETE
        } else {
            MetadataScanState.PARTIAL
        }

        val finalTrack = intermediateTrack.copy(
            artworkUrl = resolvedArtworkUrl,
            artworkSource = artworkSource,
            artworkCachePath = artworkCachePath,
            metadataScanState = finalScanState.name
        )

        Log.d("MetadataWriter", "database write: updated track id=${finalTrack.id} state=${finalTrack.metadataScanState}")

        MetadataResolutionResult(
            updatedTrack = finalTrack,
            scanState = finalScanState,
            confidence = candidateScore,
            wasRepaired = wasArtistRepaired || (candidateScore >= MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD),
            message = "Identified via Apple (score: ${"%.1f".format(candidateScore)})"
        )
    }

    /**
     * Resolves tracks where artist tag is missing/unknown (Phase 8).
     */
    private suspend fun resolveMissingArtist(
        track: Track,
        parsed: ParsedTrackIdentity
    ): Pair<AppleTrackResult, Double>? {
        Log.d(TAG, "Missing artist detected for \"${track.title}\" (file: ${track.filePath})")

        // 1. Search Apple using parsed title / clean title
        val titleQuery = parsed.title
        val candidates = appleProvider.searchTracks(titleQuery, limit = 20)
        if (candidates.isEmpty()) {
            return null
        }

        // 2. Score candidates by comparing title, version, and local duration
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

        // 3. If confidence is high, or if duration + version match closely, determine likely artist
        if (best.totalScore >= MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD) {
            val likelyArtist = best.candidate.artistName
            Log.d(TAG, "Determined likely artist: \"$likelyArtist\" for title \"${parsed.title}\"")

            // 4. Refine search with artist + title to confirm
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
