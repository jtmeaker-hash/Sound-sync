package com.example.ui.inspector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.analysis.TrackAudioMetrics
import com.example.analysis.TrackAudioMetricsService
import com.example.audio.DjAudioEngine
import com.example.audio.SpectrogramEngine
import com.example.audio.WaveformAnalyzer
import com.example.audio.WaveformCache
import com.example.audio.WaveformData
import com.example.data.AppDatabase
import com.example.data.PlaylistEntity
import com.example.data.TrackEntity
import com.example.data.TrackPlaybackStats
import com.example.model.AudioQualityRating
import com.example.model.SpectrogramAnalysis
import com.example.model.Track
import com.example.model.WaveformStyle
import com.example.ui.MainDjViewModel
import com.example.ui.components.RekordboxWaveformView
import com.example.ui.components.SpectrogramAnalyzerView
import com.example.ui.djtools.KeyConverterData
import com.example.ui.theme.*
import com.example.util.AlbumArtHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackInspectorScreen(
    initialTrack: Track,
    viewModel: MainDjViewModel,
    audioEngine: DjAudioEngine,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }
    val trackDao = remember { database.trackDao() }
    val playbackDao = remember { database.playbackSessionDao() }
    val playlistDao = remember { database.playlistDao() }

    // Live reactive track state
    var currentTrack by remember { mutableStateOf(initialTrack) }

    // Playback stats
    var playbackStats by remember { mutableStateOf<TrackPlaybackStats?>(null) }
    var playlistsContainingTrack by remember { mutableStateOf<List<PlaylistEntity>>(emptyList()) }

    // Waveform & Spectrogram
    var waveformData by remember { mutableStateOf<WaveformData?>(WaveformCache.get(WaveformCache.getCacheKey(initialTrack, context), context)) }
    var isWaveformLoading by remember { mutableStateOf(false) }
    var spectrogramData by remember { mutableStateOf<SpectrogramAnalysis?>(null) }
    var isSpectrogramLoading by remember { mutableStateOf(false) }

    // Offline Audio Metrics (LUFS/RMS/DR)
    var audioMetrics by remember { mutableStateOf<TrackAudioMetrics?>(TrackAudioMetricsService.getCached(initialTrack.id)) }
    var isMetricsLoading by remember { mutableStateOf(false) }

    // Dialog controls
    var showEditMetadataDialog by remember { mutableStateOf(false) }
    var showEditBpmDialog by remember { mutableStateOf(false) }
    var showEditKeyDialog by remember { mutableStateOf(false) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showRemoveFromLibraryDialog by remember { mutableStateOf(false) }

    // Load reactive stats & track updates
    LaunchedEffect(initialTrack.id) {
        withContext(Dispatchers.IO) {
            val dbTrack = trackDao.getTrackById(initialTrack.id)?.toTrack()
            if (dbTrack != null) {
                withContext(Dispatchers.Main) { currentTrack = dbTrack }
            }
            val stats = playbackDao.getTrackStats(initialTrack.id)
            val playlists = playlistDao.getPlaylistsContainingTrack(initialTrack.id)
            withContext(Dispatchers.Main) {
                playbackStats = stats
                playlistsContainingTrack = playlists
            }
        }
    }

    // Load or extract waveform if not cached
    LaunchedEffect(currentTrack.id) {
        if (waveformData == null) {
            isWaveformLoading = true
            withContext(Dispatchers.IO) {
                val wf = WaveformAnalyzer.analyze(context, currentTrack)
                withContext(Dispatchers.Main) {
                    waveformData = wf
                    isWaveformLoading = false
                }
            }
        }
    }

    // Load Spectrogram
    LaunchedEffect(currentTrack.id) {
        isSpectrogramLoading = true
        withContext(Dispatchers.IO) {
            try {
                val spec = SpectrogramEngine.analyzeTrack(context, currentTrack)
                withContext(Dispatchers.Main) {
                    spectrogramData = spec
                    isSpectrogramLoading = false
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { isSpectrogramLoading = false }
            }
        }
    }

    // Load Audio Metrics
    LaunchedEffect(currentTrack.id) {
        if (audioMetrics == null) {
            isMetricsLoading = true
            withContext(Dispatchers.IO) {
                val metrics = TrackAudioMetricsService.analyzeTrack(context, currentTrack)
                withContext(Dispatchers.Main) {
                    audioMetrics = metrics
                    isMetricsLoading = false
                }
            }
        }
    }

    val isPlayingThisTrack = audioEngine.isPlaying.collectAsState().value &&
            audioEngine.currentTrack.collectAsState().value?.id == currentTrack.id
    val currentPositionMs = audioEngine.currentPositionMs.collectAsState().value

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
    ) {
        // Top Navigation Bar
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
                        Text("Track Inspector", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
                        Text(currentTrack.title, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            if (isPlayingThisTrack) {
                                audioEngine.pause()
                            } else {
                                audioEngine.loadTrack(currentTrack, autoPlay = true)
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlayingThisTrack) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = if (isPlayingThisTrack) NeonAmber else DeckACyan
                        )
                    }
                    IconButton(onClick = { showEditMetadataDialog = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Metadata", tint = TextSecondary)
                    }
                }
            }
        }

        // Main Scrollable Inspector Body
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 1. HEADER ──────────────────────────────────────────────────────────
            InspectorHeaderCard(
                track = currentTrack,
                onRatingChanged = { newRating ->
                    val updated = currentTrack.copy(rating = newRating)
                    currentTrack = updated
                    coroutineScope.launch(Dispatchers.IO) {
                        trackDao.updateTrack(TrackEntity.fromTrack(updated))
                    }
                }
            )

            // ── 2. DJ INFORMATION ──────────────────────────────────────────────────
            InspectorDjInfoCard(
                track = currentTrack,
                onEnergyChanged = { newEnergy ->
                    val updated = currentTrack.copy(energyRating = newEnergy)
                    currentTrack = updated
                    coroutineScope.launch(Dispatchers.IO) {
                        trackDao.updateTrack(TrackEntity.fromTrack(updated))
                    }
                },
                onAddTag = { showAddTagDialog = true },
                onRemoveTag = { tagToRemove ->
                    val updatedTags = currentTrack.tagsList.filter { it != tagToRemove }.joinToString(",")
                    val updated = currentTrack.copy(customTags = updatedTags)
                    currentTrack = updated
                    coroutineScope.launch(Dispatchers.IO) {
                        trackDao.updateTrack(TrackEntity.fromTrack(updated))
                    }
                }
            )

            // ── 3. BPM DETAILS ─────────────────────────────────────────────────────
            InspectorBpmDetailsCard(
                track = currentTrack,
                onHalfBpm = {
                    val half = currentTrack.bpm / 2.0
                    val updated = currentTrack.copy(bpm = half, isManualBpm = true)
                    currentTrack = updated
                    coroutineScope.launch(Dispatchers.IO) {
                        trackDao.updateTrack(TrackEntity.fromTrack(updated))
                    }
                    Toast.makeText(context, "BPM halved: ${String.format(Locale.US, "%.1f", half)}", Toast.LENGTH_SHORT).show()
                },
                onDoubleBpm = {
                    val dbl = currentTrack.bpm * 2.0
                    val updated = currentTrack.copy(bpm = dbl, isManualBpm = true)
                    currentTrack = updated
                    coroutineScope.launch(Dispatchers.IO) {
                        trackDao.updateTrack(TrackEntity.fromTrack(updated))
                    }
                    Toast.makeText(context, "BPM doubled: ${String.format(Locale.US, "%.1f", dbl)}", Toast.LENGTH_SHORT).show()
                },
                onEditBpm = { showEditBpmDialog = true },
                onReanalyseBpm = {
                    Toast.makeText(context, "Reanalysing BPM...", Toast.LENGTH_SHORT).show()
                    coroutineScope.launch(Dispatchers.IO) {
                        val wf = WaveformAnalyzer.analyze(context, currentTrack)
                        if (wf.bpm in 40.0..260.0) {
                            val updated = currentTrack.copy(bpm = wf.bpm, bpmLastAnalyzed = System.currentTimeMillis())
                            withContext(Dispatchers.Main) {
                                currentTrack = updated
                                waveformData = wf
                            }
                            trackDao.updateTrack(TrackEntity.fromTrack(updated))
                        }
                    }
                }
            )

            // ── 4. KEY DETAILS ─────────────────────────────────────────────────────
            InspectorKeyDetailsCard(
                track = currentTrack,
                onEditKey = { showEditKeyDialog = true },
                onReanalyseKey = {
                    Toast.makeText(context, "Reanalysing Key...", Toast.LENGTH_SHORT).show()
                    viewModel.autoTagSingleTrack(currentTrack)
                }
            )

            // ── 5. WAVEFORM ────────────────────────────────────────────────────────
            InspectorWaveformSection(
                track = currentTrack,
                waveformData = waveformData,
                isLoading = isWaveformLoading,
                isPlaying = isPlayingThisTrack,
                currentPositionMs = if (isPlayingThisTrack) currentPositionMs else 0L,
                waveformStyle = viewModel.waveformStyle.collectAsState().value,
                onToggleWaveformStyle = { viewModel.toggleWaveformStyle() },
                onSeekToMs = { targetMs ->
                    if (isPlayingThisTrack) {
                        audioEngine.seekToMs(targetMs)
                    } else {
                        audioEngine.loadTrack(currentTrack, autoPlay = true)
                        audioEngine.seekToMs(targetMs)
                    }
                },
                onReanalyse = {
                    isWaveformLoading = true
                    coroutineScope.launch(Dispatchers.IO) {
                        val wf = WaveformAnalyzer.analyze(context, currentTrack)
                        withContext(Dispatchers.Main) {
                            waveformData = wf
                            isWaveformLoading = false
                        }
                    }
                }
            )

            // ── 6. SPECTROGRAM ─────────────────────────────────────────────────────
            InspectorSpectrogramSection(
                track = currentTrack,
                spectrogramData = spectrogramData,
                isLoading = isSpectrogramLoading,
                onReanalyse = {
                    isSpectrogramLoading = true
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val spec = SpectrogramEngine.analyzeTrack(context, currentTrack)
                            withContext(Dispatchers.Main) {
                                spectrogramData = spec
                                isSpectrogramLoading = false
                            }
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) { isSpectrogramLoading = false }
                        }
                    }
                }
            )

            // ── 7. AUDIO ANALYSIS & QUALITY ────────────────────────────────────────
            InspectorAudioAnalysisCard(
                track = currentTrack,
                spectrogram = spectrogramData,
                metrics = audioMetrics,
                isLoading = isMetricsLoading
            )

            // ── 8. PLAYBACK STATISTICS ─────────────────────────────────────────────
            InspectorPlaybackStatisticsCard(
                track = currentTrack,
                stats = playbackStats
            )

            // ── 9. PLAYLIST MEMBERSHIP ─────────────────────────────────────────────
            InspectorPlaylistsCard(
                playlists = playlistsContainingTrack,
                onAddToPlaylist = {
                    viewModel.openAddToPlaylist(currentTrack)
                },
                onRemoveFromPlaylist = { playlist ->
                    coroutineScope.launch(Dispatchers.IO) {
                        playlistDao.deleteTrackFromPlaylist(playlist.id, currentTrack.id)
                        val refreshed = playlistDao.getPlaylistsContainingTrack(currentTrack.id)
                        withContext(Dispatchers.Main) {
                            playlistsContainingTrack = refreshed
                        }
                    }
                }
            )

            // ── 10. TRACK NOTES ────────────────────────────────────────────────────
            InspectorNotesCard(
                notes = currentTrack.notes,
                onSaveNotes = { newNotes ->
                    val updated = currentTrack.copy(notes = newNotes)
                    currentTrack = updated
                    coroutineScope.launch(Dispatchers.IO) {
                        trackDao.updateTrack(TrackEntity.fromTrack(updated))
                    }
                    Toast.makeText(context, "Notes saved", Toast.LENGTH_SHORT).show()
                }
            )

            // ── 11. FILE INFORMATION ───────────────────────────────────────────────
            InspectorFileInfoCard(
                track = currentTrack,
                onOpenFileLocation = {
                    val file = File(currentTrack.filePath)
                    if (file.exists()) {
                        val parent = file.parentFile
                        Toast.makeText(context, "Location: ${parent?.absolutePath ?: file.absolutePath}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "File path: ${currentTrack.filePath}", Toast.LENGTH_LONG).show()
                    }
                }
            )

            // ── 12. ACTIONS ────────────────────────────────────────────────────────
            InspectorActionsCard(
                track = currentTrack,
                onPlay = { audioEngine.loadTrack(currentTrack, autoPlay = true) },
                onPlayNext = { viewModel.queueTrack(currentTrack, playNext = true) },
                onAddToQueue = { viewModel.queueTrack(currentTrack, playNext = false) },
                onAddToPlaylist = { viewModel.openAddToPlaylist(currentTrack) },
                onFetchMetadata = { viewModel.autoTagSingleTrack(currentTrack) },
                onShare = {
                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "audio/*"
                            putExtra(Intent.EXTRA_STREAM, Uri.parse(currentTrack.filePath))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Track"))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Cannot share: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                onRemoveFromLibrary = { showRemoveFromLibraryDialog = true },
                onDeleteFile = { showDeleteConfirmDialog = true }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // ── Dialogs ─────────────────────────────────────────────────────────────

    if (showEditMetadataDialog) {
        EditMetadataDialog(
            track = currentTrack,
            onDismiss = { showEditMetadataDialog = false },
            onSave = { updated ->
                currentTrack = updated
                showEditMetadataDialog = false
                coroutineScope.launch(Dispatchers.IO) {
                    trackDao.updateTrack(TrackEntity.fromTrack(updated))
                }
                if (audioEngine.currentTrack.value?.id == updated.id) {
                    audioEngine.loadTrack(updated, autoPlay = audioEngine.isPlaying.value)
                }
                Toast.makeText(context, "Metadata saved", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showEditBpmDialog) {
        EditBpmDialog(
            initialBpm = currentTrack.bpm,
            onDismiss = { showEditBpmDialog = false },
            onConfirm = { newBpm ->
                val updated = currentTrack.copy(bpm = newBpm, isManualBpm = true)
                currentTrack = updated
                showEditBpmDialog = false
                coroutineScope.launch(Dispatchers.IO) {
                    trackDao.updateTrack(TrackEntity.fromTrack(updated))
                }
                Toast.makeText(context, "BPM set to ${String.format(Locale.US, "%.2f", newBpm)}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showEditKeyDialog) {
        EditKeyDialog(
            currentKey = currentTrack.musicalKey,
            onDismiss = { showEditKeyDialog = false },
            onConfirm = { newKey, newCamelot ->
                val updated = currentTrack.copy(musicalKey = newKey, camelotKey = newCamelot, isManualKey = true)
                currentTrack = updated
                showEditKeyDialog = false
                coroutineScope.launch(Dispatchers.IO) {
                    trackDao.updateTrack(TrackEntity.fromTrack(updated))
                }
                Toast.makeText(context, "Key set to $newKey ($newCamelot)", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showAddTagDialog) {
        AddCustomTagDialog(
            existingTags = currentTrack.tagsList,
            onDismiss = { showAddTagDialog = false },
            onAddTag = { tag ->
                val newTags = (currentTrack.tagsList + tag).distinct().joinToString(",")
                val updated = currentTrack.copy(customTags = newTags)
                currentTrack = updated
                showAddTagDialog = false
                coroutineScope.launch(Dispatchers.IO) {
                    trackDao.updateTrack(TrackEntity.fromTrack(updated))
                }
            }
        )
    }

    if (showRemoveFromLibraryDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveFromLibraryDialog = false },
            containerColor = DjSurfaceDark,
            title = { Text("Remove from SoundSync?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("This removes '${currentTrack.title}' from your library database, but keeps the audio file safe on storage.", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showRemoveFromLibraryDialog = false
                        viewModel.deleteTrack(currentTrack)
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonRed)
                ) {
                    Text("Remove", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveFromLibraryDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = DjSurfaceDark,
            title = { Text("Delete Physical Audio File?", color = NeonRed, fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently delete the audio file '${currentTrack.title}' from device storage. This action CANNOT be undone!", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteTrack(currentTrack)
                        try {
                            val f = File(currentTrack.filePath)
                            if (f.exists()) f.delete()
                        } catch (_: Exception) {}
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonRed)
                ) {
                    Text("Delete Permanently", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

// ── Header Card ─────────────────────────────────────────────────────────────
@Composable
private fun InspectorHeaderCard(
    track: Track,
    onRatingChanged: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DjSurfaceCard)
                    .border(1.dp, DjSurfaceBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (track.artworkUrl != null) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = "Cover Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(36.dp))
                }
            }

            // Info & Star Rating
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = track.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist.ifBlank { "Unknown Artist" },
                    fontSize = 13.sp,
                    color = DeckACyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.album.ifBlank { "Unknown Album" },
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Key / BPM / Duration summary badge row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Surface(
                        color = DjSurfaceCard,
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                    ) {
                        Text(
                            text = if (track.hasValidBpm) "${String.format(Locale.US, "%.1f", track.bpm)} BPM" else "No BPM",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonAmber,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    if (track.hasValidKey) {
                        Surface(
                            color = DjSurfaceCard,
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                        ) {
                            Text(
                                text = track.camelotKey.ifBlank { track.musicalKey },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeckBPink,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = formatDuration(track.durationSeconds),
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }

                // Interactive 5-star rating
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    for (star in 1..5) {
                        val isFilled = star <= track.rating
                        Icon(
                            imageVector = if (isFilled) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Rating $star",
                            tint = if (isFilled) NeonAmber else TextMuted,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    val newRating = if (track.rating == star) 0 else star
                                    onRatingChanged(newRating)
                                }
                        )
                    }
                }
            }
        }
    }
}

// ── DJ Information Card ─────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InspectorDjInfoCard(
    track: Track,
    onEnergyChanged: (Int) -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (String) -> Unit
) {
    SectionCard(title = "DJ INFORMATION", icon = Icons.Default.Tune) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // BPM & Key Row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoMetricItem(
                    label = "BPM",
                    value = if (track.hasValidBpm) String.format(Locale.US, "%.2f", track.bpm) else "Not analysed",
                    color = NeonAmber
                )
                InfoMetricItem(
                    label = "MUSICAL KEY",
                    value = track.musicalKey.ifBlank { "Not analysed" },
                    color = DeckACyan
                )
                InfoMetricItem(
                    label = "CAMELOT",
                    value = track.camelotKey.ifBlank { "—" },
                    color = DeckBPink
                )
            }

            HorizontalDivider(color = DjSurfaceBorder, thickness = 0.5.dp)

            // Energy Rating Stepper (1 to 10)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("ENERGY RATING", fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.SemiBold)
                    Text("${track.energyRating} / 10", fontSize = 14.sp, color = NeonGreen, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { if (track.energyRating > 1) onEnergyChanged(track.energyRating - 1) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease Energy", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                    Slider(
                        value = track.energyRating.toFloat(),
                        onValueChange = { onEnergyChanged(it.toInt()) },
                        valueRange = 1f..10f,
                        steps = 8,
                        modifier = Modifier.width(130.dp),
                        colors = SliderDefaults.colors(thumbColor = NeonGreen, activeTrackColor = NeonGreen, inactiveTrackColor = DjSurfaceCard)
                    )
                    IconButton(
                        onClick = { if (track.energyRating < 10) onEnergyChanged(track.energyRating + 1) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase Energy", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }

            HorizontalDivider(color = DjSurfaceBorder, thickness = 0.5.dp)

            // Cue points row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("HOT CUES", fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cues = track.hotCues.ifEmpty { listOf(0, 32, 64, 128) }
                    cues.forEachIndexed { idx, cue ->
                        Surface(
                            color = when (idx % 4) {
                                0 -> DeckACyan.copy(alpha = 0.2f)
                                1 -> NeonAmber.copy(alpha = 0.2f)
                                2 -> DeckBPink.copy(alpha = 0.2f)
                                else -> NeonGreen.copy(alpha = 0.2f)
                            },
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                        ) {
                            Text(
                                text = "CUE ${idx + 1}: ${cue}s",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = DjSurfaceBorder, thickness = 0.5.dp)

            // Custom DJ Tags
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("CUSTOM DJ TAGS", fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onAddTag, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Tag", fontSize = 11.sp, color = DeckACyan)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (track.tagsList.isEmpty()) {
                        Text("No custom tags added yet", fontSize = 12.sp, color = TextMuted)
                    } else {
                        track.tagsList.forEach { tag ->
                            Surface(
                                color = DjSurfaceCard,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(tag, fontSize = 11.sp, color = TextPrimary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove tag",
                                        tint = TextSecondary,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { onRemoveTag(tag) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── BPM Details Card ────────────────────────────────────────────────────────
@Composable
private fun InspectorBpmDetailsCard(
    track: Track,
    onHalfBpm: () -> Unit,
    onDoubleBpm: () -> Unit,
    onEditBpm: () -> Unit,
    onReanalyseBpm: () -> Unit
) {
    SectionCard(title = "BPM DETAILS", icon = Icons.Default.Speed) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoMetricItem(
                    label = "CURRENT BPM",
                    value = if (track.hasValidBpm) String.format(Locale.US, "%.2f", track.bpm) else "Not analysed",
                    color = NeonAmber
                )
                InfoMetricItem(
                    label = "SOURCE",
                    value = if (track.isManualBpm) "Manual Edit" else if (track.bpmLastAnalyzed != null) "SoundSync Analysis" else "Embedded Metadata",
                    color = TextSecondary
                )
                InfoMetricItem(
                    label = "CONFIDENCE",
                    value = if (track.bpmConfidence > 0.0) "${(track.bpmConfidence * 100).toInt()}%" else if (track.isManualBpm) "100%" else "—",
                    color = if (track.bpmConfidence >= 0.8) NeonGreen else TextMuted
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onHalfBpm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                ) {
                    Text("Half (/2)", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onDoubleBpm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                ) {
                    Text("Double (*2)", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onEditBpm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckACyan),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                ) {
                    Text("Edit BPM", fontSize = 12.sp)
                }
                IconButton(onClick = onReanalyseBpm, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reanalyse BPM", tint = DeckACyan, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ── Key Details Card ────────────────────────────────────────────────────────
@Composable
private fun InspectorKeyDetailsCard(
    track: Track,
    onEditKey: () -> Unit,
    onReanalyseKey: () -> Unit
) {
    SectionCard(title = "KEY DETAILS", icon = Icons.Default.Equalizer) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoMetricItem(
                    label = "MUSICAL KEY",
                    value = track.musicalKey.ifBlank { "Not analysed" },
                    color = DeckACyan
                )
                InfoMetricItem(
                    label = "CAMELOT",
                    value = track.camelotKey.ifBlank { "—" },
                    color = DeckBPink
                )
                InfoMetricItem(
                    label = "SOURCE",
                    value = if (track.isManualKey) "Manual Edit" else "SoundSync DSP",
                    color = TextSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onEditKey,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceCard)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Manual Correction", color = TextPrimary, fontSize = 12.sp)
                }

                IconButton(onClick = onReanalyseKey, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reanalyse Key", tint = DeckACyan, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ── Waveform Section ────────────────────────────────────────────────────────
@Composable
private fun InspectorWaveformSection(
    track: Track,
    waveformData: WaveformData?,
    isLoading: Boolean,
    isPlaying: Boolean,
    currentPositionMs: Long,
    waveformStyle: WaveformStyle = WaveformStyle.DETAILED,
    onToggleWaveformStyle: (() -> Unit)? = null,
    onSeekToMs: (Long) -> Unit,
    onReanalyse: () -> Unit
) {
    SectionCard(title = "WAVEFORM", icon = Icons.Default.GraphicEq) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (waveformData != null) "Status: Analyzed (${waveformData.samplePoints} points)" else if (isLoading) "Analyzing waveform..." else "Waveform not cached",
                    fontSize = 11.sp,
                    color = if (waveformData != null) NeonGreen else TextSecondary
                )
                IconButton(onClick = onReanalyse, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reanalyse", tint = DeckACyan, modifier = Modifier.size(16.dp))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DjObsidian)
                    .border(1.dp, DjSurfaceBorder, RoundedCornerShape(8.dp))
            ) {
                RekordboxWaveformView(
                    track = track,
                    waveformData = waveformData,
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    durationMs = (track.durationSeconds * 1000L).coerceAtLeast(1000L),
                    onSeekToMs = onSeekToMs,
                    isLoading = isLoading,
                    waveformStyle = waveformStyle,
                    onToggleWaveformStyle = onToggleWaveformStyle,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// ── Spectrogram Section ─────────────────────────────────────────────────────
@Composable
private fun InspectorSpectrogramSection(
    track: Track,
    spectrogramData: SpectrogramAnalysis?,
    isLoading: Boolean,
    onReanalyse: () -> Unit
) {
    SectionCard(title = "SPECTROGRAM", icon = Icons.Default.Science) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (spectrogramData != null) "Cutoff: ${String.format(Locale.US, "%.1f", spectrogramData.cutoffKhz)} kHz" else if (isLoading) "Generating STFT Spectrogram..." else "Not analysed",
                    fontSize = 11.sp,
                    color = if (spectrogramData != null) DeckACyan else TextSecondary
                )
                IconButton(onClick = onReanalyse, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reanalyse", tint = DeckACyan, modifier = Modifier.size(16.dp))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DjObsidian)
                    .border(1.dp, DjSurfaceBorder, RoundedCornerShape(8.dp))
            ) {
                SpectrogramAnalyzerView(
                    analyzedTrack = track,
                    spectrogramData = spectrogramData,
                    allTracks = listOf(track),
                    isLoading = isLoading,
                    onSelectTrack = {}
                )
            }
        }
    }
}

// ── Audio Analysis Card ─────────────────────────────────────────────────────
@Composable
private fun InspectorAudioAnalysisCard(
    track: Track,
    spectrogram: SpectrogramAnalysis?,
    metrics: TrackAudioMetrics?,
    isLoading: Boolean
) {
    SectionCard(title = "AUDIO ANALYSIS", icon = Icons.Default.Equalizer) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Quality Rating Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("QUALITY RATING", fontSize = 10.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                    Text(
                        text = track.qualityRating.label,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (track.isLossless) NeonGreen else if (track.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED) NeonRed else DeckACyan
                    )
                }
                Surface(
                    color = if (track.isLossless) NeonGreen.copy(alpha = 0.15f) else DjSurfaceCard,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Text(
                        text = if (track.isLossless) "LOSSLESS" else "LOSSY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (track.isLossless) NeonGreen else TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Quality explanation if suspicious transcode
            if (track.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED || spectrogram?.possibleLossyTranscode == true) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NeonRed.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonRed.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = NeonRed, modifier = Modifier.size(20.dp))
                        Column {
                            Text("Potential Transcode Detected", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = NeonRed)
                            Text(
                                "The file header reports ${track.bitrateKbps} kbps, but spectral analysis detected a steep brickwall cutoff at ~${String.format(Locale.US, "%.1f", spectrogram?.cutoffKhz ?: 15.5f)} kHz, which resembles a low-quality source.",
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = DjSurfaceBorder, thickness = 0.5.dp)

            // Technical details grid
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoMetricItem("CODEC / FORMAT", track.format, TextPrimary)
                InfoMetricItem("BITRATE", "${track.bitrateKbps} kbps", TextPrimary)
                InfoMetricItem("SAMPLE RATE", "${spectrogram?.sampleRate ?: 44100} Hz", TextPrimary)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoMetricItem("CHANNELS", "Stereo", TextPrimary)
                InfoMetricItem("FILE SIZE", "${track.fileSizeMb} MB", TextPrimary)
                InfoMetricItem("BIT DEPTH", "${spectrogram?.bitDepth ?: 16}-bit", TextPrimary)
            }

            HorizontalDivider(color = DjSurfaceBorder, thickness = 0.5.dp)

            // DSP Metrics (True Peak, RMS, Dynamic Range)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoMetricItem(
                    label = "PEAK LEVEL",
                    value = metrics?.let { String.format(Locale.US, "%.1f dBFS", it.peakDb) } ?: "Not analysed",
                    color = if (metrics?.isClipping == true) NeonRed else TextPrimary
                )
                InfoMetricItem(
                    label = "RMS LOUDNESS",
                    value = metrics?.let { String.format(Locale.US, "%.1f dBFS", it.rmsDb) } ?: "Not analysed",
                    color = TextPrimary
                )
                InfoMetricItem(
                    label = "DYNAMIC RANGE",
                    value = metrics?.let { "DR${it.dynamicRangeScore}" } ?: "Not analysed",
                    color = NeonAmber
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoMetricItem(
                    label = "SPECTRAL CUTOFF",
                    value = spectrogram?.let { "${String.format(Locale.US, "%.1f", it.cutoffKhz)} kHz" } ?: "Not analysed",
                    color = DeckACyan
                )
                InfoMetricItem(
                    label = "CLIPPING",
                    value = if (metrics?.isClipping == true) "YES (${metrics.clippedSampleCount} samples)" else "Clean (No clipping)",
                    color = if (metrics?.isClipping == true) NeonRed else NeonGreen
                )
            }
        }
    }
}

// ── Playback Statistics Card ────────────────────────────────────────────────
@Composable
private fun InspectorPlaybackStatisticsCard(
    track: Track,
    stats: TrackPlaybackStats?
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()) }

    SectionCard(title = "PLAYBACK STATISTICS", icon = Icons.Default.Timer) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoMetricItem(
                    label = "PLAY COUNT",
                    value = "${stats?.playCount ?: 0}",
                    color = NeonAmber
                )
                InfoMetricItem(
                    label = "COMPLETIONS",
                    value = "${stats?.completedCount ?: 0}",
                    color = NeonGreen
                )
                InfoMetricItem(
                    label = "SKIPS",
                    value = "${stats?.skippedCount ?: 0}",
                    color = if ((stats?.skippedCount ?: 0) > 0) NeonRed else TextSecondary
                )
                InfoMetricItem(
                    label = "COMPLETION RATE",
                    value = stats?.let { "${it.completionRate.toInt()}%" } ?: "0%",
                    color = DeckACyan
                )
            }

            HorizontalDivider(color = DjSurfaceBorder, thickness = 0.5.dp)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoMetricItem(
                    label = "TOTAL TIME LISTENED",
                    value = formatListeningTime(stats?.totalListeningMs ?: 0L),
                    color = TextPrimary
                )
                InfoMetricItem(
                    label = "LAST PLAYED",
                    value = stats?.lastPlayed?.let { dateFormat.format(Date(it)) } ?: "Never",
                    color = TextSecondary
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoMetricItem(
                    label = "FIRST PLAYED",
                    value = stats?.firstPlayed?.let { dateFormat.format(Date(it)) } ?: "Never",
                    color = TextSecondary
                )
                InfoMetricItem(
                    label = "DATE ADDED",
                    value = dateFormat.format(Date(track.dateAdded)),
                    color = TextSecondary
                )
            }
        }
    }
}

// ── Playlists Card ──────────────────────────────────────────────────────────
@Composable
private fun InspectorPlaylistsCard(
    playlists: List<PlaylistEntity>,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (PlaylistEntity) -> Unit
) {
    SectionCard(title = "PLAYLISTS", icon = Icons.Default.QueueMusic) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playlists.isEmpty()) {
                Text("This track is not included in any playlists.", fontSize = 12.sp, color = TextMuted)
            } else {
                playlists.forEach { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(DjSurfaceCard)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.QueueMusic, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(16.dp))
                            Text(playlist.name, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(onClick = { onRemoveFromPlaylist(playlist) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove from playlist", tint = TextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onAddToPlaylist,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckACyan),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add to Playlist", fontSize = 12.sp)
            }
        }
    }
}

// ── Notes Card ──────────────────────────────────────────────────────────────
@Composable
private fun InspectorNotesCard(
    notes: String,
    onSaveNotes: (String) -> Unit
) {
    var text by remember(notes) { mutableStateOf(notes) }

    SectionCard(title = "TRACK NOTES", icon = Icons.Default.Notes) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                placeholder = { Text("Add performance notes, mix-in points, or cue reminders...", fontSize = 12.sp, color = TextMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DeckACyan,
                    unfocusedBorderColor = DjSurfaceBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = DjObsidian,
                    unfocusedContainerColor = DjObsidian
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
            )

            if (text != notes) {
                Button(
                    onClick = { onSaveNotes(text) },
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Save Notes", color = DjObsidian, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── File Information Card ───────────────────────────────────────────────────
@Composable
private fun InspectorFileInfoCard(
    track: Track,
    onOpenFileLocation: () -> Unit
) {
    SectionCard(title = "FILE INFORMATION", icon = Icons.Default.Folder) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val file = File(track.filePath)
            InfoMetricItem("FILENAME", file.name.ifBlank { track.filePath.substringAfterLast("/") }, TextPrimary)
            InfoMetricItem("LOCATION", track.filePath, TextSecondary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoMetricItem("FORMAT", track.format, TextPrimary)
                InfoMetricItem("SIZE", "${track.fileSizeMb} MB", TextPrimary)
                val storageLabel = if (track.filePath.contains("/storage/emulated/0")) "Internal Storage" else "External Storage"
                InfoMetricItem("STORAGE SOURCE", storageLabel, TextPrimary)
            }

            OutlinedButton(
                onClick = onOpenFileLocation,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Show File Location", fontSize = 12.sp)
            }
        }
    }
}

// ── Actions Card ────────────────────────────────────────────────────────────
@Composable
private fun InspectorActionsCard(
    track: Track,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onFetchMetadata: () -> Unit,
    onShare: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onDeleteFile: () -> Unit
) {
    SectionCard(title = "ACTIONS", icon = Icons.Default.PlayCircle) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DjObsidian, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play Now", color = DjObsidian, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onPlayNext,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                ) {
                    Text("Play Next", fontSize = 12.sp)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onAddToQueue,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                ) {
                    Text("Add to Queue", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onAddToPlaylist,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                ) {
                    Text("Add to Playlist", fontSize = 12.sp)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onFetchMetadata,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckBPink),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = DeckBPink, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Fetch MusicBrainz", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share", fontSize = 12.sp)
                }
            }

            HorizontalDivider(color = DjSurfaceBorder, thickness = 0.5.dp)

            // Destructive actions
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRemoveFromLibrary,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonAmber),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, NeonAmber.copy(alpha = 0.5f))
                ) {
                    Text("Remove from SoundSync", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onDeleteFile,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonRed),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, NeonRed.copy(alpha = 0.5f))
                ) {
                    Text("Delete File", fontSize = 11.sp)
                }
            }
        }
    }
}

// ── Helper Subcomponents ────────────────────────────────────────────────────

@Composable
private fun SectionCard(
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
private fun InfoMetricItem(
    label: String,
    value: String,
    color: Color = TextPrimary
) {
    Column {
        Text(label, fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 13.sp, color = color, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Modals / Dialogs ────────────────────────────────────────────────────────

@Composable
private fun EditBpmDialog(
    initialBpm: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var bpmText by remember { mutableStateOf(String.format(Locale.US, "%.2f", initialBpm)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DjSurfaceDark,
        title = { Text("Manual BPM Correction", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the accurate BPM. This will be marked as manually edited to prevent automatic overwrites.", fontSize = 12.sp, color = TextSecondary)
                OutlinedTextField(
                    value = bpmText,
                    onValueChange = { bpmText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DeckACyan,
                        unfocusedBorderColor = DjSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = bpmText.toDoubleOrNull()
                    if (parsed != null && parsed in 20.0..350.0) {
                        onConfirm(parsed)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
            ) {
                Text("Save BPM", color = DjObsidian)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
private fun EditKeyDialog(
    currentKey: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    val roots = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    var selectedRoot by remember { mutableStateOf(if (currentKey.length >= 2 && currentKey[1] == '#') currentKey.take(2) else currentKey.take(1).ifBlank { "A" }) }
    var isMinor by remember { mutableStateOf(currentKey.contains("m", ignoreCase = true)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DjSurfaceDark,
        title = { Text("Manual Key Correction", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select Root Note and Scale Mode:", fontSize = 12.sp, color = TextSecondary)
                // Root notes
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    roots.take(6).forEach { note ->
                        FilterChip(
                            selected = selectedRoot == note,
                            onClick = { selectedRoot = note },
                            label = { Text(note, fontSize = 11.sp) }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    roots.drop(6).forEach { note ->
                        FilterChip(
                            selected = selectedRoot == note,
                            onClick = { selectedRoot = note },
                            label = { Text(note, fontSize = 11.sp) }
                        )
                    }
                }
                // Mode
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isMinor,
                        onClick = { isMinor = false },
                        label = { Text("Major") }
                    )
                    FilterChip(
                        selected = isMinor,
                        onClick = { isMinor = true },
                        label = { Text("Minor (m)") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val keyName = if (isMinor) "${selectedRoot}m" else selectedRoot
                    val camelot = com.example.metadata.CamelotKey.fromMusicalKey(keyName) ?: "8A"
                    onConfirm(keyName, camelot)
                },
                colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
            ) {
                Text("Save Key", color = DjObsidian)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCustomTagDialog(
    existingTags: List<String>,
    onDismiss: () -> Unit,
    onAddTag: (String) -> Unit
) {
    val presets = listOf("Peak Time", "Warm-up", "Closing", "Vocal", "Instrumental", "Heavy Bass", "Dark", "Happy", "Festival", "Classic", "Weapon", "Afterparty")
    var customText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DjSurfaceDark,
        title = { Text("Add DJ Tag", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select a preset or enter a custom tag:", fontSize = 12.sp, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.filter { it !in existingTags }.take(8).forEach { preset ->
                        AssistChip(
                            onClick = { onAddTag(preset) },
                            label = { Text(preset, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    placeholder = { Text("Custom tag name...", fontSize = 12.sp) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (customText.isNotBlank()) onAddTag(customText.trim())
                },
                colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
            ) {
                Text("Add", color = DjObsidian)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
private fun EditMetadataDialog(
    track: Track,
    onDismiss: () -> Unit,
    onSave: (Track) -> Unit
) {
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.album) }
    var albumArtist by remember { mutableStateOf(track.albumArtist) }
    var genre by remember { mutableStateOf(track.genre) }
    var year by remember { mutableStateOf(track.releaseYear?.toString() ?: "") }
    var composer by remember { mutableStateOf(track.composer) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DjSurfaceDark,
        title = { Text("Edit Metadata", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artist") }, singleLine = true)
                OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text("Album") }, singleLine = true)
                OutlinedTextField(value = albumArtist, onValueChange = { albumArtist = it }, label = { Text("Album Artist") }, singleLine = true)
                OutlinedTextField(value = genre, onValueChange = { genre = it }, label = { Text("Genre") }, singleLine = true)
                OutlinedTextField(value = year, onValueChange = { year = it }, label = { Text("Year") }, singleLine = true)
                OutlinedTextField(value = composer, onValueChange = { composer = it }, label = { Text("Composer") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val yr = year.toIntOrNull()
                    val updated = track.copy(
                        title = title.trim(),
                        artist = artist.trim(),
                        album = album.trim(),
                        albumArtist = albumArtist.trim(),
                        genre = genre.trim(),
                        releaseYear = yr,
                        composer = composer.trim()
                    )
                    onSave(updated)
                },
                colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
            ) {
                Text("Save", color = DjObsidian)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

// ── Formatting Utilities ────────────────────────────────────────────────────

private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

private fun formatListeningTime(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}
