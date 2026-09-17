package com.example.command

import com.example.model.Track
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Executes query parsing, filter evaluation, command matching, and result ranking.
 */
object CommandPaletteEngine {

    val ALL_COMMANDS: List<PaletteCommand> = listOf(
        PaletteCommand(
            id = "rescan_selected",
            title = "Rescan Selected",
            description = "Re-read ID3 tags and validate audio files for selected tracks",
            category = CommandCategory.SELECTION,
            keywords = listOf("rescan selected", "scan selected", "refresh selected", "rescan selection"),
            requiresSelection = true
        ),
        PaletteCommand(
            id = "rescan_library",
            title = "Rescan Library",
            description = "Scan device storage and MediaStore for newly added audio tracks",
            category = CommandCategory.LIBRARY,
            keywords = listOf("rescan library", "rescan all", "scan library", "scan device", "refresh library"),
            requiresSelection = false,
            confirmationPrompt = "Rescan device library for newly added audio files?"
        ),
        PaletteCommand(
            id = "analyse_selected",
            title = "Analyse Selected",
            description = "Run DSP, BPM, and musical key detection on selected tracks",
            category = CommandCategory.SELECTION,
            keywords = listOf("analyse selected", "analyze selected", "analysis selected", "dsp selected"),
            requiresSelection = true
        ),
        PaletteCommand(
            id = "find_metadata_selected",
            title = "Find Metadata Selected",
            description = "Enrich selected tracks with online Apple & TheAudioDB artwork and catalog data",
            category = CommandCategory.SELECTION,
            keywords = listOf("find metadata selected", "metadata selected", "enrich selected", "tag selected", "lookup metadata"),
            requiresSelection = true
        ),
        PaletteCommand(
            id = "clear_queue",
            title = "Clear Queue",
            description = "Remove all upcoming tracks from the current playback queue",
            category = CommandCategory.PLAYBACK,
            keywords = listOf("clear queue", "empty queue", "reset queue", "delete queue"),
            requiresSelection = false,
            isDestructive = true,
            confirmationPrompt = "Are you sure you want to clear all upcoming tracks from the queue?"
        ),
        PaletteCommand(
            id = "shuffle_queue",
            title = "Shuffle Queue",
            description = "Randomize upcoming queue order and enable shuffle mode",
            category = CommandCategory.PLAYBACK,
            keywords = listOf("shuffle queue", "shuffle", "randomize queue"),
            requiresSelection = false
        ),
        PaletteCommand(
            id = "open_dj_prep",
            title = "Open DJ Prep",
            description = "Open the dedicated DJ Track Preparation and Beat Grid environment",
            category = CommandCategory.NAVIGATION,
            keywords = listOf("open dj prep", "dj prep", "preparation", "cue editor", "beat grid", "dj prep environment")
        ),
        PaletteCommand(
            id = "open_car_mode",
            title = "Open Car Mode",
            description = "Launch distraction-free high-visibility Car Mode with large controls",
            category = CommandCategory.NAVIGATION,
            keywords = listOf("open car mode", "car mode", "driving mode", "car")
        ),
        PaletteCommand(
            id = "open_downloads_folder",
            title = "Open Downloads Folder",
            description = "Open and browse the system Downloads directory in Folder Explorer",
            category = CommandCategory.NAVIGATION,
            keywords = listOf("open downloads folder", "open downloads", "folder downloads", "downloads folder", "downloads")
        ),
        PaletteCommand(
            id = "reanalyse_all",
            title = "Reanalyse All Tracks",
            description = "Queue entire library for complete DSP, BPM, key, and waveform re-analysis",
            category = CommandCategory.LIBRARY,
            keywords = listOf("reanalyse all", "reanalyze all", "reanalyse library", "re-analyze"),
            confirmationPrompt = "Reanalyse BPM, key, and waveforms for all library tracks? This will run in the background."
        ),
        PaletteCommand(
            id = "analyse_missing",
            title = "Analyse Missing Tracks",
            description = "Analyse only tracks missing BPM, key, or waveform data",
            category = CommandCategory.LIBRARY,
            keywords = listOf("analyse missing", "analyze missing", "scan missing dsp", "analyze incomplete")
        ),
        PaletteCommand(
            id = "open_library_doctor",
            title = "Open Library Doctor",
            description = "Inspect library health, diagnose issues, and perform safe repairs",
            category = CommandCategory.DIAGNOSTICS,
            keywords = listOf("open library doctor", "library doctor", "doctor", "health", "library health")
        ),
        PaletteCommand(
            id = "open_metadata_review",
            title = "Open Metadata Review Inbox",
            description = "Inspect pending online metadata proposals and resolve conflicts",
            category = CommandCategory.NAVIGATION,
            keywords = listOf("open metadata review", "metadata review", "review inbox", "conflicts", "review")
        ),
        PaletteCommand(
            id = "open_developer_diagnostics",
            title = "Open Developer Diagnostics",
            description = "View real-time engine metrics, memory, database, and background job states",
            category = CommandCategory.DIAGNOSTICS,
            keywords = listOf("open developer diagnostics", "developer diagnostics", "diagnostics", "system stats")
        ),
        PaletteCommand(
            id = "open_library_settings",
            title = "Open Library Settings",
            description = "Configure watched folders, scanner options, and file paths",
            category = CommandCategory.NAVIGATION,
            keywords = listOf("open library settings", "library settings", "settings library", "storage settings")
        )
    )

