package com.example.metadata

import android.content.Context
import android.util.Log
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
    private val artworkProvider: TheAudioDbArtworkProvider? = null
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

        // 1. User metadata protection
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

        // 3. Parse track identity from tags and filename
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

        if (parsed.isArtistMissing) {
            val missingArtistResolution = resolveMissingArtist(track, parsed)
            if (missingArtistResolution != null) {
                selectedCandidate = missingArtistResolution.first
                candidateScore = missingArtistResolution.second
                wasArtistRepaired = true
            }
        } else {
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

        // Section 27: Preserve original file if match is uncertain
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
        Log.d("AppleMetadata", "Match: ${selectedCandidate.artistName} - ${selectedCandidate.trackName}")

        // 5. Canonical Textual Metadata Authority: Apple iTunes (Section 8)
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
            metadataScanState = MetadataScanState.ITUNES_MATCHED.name
        )

        // 6. MusicBrainz Identifier Resolution (Section 9 & 10)
        Log.d(TAG, "Resolving MusicBrainz release identifier bridge for \"$resolvedArtist - $resolvedAlbum\"")
        val mbMatch = musicBrainzResolver.resolveMbid(
            artistName = resolvedArtist,
            trackName = resolvedTitle,
            collectionName = resolvedAlbum,
            durationMs = selectedCandidate.trackTimeMillis
        )
        Log.d(TAG, "MBID resolver result: release=${mbMatch?.releaseMbid}, releaseGroup=${mbMatch?.releaseGroupMbid}")

        // 7. Cover Art Archive Artwork Retrieval (Sections 9 & 11)
        // STRICT REQUIREMENT: Never use Apple artwork for local cover art embedding.
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
            Log.d("ArtworkResolver", "Using locally cached Cover Art Archive artwork: ${cachedFile.absolutePath}")
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
                    Log.d("ArtworkResolver", "Saved Cover Art Archive front cover (${downloadedCover.width}x${downloadedCover.height}) to ${savedFile.absolutePath}")
                } else if (artworkProvider != null) {
                    // Legacy fallback provider if configured in tests
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

        // 8. Physical Audio File Tag Writing and Read-Back Verification (Sections 13, 14, 15, 17)
        var finalScanState = if (candidateScore >= MetadataConfidenceScorer.COMMIT_CONFIDENCE_THRESHOLD) {
            MetadataScanState.COMPLETE
        } else {
            MetadataScanState.PARTIAL
        }

        val isLocalPhysicalFile = !track.filePath.startsWith("content://") &&
                !track.filePath.startsWith("demo://") &&
                File(track.filePath).exists()

        if (embedArtworkToFile && isLocalPhysicalFile) {
            Log.d("MetadataWriter", "Beginning physical tag writing and readback verification for ${track.filePath}")
            val writeResult = fileWriter.writeAsync(
                track = intermediateTrack,
                artworkBytes = activeArtworkBytes,
                artworkMimeType = activeArtworkMime
            )

            when (writeResult) {
                is MetadataWriteResult.Written -> {
                    Log.d("MetadataWriter", "Physical tag writing and readback verification PASSED for ${track.filePath}")
                    finalScanState = MetadataScanState.COMPLETE
                }
                is MetadataWriteResult.VerificationFailed -> {
                    Log.e("MetadataWriter", "Write verification FAILED on field ${writeResult.field}: expected \"${writeResult.expected}\" but found \"${writeResult.actual}\"")
                    finalScanState = MetadataScanState.FAILED_WRITE_VERIFICATION
                }
                is MetadataWriteResult.PermissionRequired -> {
                    Log.w("MetadataWriter", "Physical write requires Android storage write permission for ${track.filePath}")
                    finalScanState = MetadataScanState.NEEDS_WRITE_PERMISSION
                }
                is MetadataWriteResult.Unsupported -> {
                    Log.d("MetadataWriter", "Physical write skipped: ${writeResult.reason}")
                }
                is MetadataWriteResult.Failed -> {
                    Log.e("MetadataWriter", "Physical file write failed: ${writeResult.reason}")
                    finalScanState = MetadataScanState.FAILED
                }
            }
        }

        val finalTrack = intermediateTrack.copy(
            artworkUrl = resolvedArtworkUrl,
            artworkSource = artworkSource,
            artworkCachePath = artworkCachePath,
            metadataScanState = finalScanState.name
        )

        Log.d("MetadataWriter", "database write: updated track id=${finalTrack.id} state=${finalTrack.metadataScanState}")
        Log.d("AppleMetadata", "Metadata saved successfully")

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
