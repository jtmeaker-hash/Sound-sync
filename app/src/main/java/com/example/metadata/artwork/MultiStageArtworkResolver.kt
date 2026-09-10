package com.example.metadata.artwork

import android.content.Context
import android.util.Log
import com.example.metadata.ArtworkCache
import com.example.metadata.repair.StringNormalizer
import com.example.metadata.parser.TrackIdentityParser
import com.example.metadata.apple.AppleAlbumResult
import com.example.metadata.apple.AppleMetadataProvider
import com.example.metadata.apple.AppleTrackResult
import com.example.metadata.theaudiodb.ArtworkCandidate
import com.example.metadata.theaudiodb.TheAudioDbArtworkProvider
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Intelligent, multi-stage artwork resolution engine combining local file extraction,
 * folder inspection, identifier lookups (ISRC/UPC), multi-tiered normalized search queries,
 * track/album catalogue matching across Apple iTunes and TheAudioDB, and manual cover picker.
 */
class MultiStageArtworkResolver(
    private val context: Context,
    private val localFinder: LocalArtworkFinder = LocalArtworkFinder(context),
    private val appleProvider: AppleMetadataProvider = AppleMetadataProvider(),
    private val theAudioDbProvider: TheAudioDbArtworkProvider = TheAudioDbArtworkProvider(),
    private val artworkCache: ArtworkCache = ArtworkCache(context)
) {
    companion object {
        private const val TAG = "MultiStageArtwork"
    }

    /**
     * Resolves artwork through Stages 1 through 7.
     */
    suspend fun resolveArtwork(
        track: Track,
        forceRefresh: Boolean = false
    ): ResolvedArtworkResult = withContext(Dispatchers.IO) {
        // Respect explicit user manual selection unless forced
        if (!forceRefresh && track.userConfirmedMetadata && !track.artworkCachePath.isNullOrBlank()) {
            val file = File(track.artworkCachePath)
            if (file.exists() && file.length() > 0) {
                return@withContext ResolvedArtworkResult(
                    state = ArtworkLookupState.MANUAL_SELECTED,
                    artworkCachePath = track.artworkCachePath,
                    artworkUrl = track.artworkUrl,
                    artworkSource = track.artworkSource ?: "User Selected",
                    confidence = 100.0,
                    message = "Preserved user-confirmed artwork"
                )
            }
        }

        // Check if track already has valid cached artwork on disk
        if (!forceRefresh && !track.artworkCachePath.isNullOrBlank()) {
            val file = File(track.artworkCachePath)
            if (file.exists() && file.length() > 0) {
                return@withContext ResolvedArtworkResult(
                    state = ArtworkLookupState.LOCAL_FOUND,
                    artworkCachePath = track.artworkCachePath,
                    artworkUrl = track.artworkUrl ?: track.artworkCachePath,
                    artworkSource = track.artworkSource ?: "Artwork Cache",
                    confidence = 100.0,
                    message = "Loaded existing artwork from cache"
                )
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // STAGE 1: Local Artwork (Embedded ID3/FLAC/MP4 tags + Folder Artwork)
        // ══════════════════════════════════════════════════════════════════════
        val localResult = try {
            localFinder.findLocalArtwork(track)
        } catch (e: Exception) {
            Log.d(TAG, "Stage 1 local lookup exception: ${e.message}")
            null
        }

        if (localResult != null && localResult.file.exists() && localResult.file.length() > 0) {
            Log.d(TAG, "Stage 1 Success: found local artwork via ${localResult.source}")
            return@withContext ResolvedArtworkResult(
                state = ArtworkLookupState.LOCAL_FOUND,
                artworkCachePath = localResult.file.absolutePath,
                artworkUrl = localResult.file.absolutePath,
                artworkSource = localResult.source,
                confidence = 100.0,
                message = "Found local artwork via ${localResult.source}"
            )
        }

        // ══════════════════════════════════════════════════════════════════════
        // STAGE 4: Identifier Lookup (ISRC & UPC/Barcode)
        // ══════════════════════════════════════════════════════════════════════
        if (!track.isrc.isNullOrBlank()) {
            try {
                Log.d(TAG, "Stage 4: Attempting ISRC lookup for ${track.isrc}")
                val isrcResult = appleProvider.lookupByIsrc(track.isrc)
                if (isrcResult != null && !isrcResult.artworkUrl100.isNullOrBlank()) {
                    val downloaded = downloadAndCacheAppleArtwork(track, isrcResult)
                    if (downloaded != null) {
                        return@withContext ResolvedArtworkResult(
                            state = ArtworkLookupState.FALLBACK_FOUND,
                            artworkCachePath = downloaded.absolutePath,
                            artworkUrl = isrcResult.artworkUrl600 ?: isrcResult.artworkUrl100,
                            artworkSource = "Apple iTunes (ISRC: ${track.isrc})",
                            confidence = 98.0,
                            message = "Resolved via verified ISRC lookup"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Stage 4 ISRC lookup error: ${e.message}")
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // STAGE 3: Normalise Track Information & Build Tiered Search Queries
        // ══════════════════════════════════════════════════════════════════════
        val parsedIdentity = TrackIdentityParser.parse(
            existingTitle = track.title,
            existingArtist = track.artist,
            album = track.album,
            filename = track.filePath,
            durationSeconds = track.durationSeconds
        )
        val effectiveArtist = parsedIdentity.artist?.ifBlank { track.artist }?.trim() ?: track.artist.trim()
        val effectiveTitle = parsedIdentity.title?.ifBlank { track.title }?.trim() ?: track.title.trim()
        val cleanBaseTitle = StringNormalizer.stripVersionAndExtension(effectiveTitle)

        val queryTier1 = if (effectiveArtist.isNotBlank()) "$effectiveArtist $effectiveTitle" else effectiveTitle
        val queryTier2 = if (effectiveArtist.isNotBlank()) "$effectiveArtist $cleanBaseTitle" else cleanBaseTitle
        val queryTier3 = cleanBaseTitle

        val candidateItems = mutableListOf<ArtworkCandidateItem>()

        // ══════════════════════════════════════════════════════════════════════
        // STAGE 2 & 5: Track Lookup across Apple iTunes & TheAudioDB
        // ══════════════════════════════════════════════════════════════════════
        val searchQueries = listOf(queryTier1, queryTier2).distinct().filter(String::isNotBlank)

        for ((tierIdx, query) in searchQueries.withIndex()) {
            try {
                // Search Apple Songs
                val appleSongs = appleProvider.searchTracks(query = query, limit = 6)
                for (song in appleSongs) {
                    val artUrl = song.artworkUrl100?.replace("100x100bb", "1200x1200bb") ?: continue
                    val eval = ArtworkConfidenceEvaluator.evaluateCandidate(
                        track = track,
                        candidateArtist = song.artistName,
                        candidateTitle = song.trackName,
                        candidateAlbum = song.collectionName,
                        candidateDurationSeconds = song.durationSeconds,
                        candidateYear = song.releaseYear
                    )

                    candidateItems.add(
                        ArtworkCandidateItem(
                            artworkUrl = artUrl,
                            thumbnailUrl = song.artworkUrl100,
                            provider = if (tierIdx == 0) "Apple iTunes" else "Apple iTunes (Relaxed)",
                            artist = song.artistName,
                            title = song.trackName,
                            album = song.collectionName,
                            releaseYear = song.releaseYear,
                            durationSeconds = song.durationSeconds,
                            confidence = eval.totalScore,
                            scoreBreakdown = eval.breakdown,
                            isHighResolution = true
                        )
                    )
                }

                // If high confidence match in Apple, can break early
                val bestSoFar = candidateItems.maxByOrNull { it.confidence }
                if (bestSoFar != null && bestSoFar.confidence >= 90.0) {
                    break
                }
            } catch (e: Exception) {
                Log.d(TAG, "Apple search query error: ${e.message}")
            }

            // Search TheAudioDB
            if (effectiveArtist.isNotBlank()) {
                try {
                    val adbResults = theAudioDbProvider.findArtwork(artist = effectiveArtist, album = track.album, track = effectiveTitle)
                    for (c in adbResults) {
                        val eval = ArtworkConfidenceEvaluator.evaluateCandidate(
                            track = track,
                            candidateArtist = c.artist,
                            candidateTitle = c.track,
                            candidateAlbum = c.album,
                            candidateDurationSeconds = 0,
                            candidateYear = null
                        )

                        candidateItems.add(
                            ArtworkCandidateItem(
                                artworkUrl = c.artworkUrl,
                                thumbnailUrl = c.artworkUrl,
                                provider = "TheAudioDB",
                                artist = c.artist,
                                title = c.track,
                                album = c.album,
                                releaseYear = null,
                                durationSeconds = 0,
                                confidence = eval.totalScore,
                                scoreBreakdown = eval.breakdown,
                                isHighResolution = c.isHighQuality
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "TheAudioDB search error: ${e.message}")
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // STAGE 6: Album / Release Fallback
        // ══════════════════════════════════════════════════════════════════════
        val highestCurrentScore = candidateItems.maxOfOrNull { it.confidence } ?: 0.0
        if (highestCurrentScore < 85.0 && effectiveArtist.isNotBlank()) {
            val albumQuery = if (!track.album.isNullOrBlank() && track.album != "Unknown Album") {
                "$effectiveArtist ${track.album}"
            } else {
                "$effectiveArtist $cleanBaseTitle"
            }

            try {
                val appleAlbums = appleProvider.searchAlbums(albumQuery, limit = 5)
                for (alb in appleAlbums) {
                    val artUrl = alb.artworkUrl1200 ?: alb.artworkUrl100 ?: continue
                    val eval = ArtworkConfidenceEvaluator.evaluateCandidate(
                        track = track,
                        candidateArtist = alb.artistName,
                        candidateTitle = null,
                        candidateAlbum = alb.collectionName,
                        candidateDurationSeconds = 0,
                        candidateYear = alb.releaseYear
                    )

                    candidateItems.add(
                        ArtworkCandidateItem(
                            artworkUrl = artUrl,
                            thumbnailUrl = alb.artworkUrl100 ?: artUrl,
                            provider = "Apple iTunes (Album)",
                            artist = alb.artistName,
                            title = null,
                            album = alb.collectionName,
                            releaseYear = alb.releaseYear,
                            durationSeconds = 0,
                            confidence = eval.totalScore,
                            scoreBreakdown = eval.breakdown,
                            isHighResolution = true
                        )
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Stage 6 album fallback error: ${e.message}")
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // STAGE 7: Internet Metadata Resolver (Unknown Artist Extraction)
        // ══════════════════════════════════════════════════════════════════════
        if (candidateItems.isEmpty() && (track.artist.isBlank() || track.artist.equals("Unknown Artist", ignoreCase = true))) {
            val fallbackQuery = queryTier3
            if (fallbackQuery.isNotBlank() && fallbackQuery != queryTier1) {
                try {
                    val results = appleProvider.searchTracks(fallbackQuery, limit = 4)
                    for (song in results) {
                        val artUrl = song.artworkUrl100?.replace("100x100bb", "1200x1200bb") ?: continue
                        val eval = ArtworkConfidenceEvaluator.evaluateCandidate(
                            track = track,
                            candidateArtist = song.artistName,
                            candidateTitle = song.trackName,
                            candidateAlbum = song.collectionName,
                            candidateDurationSeconds = song.durationSeconds,
                            candidateYear = song.releaseYear
                        )
                        candidateItems.add(
                            ArtworkCandidateItem(
                                artworkUrl = artUrl,
                                thumbnailUrl = song.artworkUrl100,
                                provider = "Apple iTunes (Title Fallback)",
                                artist = song.artistName,
                                title = song.trackName,
                                album = song.collectionName,
                                releaseYear = song.releaseYear,
                                durationSeconds = song.durationSeconds,
                                confidence = eval.totalScore,
                                scoreBreakdown = eval.breakdown,
                                isHighResolution = true
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        // Deduplicate candidates by artworkUrl and sort by confidence descending
        val uniqueCandidates = candidateItems
            .distinctBy { it.artworkUrl }
            .sortedByDescending { it.confidence }

        if (uniqueCandidates.isEmpty()) {
            return@withContext ResolvedArtworkResult(
                state = ArtworkLookupState.NOT_FOUND,
                candidates = emptyList(),
                message = "No online artwork match found across providers"
            )
        }

        val bestCandidate = uniqueCandidates.first()

        // Auto-accept if confidence >= 90.0, or 70.0..89.9 without severe penalty
        val shouldAutoAccept = bestCandidate.confidence >= 90.0 ||
                (bestCandidate.confidence >= 70.0 && !bestCandidate.scoreBreakdown.contains("mismatch", ignoreCase = true))

        if (shouldAutoAccept) {
            val cachedFile = downloadCandidateArtwork(track, bestCandidate)
            if (cachedFile != null) {
                val state = if (bestCandidate.provider.startsWith("Apple iTunes") && !bestCandidate.provider.contains("Album")) {
                    ArtworkLookupState.PRIMARY_PROVIDER_FOUND
                } else {
                    ArtworkLookupState.FALLBACK_FOUND
                }

                return@withContext ResolvedArtworkResult(
                    state = state,
                    artworkCachePath = cachedFile.absolutePath,
                    artworkUrl = bestCandidate.artworkUrl,
                    artworkSource = bestCandidate.provider,
                    confidence = bestCandidate.confidence,
                    candidates = uniqueCandidates,
                    message = "Auto-applied artwork from ${bestCandidate.provider} (${bestCandidate.confidence.toInt()}%)"
                )
            }
        }

        // Below automatic acceptance threshold: retain candidates for manual selection
        ResolvedArtworkResult(
            state = ArtworkLookupState.LOW_CONFIDENCE,
            candidates = uniqueCandidates,
            confidence = bestCandidate.confidence,
            message = "Found ${uniqueCandidates.size} potential covers; user confirmation recommended"
        )
    }

    /**
     * Searches all candidate covers for the manual Find Cover picker dialog.
     */
    suspend fun searchCandidates(
        track: Track,
        customQuery: String? = null
    ): List<ArtworkCandidateItem> = withContext(Dispatchers.IO) {
        val query = customQuery?.trim()?.takeIf(String::isNotBlank)
            ?: "${track.artist} ${track.title}".trim()

        val results = mutableListOf<ArtworkCandidateItem>()

        // 1. Search Apple tracks
        try {
            val appleTracks = appleProvider.searchTracks(query, limit = 10)
            for (song in appleTracks) {
                val artUrl = song.artworkUrl100?.replace("100x100bb", "1200x1200bb") ?: continue
                val eval = ArtworkConfidenceEvaluator.evaluateCandidate(
                    track = track,
                    candidateArtist = song.artistName,
                    candidateTitle = song.trackName,
                    candidateAlbum = song.collectionName,
                    candidateDurationSeconds = song.durationSeconds,
                    candidateYear = song.releaseYear
                )
                results.add(
                    ArtworkCandidateItem(
                        artworkUrl = artUrl,
                        thumbnailUrl = song.artworkUrl100,
                        provider = "Apple iTunes",
                        artist = song.artistName,
                        title = song.trackName,
                        album = song.collectionName,
                        releaseYear = song.releaseYear,
                        durationSeconds = song.durationSeconds,
                        confidence = eval.totalScore,
                        scoreBreakdown = eval.breakdown,
                        isHighResolution = true
                    )
                )
            }
        } catch (_: Exception) {}

        // 2. Search Apple albums
        try {
            val appleAlbums = appleProvider.searchAlbums(query, limit = 6)
            for (alb in appleAlbums) {
                val artUrl = alb.artworkUrl1200 ?: alb.artworkUrl100 ?: continue
                val eval = ArtworkConfidenceEvaluator.evaluateCandidate(
                    track = track,
                    candidateArtist = alb.artistName,
                    candidateTitle = null,
                    candidateAlbum = alb.collectionName,
                    candidateDurationSeconds = 0,
                    candidateYear = alb.releaseYear
                )
                results.add(
                    ArtworkCandidateItem(
                        artworkUrl = artUrl,
                        thumbnailUrl = alb.artworkUrl100 ?: artUrl,
                        provider = "Apple iTunes (Album)",
                        artist = alb.artistName,
                        title = null,
                        album = alb.collectionName,
                        releaseYear = alb.releaseYear,
                        durationSeconds = 0,
                        confidence = eval.totalScore,
                        scoreBreakdown = eval.breakdown,
                        isHighResolution = true
                    )
                )
            }
        } catch (_: Exception) {}

        // 3. Search TheAudioDB
        try {
            val adbItems = theAudioDbProvider.findArtwork(
                artist = track.artist.ifBlank { query },
                album = track.album,
                track = track.title
            )
            for (c in adbItems) {
                val eval = ArtworkConfidenceEvaluator.evaluateCandidate(
                    track = track,
                    candidateArtist = c.artist,
                    candidateTitle = c.track,
                    candidateAlbum = c.album
                )
                results.add(
                    ArtworkCandidateItem(
                        artworkUrl = c.artworkUrl,
                        thumbnailUrl = c.artworkUrl,
                        provider = "TheAudioDB",
                        artist = c.artist,
                        title = c.track,
                        album = c.album,
                        confidence = eval.totalScore,
                        scoreBreakdown = eval.breakdown,
                        isHighResolution = c.isHighQuality
                    )
                )
            }
        } catch (_: Exception) {}

        results.distinctBy { it.artworkUrl }.sortedByDescending { it.confidence }
    }

    /**
     * Applies a chosen candidate from the manual picker, downloads and caches it,
     * marking it as user confirmed.
     */
    suspend fun applyCandidate(
        track: Track,
        candidate: ArtworkCandidateItem
    ): ResolvedArtworkResult = withContext(Dispatchers.IO) {
        val cachedFile = downloadCandidateArtwork(track, candidate)
        if (cachedFile != null && cachedFile.exists()) {
            ResolvedArtworkResult(
                state = ArtworkLookupState.MANUAL_SELECTED,
                artworkCachePath = cachedFile.absolutePath,
                artworkUrl = candidate.artworkUrl,
                artworkSource = "User Selected (${candidate.provider})",
                confidence = 100.0,
                message = "Artwork manually applied from ${candidate.provider}"
            )
        } else {
            ResolvedArtworkResult(
                state = ArtworkLookupState.TEMPORARY_FAILURE,
                artworkCachePath = null,
                artworkUrl = null,
                message = "Failed to download selected artwork from ${candidate.provider}"
            )
        }
    }

    private suspend fun downloadAndCacheAppleArtwork(track: Track, appleResult: AppleTrackResult): File? {
        val highResUrl = appleResult.artworkUrl100?.replace("100x100bb", "1200x1200bb") ?: return null
        val downloaded = appleProvider.downloadArtwork(highResUrl) ?: return null

        val file = artworkCache.saveArtworkForTrack(
            trackId = track.id,
            bytes = downloaded.bytes,
            mimeType = downloaded.mimeType,
            sourceProvider = "Apple iTunes"
        )

        if (track.artist.isNotBlank() && !track.album.isNullOrBlank()) {
            artworkCache.saveArtwork(track.artist, track.album, downloaded, "Apple iTunes")
        }
        return file
    }

    private suspend fun downloadCandidateArtwork(track: Track, candidate: ArtworkCandidateItem): File? {
        val downloaded = appleProvider.downloadArtwork(candidate.artworkUrl)
            ?: theAudioDbProvider.downloadArtwork(candidate.artworkUrl)
            ?: return null

        val file = artworkCache.saveArtworkForTrack(
            trackId = track.id,
            bytes = downloaded.bytes,
            mimeType = downloaded.mimeType,
            sourceProvider = candidate.provider
        )

        if (track.artist.isNotBlank() && !track.album.isNullOrBlank()) {
            artworkCache.saveArtwork(track.artist, track.album, downloaded, candidate.provider)
        }
        return file
    }
}
