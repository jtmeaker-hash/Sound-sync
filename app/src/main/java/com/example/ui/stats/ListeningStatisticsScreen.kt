package com.example.ui.stats

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.*
import com.example.model.Track
import com.example.ui.MainDjViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class StatsTimeFilter(val label: String) {
    TODAY("Today"),
    LAST_7_DAYS("Last 7 Days"),
    LAST_30_DAYS("Last 30 Days"),
    THIS_MONTH("This Month"),
    THIS_YEAR("This Year"),
    ALL_TIME("All Time")
}

enum class StatsSubTab(val label: String) {
    OVERVIEW("Overview"),
    HISTORY("History"),
    RECENTLY_PLAYED("Recently Played"),
    NEVER_PLAYED("Never Played"),
    REDISCOVER("Rediscover"),
    LIBRARY_STATS("Library Stats")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListeningStatisticsScreen(
    allTracks: List<Track>,
    viewModel: MainDjViewModel,
    onInspectTrack: (Track) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }
    val playbackDao = remember { database.playbackSessionDao() }

    var selectedTimeFilter by remember { mutableStateOf(StatsTimeFilter.LAST_30_DAYS) }
    var selectedSubTab by remember { mutableStateOf(StatsSubTab.OVERVIEW) }

    // Aggregate Stats state
    var overviewStats by remember { mutableStateOf(ListeningOverviewStats()) }
    var topPlayedTracks by remember { mutableStateOf<List<TopPlayedTrackResult>>(emptyList()) }
    var topSkippedTracks by remember { mutableStateOf<List<TopSkippedTrackResult>>(emptyList()) }
    var highestCompletionTracks by remember { mutableStateOf<List<TopPlayedTrackResult>>(emptyList()) }
    var historySessions by remember { mutableStateOf<List<PlaybackSessionEntity>>(emptyList()) }
    var recentlyPlayedTrackIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var allPlayedTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }

    // History search
    var historySearchQuery by remember { mutableStateOf("") }
    var showResetDialog by remember { mutableStateOf(false) }

    // Calculate time bounds for filter
    val timeRange = remember(selectedTimeFilter) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        when (selectedTimeFilter) {
            StatsTimeFilter.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            StatsTimeFilter.LAST_7_DAYS -> (now - 7L * 24 * 3600 * 1000) to now
            StatsTimeFilter.LAST_30_DAYS -> (now - 30L * 24 * 3600 * 1000) to now
            StatsTimeFilter.THIS_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            StatsTimeFilter.THIS_YEAR -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            StatsTimeFilter.ALL_TIME -> 0L to Long.MAX_VALUE
        }
    }

    // Refresh data whenever time filter or sub tab changes
    LaunchedEffect(selectedTimeFilter) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val from = timeRange.first
            val to = timeRange.second
            val stats = playbackDao.getOverviewStats(from, to)
            val top = playbackDao.getTopPlayedTracks(from, to, limit = 20)
            val skipped = playbackDao.getTopSkippedTracks(from, to, minPlays = 2, limit = 20)
            val completed = playbackDao.getHighestCompletionTracks(from, to, minPlays = 2, limit = 20)
            val history = playbackDao.getHistoryPaginated(limit = 100, offset = 0)
            val recentIds = playbackDao.getRecentlyPlayedTrackIds(limit = 30)
            val playedSet = playbackDao.getAllPlayedTrackIds().toSet()

            withContext(Dispatchers.Main) {
                overviewStats = stats
                topPlayedTracks = top
                topSkippedTracks = skipped
                highestCompletionTracks = completed
                historySessions = history
                recentlyPlayedTrackIds = recentIds
                allPlayedTrackIds = playedSet
                isLoading = false
            }
        }
    }

    // Track map for quick lookup by ID
    val trackMap = remember(allTracks) { allTracks.associateBy { it.id } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
    ) {
        // Top App Bar
        Surface(
            color = DjSurfaceDark,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DeckACyan)
                    }
                    Column {
                        Text("Listening Statistics", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                        Text("${allTracks.size} tracks in library", color = TextSecondary, fontSize = 11.sp)
                    }
                }

                IconButton(onClick = { showResetDialog = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Reset History", tint = NeonAmber)
                }
            }
        }

        // Time Filter Chips Row
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(StatsTimeFilter.values()) { filter ->
                FilterChip(
                    selected = selectedTimeFilter == filter,
                    onClick = { selectedTimeFilter = filter },
                    label = { Text(filter.label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DeckACyan,
                        selectedLabelColor = DjObsidian,
                        containerColor = DjSurfaceCard,
                        labelColor = TextPrimary
                    )
                )
            }
        }

        // Sub-Tab Navigation Bar
        ScrollableTabRow(
            selectedTabIndex = selectedSubTab.ordinal,
            containerColor = DjSurfaceDark,
            contentColor = DeckACyan,
            edgePadding = 16.dp,
            divider = {}
        ) {
            StatsSubTab.values().forEach { tab ->
                Tab(
                    selected = selectedSubTab == tab,
                    onClick = { selectedSubTab = tab },
                    text = {
                        Text(
                            text = tab.label,
                            fontSize = 12.sp,
                            fontWeight = if (selectedSubTab == tab) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedSubTab == tab) DeckACyan else TextSecondary
                        )
                    }
                )
            }
        }

        // SubTab Content
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedSubTab) {
                StatsSubTab.OVERVIEW -> {
                    StatsOverviewTab(
                        overviewStats = overviewStats,
                        topTracks = topPlayedTracks,
                        topSkipped = topSkippedTracks,
                        highestCompletion = highestCompletionTracks,
                        trackMap = trackMap,
                        allTracks = allTracks,
                        onInspectTrack = onInspectTrack
                    )
                }
                StatsSubTab.HISTORY -> {
                    StatsHistoryTab(
                        sessions = historySessions,
                        trackMap = trackMap,
                        searchQuery = historySearchQuery,
                        onSearchQueryChanged = { historySearchQuery = it },
                        onInspectTrack = onInspectTrack,
                        onDeleteSession = { sessionId ->
                            coroutineScope.launch(Dispatchers.IO) {
                                playbackDao.deleteSession(sessionId)
                                val refreshed = playbackDao.getHistoryPaginated(100, 0)
                                withContext(Dispatchers.Main) { historySessions = refreshed }
                            }
                        }
                    )
                }
                StatsSubTab.RECENTLY_PLAYED -> {
                    StatsRecentlyPlayedTab(
                        trackIds = recentlyPlayedTrackIds,
                        trackMap = trackMap,
                        onInspectTrack = onInspectTrack
                    )
                }
                StatsSubTab.NEVER_PLAYED -> {
                    val neverPlayed = remember(allTracks, allPlayedTrackIds) {
                        allTracks.filter { it.id !in allPlayedTrackIds }
                    }
                    StatsNeverPlayedTab(
                        tracks = neverPlayed,
                        onInspectTrack = onInspectTrack
                    )
                }
                StatsSubTab.REDISCOVER -> {
                    val rediscoverTracks = remember(allTracks, allPlayedTrackIds) {
                        allTracks.filter { track ->
                            (track.rating >= 4 || track.tagsList.isNotEmpty()) && track.id in allPlayedTrackIds
                        }
                    }
                    StatsRediscoverTab(
                        tracks = rediscoverTracks,
                        onInspectTrack = onInspectTrack
                    )
                }
                StatsSubTab.LIBRARY_STATS -> {
                    StatsLibraryMetricsTab(
                        allTracks = allTracks,
                        onInspectTrack = onInspectTrack
                    )
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = DjSurfaceDark,
            title = { Text("Reset Listening Statistics?", color = NeonRed, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will permanently delete play counts, skip counts, listening history, and completion statistics.\n\nYour audio files, playlists, metadata, ratings, and tags will NOT be deleted.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            playbackDao.deleteAllSessions()
                            withContext(Dispatchers.Main) {
                                overviewStats = ListeningOverviewStats()
                                topPlayedTracks = emptyList()
                                topSkippedTracks = emptyList()
                                historySessions = emptyList()
                                recentlyPlayedTrackIds = emptyList()
                                allPlayedTrackIds = emptySet()
                                Toast.makeText(context, "Listening history reset", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonRed)
                ) {
                    Text("Reset History", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

// ── Overview Tab ────────────────────────────────────────────────────────────
@Composable
private fun StatsOverviewTab(
    overviewStats: ListeningOverviewStats,
    topTracks: List<TopPlayedTrackResult>,
    topSkipped: List<TopSkippedTrackResult>,
    highestCompletion: List<TopPlayedTrackResult>,
    trackMap: Map<String, Track>,
    allTracks: List<Track>,
    onInspectTrack: (Track) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // High-level KPI Cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiStatCard(
                title = "TRACKS PLAYED",
                value = "${overviewStats.totalPlays}",
                color = NeonAmber,
                modifier = Modifier.weight(1f)
            )
            KpiStatCard(
                title = "TIME LISTENED",
                value = formatListeningTime(overviewStats.totalListeningMs),
                color = DeckACyan,
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiStatCard(
                title = "UNIQUE TRACKS",
                value = "${overviewStats.uniqueTracksPlayed}",
                color = DeckBPink,
                modifier = Modifier.weight(1f)
            )
            KpiStatCard(
                title = "COMPLETION RATE",
                value = "${overviewStats.completionRate.toInt()}%",
                color = NeonGreen,
                modifier = Modifier.weight(1f)
            )
        }

        // Most Played Tracks
        StatsSectionCard(title = "MOST PLAYED TRACKS", icon = Icons.Default.TrendingUp) {
            if (topTracks.isEmpty()) {
                Text("No playback sessions recorded in this period yet.", fontSize = 12.sp, color = TextMuted)
            } else {
                topTracks.take(8).forEachIndexed { index, item ->
                    val track = trackMap[item.trackId]
                    if (track != null) {
                        TrackRankRow(
                            rank = index + 1,
                            track = track,
                            subtitle = "${item.playCount} plays • ${formatListeningTime(item.totalListeningMs)}",
                            trailingBadge = if (item.playCount > 0) "${((item.completedCount.toFloat() / item.playCount) * 100).toInt()}% completed" else "",
                            badgeColor = NeonGreen,
                            onClick = { onInspectTrack(track) }
                        )
                    }
                }
            }
        }

        // Most Skipped Tracks
        StatsSectionCard(title = "MOST SKIPPED TRACKS", icon = Icons.Default.SkipNext) {
            if (topSkipped.isEmpty()) {
                Text("No skipped tracks detected in this period.", fontSize = 12.sp, color = TextMuted)
            } else {
                topSkipped.take(5).forEachIndexed { index, item ->
                    val track = trackMap[item.trackId]
                    if (track != null) {
                        TrackRankRow(
                            rank = index + 1,
                            track = track,
                            subtitle = "${item.skipCount} skips out of ${item.totalPlays} plays",
                            trailingBadge = "${item.skipRate.toInt()}% skip rate",
                            badgeColor = NeonRed,
                            onClick = { onInspectTrack(track) }
                        )
                    }
                }
            }
        }

        // Highest Completion Rate
        StatsSectionCard(title = "HIGHEST COMPLETION RATE", icon = Icons.Default.CheckCircle) {
            if (highestCompletion.isEmpty()) {
                Text("No completion data recorded yet.", fontSize = 12.sp, color = TextMuted)
            } else {
                highestCompletion.take(5).forEachIndexed { index, item ->
                    val track = trackMap[item.trackId]
                    if (track != null) {
                        val rate = if (item.playCount > 0) (item.completedCount.toFloat() / item.playCount) * 100f else 0f
                        TrackRankRow(
                            rank = index + 1,
                            track = track,
                            subtitle = "${item.completedCount} full plays out of ${item.playCount}",
                            trailingBadge = "${rate.toInt()}%",
                            badgeColor = NeonGreen,
                            onClick = { onInspectTrack(track) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── History Tab ─────────────────────────────────────────────────────────────
@Composable
private fun StatsHistoryTab(
    sessions: List<PlaybackSessionEntity>,
    trackMap: Map<String, Track>,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onInspectTrack: (Track) -> Unit,
    onDeleteSession: (Long) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val dayFormat = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()) }

    val filtered = remember(sessions, searchQuery) {
        if (searchQuery.isBlank()) sessions else {
            val q = searchQuery.lowercase().trim()
            sessions.filter { s ->
                val track = trackMap[s.trackId]
                track != null && (track.title.lowercase().contains(q) || track.artist.lowercase().contains(q))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search history by title or artist...", fontSize = 12.sp, color = TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = DeckACyan,
                unfocusedBorderColor = DjSurfaceBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No listening history records found.", color = TextMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered) { session ->
                    val track = trackMap[session.trackId]
                    if (track != null) {
                        Surface(
                            color = DjSurfaceDark,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onInspectTrack(track) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(DjSurfaceCard),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (track.artworkUrl != null) {
                                            AsyncImage(
                                                model = track.artworkUrl,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(track.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${track.artist} • ${formatListeningTime(session.listenedDurationMs)} listened", fontSize = 11.sp, color = TextSecondary, maxLines = 1)
                                        Text(
                                            "${dayFormat.format(Date(session.startedAt))} at ${dateFormat.format(Date(session.startedAt))}",
                                            fontSize = 10.sp,
                                            color = TextMuted
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (session.completed) {
                                        Surface(color = NeonGreen.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                            Text("Completed", color = NeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    } else if (session.skipped) {
                                        Surface(color = NeonRed.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                            Text("Skipped", color = NeonRed, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }

                                    IconButton(onClick = { onDeleteSession(session.id) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Delete entry", tint = TextMuted, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Recently Played Tab ─────────────────────────────────────────────────────
@Composable
private fun StatsRecentlyPlayedTab(
    trackIds: List<String>,
    trackMap: Map<String, Track>,
    onInspectTrack: (Track) -> Unit
) {
    if (trackIds.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No recently played tracks.", color = TextMuted, fontSize = 13.sp)
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(trackIds) { index, id ->
                val track = trackMap[id]
                if (track != null) {
                    TrackRankRow(
                        rank = index + 1,
                        track = track,
                        subtitle = "${track.album} • ${track.format} ${track.bitrateKbps}k",
                        trailingBadge = if (track.hasValidBpm) "${track.bpm.toInt()} BPM" else "",
                        badgeColor = NeonAmber,
                        onClick = { onInspectTrack(track) }
                    )
                }
            }
        }
    }
}

// ── Never Played Tab ────────────────────────────────────────────────────────
@Composable
private fun StatsNeverPlayedTab(
    tracks: List<Track>,
    onInspectTrack: (Track) -> Unit
) {
    if (tracks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Every track in your library has been played at least once!", color = NeonGreen, fontSize = 13.sp)
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("${tracks.size} tracks never played", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tracks) { track ->
                    TrackRankRow(
                        rank = null,
                        track = track,
                        subtitle = "${track.album} • ${track.genre}",
                        trailingBadge = "Unplayed",
                        badgeColor = TextMuted,
                        onClick = { onInspectTrack(track) }
                    )
                }
            }
        }
    }
}

// ── Rediscover Tab ──────────────────────────────────────────────────────────
@Composable
private fun StatsRediscoverTab(
    tracks: List<Track>,
    onInspectTrack: (Track) -> Unit
) {
    if (tracks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No high-rated or favorite tracks needing rediscovery yet.", color = TextMuted, fontSize = 13.sp)
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Tracks you loved that you haven't listened to recently:", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tracks) { track ->
                    TrackRankRow(
                        rank = null,
                        track = track,
                        subtitle = "${track.album} • Rated ${track.rating} stars",
                        trailingBadge = "Rediscover",
                        badgeColor = DeckBPink,
                        onClick = { onInspectTrack(track) }
                    )
                }
            }
        }
    }
}

// ── Library Metrics Tab ─────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsLibraryMetricsTab(
    allTracks: List<Track>,
    onInspectTrack: (Track) -> Unit
) {
    val totalTracks = allTracks.size
    val totalDurationSec = remember(allTracks) { allTracks.sumOf { it.durationSeconds.toLong() } }
    val totalSizeMb = remember(allTracks) { allTracks.sumOf { it.fileSizeMb } }
    val avgBpm = remember(allTracks) {
        val valid = allTracks.filter { it.hasValidBpm }
        if (valid.isNotEmpty()) valid.map { it.bpm }.average() else 0.0
    }

    val missingBpm = remember(allTracks) { allTracks.count { !it.hasValidBpm } }
    val missingKey = remember(allTracks) { allTracks.count { !it.hasValidKey } }
    val missingArtwork = remember(allTracks) { allTracks.count { it.artworkUrl == null } }

    val losslessCount = remember(allTracks) { allTracks.count { it.isLossless } }
    val mp3320Count = remember(allTracks) { allTracks.count { it.bitrateKbps >= 320 && !it.isLossless } }

    // BPM Distribution
    val bpmRanges = remember(allTracks) {
        mapOf(
            "< 80" to allTracks.count { it.hasValidBpm && it.bpm < 80 },
            "80–99" to allTracks.count { it.hasValidBpm && it.bpm in 80.0..99.9 },
            "100–119" to allTracks.count { it.hasValidBpm && it.bpm in 100.0..119.9 },
            "120–129" to allTracks.count { it.hasValidBpm && it.bpm in 120.0..129.9 },
            "130–139" to allTracks.count { it.hasValidBpm && it.bpm in 130.0..139.9 },
            "140–159" to allTracks.count { it.hasValidBpm && it.bpm in 140.0..159.9 },
            "160+" to allTracks.count { it.hasValidBpm && it.bpm >= 160.0 }
        )
    }

    // Key Distribution
    val keyDistribution = remember(allTracks) {
        allTracks.filter { it.hasValidKey }
            .groupBy { it.camelotKey.ifBlank { it.musicalKey } }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(8)
    }

    // Genre Distribution
    val genreDistribution = remember(allTracks) {
        allTracks.groupBy { it.genre.ifBlank { "Unknown" } }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(8)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // High-level overview
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiStatCard(title = "TOTAL TRACKS", value = "$totalTracks", color = DeckACyan, modifier = Modifier.weight(1f))
            KpiStatCard(title = "TOTAL DURATION", value = "${totalDurationSec / 3600} hours", color = NeonAmber, modifier = Modifier.weight(1f))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiStatCard(title = "STORAGE SIZE", value = "${String.format(Locale.US, "%.1f", totalSizeMb / 1024.0)} GB", color = TextPrimary, modifier = Modifier.weight(1f))
            KpiStatCard(title = "AVERAGE BPM", value = if (avgBpm > 0) String.format(Locale.US, "%.1f", avgBpm) else "—", color = DeckBPink, modifier = Modifier.weight(1f))
        }

        // Library Hygiene
        StatsSectionCard(title = "METADATA & HYGIENE", icon = Icons.Default.Info) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("MISSING BPM", fontSize = 10.sp, color = TextMuted)
                    Text("$missingBpm tracks", fontSize = 13.sp, color = if (missingBpm > 0) NeonAmber else NeonGreen, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("MISSING KEY", fontSize = 10.sp, color = TextMuted)
                    Text("$missingKey tracks", fontSize = 13.sp, color = if (missingKey > 0) NeonAmber else NeonGreen, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("MISSING ARTWORK", fontSize = 10.sp, color = TextMuted)
                    Text("$missingArtwork tracks", fontSize = 13.sp, color = if (missingArtwork > 0) NeonAmber else NeonGreen, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Audio Quality Breakdown
        StatsSectionCard(title = "AUDIO QUALITY BREAKDOWN", icon = Icons.Default.Equalizer) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Lossless (FLAC/WAV/AIFF):", fontSize = 12.sp, color = TextPrimary)
                    Text("$losslessCount (${if (totalTracks > 0) (losslessCount * 100 / totalTracks) else 0}%)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NeonGreen)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("320 kbps MP3 / AAC:", fontSize = 12.sp, color = TextPrimary)
                    Text("$mp3320Count (${if (totalTracks > 0) (mp3320Count * 100 / totalTracks) else 0}%)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DeckACyan)
                }
            }
        }

        // BPM Distribution
        StatsSectionCard(title = "BPM DISTRIBUTION", icon = Icons.Default.Speed) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val maxCount = bpmRanges.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                bpmRanges.forEach { (range, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(range, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.width(60.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(DjSurfaceCard)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction = count.toFloat() / maxCount)
                                    .background(NeonAmber)
                            )
                        }
                        Text("$count", fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                    }
                }
            }
        }

        // Key Distribution
        StatsSectionCard(title = "TOP MUSICAL KEYS (CAMELOT)", icon = Icons.Default.GraphicEq) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                keyDistribution.forEach { (key, count) ->
                    Surface(
                        color = DjSurfaceCard,
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(key, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = DeckBPink)
                            Text("($count)", fontSize = 11.sp, color = TextSecondary)
                        }
                    }
                }
            }
        }

        // Genre Distribution
        StatsSectionCard(title = "TOP GENRES", icon = Icons.Default.MusicNote) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                genreDistribution.forEach { (genre, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(genre, fontSize = 12.sp, color = TextPrimary)
                        Text("$count tracks", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DeckACyan)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Shared Card & Row Helpers ───────────────────────────────────────────────

@Composable
private fun KpiStatCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun StatsSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(16.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary, letterSpacing = 1.sp)
            }
            content()
        }
    }
}

@Composable
private fun TrackRankRow(
    rank: Int?,
    track: Track,
    subtitle: String,
    trailingBadge: String,
    badgeColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(DjSurfaceCard)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (rank != null) {
                Text(
                    text = "#$rank",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (rank <= 3) NeonAmber else TextMuted,
                    modifier = Modifier.width(26.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(DjObsidian),
                contentAlignment = Alignment.Center
            ) {
                if (track.artworkUrl != null) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (trailingBadge.isNotBlank()) {
            Surface(
                color = badgeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = trailingBadge,
                    color = badgeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun formatListeningTime(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}
