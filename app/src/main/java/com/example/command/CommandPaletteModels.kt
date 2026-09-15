package com.example.command

import com.example.model.Track

/**
 * Supported field types for "missing <field>" queries.
 */
enum class MissingFieldType(val label: String) {
    ARTWORK("artwork"),
    BPM("bpm"),
    KEY("key"),
    METADATA("metadata")
}

/**
 * Structured filter criteria extracted from the query.
 */
sealed class PaletteFilter {
    data class Artist(val artist: String) : PaletteFilter()
    data class BpmExact(val bpm: Double) : PaletteFilter()
    data class BpmRange(val minBpm: Double, val maxBpm: Double) : PaletteFilter()
    data class Key(val rawKey: String, val camelotKey: String?, val musicalKey: String?) : PaletteFilter()
    data class Folder(val folderName: String) : PaletteFilter()
    data class Missing(val fieldType: MissingFieldType) : PaletteFilter()
    object RecentlyAdded : PaletteFilter()
    object RecentlyPlayed : PaletteFilter()
    object Unplayed : PaletteFilter()
}

/**
 * Clean representation of a parsed query.
 */
data class ParsedPaletteQuery(
    val rawQuery: String,
    val textTerms: List<String>,
    val filters: List<PaletteFilter>,
    val explicitCommandQuery: String? = null
)

/**
 * Palette command categories.
 */
enum class CommandCategory(val displayTitle: String) {
    LIBRARY("Library"),
    SELECTION("Selection Actions"),
    PLAYBACK("Playback & Queue"),
    NAVIGATION("Navigation"),
    DIAGNOSTICS("Tools & Diagnostics")
}

/**
 * A command that can be executed from the Command Palette.
 */
data class PaletteCommand(
    val id: String,
    val title: String,
    val description: String,
    val category: CommandCategory,
    val keywords: List<String>,
    val requiresSelection: Boolean = false,
    val isDestructive: Boolean = false,
    val confirmationPrompt: String? = null
)

/**
 * Command presentation with live selection readiness.
 */
data class CommandExecutionState(
    val command: PaletteCommand,
    val isEnabled: Boolean,
    val disabledReason: String? = null,
    val selectionCount: Int = 0
)

/**
 * Extracted artist summary.
 */
data class PaletteArtistResult(
    val name: String,
    val trackCount: Int
)

/**
 * Extracted album summary.
 */
data class PaletteAlbumResult(
    val title: String,
    val artist: String,
    val trackCount: Int
)

/**
 * Extracted folder summary.
 */
data class PaletteFolderResult(
    val path: String,
    val name: String,
    val trackCount: Int
)

/**
 * Complete search results grouped by category.
 */
data class PaletteSearchResults(
    val parsedQuery: ParsedPaletteQuery,
    val commands: List<CommandExecutionState> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val artists: List<PaletteArtistResult> = emptyList(),
    val albums: List<PaletteAlbumResult> = emptyList(),
    val folders: List<PaletteFolderResult> = emptyList(),
    val totalMatches: Int = 0
)
