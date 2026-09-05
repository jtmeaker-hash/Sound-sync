package com.example.metadata.provider

import com.example.metadata.apple.AppleMetadataProvider
import com.example.metadata.apple.AppleTrackResult
import com.example.metadata.theaudiodb.TheAudioDbArtworkProvider
import kotlinx.coroutines.withTimeoutOrNull

enum class MetadataField {
    TITLE,
    ARTIST,
    ALBUM,
    ALBUM_ARTIST,
    GENRE,
    RELEASE_YEAR,
    RELEASE_DATE,
    TRACK_NUMBER,
    ARTWORK,
    EXTERNAL_ID
}

data class CandidateMetadata(
    val provider: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String? = null,
    val genre: String? = null,
    val releaseYear: Int? = null,
    val releaseDate: String? = null,
    val trackNumber: Int? = null,
    val artworkUrl: String? = null,
    val durationSeconds: Int = 0,
    val externalTrackId: String? = null,
    val externalCollectionId: String? = null,
    val externalArtistId: String? = null,
    val confidence: Double = 0.0,
    val rawResult: Any? = null
)

interface MetadataProvider {
    val name: String
    val supportedFields: Set<MetadataField>
    var isEnabled: Boolean

    suspend fun searchTrack(
        artist: String,
        title: String,
        durationSeconds: Int = 0
    ): List<CandidateMetadata>

    suspend fun fetchArtwork(
        album: String,
        artist: String
    ): String?
}

/**
 * Adapter wrapping the existing AppleMetadataProvider
 */
class AppleMetadataProviderAdapter(
    private val delegate: AppleMetadataProvider = AppleMetadataProvider()
) : MetadataProvider {
    override val name: String = "Apple Music"
    override val supportedFields: Set<MetadataField> = setOf(
        MetadataField.TITLE,
        MetadataField.ARTIST,
        MetadataField.ALBUM,
        MetadataField.GENRE,
        MetadataField.RELEASE_YEAR,
        MetadataField.RELEASE_DATE,
        MetadataField.TRACK_NUMBER,
        MetadataField.EXTERNAL_ID
    )
    override var isEnabled: Boolean = true

    override suspend fun searchTrack(
        artist: String,
        title: String,
        durationSeconds: Int
    ): List<CandidateMetadata> {
        if (!isEnabled) return emptyList()
        return try {
            withTimeoutOrNull(6000L) {
                val query = if (artist.isBlank()) title else "$artist $title"
                val tracks = delegate.searchTracks(query)
                tracks.map { appleTrack ->
                    CandidateMetadata(
                        provider = name,
                        title = appleTrack.trackName,
                        artist = appleTrack.artistName,
                        album = appleTrack.collectionName ?: "Single",
                        genre = appleTrack.primaryGenreName,
                        releaseYear = appleTrack.releaseDate?.take(4)?.toIntOrNull(),
                        releaseDate = appleTrack.releaseDate,
                        trackNumber = appleTrack.trackNumber,
                        artworkUrl = null,
                        durationSeconds = appleTrack.durationSeconds,
                        externalTrackId = appleTrack.trackId?.toString(),
                        externalCollectionId = appleTrack.collectionId?.toString(),
                        externalArtistId = appleTrack.artistId?.toString(),
                        rawResult = appleTrack
                    )
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchArtwork(album: String, artist: String): String? {
        // As per architecture rules, Apple artwork is secondary or fallback only
        return null
    }
}

/**
 * Adapter wrapping the existing TheAudioDbArtworkProvider
 */
class TheAudioDbProviderAdapter(
    private val delegate: TheAudioDbArtworkProvider = TheAudioDbArtworkProvider()
) : MetadataProvider {
    override val name: String = "TheAudioDB"
    override val supportedFields: Set<MetadataField> = setOf(
        MetadataField.ARTWORK,
        MetadataField.ALBUM,
        MetadataField.ARTIST,
        MetadataField.RELEASE_YEAR
    )
    override var isEnabled: Boolean = true

    override suspend fun searchTrack(
        artist: String,
        title: String,
        durationSeconds: Int
    ): List<CandidateMetadata> {
        // TheAudioDB is primarily artwork/album info
        return emptyList()
    }

    override suspend fun fetchArtwork(album: String, artist: String): String? {
        if (!isEnabled) return null
        return try {
            withTimeoutOrNull(7000L) {
                val candidates = delegate.findArtwork(artist, album, null)
                candidates.firstOrNull()?.artworkUrl
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Priority and Registry management for Pluggable Metadata Providers.
 */
class MetadataProviderRegistry(
    val providers: List<MetadataProvider> = listOf(
        AppleMetadataProviderAdapter(),
        TheAudioDbProviderAdapter()
    )
) {
    var globalOrder: List<String> = listOf("Apple Music", "TheAudioDB")
    var artworkPriority: List<String> = listOf("TheAudioDB", "Apple Music")
    var genrePriority: List<String> = listOf("Apple Music")
    var releaseDatePriority: List<String> = listOf("Apple Music")

    fun getActiveProviders(): List<MetadataProvider> {
        val providerMap = providers.associateBy { it.name }
        return globalOrder.mapNotNull { providerMap[it] }.filter { it.isEnabled }
    }

    suspend fun resolveTrackWithFallback(
        artist: String,
        title: String,
        durationSeconds: Int
    ): List<CandidateMetadata> {
        for (provider in getActiveProviders()) {
            val candidates = provider.searchTrack(artist, title, durationSeconds)
            if (candidates.isNotEmpty()) {
                return candidates
            }
        }
        return emptyList()
    }

    suspend fun resolveArtworkWithFallback(
        album: String,
        artist: String
    ): String? {
        val providerMap = providers.associateBy { it.name }
        for (name in artworkPriority) {
            val provider = providerMap[name]
            if (provider != null && provider.isEnabled) {
                val art = provider.fetchArtwork(album, artist)
                if (!art.isNullOrBlank()) return art
            }
        }
        return null
    }
}