    /**
     * Executes palette search against in-memory tracks and command catalog.
     */
    fun search(
        query: String,
        allTracks: List<Track>,
        selectedTrackIds: Set<String> = emptySet(),
        recentTrackIds: Set<String> = emptySet(),
        maxTrackResults: Int = 100
    ): PaletteSearchResults {
        val parsed = CommandPaletteParser.parse(query)

        // 1. Evaluate commands
        val matchingCommands = evaluateCommands(query, selectedTrackIds)

        // If query is blank and no filters, return default command suggestions and recent tracks
        if (query.isBlank()) {
            val defaultCommands = matchingCommands.take(6)
            val topRecent = allTracks.sortedByDescending { it.dateAdded }.take(20)
            return PaletteSearchResults(
                parsedQuery = parsed,
                commands = defaultCommands,
                tracks = topRecent,
                totalMatches = topRecent.size
            )
        }

        // 2. Filter & rank tracks
        val rankedTracks = filterAndRankTracks(parsed, allTracks, recentTrackIds)
        val limitedTracks = rankedTracks.take(maxTrackResults)

        // 3. Extract grouped Artists, Albums, and Folders
        val artists = extractArtists(parsed, allTracks, rankedTracks)
        val albums = extractAlbums(parsed, allTracks, rankedTracks)
        val folders = extractFolders(parsed, allTracks, rankedTracks)

        return PaletteSearchResults(
            parsedQuery = parsed,
            commands = matchingCommands,
            tracks = limitedTracks,
            artists = artists,
            albums = albums,
            folders = folders,
            totalMatches = rankedTracks.size
        )
    }

    private fun evaluateCommands(
        query: String,
        selectedTrackIds: Set<String>
    ): List<CommandExecutionState> {
        val q = query.trim().lowercase(Locale.ROOT)
        val selectedCount = selectedTrackIds.size

        return ALL_COMMANDS.mapNotNull { cmd ->
            val matchScore = calculateCommandMatch(q, cmd)
            if (q.isEmpty() || matchScore > 0) {
                val isEnabled = if (cmd.requiresSelection) selectedCount > 0 else true
                val disabledReason = if (cmd.requiresSelection && selectedCount == 0) {
                    "Requires track selection (0 tracks currently selected)"
                } else null

                Pair(
                    CommandExecutionState(
                        command = cmd,
                        isEnabled = isEnabled,
                        disabledReason = disabledReason,
                        selectionCount = selectedCount
                    ),
                    matchScore
                )
            } else null
        }
        .sortedByDescending { it.second }
        .map { it.first }
    }

