package com.example.ui.command

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.command.CommandExecutionState
import com.example.command.CommandPaletteEngine
import com.example.command.PaletteAlbumResult
import com.example.command.PaletteArtistResult
import com.example.command.PaletteCommand
import com.example.command.PaletteFilter
import com.example.command.PaletteFolderResult
import com.example.command.PaletteSearchResults
import com.example.model.Track
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeonRed
import com.example.ui.theme.SoundSyncTheme
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun CommandPaletteDialog(
    isOpen: Boolean,
    initialQuery: String,
    allTracks: List<Track>,
    selectedTrackIds: Set<String>,
    recentTrackIds: Set<String>,
    onDismiss: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onExecuteCommand: (PaletteCommand) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onOpenDjPrep: (Track) -> Unit,
    onOpenFolder: (String) -> Unit
) {
    if (!isOpen) return

    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var pendingConfirmationCommand by remember { mutableStateOf<PaletteCommand?>(null) }
    val focusRequester = remember { FocusRequester() }

    // Execute real-time search
    val results: PaletteSearchResults = remember(query, allTracks, selectedTrackIds, recentTrackIds) {
        CommandPaletteEngine.search(
            query = query,
            allTracks = allTracks,
            selectedTrackIds = selectedTrackIds,
            recentTrackIds = recentTrackIds
        )
    }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        val theme = SoundSyncTheme.current

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, theme.accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .testTag("command_palette_dialog"),
            color = theme.surfaceSunken
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top Search Bar Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(theme.surfaceRaised)
                            .border(1.dp, theme.accent.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Command & Search",
                                tint = theme.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))

                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = "Search library or enter command (e.g. artist Fred again, bpm 128, missing artwork)...",
                                        color = theme.textSecondary.copy(alpha = 0.7f),
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = query,
                                    onValueChange = {
                                        query = it
                                        onQueryChanged(it)
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = theme.textPrimary,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    cursorBrush = SolidColor(theme.accent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                        .testTag("command_palette_input")
                                )
                            }

                            if (query.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        query = ""
                                        onQueryChanged("")
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear query",
                                        tint = theme.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(theme.surfaceRaised)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Command Palette",
                            tint = theme.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Active Filters Chips (if any detected)
                if (results.parsedQuery.filters.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        results.parsedQuery.filters.forEach { filter ->
                            FilterBadgePill(
                                filter = filter,
                                onRemove = {
                                    // Remove filter keyword representation from query
                                    val cleaned = removeFilterFromQuery(query, filter)
                                    query = cleaned
                                    onQueryChanged(cleaned)
                                }
                            )
                        }
                    }
                }

                // Quick Suggestion Chips (when typing or empty)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val quickFilters = listOf(
                        "artist " to "Artist",
                        "bpm 128" to "BPM 128",
                        "bpm 120-130" to "BPM Range",
                        "key 8A" to "Key 8A",
                        "key Am" to "Key Am",
                        "folder Downloads" to "Downloads",
                        "missing artwork" to "No Art",
                        "missing bpm" to "No BPM",
                        "missing key" to "No Key",
                        "missing metadata" to "No Meta",
                        "recently added" to "Recent",
                        "rescan selected" to "Rescan Sel",
                        "clear queue" to "Clear Q"
                    )

                    quickFilters.forEach { (text, label) ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = theme.surfaceRaised,
                            border = BorderStroke(0.5.dp, theme.divider),
                            modifier = Modifier.clickable {
                                query = if (query.isBlank()) text else "$query $text"
                                onQueryChanged(query)
                            }
                        ) {
                            Text(
                                text = label,
                                color = theme.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Grouped Search Results
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. COMMANDS SECTION
                    if (results.commands.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "COMMANDS",
                                count = results.commands.size,
                                icon = Icons.Default.Terminal,
                                accentColor = theme.accent
                            )
                        }

                        items(results.commands, key = { "cmd_${it.command.id}" }) { cmdState ->
                            CommandResultItem(
                                state = cmdState,
                                onClick = {
                                    if (cmdState.isEnabled) {
                                        if (cmdState.command.confirmationPrompt != null) {
                                            pendingConfirmationCommand = cmdState.command
                                        } else {
                                            onExecuteCommand(cmdState.command)
                                            onDismiss()
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // 2. TRACKS SECTION
                    if (results.tracks.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "TRACKS",
                                count = results.totalMatches,
                                icon = Icons.Default.MusicNote,
                                accentColor = DeckACyan
                            )
                        }

                        items(results.tracks, key = { "trk_${it.id}" }) { track ->
                            TrackPaletteItem(
                                track = track,
                                onPlay = {
                                    onPlayTrack(track)
                                    onDismiss()
                                },
                                onPlayNext = {
                                    onPlayNext(track)
                                },
                                onAddToQueue = {
                                    onAddToQueue(track)
                                },
                                onOpenDjPrep = {
                                    onOpenDjPrep(track)
                                    onDismiss()
                                },
                                onLocateFolder = {
                                    onOpenFolder(track.directoryPath)
                                    onDismiss()
                                }
                            )
                        }
                    }

                    // 3. ARTISTS SECTION
                    if (results.artists.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "ARTISTS",
                                count = results.artists.size,
                                icon = Icons.Default.Person,
                                accentColor = NeonGreen
                            )
                        }

                        items(results.artists, key = { "art_${it.name}" }) { artist ->
                            ArtistPaletteItem(
                                artist = artist,
                                onClick = {
                                    query = "artist:\"${artist.name}\""
                                    onQueryChanged(query)
                                }
                            )
                        }
                    }

                    // 4. ALBUMS SECTION
                    if (results.albums.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "ALBUMS",
                                count = results.albums.size,
                                icon = Icons.Default.Album,
                                accentColor = NeonAmber
                            )
                        }

                        items(results.albums, key = { "alb_${it.title}_${it.artist}" }) { album ->
                            AlbumPaletteItem(
                                album = album,
                                onClick = {
                                    query = "\"${album.title}\""
                                    onQueryChanged(query)
                                }
                            )
                        }
                    }

                    // 5. FOLDERS SECTION
                    if (results.folders.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "FOLDERS",
                                count = results.folders.size,
                                icon = Icons.Default.Folder,
                                accentColor = DeckBPink
                            )
                        }

                        items(results.folders, key = { "fld_${it.path}" }) { folder ->
                            FolderPaletteItem(
                                folder = folder,
                                onClick = {
                                    onOpenFolder(folder.path)
                                    onDismiss()
                                }
                            )
                        }
                    }

                    // Empty state
                    if (results.commands.isEmpty() && results.tracks.isEmpty() && results.artists.isEmpty() && results.albums.isEmpty() && results.folders.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = theme.textSecondary.copy(alpha = 0.4f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "No matching tracks, commands, or folders found",
                                        color = theme.textSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation Dialog for Broad or Destructive Commands
    if (pendingConfirmationCommand != null) {
        val cmd = pendingConfirmationCommand!!
        AlertDialog(
            onDismissRequest = { pendingConfirmationCommand = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (cmd.isDestructive) Icons.Default.Warning else Icons.Default.Terminal,
                        contentDescription = null,
                        tint = if (cmd.isDestructive) NeonRed else DeckACyan
                    )
                    Text(text = cmd.title)
                }
            },
            text = {
                Text(text = cmd.confirmationPrompt ?: "Are you sure you want to execute '${cmd.title}'?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onExecuteCommand(cmd)
                        pendingConfirmationCommand = null
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (cmd.isDestructive) NeonRed else DeckACyan
                    )
                ) {
                    Text(text = "Execute", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmationCommand = null }) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    icon: ImageVector,
    accentColor: Color
) {
    val theme = SoundSyncTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp))
        Text(
            text = title,
            color = theme.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = accentColor.copy(alpha = 0.15f),
            border = BorderStroke(0.5.dp, accentColor.copy(alpha = 0.4f))
        ) {
            Text(
                text = count.toString(),
                color = accentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun FilterBadgePill(
    filter: PaletteFilter,
    onRemove: () -> Unit
) {
    val theme = SoundSyncTheme.current
    val (label, value) = when (filter) {
        is PaletteFilter.Artist -> "Artist" to filter.artist
        is PaletteFilter.BpmExact -> "BPM" to "${filter.bpm}"
        is PaletteFilter.BpmRange -> "BPM" to "${filter.minBpm}-${filter.maxBpm}"
        is PaletteFilter.Key -> "Key" to (filter.camelotKey ?: filter.rawKey)
        is PaletteFilter.Folder -> "Folder" to filter.folderName
        is PaletteFilter.Missing -> "Missing" to filter.fieldType.label.uppercase(Locale.ROOT)
        is PaletteFilter.RecentlyAdded -> "Filter" to "Recently Added"
        is PaletteFilter.RecentlyPlayed -> "Filter" to "Recently Played"
        is PaletteFilter.Unplayed -> "Filter" to "Unplayed"
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = theme.accent.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, theme.accent.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$label:",
                color = theme.textSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = value,
                color = theme.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black
            )
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove filter",
                tint = theme.accent,
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onRemove() }
            )
        }
    }
}

