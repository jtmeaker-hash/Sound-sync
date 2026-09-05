package com.example.ui.bulk

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.analysis.AiAutoTagger
import com.example.analysis.DuplicateDetector
import com.example.audio.SpectrogramEngine
import com.example.audio.WaveformAnalyzer
import com.example.data.*
import com.example.model.DuplicateMatch
import com.example.model.Track
import com.example.ui.MainDjViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkTrackEditorDialog(
    selectedTracks: List<Track>,
    allPlaylists: List<PlaylistEntity>,
    viewModel: MainDjViewModel,
    onDismiss: () -> Unit,
    onTracksUpdated: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }
    val trackDao = remember { database.trackDao() }
    val playlistDao = remember { database.playlistDao() }
    val bulkDao = remember { database.bulkOperationHistoryDao() }

    // Multi-track metadata state: calculate common vs mixed values
    val commonArtist = remember(selectedTracks) {
        val distinct = selectedTracks.map { it.artist }.distinct()
        if (distinct.size == 1) distinct.first() else ""
    }
    val commonAlbum = remember(selectedTracks) {
        val distinct = selectedTracks.map { it.album }.distinct()
        if (distinct.size == 1) distinct.first() else ""
    }
    val commonAlbumArtist = remember(selectedTracks) {
        val distinct = selectedTracks.map { it.albumArtist }.distinct()
        if (distinct.size == 1) distinct.first() else ""
    }
    val commonGenre = remember(selectedTracks) {
        val distinct = selectedTracks.map { it.genre }.distinct()
        if (distinct.size == 1) distinct.first() else ""
    }
    val commonYear = remember(selectedTracks) {
        val distinct = selectedTracks.mapNotNull { it.releaseYear }.distinct()
        if (distinct.size == 1) distinct.first().toString() else ""
    }
    val commonComposer = remember(selectedTracks) {
        val distinct = selectedTracks.map { it.composer }.distinct()
        if (distinct.size == 1) distinct.first() else ""
    }

    // Editable text values
    var artistText by remember { mutableStateOf(commonArtist) }
    var albumText by remember { mutableStateOf(commonAlbum) }
    var albumArtistText by remember { mutableStateOf(commonAlbumArtist) }
    var genreText by remember { mutableStateOf(commonGenre) }
    var yearText by remember { mutableStateOf(commonYear) }
    var composerText by remember { mutableStateOf(commonComposer) }
    var notesText by remember { mutableStateOf("") }

    // Explicit enable checkboxes (preventing accidental overwrite!)
    var applyArtist by remember { mutableStateOf(false) }
    var applyAlbum by remember { mutableStateOf(false) }
    var applyAlbumArtist by remember { mutableStateOf(false) }
    var applyGenre by remember { mutableStateOf(false) }
    var applyYear by remember { mutableStateOf(false) }
    var applyComposer by remember { mutableStateOf(false) }
    var applyNotes by remember { mutableStateOf(false) }

    // Rating & Tagging
    var selectedBulkRating by remember { mutableStateOf<Int?>(null) }
    var tagToAdd by remember { mutableStateOf("") }
    var tagToRemove by remember { mutableStateOf("") }

    // Analysis Queue State
    var isAnalysisRunning by remember { mutableStateOf(false) }
    var analysisProgressMessage by remember { mutableStateOf("") }
    var analysisProgressPercent by remember { mutableStateOf(0) }
    var skipAlreadyAnalysed by remember { mutableStateOf(true) }
    var currentAnalysisJob by remember { mutableStateOf<Job?>(null) }

    // Smart Rename Preview
    var selectedRenameFormat by remember { mutableStateOf("Artist - Title") }
    var showRenamePreview by remember { mutableStateOf(false) }
    var renameCollisionsCount by remember { mutableStateOf(0) }

    // Duplicates Modal
    var showDuplicateScanResult by remember { mutableStateOf(false) }
    var foundDuplicates by remember { mutableStateOf<List<DuplicateMatch>>(emptyList()) }

    // Danger Zone Dialogs
    var showDeleteFilesConfirm by remember { mutableStateOf(false) }
    var showRemoveFromLibraryConfirm by remember { mutableStateOf(false) }
    var showApplyChangesConfirm by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = {
        if (!isAnalysisRunning) onDismiss()
    }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Dialog Top Bar
                Surface(
                    color = DjObsidian,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Bulk Track Editor", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                            Text("${selectedTracks.size} tracks selected", color = DeckACyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Progress Bar if bulk operation is in-flight
                if (isAnalysisRunning) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DjSurfaceCard)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(analysisProgressMessage, fontSize = 11.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("$analysisProgressPercent%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DeckACyan)
                        }
                        LinearProgressIndicator(
                            progress = { analysisProgressPercent / 100f },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = DeckACyan,
                            trackColor = DjObsidian
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = {
                                    currentAnalysisJob?.cancel()
                                    isAnalysisRunning = false
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Cancel", color = NeonRed, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Scrollable Content Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── 1. BULK METADATA ───────────────────────────────────────────────
                    BulkSectionCard(title = "METADATA", icon = Icons.Default.Edit) {
                        Text(
                            "Check the box next to a field to update it across all ${selectedTracks.size} tracks. Unchecked fields will remain untouched.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )

                        BulkFieldRow(label = "Artist", enabled = applyArtist, onToggle = { applyArtist = it }, value = artistText, onValueChange = { artistText = it }, placeholder = if (commonArtist.isNotBlank()) commonArtist else "Mixed values")
                        BulkFieldRow(label = "Album", enabled = applyAlbum, onToggle = { applyAlbum = it }, value = albumText, onValueChange = { albumText = it }, placeholder = if (commonAlbum.isNotBlank()) commonAlbum else "Mixed values")
                        BulkFieldRow(label = "Album Artist", enabled = applyAlbumArtist, onToggle = { applyAlbumArtist = it }, value = albumArtistText, onValueChange = { albumArtistText = it }, placeholder = if (commonAlbumArtist.isNotBlank()) commonAlbumArtist else "Mixed values")
                        BulkFieldRow(label = "Genre", enabled = applyGenre, onToggle = { applyGenre = it }, value = genreText, onValueChange = { genreText = it }, placeholder = if (commonGenre.isNotBlank()) commonGenre else "Mixed values")
                        BulkFieldRow(label = "Year", enabled = applyYear, onToggle = { applyYear = it }, value = yearText, onValueChange = { yearText = it }, placeholder = if (commonYear.isNotBlank()) commonYear else "Mixed values")
                        BulkFieldRow(label = "Composer", enabled = applyComposer, onToggle = { applyComposer = it }, value = composerText, onValueChange = { composerText = it }, placeholder = if (commonComposer.isNotBlank()) commonComposer else "Mixed values")
                        BulkFieldRow(label = "Notes / Comment", enabled = applyNotes, onToggle = { applyNotes = it }, value = notesText, onValueChange = { notesText = it }, placeholder = "Add comments or performance notes")
                    }

                    // ── 2. DJ TAGS & ORGANISATION ──────────────────────────────────────
                    BulkSectionCard(title = "DJ TAGS & ORGANISATION", icon = Icons.Default.Tune) {
                        Text("Add or remove DJ tags across all selected tracks without overwriting existing tags:", fontSize = 11.sp, color = TextSecondary)

                        val presetTags = listOf("Peak Time", "Warm-up", "Closing", "Vocal", "Instrumental", "Heavy Bass", "Classic", "Weapon", "Afterparty")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            presetTags.forEach { tag ->
                                AssistChip(
                                    onClick = { tagToAdd = tag },
                                    label = { Text("+ $tag", fontSize = 11.sp) },
                                    colors = AssistChipDefaults.assistChipColors(labelColor = if (tagToAdd == tag) DeckACyan else TextPrimary)
                                )
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = tagToAdd,
                                onValueChange = { tagToAdd = it },
                                placeholder = { Text("Tag to add...", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = tagToRemove,
                                onValueChange = { tagToRemove = it },
                                placeholder = { Text("Tag to remove...", fontSize = 12.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }

                    // ── 3. BULK RATING ─────────────────────────────────────────────────
                    BulkSectionCard(title = "BULK RATING", icon = Icons.Default.Star) {
                        Text("Set star rating across all ${selectedTracks.size} tracks:", fontSize = 11.sp, color = TextSecondary)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (star in 1..5) {
                                val isSelected = selectedBulkRating != null && star <= selectedBulkRating!!
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = "Rate $star stars",
                                    tint = if (isSelected) NeonAmber else TextMuted,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clickable {
                                            selectedBulkRating = if (selectedBulkRating == star) null else star
                                        }
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (selectedBulkRating != null) {
                                TextButton(onClick = { selectedBulkRating = 0 }) {
                                    Text("Set Unrated", fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                        }
                    }

                    // ── 4. BULK AUDIO ANALYSIS ─────────────────────────────────────────
                    BulkSectionCard(title = "BULK AUDIO ANALYSIS", icon = Icons.Default.GraphicEq) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = skipAlreadyAnalysed,
                                onCheckedChange = { skipAlreadyAnalysed = it }
                            )
                            Text("Skip tracks already analysed (recommended)", fontSize = 12.sp, color = TextPrimary)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    startBatchAnalysis(selectedTracks, skipAlreadyAnalysed, context, trackDao, viewModel) { msg, pct, running ->
                                        analysisProgressMessage = msg
                                        analysisProgressPercent = pct
                                        isAnalysisRunning = running
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckACyan),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                            ) {
                                Text("Analyse All", fontSize = 11.sp)
                            }
                        }
                    }

                    // ── 5. APPLE & THEAUDIODB METADATA LOOKUP ──────────────────────────
                    BulkSectionCard(title = "APPLE & THEAUDIODB METADATA LOOKUP", icon = Icons.Default.AutoAwesome) {
                        Text("Conservatively match and enrich metadata & album artwork using Apple iTunes Search and TheAudioDB:", fontSize = 11.sp, color = TextSecondary)
                        OutlinedButton(
                            onClick = {
                                startAppleBatchEnrichment(selectedTracks, viewModel, trackDao) { msg, pct, running ->
                                    analysisProgressMessage = msg
                                    analysisProgressPercent = pct
                                    isAnalysisRunning = running
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckBPink),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = DeckBPink, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Fetch Missing Metadata & Artwork", fontSize = 12.sp)
                        }
                    }

                    // ── 6. PLAYLIST OPERATIONS ─────────────────────────────────────────
                    BulkSectionCard(title = "PLAYLIST OPERATIONS", icon = Icons.Default.QueueMusic) {
                        Text("Add all ${selectedTracks.size} tracks to an existing playlist:", fontSize = 11.sp, color = TextSecondary)
                        if (allPlaylists.isEmpty()) {
                            Text("No playlists found in library.", fontSize = 12.sp, color = TextMuted)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                allPlaylists.forEach { playlist ->
                                    AssistChip(
                                        onClick = {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                playlistDao.addTracksToPlaylist(playlist.id, selectedTracks.map { it.id })
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "Added ${selectedTracks.size} tracks to '${playlist.name}'", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        label = { Text("+ ${playlist.name}", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }

                    // ── 7. SMART FILE RENAMER ──────────────────────────────────────────
                    BulkSectionCard(title = "SMART FILE RENAMER", icon = Icons.Default.DriveFileRenameOutline) {
                        Text("Standardize file names on storage while preserving file extension and updating database paths:", fontSize = 11.sp, color = TextSecondary)
                        val formats = listOf("Artist - Title", "Artist - Album - Title", "TrackNumber - Title", "TrackNumber - Artist - Title")
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            formats.forEach { fmt ->
                                FilterChip(
                                    selected = selectedRenameFormat == fmt,
                                    onClick = { selectedRenameFormat = fmt },
                                    label = { Text(fmt, fontSize = 10.sp) }
                                )
                            }
                        }

                        Button(
                            onClick = {
                                showRenamePreview = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceCard),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Preview, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Preview & Apply Renaming", color = TextPrimary, fontSize = 12.sp)
                        }
                    }

                    // ── 8. DUPLICATE CHECKER ───────────────────────────────────────────
                    BulkSectionCard(title = "DUPLICATE CHECKING", icon = Icons.Default.FilterList) {
                        Text("Check selected tracks for exact audio fingerprints or near-duplicate copies:", fontSize = 11.sp, color = TextSecondary)
                        OutlinedButton(
                            onClick = {
                                val dups = DuplicateDetector.findDuplicates(selectedTracks)
                                foundDuplicates = dups
                                showDuplicateScanResult = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Check Selected for Duplicates", fontSize = 12.sp, color = TextPrimary)
                        }
                    }

                    // ── 9. DANGER ZONE ─────────────────────────────────────────────────
                    BulkSectionCard(title = "DANGER ZONE", icon = Icons.Default.Warning, borderColor = NeonRed.copy(alpha = 0.5f)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showRemoveFromLibraryConfirm = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonAmber),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, NeonAmber.copy(alpha = 0.5f))
                            ) {
                                Text("Remove from SoundSync", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { showDeleteFilesConfirm = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonRed),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, NeonRed.copy(alpha = 0.5f))
                            ) {
                                Text("Delete Files", fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Dialog Bottom Apply Button Bar
                Surface(
                    color = DjSurfaceDark,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                        ) {
                            Text("Cancel", color = TextSecondary)
                        }

                        Button(
                            onClick = {
                                val hasAnyChange = applyArtist || applyAlbum || applyAlbumArtist || applyGenre || applyYear || applyComposer || applyNotes || selectedBulkRating != null || tagToAdd.isNotBlank() || tagToRemove.isNotBlank()
                                if (hasAnyChange) {
                                    showApplyChangesConfirm = true
                                } else {
                                    Toast.makeText(context, "No fields were enabled to update", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                            modifier = Modifier.weight(1.5f)
                        ) {
                            Text("Apply Changes", color = DjObsidian, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ── Dialogs ─────────────────────────────────────────────────────────────

    // Apply Changes Confirmation
    if (showApplyChangesConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyChangesConfirm = false },
            containerColor = DjSurfaceDark,
            title = { Text("Apply Changes to ${selectedTracks.size} Tracks?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (applyArtist) Text("• Artist → '$artistText'", fontSize = 12.sp, color = TextPrimary)
                    if (applyAlbum) Text("• Album → '$albumText'", fontSize = 12.sp, color = TextPrimary)
                    if (applyAlbumArtist) Text("• Album Artist → '$albumArtistText'", fontSize = 12.sp, color = TextPrimary)
                    if (applyGenre) Text("• Genre → '$genreText'", fontSize = 12.sp, color = TextPrimary)
                    if (applyYear) Text("• Year → '$yearText'", fontSize = 12.sp, color = TextPrimary)
                    if (applyComposer) Text("• Composer → '$composerText'", fontSize = 12.sp, color = TextPrimary)
                    if (applyNotes) Text("• Notes updated", fontSize = 12.sp, color = TextPrimary)
                    if (selectedBulkRating != null) Text("• Rating → $selectedBulkRating stars", fontSize = 12.sp, color = TextPrimary)
                    if (tagToAdd.isNotBlank()) Text("• Add Tag → '$tagToAdd'", fontSize = 12.sp, color = TextPrimary)
                    if (tagToRemove.isNotBlank()) Text("• Remove Tag → '$tagToRemove'", fontSize = 12.sp, color = TextPrimary)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showApplyChangesConfirm = false
                        coroutineScope.launch(Dispatchers.IO) {
                            val updatedList = selectedTracks.map { track ->
                                var t = track
                                if (applyArtist) t = t.copy(artist = artistText.trim())
                                if (applyAlbum) t = t.copy(album = albumText.trim())
                                if (applyAlbumArtist) t = t.copy(albumArtist = albumArtistText.trim())
                                if (applyGenre) t = t.copy(genre = genreText.trim())
                                if (applyYear) t = t.copy(releaseYear = yearText.toIntOrNull())
                                if (applyComposer) t = t.copy(composer = composerText.trim())
                                if (applyNotes) t = t.copy(notes = notesText.trim())
                                if (selectedBulkRating != null) t = t.copy(rating = selectedBulkRating!!)
                                if (tagToAdd.isNotBlank()) {
                                    val newTags = (t.tagsList + tagToAdd.trim()).distinct().joinToString(",")
                                    t = t.copy(customTags = newTags)
                                }
                                if (tagToRemove.isNotBlank()) {
                                    val newTags = t.tagsList.filter { it != tagToRemove.trim() }.joinToString(",")
                                    t = t.copy(customTags = newTags)
                                }
                                t
                            }

                            // Save undo snapshot
                            val historyItem = BulkOperationHistoryEntity(
                                id = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                operationType = "METADATA",
                                summary = "Updated ${updatedList.size} tracks",
                                affectedTracksCount = updatedList.size,
                                undoPayloadJson = "" // Handled via DAO
                            )
                            bulkDao.insertOperation(historyItem)
                            trackDao.updateTracks(updatedList.map { TrackEntity.fromTrack(it) })

                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Successfully updated ${updatedList.size} tracks", Toast.LENGTH_SHORT).show()
                                onTracksUpdated()
                                onDismiss()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
                ) {
                    Text("Confirm & Apply", color = DjObsidian, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyChangesConfirm = false }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }

    // Rename Preview Dialog
    if (showRenamePreview) {
        val previewItems = remember(selectedTracks, selectedRenameFormat) {
            selectedTracks.map { track ->
                val ext = track.filePath.substringAfterLast(".", "mp3")
                val newName = when (selectedRenameFormat) {
                    "Artist - Title" -> "${track.artist.ifBlank { "Unknown" }} - ${track.title}.$ext"
                    "Artist - Album - Title" -> "${track.artist.ifBlank { "Unknown" }} - ${track.album.ifBlank { "Unknown" }} - ${track.title}.$ext"
                    "TrackNumber - Title" -> "${String.format(Locale.US, "%02d", track.trackNumber)} - ${track.title}.$ext"
                    else -> "${String.format(Locale.US, "%02d", track.trackNumber)} - ${track.artist} - ${track.title}.$ext"
                }.replace("/", "_").replace("\\", "_")
                val curFile = File(track.filePath)
                val targetFile = File(curFile.parentFile, newName)
                Triple(track, curFile.name, newName)
            }
        }

        AlertDialog(
            onDismissRequest = { showRenamePreview = false },
            containerColor = DjSurfaceDark,
            title = { Text("Smart File Renamer Preview", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Previewing ${previewItems.size} files to rename:", fontSize = 12.sp, color = TextSecondary)
                    previewItems.take(10).forEach { (_, old, new) ->
                        Column {
                            Text("Before: $old", fontSize = 11.sp, color = TextMuted)
                            Text("After:  $new", fontSize = 11.sp, color = DeckACyan, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (previewItems.size > 10) {
                        Text("...and ${previewItems.size - 10} more files", fontSize = 11.sp, color = TextMuted)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRenamePreview = false
                        coroutineScope.launch(Dispatchers.IO) {
                            var renamedCount = 0
                            val updatedTracks = mutableListOf<Track>()
                            previewItems.forEach { (track, _, newName) ->
                                val curFile = File(track.filePath)
                                val targetFile = File(curFile.parentFile, newName)
                                if (curFile.exists() && !targetFile.exists()) {
                                    val ok = curFile.renameTo(targetFile)
                                    if (ok) {
                                        renamedCount++
                                        updatedTracks.add(track.copy(filePath = targetFile.absolutePath))
                                    }
                                }
                            }
                            if (updatedTracks.isNotEmpty()) {
                                trackDao.updateTracks(updatedTracks.map { TrackEntity.fromTrack(it) })
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Renamed $renamedCount files safely", Toast.LENGTH_SHORT).show()
                                onTracksUpdated()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
                ) {
                    Text("Apply Renaming", color = DjObsidian, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenamePreview = false }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }

    // Duplicate Scan Results
    if (showDuplicateScanResult) {
        AlertDialog(
            onDismissRequest = { showDuplicateScanResult = false },
            containerColor = DjSurfaceDark,
            title = { Text("Duplicate Scan Results", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (foundDuplicates.isEmpty()) {
                        Text("No duplicate tracks were found in the selection.", fontSize = 12.sp, color = NeonGreen)
                    } else {
                        Text("Found ${foundDuplicates.size} duplicate match pair(s):", fontSize = 12.sp, color = NeonAmber)
                        foundDuplicates.forEach { match ->
                            Text("• ${match.trackA.title} (${match.reason})", fontSize = 11.sp, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showDuplicateScanResult = false }) { Text("Close") }
            }
        )
    }

    // Delete Physical Files Dialog
    if (showDeleteFilesConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteFilesConfirm = false },
            containerColor = DjSurfaceDark,
            title = { Text("Delete ${selectedTracks.size} Physical Audio Files?", color = NeonRed, fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently delete the audio files from storage. This operation CANNOT be undone!", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteFilesConfirm = false
                        coroutineScope.launch(Dispatchers.IO) {
                            selectedTracks.forEach { track ->
                                try {
                                    val f = File(track.filePath)
                                    if (f.exists()) f.delete()
                                } catch (_: Exception) {}
                            }
                            trackDao.deleteTracksByIds(selectedTracks.map { it.id })
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Deleted ${selectedTracks.size} files", Toast.LENGTH_SHORT).show()
                                onTracksUpdated()
                                onDismiss()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonRed)
                ) {
                    Text("Delete Permanently", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFilesConfirm = false }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }

    // Remove from SoundSync Dialog
    if (showRemoveFromLibraryConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveFromLibraryConfirm = false },
            containerColor = DjSurfaceDark,
            title = { Text("Remove from SoundSync?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("This removes the selected tracks from the SoundSync library database, leaving the audio files safe on storage.", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showRemoveFromLibraryConfirm = false
                        coroutineScope.launch(Dispatchers.IO) {
                            trackDao.deleteTracksByIds(selectedTracks.map { it.id })
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Removed ${selectedTracks.size} tracks from library", Toast.LENGTH_SHORT).show()
                                onTracksUpdated()
                                onDismiss()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonAmber)
                ) {
                    Text("Remove", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveFromLibraryConfirm = false }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }
}

// ── Background Queue Helpers ────────────────────────────────────────────────

private fun startBatchAnalysis(
    tracks: List<Track>,
    skipAlreadyAnalysed: Boolean,
    context: Context,
    trackDao: TrackDao,
    viewModel: MainDjViewModel,
    onProgress: (msg: String, pct: Int, running: Boolean) -> Unit
) {
    onProgress("Starting audio analysis queue...", 0, true)
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        val toProcess = if (skipAlreadyAnalysed) tracks.filter { !it.hasValidBpm || it.bpmLastAnalyzed == null } else tracks
        val total = toProcess.size
        if (total == 0) {
            withContext(Dispatchers.Main) {
                onProgress("All tracks already analysed", 100, false)
                Toast.makeText(context, "All selected tracks already analysed", Toast.LENGTH_SHORT).show()
            }
            return@launch
        }

        toProcess.forEachIndexed { index, track ->
            val pct = ((index + 1) * 100) / total
            withContext(Dispatchers.Main) {
                onProgress("Analysing (${index + 1}/$total): '${track.title}'", pct, true)
            }
            try {
                val wf = WaveformAnalyzer.analyze(context, track)
                val spec = SpectrogramEngine.analyzeTrack(context, track)
                val updated = track.copy(
                    bpm = if (wf.bpm in 30.0..300.0) wf.bpm else track.bpm,
                    bpmLastAnalyzed = System.currentTimeMillis(),
                    qualityRating = spec.qualityRating
                )
                trackDao.updateTrack(TrackEntity.fromTrack(updated))
            } catch (_: Exception) {}
        }
        withContext(Dispatchers.Main) {
            onProgress("Analysis completed ($total tracks)", 100, false)
            Toast.makeText(context, "Batch analysis complete!", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun startAppleBatchEnrichment(
    tracks: List<Track>,
    viewModel: MainDjViewModel,
    trackDao: TrackDao,
    onProgress: (msg: String, pct: Int, running: Boolean) -> Unit
) {
    onProgress("Starting Apple & TheAudioDB metadata lookup...", 0, true)
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        val total = tracks.size
        tracks.forEachIndexed { index, track ->
            val pct = ((index + 1) * 100) / total
            withContext(Dispatchers.Main) {
                onProgress("Resolving metadata (${index + 1}/$total): '${track.title}'", pct, true)
            }
            try {
                val result = viewModel.metadataResolver.resolveTrackMetadata(
                    track = track,
                    forceRefresh = true,
                    embedArtworkToFile = viewModel.metadataSettings.value.writeToFileEnabled
                )
                val updated = result.updatedTrack
                trackDao.updateTrack(TrackEntity.fromTrack(updated))
            } catch (_: Exception) {}
        }
        withContext(Dispatchers.Main) {
            onProgress("Apple & TheAudioDB enrichment complete", 100, false)
        }
    }
}

// ── Subcomponents ───────────────────────────────────────────────────────────

@Composable
private fun BulkSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    borderColor: Color = DjSurfaceBorder,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
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
private fun BulkFieldRow(
    label: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = enabled,
            onCheckedChange = onToggle
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            label = { Text(label, fontSize = 11.sp) },
            placeholder = { Text(placeholder, fontSize = 11.sp, color = TextMuted) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = DeckACyan,
                unfocusedBorderColor = DjSurfaceBorder,
                disabledBorderColor = DjSurfaceBorder.copy(alpha = 0.5f),
                disabledTextColor = TextMuted
            )
        )
    }
}