    private fun calculateCommandMatch(query: String, cmd: PaletteCommand): Int {
        if (query.isEmpty()) return 1
        val q = query.lowercase(Locale.ROOT)
        val title = cmd.title.lowercase(Locale.ROOT)

        if (title == q) return 100
        if (title.startsWith(q)) return 80
        if (title.contains(q)) return 60

        // Keyword matches
        for (kw in cmd.keywords) {
            val k = kw.lowercase(Locale.ROOT)
            if (k == q) return 95
            if (k.startsWith(q)) return 75
            if (k.contains(q)) return 50
        }

        // Tokenized word match
        val words = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isNotEmpty() && words.all { w -> title.contains(w) || cmd.keywords.any { it.contains(w) } }) {
            return 40
        }

        return 0
    }

    private fun filterAndRankTracks(
        parsed: ParsedPaletteQuery,
        allTracks: List<Track>,
        recentTrackIds: Set<String>
    ): List<Track> {
        val scoredList = mutableListOf<Pair<Track, Int>>()

        val hasRecentlyAddedFilter = parsed.filters.any { it is PaletteFilter.RecentlyAdded }

        for (track in allTracks) {
            // 1. Structured filters evaluation
            var passesFilters = true
            for (filter in parsed.filters) {
                if (!matchesFilter(track, filter, recentTrackIds)) {
                    passesFilters = false
                    break
                }
            }
            if (!passesFilters) continue

            // 2. Text terms evaluation
            // Every text term MUST match at least one metadata field
            var textScore = 0
            if (parsed.textTerms.isNotEmpty()) {
                var matchesAllTerms = true
                for (term in parsed.textTerms) {
                    val termScore = calculateTermScore(track, term)
                    if (termScore <= 0) {
                        matchesAllTerms = false
                        break
                    }
                    textScore += termScore
                }
                if (!matchesAllTerms) continue
            } else {
                textScore = 10
            }

            scoredList.add(Pair(track, textScore))
        }

        // Sorting: If "recently added" filter is present, sort by dateAdded descending
        return if (hasRecentlyAddedFilter) {
            scoredList.sortedWith(
                compareByDescending<Pair<Track, Int>> { it.first.dateAdded }
                    .thenByDescending { it.second }
            ).map { it.first }
        } else {
            scoredList.sortedByDescending { it.second }.map { it.first }
        }
    }

    private fun matchesFilter(track: Track, filter: PaletteFilter, recentTrackIds: Set<String>): Boolean {
        return when (filter) {
            is PaletteFilter.Artist -> {
                track.artist.contains(filter.artist, ignoreCase = true) ||
                track.albumArtist.contains(filter.artist, ignoreCase = true)
            }
            is PaletteFilter.BpmExact -> {
                track.hasValidBpm && abs(track.bpm - filter.bpm) <= 0.75
            }
            is PaletteFilter.BpmRange -> {
                track.hasValidBpm && track.bpm >= (filter.minBpm - 0.5) && track.bpm <= (filter.maxBpm + 0.5)
            }
            is PaletteFilter.Key -> {
                val raw = filter.rawKey.trim().uppercase(Locale.ROOT)
                val targetCamelot = filter.camelotKey?.uppercase(Locale.ROOT)
                val targetMusical = filter.musicalKey?.trim()?.uppercase(Locale.ROOT)

                val trackCamelot = track.camelotKey.trim().uppercase(Locale.ROOT)
                val trackMusical = track.musicalKey.trim().uppercase(Locale.ROOT)

                (targetCamelot != null && trackCamelot == targetCamelot) ||
                (targetMusical != null && (trackMusical.contains(targetMusical) || trackMusical == targetMusical)) ||
                (trackCamelot == raw || trackMusical == raw)
            }
            is PaletteFilter.Folder -> {
                val f = filter.folderName
                track.directoryPath.contains(f, ignoreCase = true) ||
                track.filePath.contains(f, ignoreCase = true) ||
                track.storageRelativePath.contains(f, ignoreCase = true)
            }
            is PaletteFilter.Missing -> {
                when (filter.fieldType) {
                    MissingFieldType.ARTWORK -> {
                        !track.hasRealArtwork
                    }
                    MissingFieldType.BPM -> {
                        !track.hasValidBpm || track.bpm <= 0.0
                    }
                    MissingFieldType.KEY -> {
                        track.camelotKey.isBlank() && track.musicalKey.isBlank()
                    }
                    MissingFieldType.METADATA -> {
                        track.artist.isBlank() || track.artist.equals("Unknown Artist", ignoreCase = true) ||
                        track.title.isBlank() || track.title.startsWith("Track ", ignoreCase = true) ||
                        track.album.isBlank() || track.album.equals("Single", ignoreCase = true) ||
                        track.durationSeconds <= 0
                    }
                }
            }
            is PaletteFilter.RecentlyAdded -> true // Filter accepts all, ordering handles it
            is PaletteFilter.RecentlyPlayed -> {
                recentTrackIds.contains(track.id) || track.rating > 0
            }
            is PaletteFilter.Unplayed -> {
                !recentTrackIds.contains(track.id)
            }
        }
    }

    private fun calculateTermScore(track: Track, rawTerm: String): Int {
        val term = rawTerm.lowercase(Locale.ROOT)
        val title = track.title.lowercase(Locale.ROOT)
        val artist = track.artist.lowercase(Locale.ROOT)
        val album = track.album.lowercase(Locale.ROOT)
        val filename = track.filePath.substringAfterLast('/').lowercase(Locale.ROOT)
        val genre = track.genre.lowercase(Locale.ROOT)
        val tags = track.customTags.lowercase(Locale.ROOT)

        var score = 0

        // Title ranking (highest priority)
        when {
            title == term -> score += 120
            title.startsWith(term) -> score += 90
            Regex("\\b${Regex.escape(term)}\\b").containsMatchIn(title) -> score += 70
            title.contains(term) -> score += 50
        }

        // Artist ranking
        when {
            artist == term -> score += 60
            artist.startsWith(term) -> score += 45
            artist.contains(term) -> score += 30
        }

        // Album ranking
        if (album.contains(term)) score += 20

        // Filename & genre ranking
        if (filename.contains(term)) score += 15
        if (genre.contains(term)) score += 10
        if (tags.contains(term)) score += 10

        return score
    }

    private fun extractArtists(
        parsed: ParsedPaletteQuery,
        allTracks: List<Track>,
        matchedTracks: List<Track>
    ): List<PaletteArtistResult> {
        val pool = if (parsed.textTerms.isEmpty() && parsed.filters.isEmpty()) allTracks else matchedTracks
        return pool.asSequence()
            .map { it.artist.trim() }
            .filter { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
            .groupBy { it }
            .map { (name, tracks) -> PaletteArtistResult(name, tracks.size) }
            .sortedByDescending { it.trackCount }
            .take(15)
            .toList()
    }

    private fun extractAlbums(
        parsed: ParsedPaletteQuery,
        allTracks: List<Track>,
        matchedTracks: List<Track>
    ): List<PaletteAlbumResult> {
        val pool = if (parsed.textTerms.isEmpty() && parsed.filters.isEmpty()) allTracks else matchedTracks
        return pool.asSequence()
            .filter { it.album.isNotBlank() && !it.album.equals("Single", ignoreCase = true) }
            .groupBy { it.album.trim() to it.artist.trim() }
            .map { (key, tracks) -> PaletteAlbumResult(key.first, key.second, tracks.size) }
            .sortedByDescending { it.trackCount }
            .take(15)
            .toList()
    }

    private fun extractFolders(
        parsed: ParsedPaletteQuery,
        allTracks: List<Track>,
        matchedTracks: List<Track>
    ): List<PaletteFolderResult> {
        val pool = if (parsed.textTerms.isEmpty() && parsed.filters.isEmpty()) allTracks else matchedTracks
        return pool.asSequence()
            .map { it.directoryPath.trim() }
            .filter { it.isNotBlank() }
            .groupBy { it }
            .map { (path, tracks) ->
                val name = path.substringAfterLast('/').ifBlank { path }
                PaletteFolderResult(path, name, tracks.size)
            }
            .sortedByDescending { it.trackCount }
            .take(10)
            .toList()
    }
}