@Composable
private fun CommandResultItem(
    state: CommandExecutionState,
    onClick: () -> Unit
) {
    val theme = SoundSyncTheme.current
    val cmd = state.command

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = state.isEnabled, onClick = onClick)
            .testTag("command_item_${cmd.id}"),
        shape = RoundedCornerShape(10.dp),
        color = if (state.isEnabled) theme.surfaceRaised else theme.surfaceRaised.copy(alpha = 0.5f),
        border = BorderStroke(
            0.5.dp,
            if (state.isEnabled) theme.divider else theme.divider.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (state.isEnabled) theme.surfaceSunken else theme.surfaceSunken.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        cmd.isDestructive -> Icons.Default.Warning
                        cmd.id.contains("scan") -> Icons.Default.Refresh
                        cmd.id.contains("queue") -> Icons.Default.QueueMusic
                        cmd.id.contains("folder") -> Icons.Default.FolderOpen
                        else -> Icons.Default.Terminal
                    },
                    contentDescription = null,
                    tint = when {
                        !state.isEnabled -> theme.textSecondary.copy(alpha = 0.4f)
                        cmd.isDestructive -> NeonRed
                        else -> theme.accent
                    },
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = cmd.title,
                        color = if (state.isEnabled) theme.textPrimary else theme.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = theme.surfaceSunken
                    ) {
                        Text(
                            text = cmd.category.displayTitle.uppercase(Locale.ROOT),
                            color = theme.textSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (!state.isEnabled && state.disabledReason != null) {
                        state.disabledReason
                    } else {
                        cmd.description
                    },
                    color = if (!state.isEnabled) NeonAmber else theme.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (cmd.requiresSelection && state.isEnabled) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = NeonGreen.copy(alpha = 0.2f),
                    border = BorderStroke(0.5.dp, NeonGreen)
                ) {
                    Text(
                        text = "${state.selectionCount} Selected",
                        color = NeonGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackPaletteItem(
    track: Track,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onOpenDjPrep: () -> Unit,
    onLocateFolder: () -> Unit
) {
    val theme = SoundSyncTheme.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(10.dp),
        color = theme.surfaceRaised,
        border = BorderStroke(0.5.dp, theme.divider)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Play Button
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(DeckACyan.copy(alpha = 0.15f))
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = DeckACyan,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Track info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = theme.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track.artist,
                        color = theme.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (track.hasValidBpm) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = theme.surfaceSunken
                        ) {
                            Text(
                                text = "${track.bpm.toInt()} BPM",
                                color = NeonAmber,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    if (track.camelotKey.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = theme.surfaceSunken
                        ) {
                            Text(
                                text = track.camelotKey,
                                color = NeonGreen,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            // Quick Actions: Play Next, Queue, DJ Prep, Locate
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlayNext, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = "Play Next", tint = theme.textSecondary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onAddToQueue, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Queue, contentDescription = "Add to Queue", tint = theme.textSecondary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onOpenDjPrep, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Speed, contentDescription = "DJ Prep", tint = DeckBPink, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onLocateFolder, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Locate File", tint = theme.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ArtistPaletteItem(
    artist: PaletteArtistResult,
    onClick: () -> Unit
) {
    val theme = SoundSyncTheme.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = theme.surfaceRaised,
        border = BorderStroke(0.5.dp, theme.divider)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(16.dp))
                Text(text = artist.name, color = theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Text(text = "${artist.trackCount} tracks", color = theme.textSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AlbumPaletteItem(
    album: PaletteAlbumResult,
    onClick: () -> Unit
) {
    val theme = SoundSyncTheme.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = theme.surfaceRaised,
        border = BorderStroke(0.5.dp, theme.divider)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Album, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(16.dp))
                Column {
                    Text(text = album.title, color = theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (album.artist.isNotBlank()) {
                        Text(text = album.artist, color = theme.textSecondary, fontSize = 10.sp)
                    }
                }
            }
            Text(text = "${album.trackCount} tracks", color = theme.textSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FolderPaletteItem(
    folder: PaletteFolderResult,
    onClick: () -> Unit
) {
    val theme = SoundSyncTheme.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = theme.surfaceRaised,
        border = BorderStroke(0.5.dp, theme.divider)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = DeckBPink, modifier = Modifier.size(16.dp))
                Column {
                    Text(text = folder.name, color = theme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(text = folder.path, color = theme.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(text = "${folder.trackCount} tracks", color = theme.textSecondary, fontSize = 11.sp)
        }
    }
}

private fun removeFilterFromQuery(query: String, filter: PaletteFilter): String {
    val keyword = when (filter) {
        is PaletteFilter.Artist -> "artist"
        is PaletteFilter.BpmExact, is PaletteFilter.BpmRange -> "bpm"
        is PaletteFilter.Key -> "key"
        is PaletteFilter.Folder -> "folder"
        is PaletteFilter.Missing -> "missing"
        is PaletteFilter.RecentlyAdded -> "recently added"
        is PaletteFilter.RecentlyPlayed -> "recently played"
        is PaletteFilter.Unplayed -> "unplayed"
    }
    return query.replace(Regex("(?i)\\b$keyword\\b[^\\s]*"), "").trim().replace(Regex("\\s+"), " ")
}
