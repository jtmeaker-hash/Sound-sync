package com.example.ui.djprep

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.audio.DjAudioEngine
import com.example.djprep.*
import com.example.model.Track
import com.example.ui.MainDjViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Stage 3: Dedicated DJ Track Preparation Environment Screen.
 *
 * Provides a utilitarian, high-precision track preparation workspace:
 * - Cover art thumbnail, track title, artist, BPM, musical & Camelot key.
 * - Dual waveform system: Mini Overview Strip + Zoomable/scrollable detailed waveform with beat grid lines, cues, and phrase ribbon.
 * - Transport controls (Play/Pause, Cue, Beat Jump, Key Lock / Master Tempo, Metronome, Tempo/Pitch Slider with Reset).
 * - Beat grid manipulation (Shift downbeat, fine nudge, double/halve BPM, reset to analyzed, tap BPM, undo recent changes).
 * - 8 Hot Cue pads (A-H) with color-coding, name editing, jump, and position update to playhead.
 * - Ordered Memory Cues with diamond markers (◆), Previous/Next navigation, editing, and playhead sync.
 * - Structural Phrase Markers (Intro, Verse, Build, Drop, Breakdown, Chorus, Outro, Custom) with editing.
 * - Responsive 2-column layout on wide screens/landscape, touch-friendly on phone.
 * - Prep status workflow (NOT_ANALYSED, ANALYSED, NEEDS_REVIEW, PREPPED).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjPrepScreen(
    track: Track,
    viewModel: MainDjViewModel,
    audioEngine: DjAudioEngine,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val prepManager = remember { DjPrepManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var prepData by remember { mutableStateOf<DjPrepTrackData?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Load or initialize DJ Prep data
    LaunchedEffect(track.id) {
        isLoading = true
        prepData = prepManager.getOrInitPrepData(track)
        isLoading = false
    }

    // Playback state
    val isPlaying by audioEngine.isPlaying.collectAsState()
    val positionMs by audioEngine.currentPositionMs.collectAsState()
    val keyLockEnabled by audioEngine.keyLockEnabled.collectAsState()
    val pitchPercent by audioEngine.pitchPercent.collectAsState()
    val isMetronomeEnabled by prepManager.isMetronomeEnabled.collectAsState()

    // Waveform zoom state
    var zoomLevel by remember { mutableFloatStateOf(1.0f) }

    // Dialog states
    var showKeyDialog by remember { mutableStateOf(false) }
    var showAddPhraseDialog by remember { mutableStateOf(false) }
    var editingPhrase by remember { mutableStateOf<PhraseMarker?>(null) }
    var editingCue by remember { mutableStateOf<CuePoint?>(null) }
    var editingMemoryCue by remember { mutableStateOf<CuePoint?>(null) }
    var showStatusDropdown by remember { mutableStateOf(false) }

    // Cover art model resolution
    val artworkModel = remember(track.artworkCachePath, track.artworkUrl) {
        track.artworkCachePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
            ?: track.artworkUrl
    }

    // Metronome tick tracking
    LaunchedEffect(positionMs, isPlaying, isMetronomeEnabled) {
        if (isPlaying && isMetronomeEnabled && prepData != null) {
            val grid = prepData!!.grid
            val interval = grid.intervalMs
            if (interval > 0.0) {
                val rel = positionMs - (grid.firstDownbeatMs + grid.gridOffsetMs)
                val beatIndex = Math.floor(rel / interval).toLong()
                val isDownbeat = (beatIndex % 4L) == 0L
                val beatPos = (grid.firstDownbeatMs + grid.gridOffsetMs + (beatIndex * interval)).toLong()
                if (Math.abs(positionMs - beatPos) < 25L) {
                    prepManager.playMetronomeTick(isDownbeat)
                }
            }
        }
    }

    val currentData = prepData

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Artwork Thumbnail
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1F232B))
                                .border(0.5.dp, Color(0xFF333842), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (artworkModel != null) {
                                AsyncImage(
                                    model = artworkModel,
                                    contentDescription = "Cover Art",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "DJ PREP",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PioneerAmber,
                                    letterSpacing = 1.5.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                if (currentData != null) {
                                    PrepStatusBadge(
                                        status = currentData.prepStatus,
                                        onClick = { showStatusDropdown = true }
                                    )
                                    DropdownMenu(
                                        expanded = showStatusDropdown,
                                        onDismissRequest = { showStatusDropdown = false }
                                    ) {
                                        PrepStatus.entries.forEach { status ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(8.dp)
                                                                .background(getStatusColor(status), CircleShape)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(status.label)
                                                    }
                                                },
                                                onClick = {
                                                    showStatusDropdown = false
                                                    scope.launch {
                                                        prepData = prepManager.setPrepStatus(track, status)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 16.dp).widthIn(max = 140.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (isLoading || currentData == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = PioneerAmber)
            }
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color(0xFF0E1013))
            ) {
                val isWide = maxWidth >= 720.dp

                if (isWide) {
                    // Landscape / Tablet 2-Column Responsive Layout
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Left Column: Waveform, Transport, Hot Cues
                        Column(
                            modifier = Modifier
                                .weight(1.1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            PrepWaveformSection(
                                track = track,
                                audioEngine = audioEngine,
                                prepData = currentData,
                                currentPositionMs = positionMs,
                                zoomLevel = zoomLevel,
                                onSeek = { targetMs -> audioEngine.seekToMs(targetMs) },
                                onZoomChange = { newZoom -> zoomLevel = newZoom }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            PrepTransportBar(
                                isPlaying = isPlaying,
                                positionMs = positionMs,
                                durationMs = track.durationSeconds * 1000L,
                                keyLockEnabled = keyLockEnabled,
                                pitchPercent = pitchPercent,
                                baseBpm = currentData.bpm,
                                isMetronomeEnabled = isMetronomeEnabled,
                                onPlayPause = {
                                    if (isPlaying) audioEngine.pause() else audioEngine.play()
                                },
                                onCue = {
                                    if (isPlaying) {
                                        audioEngine.pause()
                                        val firstCue = currentData.hotCues.firstOrNull()?.positionMs ?: 0L
                                        audioEngine.seekToMs(firstCue)
                                    } else {
                                        audioEngine.seekToMs(0L)
                                    }
                                },
                                onBeatJump = { beatDelta ->
                                    val bpm = if (currentData.bpm > 0.0) currentData.bpm else 120.0
                                    val msPerBeat = 60_000.0 / bpm
                                    val target = (positionMs + (beatDelta * msPerBeat)).toLong().coerceIn(0L, track.durationSeconds * 1000L)
                                    audioEngine.seekToMs(target)
                                },
                                onPitchChange = { percent -> audioEngine.setPitch(percent) },
                                onToggleKeyLock = { audioEngine.setKeyLock(!keyLockEnabled) },
                                onToggleMetronome = { prepManager.toggleMetronome() }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            HotCuesPanel(
                                prepData = currentData,
                                currentPositionMs = positionMs,
                                onTriggerCue = { cue -> audioEngine.seekToMs(cue.positionMs) },
                                onSetCue = { slot ->
                                    scope.launch {
                                        prepData = prepManager.addOrUpdateHotCue(track, slot, positionMs)
                                    }
                                },
                                onClearCue = { cueId ->
                                    scope.launch {
                                        prepData = prepManager.deleteHotCue(track, cueId)
                                    }
                                },
                                onEditCue = { cue -> editingCue = cue }
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // Right Column: Beat Grid, Memory Cues, Phrase Markers
                        Column(
                            modifier = Modifier
                                .weight(0.9f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            BeatGridControlPanel(
                                prepData = currentData,
                                currentPositionMs = positionMs,
                                canUndo = prepManager.canUndo(track.id),
                                onUndo = {
                                    scope.launch { prepData = prepManager.undo(track) }
                                },
                                onDoubleBpm = {
                                    scope.launch { prepData = prepManager.doubleBpm(track) }
                                },
                                onHalveBpm = {
                                    scope.launch { prepData = prepManager.halveBpm(track) }
                                },
                                onBpmNudge = { delta ->
                                    scope.launch { prepData = prepManager.setBpm(track, currentData.bpm + delta) }
                                },
                                onSetBpm = { newBpm ->
                                    scope.launch { prepData = prepManager.setBpm(track, newBpm) }
                                },
                                onSetFirstDownbeat = {
                                    scope.launch { prepData = prepManager.setFirstDownbeat(track, positionMs) }
                                },
                                onNudgeGrid = { offsetDeltaMs ->
                                    scope.launch { prepData = prepManager.nudgeGrid(track, offsetDeltaMs) }
                                },
                                onResetGrid = {
                                    scope.launch { prepData = prepManager.resetGridToAnalyzed(track, track.bpm, track.musicalKey) }
                                },
                                onOpenKeyDialog = { showKeyDialog = true }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            MemoryCuesPanel(
                                prepData = currentData,
                                currentPositionMs = positionMs,
                                onAddMemoryCue = {
                                    scope.launch {
                                        prepData = prepManager.addMemoryCue(track, positionMs)
                                    }
                                },
                                onJumpPrevious = {
                                    prepManager.getPreviousMemoryCue(currentData, positionMs)?.let {
                                        audioEngine.seekToMs(it.positionMs)
                                    }
                                },
                                onJumpNext = {
                                    prepManager.getNextMemoryCue(currentData, positionMs)?.let {
                                        audioEngine.seekToMs(it.positionMs)
                                    }
                                },
                                onJumpTo = { cue -> audioEngine.seekToMs(cue.positionMs) },
                                onEdit = { cue -> editingMemoryCue = cue },
                                onDelete = { cueId ->
                                    scope.launch {
                                        prepData = prepManager.deleteMemoryCue(track, cueId)
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            PhraseMarkersPanel(
                                prepData = currentData,
                                currentPositionMs = positionMs,
                                onAddPhraseClick = { showAddPhraseDialog = true },
                                onEditPhrase = { phrase -> editingPhrase = phrase },
                                onDeletePhrase = { phraseId ->
                                    scope.launch {
                                        prepData = prepManager.deletePhraseMarker(track, phraseId)
                                    }
                                },
                                onJumpToPhrase = { phrase -> audioEngine.seekToMs(phrase.startMs) }
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                } else {
                    // Portrait Phone Touch-Friendly Layout
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // 1. Dual Waveform (Overview + Zoomable Detailed)
                        PrepWaveformSection(
                            track = track,
                            audioEngine = audioEngine,
                            prepData = currentData,
                            currentPositionMs = positionMs,
                            zoomLevel = zoomLevel,
                            onSeek = { targetMs -> audioEngine.seekToMs(targetMs) },
                            onZoomChange = { newZoom -> zoomLevel = newZoom }
                        )

                        // 2. Transport Bar with Tempo Slider & Key Lock
                        PrepTransportBar(
                            isPlaying = isPlaying,
                            positionMs = positionMs,
                            durationMs = track.durationSeconds * 1000L,
                            keyLockEnabled = keyLockEnabled,
                            pitchPercent = pitchPercent,
                            baseBpm = currentData.bpm,
                            isMetronomeEnabled = isMetronomeEnabled,
                            onPlayPause = {
                                if (isPlaying) audioEngine.pause() else audioEngine.play()
                            },
                            onCue = {
                                if (isPlaying) {
                                    audioEngine.pause()
                                    val firstCue = currentData.hotCues.firstOrNull()?.positionMs ?: 0L
                                    audioEngine.seekToMs(firstCue)
                                } else {
                                    audioEngine.seekToMs(0L)
                                }
                            },
                            onBeatJump = { beatDelta ->
                                val bpm = if (currentData.bpm > 0.0) currentData.bpm else 120.0
                                val msPerBeat = 60_000.0 / bpm
                                val target = (positionMs + (beatDelta * msPerBeat)).toLong().coerceIn(0L, track.durationSeconds * 1000L)
                                audioEngine.seekToMs(target)
                            },
                            onPitchChange = { percent -> audioEngine.setPitch(percent) },
                            onToggleKeyLock = { audioEngine.setKeyLock(!keyLockEnabled) },
                            onToggleMetronome = { prepManager.toggleMetronome() }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 3. Beat Grid & BPM Control Panel with Undo
                        BeatGridControlPanel(
                            prepData = currentData,
                            currentPositionMs = positionMs,
                            canUndo = prepManager.canUndo(track.id),
                            onUndo = {
                                scope.launch { prepData = prepManager.undo(track) }
                            },
                            onDoubleBpm = {
                                scope.launch { prepData = prepManager.doubleBpm(track) }
                            },
                            onHalveBpm = {
                                scope.launch { prepData = prepManager.halveBpm(track) }
                            },
                            onBpmNudge = { delta ->
                                scope.launch { prepData = prepManager.setBpm(track, currentData.bpm + delta) }
                            },
                            onSetBpm = { newBpm ->
                                scope.launch { prepData = prepManager.setBpm(track, newBpm) }
                            },
                            onSetFirstDownbeat = {
                                scope.launch { prepData = prepManager.setFirstDownbeat(track, positionMs) }
                            },
                            onNudgeGrid = { offsetDeltaMs ->
                                scope.launch { prepData = prepManager.nudgeGrid(track, offsetDeltaMs) }
                            },
                            onResetGrid = {
                                scope.launch { prepData = prepManager.resetGridToAnalyzed(track, track.bpm, track.musicalKey) }
                            },
                            onOpenKeyDialog = { showKeyDialog = true }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 4. Hot Cues (CDJ Pads A-H)
                        HotCuesPanel(
                            prepData = currentData,
                            currentPositionMs = positionMs,
                            onTriggerCue = { cue -> audioEngine.seekToMs(cue.positionMs) },
                            onSetCue = { slot ->
                                scope.launch {
                                    prepData = prepManager.addOrUpdateHotCue(track, slot, positionMs)
                                }
                            },
                            onClearCue = { cueId ->
                                scope.launch {
                                    prepData = prepManager.deleteHotCue(track, cueId)
                                }
                            },
                            onEditCue = { cue -> editingCue = cue }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 5. Memory Cues Section
                        MemoryCuesPanel(
                            prepData = currentData,
                            currentPositionMs = positionMs,
                            onAddMemoryCue = {
                                scope.launch {
                                    prepData = prepManager.addMemoryCue(track, positionMs)
                                }
                            },
                            onJumpPrevious = {
                                prepManager.getPreviousMemoryCue(currentData, positionMs)?.let {
                                    audioEngine.seekToMs(it.positionMs)
                                }
                            },
                            onJumpNext = {
                                prepManager.getNextMemoryCue(currentData, positionMs)?.let {
                                    audioEngine.seekToMs(it.positionMs)
                                }
                            },
                            onJumpTo = { cue -> audioEngine.seekToMs(cue.positionMs) },
                            onEdit = { cue -> editingMemoryCue = cue },
                            onDelete = { cueId ->
                                scope.launch {
                                    prepData = prepManager.deleteMemoryCue(track, cueId)
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 6. Phrase Markers Section
                        PhraseMarkersPanel(
                            prepData = currentData,
                            currentPositionMs = positionMs,
                            onAddPhraseClick = { showAddPhraseDialog = true },
                            onEditPhrase = { phrase -> editingPhrase = phrase },
                            onDeletePhrase = { phraseId ->
                                scope.launch {
                                    prepData = prepManager.deletePhraseMarker(track, phraseId)
                                }
                            },
                            onJumpToPhrase = { phrase -> audioEngine.seekToMs(phrase.startMs) }
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // Key selection dialog
    if (showKeyDialog && currentData != null) {
        KeyCorrectionDialog(
            currentKey = currentData.musicalKey,
            onDismiss = { showKeyDialog = false },
            onKeySelected = { newKey ->
                showKeyDialog = false
                scope.launch {
                    prepData = prepManager.setKey(track, newKey)
                }
            }
        )
    }

    // Add Phrase dialog
    if (showAddPhraseDialog && currentData != null) {
        AddPhraseDialog(
            currentPositionMs = positionMs,
            bpm = currentData.bpm,
            onDismiss = { showAddPhraseDialog = false },
            onAdd = { newPhrase ->
                showAddPhraseDialog = false
                scope.launch {
                    prepData = prepManager.addPhraseMarker(track, newPhrase)
                }
            }
        )
    }

    // Edit Phrase dialog
    editingPhrase?.let { phrase ->
        EditPhraseDialog(
            phrase = phrase,
            currentPositionMs = positionMs,
            bpm = currentData?.bpm ?: 120.0,
            onDismiss = { editingPhrase = null },
            onSave = { updatedPhrase ->
                editingPhrase = null
                scope.launch {
                    prepData = prepManager.updatePhraseMarker(track, updatedPhrase)
                }
            }
        )
    }

    // Edit Hot Cue dialog (includes update position to playhead)
    editingCue?.let { cue ->
        EditCueDialog(
            cue = cue,
            currentPositionMs = positionMs,
            onDismiss = { editingCue = null },
            onSave = { updatedLabel, updatedColor, updatedPos ->
                editingCue = null
                scope.launch {
                    prepData = prepManager.addOrUpdateHotCue(
                        track = track,
                        slot = cue.id,
                        positionMs = updatedPos,
                        label = updatedLabel,
                        colorHex = updatedColor
                    )
                }
            }
        )
    }

    // Edit Memory Cue dialog (includes rename & update position to playhead)
    editingMemoryCue?.let { cue ->
        EditMemoryCueDialog(
            cue = cue,
            currentPositionMs = positionMs,
            onDismiss = { editingMemoryCue = null },
            onSave = { updatedLabel, updatedPos ->
                editingMemoryCue = null
                scope.launch {
                    prepData = prepManager.updateMemoryCue(
                        track = track,
                        cueId = cue.id,
                        newLabel = updatedLabel,
                        newPositionMs = updatedPos
                    )
                }
            }
        )
    }
}

// ── Status Badge ─────────────────────────────────────────────────────────────

@Composable
fun PrepStatusBadge(status: PrepStatus, onClick: () -> Unit) {
    val color = getStatusColor(status)
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = status.label,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

fun getStatusColor(status: PrepStatus): Color {
    return when (status) {
        PrepStatus.NOT_ANALYSED -> Color(0xFF9E9E9E)
        PrepStatus.ANALYSED -> Color(0xFF2196F3)
        PrepStatus.NEEDS_REVIEW -> Color(0xFFFFB300)
        PrepStatus.PREPPED -> Color(0xFF00E676)
    }
}

// ── Waveform & Beat Grid Canvas ───────────────────────────────────────────────

@Composable
fun PrepWaveformSection(
    track: Track,
    audioEngine: DjAudioEngine,
    prepData: DjPrepTrackData,
    currentPositionMs: Long,
    zoomLevel: Float,
    onSeek: (Long) -> Unit,
    onZoomChange: (Float) -> Unit
) {
    val durationMs = (track.durationSeconds * 1000L).coerceAtLeast(1000L)
    val grid = prepData.grid

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF14171C))
            .padding(8.dp)
    ) {
        // Top: Zoom controls & timestamps
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatTime(currentPositionMs)} / ${formatTime(durationMs)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(1.0f, 2.0f, 4.0f, 8.0f, 16.0f).forEach { zoom ->
                    val selected = Math.abs(zoomLevel - zoom) < 0.1f
                    TextButton(
                        onClick = { onZoomChange(zoom) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = "${zoom.toInt()}x",
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) PioneerAmber else Color.Gray
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ── 1. Mini Waveform Overview Strip ──────────────────────────────────
        Text(
            text = "OVERVIEW",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(Color(0xFF0A0B0E), RoundedCornerShape(3.dp))
                .border(0.5.dp, Color(0xFF252932), RoundedCornerShape(3.dp))
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek((ratio * durationMs).toLong())
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Draw phrase markers on overview
                prepData.phraseMarkers.forEach { phrase ->
                    val xStart = (phrase.startMs.toFloat() / durationMs) * w
                    val xEnd = (phrase.endMs.toFloat() / durationMs) * w
                    val phraseColor = Color(android.graphics.Color.parseColor(phrase.colorHex))
                    drawRect(
                        color = phraseColor.copy(alpha = 0.5f),
                        topLeft = Offset(xStart, 0f),
                        size = Size((xEnd - xStart).coerceAtLeast(1f), h)
                    )
                }

                // Draw overview envelope bars
                val barCount = 100
                val barW = w / barCount
                for (i in 0 until barCount) {
                    val amp = 0.3f + ((i % 5) * 0.12f)
                    val barH = h * amp
                    val topY = (h - barH) / 2f
                    drawRect(
                        color = Color(0xFF00B0FF).copy(alpha = 0.4f),
                        topLeft = Offset(i * barW, topY),
                        size = Size((barW - 1f).coerceAtLeast(1f), barH)
                    )
                }

                // Draw Hot Cues on overview
                prepData.hotCues.forEach { cue ->
                    val cueX = (cue.positionMs.toFloat() / durationMs) * w
                    val cueColor = Color(android.graphics.Color.parseColor(cue.colorHex))
                    drawLine(
                        color = cueColor,
                        start = Offset(cueX, 0f),
                        end = Offset(cueX, h),
                        strokeWidth = 1.5f
                    )
                }

                // Draw Memory Cues on overview
                prepData.memoryCues.forEach { mem ->
                    val memX = (mem.positionMs.toFloat() / durationMs) * w
                    drawCircle(
                        color = Color(0xFFFFCC00),
                        radius = 2.5f,
                        center = Offset(memX, h / 2f)
                    )
                }

                // If zoomed in, draw viewport window highlight
                if (zoomLevel > 1.0f) {
                    val windowDurationMs = (durationMs / zoomLevel.toDouble()).toLong().coerceAtLeast(100L)
                    val halfWindowMs = windowDurationMs / 2L
                    val startMs = (currentPositionMs - halfWindowMs).coerceIn(0L, (durationMs - windowDurationMs).coerceAtLeast(0L))
                    val endMs = (startMs + windowDurationMs).coerceAtMost(durationMs)
                    val leftX = (startMs.toFloat() / durationMs) * w
                    val rightX = (endMs.toFloat() / durationMs) * w
                    drawRect(
                        color = PioneerAmber.copy(alpha = 0.25f),
                        topLeft = Offset(leftX, 0f),
                        size = Size(rightX - leftX, h)
                    )
                    drawRect(
                        color = PioneerAmber,
                        topLeft = Offset(leftX, 0f),
                        size = Size(rightX - leftX, h),
                        style = Stroke(width = 1f)
                    )
                }

                // Draw Overview Playhead
                val playheadX = (currentPositionMs.toFloat() / durationMs) * w
                drawLine(
                    color = Color.White,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, h),
                    strokeWidth = 2f
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ── 2. Zoomable Detailed Waveform Region ──────────────────────────────
        Text(
            text = "DETAILED WAVEFORM (${zoomLevel.toInt()}x)",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .background(Color(0xFF0A0B0E), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF252932), RoundedCornerShape(4.dp))
                .pointerInput(durationMs, zoomLevel, currentPositionMs) {
                    detectTapGestures { offset ->
                        val w = size.width
                        val windowDurationMs = (durationMs / zoomLevel.toDouble()).toLong().coerceAtLeast(100L)
                        val startMs = if (zoomLevel > 1.0f) {
                            val half = windowDurationMs / 2L
                            (currentPositionMs - half).coerceIn(0L, (durationMs - windowDurationMs).coerceAtLeast(0L))
                        } else 0L
                        val ratio = (offset.x / w).coerceIn(0f, 1f)
                        val targetMs = (startMs + (ratio * windowDurationMs.toFloat()).toLong()).coerceIn(0L, durationMs)
                        onSeek(targetMs)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                val windowDurationMs = (durationMs / zoomLevel.toDouble()).toLong().coerceAtLeast(100L)
                val startMs = if (zoomLevel > 1.0f) {
                    val half = windowDurationMs / 2L
                    (currentPositionMs - half).coerceIn(0L, (durationMs - windowDurationMs).coerceAtLeast(0L))
                } else 0L
                val endMs = (startMs + windowDurationMs).coerceAtMost(durationMs)

                // Background Centerline
                drawLine(
                    color = Color(0xFF252932),
                    start = Offset(0f, h / 2f),
                    end = Offset(w, h / 2f),
                    strokeWidth = 1f
                )

                // 1. Draw Phrase Ribbon at top
                val phraseHeight = 14f
                prepData.phraseMarkers.forEach { phrase ->
                    if (phrase.endMs >= startMs && phrase.startMs <= endMs) {
                        val xStart = ((phrase.startMs - startMs).toFloat() / windowDurationMs.toFloat()) * w
                        val xEnd = ((phrase.endMs - startMs).toFloat() / windowDurationMs.toFloat()) * w
                        val phraseColor = Color(android.graphics.Color.parseColor(phrase.colorHex))
                        drawRect(
                            color = phraseColor.copy(alpha = 0.85f),
                            topLeft = Offset(xStart.coerceAtLeast(0f), 0f),
                            size = Size((xEnd - xStart.coerceAtLeast(0f)).coerceAtLeast(2f), phraseHeight)
                        )
                    }
                }

                // 2. Draw Waveform Bars
                val step = 3f
                var x = 0f
                while (x < w) {
                    val pos = startMs + ((x / w) * windowDurationMs.toFloat()).toLong()
                    val beatIndex = if (grid.intervalMs > 0.0) ((pos / grid.intervalMs).toInt() % 4) else 0
                    val amp = if (beatIndex == 0) 0.85f else 0.45f + ((x.toInt() % 7) * 0.05f)
                    val barH = (h - 24f) * amp
                    val topY = (h / 2f) - (barH / 2f)
                    drawLine(
                        color = Color(0xFF00B0FF).copy(alpha = 0.65f),
                        start = Offset(x, topY),
                        end = Offset(x, topY + barH),
                        strokeWidth = 2f
                    )
                    x += step
                }

                // 3. Draw Beat Grid Lines
                val intervalMs = grid.intervalMs
                if (intervalMs > 0.0 && durationMs > 0L) {
                    val anchor = grid.firstDownbeatMs + grid.gridOffsetMs
                    val firstVisibleBeatIndex = Math.floor((startMs - anchor).toDouble() / intervalMs).toLong()
                    var beatNum = firstVisibleBeatIndex
                    while (true) {
                        val beatPos = (anchor + (beatNum * intervalMs)).toLong()
                        if (beatPos > endMs) break
                        if (beatPos >= startMs) {
                            val beatX = ((beatPos - startMs).toFloat() / windowDurationMs.toFloat()) * w
                            val isDownbeat = (beatNum % 4L) == 0L
                            val gridColor = if (isDownbeat) Color(0xFFFF8A00) else Color(0x44FFFFFF)
                            val strokeW = if (isDownbeat) 1.8f else 0.8f
                            drawLine(
                                color = gridColor,
                                start = Offset(beatX, phraseHeight),
                                end = Offset(beatX, h),
                                strokeWidth = strokeW
                            )
                        }
                        beatNum++
                    }
                }

                // 4. Draw Hot Cue Flags
                prepData.hotCues.forEach { cue ->
                    if (cue.positionMs in startMs..endMs) {
                        val cueX = ((cue.positionMs - startMs).toFloat() / windowDurationMs.toFloat()) * w
                        val cueColor = Color(android.graphics.Color.parseColor(cue.colorHex))
                        drawLine(
                            color = cueColor,
                            start = Offset(cueX, 0f),
                            end = Offset(cueX, h),
                            strokeWidth = 2f
                        )
                        drawCircle(
                            color = cueColor,
                            radius = 6f,
                            center = Offset(cueX, 18f)
                        )
                    }
                }

                // 5. Draw Memory Cues with Distinguishable Diamond Markers (◆)
                prepData.memoryCues.forEach { mem ->
                    if (mem.positionMs in startMs..endMs) {
                        val memX = ((mem.positionMs - startMs).toFloat() / windowDurationMs.toFloat()) * w
                        // Distinct vertical marker line in yellow/amber
                        drawLine(
                            color = Color(0xFFFFCC00).copy(alpha = 0.7f),
                            start = Offset(memX, phraseHeight),
                            end = Offset(memX, h),
                            strokeWidth = 1.2f
                        )
                        // Diamond Marker (◆) at top
                        val dSize = 6f
                        val diamondTop = Path().apply {
                            moveTo(memX, phraseHeight + 2f)
                            lineTo(memX + dSize, phraseHeight + 2f + dSize)
                            lineTo(memX, phraseHeight + 2f + 2 * dSize)
                            lineTo(memX - dSize, phraseHeight + 2f + dSize)
                            close()
                        }
                        drawPath(diamondTop, color = Color(0xFFFFCC00))
                    }
                }

                // 6. Draw Current Playhead
                if (currentPositionMs in startMs..endMs) {
                    val playheadX = ((currentPositionMs - startMs).toFloat() / windowDurationMs.toFloat()) * w
                    drawLine(
                        color = Color.White,
                        start = Offset(playheadX, 0f),
                        end = Offset(playheadX, h),
                        strokeWidth = 2.5f
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 5f,
                        center = Offset(playheadX, h / 2f)
                    )
                }
            }
        }
    }
}

// ── Transport Bar ─────────────────────────────────────────────────────────────

@Composable
fun PrepTransportBar(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    keyLockEnabled: Boolean,
    pitchPercent: Float,
    baseBpm: Double,
    isMetronomeEnabled: Boolean,
    onPlayPause: () -> Unit,
    onCue: () -> Unit,
    onBeatJump: (Int) -> Unit,
    onPitchChange: (Float) -> Unit,
    onToggleKeyLock: () -> Unit,
    onToggleMetronome: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF14171C))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // Row 1: Primary transport & audio buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // CUE button
            Button(
                onClick = onCue,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8A00)),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .width(68.dp)
                    .height(40.dp)
            ) {
                Text("CUE", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Black)
            }

            Spacer(modifier = Modifier.width(6.dp))

            // PLAY / PAUSE button
            Button(
                onClick = onPlayPause,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPlaying) Color(0xFF00E676) else Color(0xFF2979FF)
                ),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.Black
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isPlaying) "PAUSE" else "PLAY",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.Black
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Master Tempo / Key Lock button
            OutlinedButton(
                onClick = onToggleKeyLock,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(
                    1.dp,
                    if (keyLockEnabled) PioneerAmber else Color(0xFF404652)
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (keyLockEnabled) PioneerAmber.copy(alpha = 0.2f) else Color.Transparent
                ),
                modifier = Modifier
                    .width(62.dp)
                    .height(40.dp)
            ) {
                Text(
                    text = "MT",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (keyLockEnabled) PioneerAmber else Color.Gray
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Metronome click button
            OutlinedButton(
                onClick = onToggleMetronome,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(
                    1.dp,
                    if (isMetronomeEnabled) Color(0xFF00E676) else Color(0xFF404652)
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isMetronomeEnabled) Color(0xFF00E676).copy(alpha = 0.2f) else Color.Transparent
                ),
                modifier = Modifier
                    .width(76.dp)
                    .height(40.dp)
            ) {
                Text(
                    text = "CLICK",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = if (isMetronomeEnabled) Color(0xFF00E676) else Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 2: Tempo / Pitch Audition Slider & Reset
        val effectiveBpm = (if (baseBpm > 0.0) baseBpm else 120.0) * (1.0 + pitchPercent / 100.0)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PITCH",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                modifier = Modifier.width(42.dp)
            )

            Slider(
                value = pitchPercent,
                onValueChange = onPitchChange,
                valueRange = -16f..16f,
                colors = SliderDefaults.colors(
                    thumbColor = PioneerAmber,
                    activeTrackColor = PioneerAmber,
                    inactiveTrackColor = Color(0xFF252932)
                ),
                modifier = Modifier.weight(1f).height(32.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = String.format(Locale.US, "%+.1f%%", pitchPercent),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (Math.abs(pitchPercent) > 0.05f) PioneerAmber else Color.White,
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.End
            )

            Spacer(modifier = Modifier.width(4.dp))

            Surface(
                color = Color(0xFF1F232B),
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier.clickable { onPitchChange(0f) }
            ) {
                Text(
                    text = "0%",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (Math.abs(pitchPercent) < 0.05f) PioneerAmber else Color.Gray,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Row 3: Beat Jump
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("JUMP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)

            listOf(-16, -4, -1, 1, 4, 16).forEach { jump ->
                val label = if (jump > 0) "+$jump" else "$jump"
                Surface(
                    color = Color(0xFF1F232B),
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier.clickable { onBeatJump(jump * 4) }
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ── Beat Grid & BPM Control Panel ─────────────────────────────────────────────

@Composable
fun BeatGridControlPanel(
    prepData: DjPrepTrackData,
    currentPositionMs: Long,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onDoubleBpm: () -> Unit,
    onHalveBpm: () -> Unit,
    onBpmNudge: (Double) -> Unit,
    onSetBpm: (Double) -> Unit,
    onSetFirstDownbeat: () -> Unit,
    onNudgeGrid: (Long) -> Unit,
    onResetGrid: () -> Unit,
    onOpenKeyDialog: () -> Unit
) {
    // Local state for Tap BPM
    var tapTimes by remember { mutableStateOf(listOf<Long>()) }

    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14171C)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Row 1: BPM & Musical Key headers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // BPM section with TAP button
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = String.format(Locale.US, "%.1f", prepData.bpm),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("BPM", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    OriginBadge(isManual = prepData.isManualBpm)
                    Spacer(modifier = Modifier.width(8.dp))

                    // TAP BPM Button
                    Surface(
                        color = Color(0xFF2979FF).copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, Color(0xFF2979FF)),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.clickable {
                            val now = SystemClock.elapsedRealtime()
                            val recentTaps = (tapTimes + now).filter { now - it < 3000L }.takeLast(8)
                            tapTimes = recentTaps
                            if (recentTaps.size >= 2) {
                                val intervals = recentTaps.zipWithNext { a, b -> b - a }
                                val avg = intervals.average()
                                if (avg > 0.0) {
                                    val calcBpm = (60_000.0 / avg).coerceIn(40.0, 300.0)
                                    onSetBpm(calcBpm)
                                }
                            }
                        }
                    ) {
                        Text(
                            text = "TAP",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2979FF),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Key section
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onOpenKeyDialog() }
                ) {
                    OriginBadge(isManual = prepData.isManualKey)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (prepData.camelotKey.isNotBlank()) prepData.camelotKey else "—",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = prepData.musicalKey.ifBlank { "Unknown" },
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Key",
                        tint = Color.Gray,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 2: BPM Corrections (÷2, ×2, -0.1, +0.1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PrepActionButton(label = "÷2", onClick = onHalveBpm, modifier = Modifier.weight(1f))
                PrepActionButton(label = "×2", onClick = onDoubleBpm, modifier = Modifier.weight(1f))
                PrepActionButton(label = "-0.1", onClick = { onBpmNudge(-0.1) }, modifier = Modifier.weight(1f))
                PrepActionButton(label = "+0.1", onClick = { onBpmNudge(0.1) }, modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 3: Grid Align, Downbeat, Reset, and UNDO
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSetFirstDownbeat,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F232B)),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Text("SET BEAT 1", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PioneerAmber)
                }

                PrepActionButton(label = "-10ms", onClick = { onNudgeGrid(-10L) }, modifier = Modifier.weight(1f))
                PrepActionButton(label = "-1ms", onClick = { onNudgeGrid(-1L) }, modifier = Modifier.weight(1f))
                PrepActionButton(label = "+1ms", onClick = { onNudgeGrid(1L) }, modifier = Modifier.weight(1f))
                PrepActionButton(label = "+10ms", onClick = { onNudgeGrid(10L) }, modifier = Modifier.weight(1f))

                IconButton(
                    onClick = onResetGrid,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset Grid",
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // UNDO BUTTON
                IconButton(
                    onClick = onUndo,
                    enabled = canUndo,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Undo,
                        contentDescription = "Undo Beat Grid Edit",
                        tint = if (canUndo) PioneerAmber else Color(0xFF404652),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun OriginBadge(isManual: Boolean) {
    Surface(
        color = if (isManual) PioneerAmber.copy(alpha = 0.2f) else Color(0x33404652),
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(
            0.5.dp,
            if (isManual) PioneerAmber else Color(0xFF404652)
        )
    ) {
        Text(
            text = if (isManual) "MANUAL" else "AUTO",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (isManual) PioneerAmber else Color.Gray,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

@Composable
fun PrepActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFF1F232B),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

// ── Hot Cues Panel (Pads A to H) ──────────────────────────────────────────────

@Composable
fun HotCuesPanel(
    prepData: DjPrepTrackData,
    currentPositionMs: Long,
    onTriggerCue: (CuePoint) -> Unit,
    onSetCue: (String) -> Unit,
    onClearCue: (String) -> Unit,
    onEditCue: (CuePoint) -> Unit
) {
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14171C)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "HOT CUES (A — H)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))

            val slotsRow1 = listOf("A", "B", "C", "D")
            val slotsRow2 = listOf("E", "F", "G", "H")

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                slotsRow1.forEach { slot ->
                    val cue = prepData.hotCues.find { it.id == slot }
                    HotCuePad(
                        slot = slot,
                        cue = cue,
                        modifier = Modifier.weight(1f),
                        onTrigger = { if (cue != null) onTriggerCue(cue) else onSetCue(slot) },
                        onClear = { cue?.let { onClearCue(it.id) } },
                        onEdit = { cue?.let { onEditCue(it) } }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                slotsRow2.forEach { slot ->
                    val cue = prepData.hotCues.find { it.id == slot }
                    HotCuePad(
                        slot = slot,
                        cue = cue,
                        modifier = Modifier.weight(1f),
                        onTrigger = { if (cue != null) onTriggerCue(cue) else onSetCue(slot) },
                        onClear = { cue?.let { onClearCue(it.id) } },
                        onEdit = { cue?.let { onEditCue(it) } }
                    )
                }
            }
        }
    }
}

@Composable
fun HotCuePad(
    slot: String,
    cue: CuePoint?,
    modifier: Modifier = Modifier,
    onTrigger: () -> Unit,
    onClear: () -> Unit,
    onEdit: () -> Unit
) {
    val isSet = cue != null
    val padColor = if (isSet) {
        Color(android.graphics.Color.parseColor(cue.colorHex))
    } else {
        Color(0xFF1F232B)
    }

    Surface(
        color = padColor.copy(alpha = if (isSet) 0.85f else 0.4f),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(
            1.dp,
            if (isSet) padColor else Color(0xFF252932)
        ),
        modifier = modifier
            .height(52.dp)
            .clickable { onTrigger() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = slot,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (isSet) Color.Black else Color.Gray
                )
                if (isSet) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.clickable { onEdit() }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Cue",
                                tint = Color.Black.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(modifier = Modifier.clickable { onClear() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear Cue",
                                tint = Color.Black.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }

            Text(
                text = if (isSet) formatTime(cue.positionMs) else "+ Set",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = if (isSet) Color.Black else Color.Gray,
                maxLines = 1
            )
        }
    }
}

// ── Memory Cues Panel ─────────────────────────────────────────────────────────

@Composable
fun MemoryCuesPanel(
    prepData: DjPrepTrackData,
    currentPositionMs: Long,
    onAddMemoryCue: () -> Unit,
    onJumpPrevious: () -> Unit,
    onJumpNext: () -> Unit,
    onJumpTo: (CuePoint) -> Unit,
    onEdit: (CuePoint) -> Unit,
    onDelete: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14171C)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MEMORY CUES (${prepData.memoryCues.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onJumpPrevious,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Prev Memory Cue",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onJumpNext,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Memory Cue",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = onAddMemoryCue,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFCC00)),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("+ MEMORY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }

            if (prepData.memoryCues.isEmpty()) {
                Text(
                    text = "No memory cues set. Tap '+ MEMORY' to save reference markers.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(6.dp))
                prepData.memoryCues.forEach { cue ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .background(Color(0xFF1A1D23), RoundedCornerShape(4.dp))
                            .clickable { onJumpTo(cue) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Distinct diamond symbol for Memory Cues
                            Text(
                                text = "◆",
                                color = Color(0xFFFFCC00),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = cue.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = formatTime(cue.positionMs),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = PioneerAmber
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = { onEdit(cue) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Memory Cue",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            IconButton(
                                onClick = { onDelete(cue.id) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Phrase Markers Panel ──────────────────────────────────────────────────────

@Composable
fun PhraseMarkersPanel(
    prepData: DjPrepTrackData,
    currentPositionMs: Long,
    onAddPhraseClick: () -> Unit,
    onEditPhrase: (PhraseMarker) -> Unit,
    onDeletePhrase: (String) -> Unit,
    onJumpToPhrase: (PhraseMarker) -> Unit
) {
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14171C)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PHRASE MARKERS (${prepData.phraseMarkers.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Button(
                    onClick = onAddPhraseClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF)),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("+ PHRASE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            if (prepData.phraseMarkers.isEmpty()) {
                Text(
                    text = "No phrase markers defined. Add Intro, Verse, Drop, or Breakdown markers.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(6.dp))
                prepData.phraseMarkers.forEach { phrase ->
                    val color = Color(android.graphics.Color.parseColor(phrase.colorHex))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .background(Color(0xFF1A1D23), RoundedCornerShape(4.dp))
                            .clickable { onJumpToPhrase(phrase) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(16.dp)
                                    .background(color, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = phrase.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "(${phrase.barCount} bars)",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${formatTime(phrase.startMs)} — ${formatTime(phrase.endMs)}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.LightGray
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = { onEditPhrase(phrase) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Phrase",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            IconButton(
                                onClick = { onDeletePhrase(phrase.id) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Dialogs: Key, Phrase, Edit Cue, Edit Memory Cue ──────────────────────────

@Composable
fun KeyCorrectionDialog(
    currentKey: String,
    onDismiss: () -> Unit,
    onKeySelected: (String) -> Unit
) {
    val camelotKeys = listOf(
        "1A (Abm)", "1B (B)", "2A (Ebm)", "2B (F#)",
        "3A (Bbm)", "3B (Db)", "4A (Fm)", "4B (Ab)",
        "5A (Cm)", "5B (Eb)", "6A (Gm)", "6B (Bb)",
        "7A (Dm)", "7B (F)", "8A (Am)", "8B (C)",
        "9A (Em)", "9B (G)", "10A (Bm)", "10B (D)",
        "11A (F#m)", "11B (A)", "12A (Dbm)", "12B (E)"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Musical Key", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                camelotKeys.forEach { item ->
                    val cleanKey = item.substringAfter("(").substringBefore(")")
                    val isSelected = currentKey.equals(cleanKey, ignoreCase = true)
                    Text(
                        text = item,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) PioneerAmber else Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onKeySelected(cleanKey) }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        }
    )
}

@Composable
fun AddPhraseDialog(
    currentPositionMs: Long,
    bpm: Double,
    onDismiss: () -> Unit,
    onAdd: (PhraseMarker) -> Unit
) {
    var selectedType by remember { mutableStateOf(PhraseType.INTRO) }
    var barCount by remember { mutableIntStateOf(16) }
    val msPerBar = (60_000.0 / (if (bpm > 0.0) bpm else 120.0)) * 4.0
    val endMs = (currentPositionMs + (barCount * msPerBar)).toLong()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Phrase Marker", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Phrase Type", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf(PhraseType.INTRO, PhraseType.VERSE, PhraseType.BUILD, PhraseType.DROP).forEach { type ->
                        val selected = selectedType == type
                        TextButton(
                            onClick = { selectedType = type },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = type.label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color(android.graphics.Color.parseColor(type.colorHex)) else Color.Gray
                            )
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf(PhraseType.BREAKDOWN, PhraseType.CHORUS, PhraseType.OUTRO, PhraseType.CUSTOM).forEach { type ->
                        val selected = selectedType == type
                        TextButton(
                            onClick = { selectedType = type },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = type.label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color(android.graphics.Color.parseColor(type.colorHex)) else Color.Gray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("Length (Bars): $barCount bars", fontSize = 12.sp, color = Color.White)
                Row {
                    listOf(8, 16, 32, 64).forEach { bars ->
                        TextButton(onClick = { barCount = bars }) {
                            Text(
                                text = "$bars",
                                fontWeight = if (barCount == bars) FontWeight.Bold else FontWeight.Normal,
                                color = if (barCount == bars) PioneerAmber else Color.Gray
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(
                        PhraseMarker(
                            id = "phrase_${System.currentTimeMillis()}",
                            type = selectedType,
                            label = selectedType.label,
                            startMs = currentPositionMs,
                            endMs = endMs,
                            barCount = barCount,
                            colorHex = selectedType.colorHex
                        )
                    )
                }
            ) { Text("ADD PHRASE") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        }
    )
}

@Composable
fun EditPhraseDialog(
    phrase: PhraseMarker,
    currentPositionMs: Long,
    bpm: Double,
    onDismiss: () -> Unit,
    onSave: (PhraseMarker) -> Unit
) {
    var label by remember { mutableStateOf(phrase.label) }
    var selectedType by remember { mutableStateOf(phrase.type) }
    var barCount by remember { mutableIntStateOf(phrase.barCount) }
    var startMs by remember { mutableLongStateOf(phrase.startMs) }

    val msPerBar = (60_000.0 / (if (bpm > 0.0) bpm else 120.0)) * 4.0
    val endMs = (startMs + (barCount * msPerBar)).toLong()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Phrase Marker", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Phrase Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text("Position: ${formatTime(startMs)} — ${formatTime(endMs)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = PioneerAmber)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { startMs = currentPositionMs },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Set Start to Playhead (${formatTime(currentPositionMs)})", fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("Phrase Type", fontSize = 12.sp, color = Color.Gray)
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf(PhraseType.INTRO, PhraseType.VERSE, PhraseType.BUILD, PhraseType.DROP).forEach { type ->
                        val selected = selectedType == type
                        TextButton(
                            onClick = {
                                selectedType = type
                                if (label.isBlank() || PhraseType.entries.any { it.label == label }) {
                                    label = type.label
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = type.label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color(android.graphics.Color.parseColor(type.colorHex)) else Color.Gray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text("Length (Bars): $barCount bars", fontSize = 12.sp, color = Color.White)
                Row {
                    listOf(8, 16, 32, 64).forEach { bars ->
                        TextButton(onClick = { barCount = bars }) {
                            Text(
                                text = "$bars",
                                fontWeight = if (barCount == bars) FontWeight.Bold else FontWeight.Normal,
                                color = if (barCount == bars) PioneerAmber else Color.Gray
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        phrase.copy(
                            type = selectedType,
                            label = label.ifBlank { selectedType.label },
                            startMs = startMs,
                            endMs = endMs,
                            barCount = barCount,
                            colorHex = selectedType.colorHex
                        )
                    )
                }
            ) { Text("SAVE") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        }
    )
}

@Composable
fun EditCueDialog(
    cue: CuePoint,
    currentPositionMs: Long,
    onDismiss: () -> Unit,
    onSave: (String, String, Long) -> Unit
) {
    var label by remember { mutableStateOf(cue.label) }
    var selectedColor by remember { mutableStateOf(cue.colorHex) }
    var cuePositionMs by remember { mutableLongStateOf(cue.positionMs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Hot Cue ${cue.id}", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Cue Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))

                Text("Position: ${formatTime(cuePositionMs)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = PioneerAmber)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { cuePositionMs = currentPositionMs },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Set Position to Playhead (${formatTime(currentPositionMs)})", fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Color", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CuePoint.DEFAULT_HOT_CUE_COLORS.forEach { hex ->
                        val color = Color(android.graphics.Color.parseColor(hex))
                        val isSelected = selectedColor.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(color, CircleShape)
                                .border(
                                    if (isSelected) 2.dp else 0.dp,
                                    if (isSelected) Color.White else Color.Transparent,
                                    CircleShape
                                )
                                .clickable { selectedColor = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(label, selectedColor, cuePositionMs) }) { Text("SAVE") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        }
    )
}

@Composable
fun EditMemoryCueDialog(
    cue: CuePoint,
    currentPositionMs: Long,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit
) {
    var label by remember { mutableStateOf(cue.label) }
    var cuePositionMs by remember { mutableLongStateOf(cue.positionMs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Memory Cue", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Memory Cue Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))

                Text("Position: ${formatTime(cuePositionMs)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFFFCC00))
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { cuePositionMs = currentPositionMs },
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Set Position to Playhead (${formatTime(currentPositionMs)})", fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(label, cuePositionMs) }) { Text("SAVE") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        }
    )
}

fun formatTime(ms: Long): String {
    val totalSec = ms / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    val millis = (ms % 1000L) / 10L
    return String.format(Locale.US, "%02d:%02d.%02d", min, sec, millis)
}
