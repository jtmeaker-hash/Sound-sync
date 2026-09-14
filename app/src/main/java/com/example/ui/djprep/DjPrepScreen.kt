package com.example.ui.djprep

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.DjAudioEngine
import com.example.djprep.*
import com.example.model.Track
import com.example.ui.MainDjViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Stage 28: Dedicated DJ Track Preparation Environment Screen.
 *
 * Provides a utilitarian, high-precision track preparation workspace:
 * - Zoomable/scrollable waveform with beat grid lines, cues, and phrase ribbon.
 * - Transport controls (Play/Pause, Cue, Beat Jump, Key Lock / Master Tempo, Metronome).
 * - Beat grid manipulation (Shift downbeat, fine nudge, double/halve BPM, reset).
 * - 8 Hot Cue pads (A-H) with color-coding, name editing, and instant jumping.
 * - Ordered Memory Cues with Previous/Next navigation.
 * - Structural Phrase Markers (Intro, Verse, Build, Drop, Breakdown, Chorus, Outro).
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
    var editingCue by remember { mutableStateOf<CuePoint?>(null) }
    var showStatusDropdown by remember { mutableStateOf(false) }

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
                    Column {
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
                        modifier = Modifier.padding(end = 16.dp)
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color(0xFF0E1013))
                    .verticalScroll(rememberScrollState())
            ) {
                // 1. Zoomable Waveform & Beat Grid Display
                PrepWaveformSection(
                    track = track,
                    audioEngine = audioEngine,
                    prepData = currentData,
                    currentPositionMs = positionMs,
                    zoomLevel = zoomLevel,
                    onSeek = { targetMs -> audioEngine.seekToMs(targetMs) },
                    onZoomChange = { newZoom -> zoomLevel = newZoom }
                )

                // 2. Transport & Key Lock Controls
                PrepTransportBar(
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = track.durationSeconds * 1000L,
                    keyLockEnabled = keyLockEnabled,
                    pitchPercent = pitchPercent,
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

                // 3. Beat Grid & BPM Control Panel
                BeatGridControlPanel(
                    prepData = currentData,
                    currentPositionMs = positionMs,
                    onDoubleBpm = {
                        scope.launch { prepData = prepManager.doubleBpm(track) }
                    },
                    onHalveBpm = {
                        scope.launch { prepData = prepManager.halveBpm(track) }
                    },
                    onBpmNudge = { delta ->
                        scope.launch { prepData = prepManager.setBpm(track, currentData.bpm + delta) }
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
                    onTriggerCue = { cue ->
                        audioEngine.seekToMs(cue.positionMs)
                    },
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
                    onDeletePhrase = { phraseId ->
                        scope.launch {
                            prepData = prepManager.deletePhraseMarker(track, phraseId)
                        }
                    },
                    onJumpToPhrase = { phrase ->
                        audioEngine.seekToMs(phrase.startMs)
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))
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

    // Edit Hot Cue dialog
    editingCue?.let { cue ->
        EditCueDialog(
            cue = cue,
            onDismiss = { editingCue = null },
            onSave = { updatedLabel, updatedColor ->
                editingCue = null
                scope.launch {
                    prepData = prepManager.addOrUpdateHotCue(
                        track = track,
                        slot = cue.id,
                        positionMs = cue.positionMs,
                        label = updatedLabel,
                        colorHex = updatedColor
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
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f)),
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
        // Zoom controls & timestamps
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
                listOf(1.0f, 2.0f, 4.0f, 8.0f).forEach { zoom ->
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

        Spacer(modifier = Modifier.height(4.dp))

        // Interactive Waveform & Grid Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .background(Color(0xFF0A0B0E), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF252932), RoundedCornerShape(4.dp))
                .pointerInput(durationMs, zoomLevel) {
                    detectTapGestures { offset ->
                        val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                        val targetMs = (ratio * durationMs).toLong()
                        onSeek(targetMs)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Draw background centerline
                drawLine(
                    color = Color(0xFF252932),
                    start = Offset(0f, h / 2f),
                    end = Offset(w, h / 2f),
                    strokeWidth = 1f
                )

                // 1. Draw Phrase Ribbon at top
                val phraseHeight = 12f
                prepData.phraseMarkers.forEach { phrase ->
                    val xStart = (phrase.startMs.toFloat() / durationMs) * w
                    val xEnd = (phrase.endMs.toFloat() / durationMs) * w
                    val phraseColor = Color(android.graphics.Color.parseColor(phrase.colorHex))
                    drawRect(
                        color = phraseColor.copy(alpha = 0.85f),
                        topLeft = Offset(xStart, 0f),
                        size = Size((xEnd - xStart).coerceAtLeast(2f), phraseHeight)
                    )
                }

                // 2. Draw Simulated/Sample Waveform Envelope
                val step = 3f
                var x = 0f
                while (x < w) {
                    val pos = (x / w) * durationMs
                    val beatIndex = if (grid.intervalMs > 0.0) ((pos / grid.intervalMs).toInt() % 4) else 0
                    val amp = if (beatIndex == 0) 0.85f else 0.45f + ((x.toInt() % 7) * 0.05f)
                    val barH = (h - 20f) * amp
                    val topY = (h / 2f) - (barH / 2f)
                    drawLine(
                        color = Color(0xFF00B0FF).copy(alpha = 0.6f),
                        start = Offset(x, topY),
                        end = Offset(x, topY + barH),
                        strokeWidth = 2f
                    )
                    x += step
                }

                // 3. Draw Beat Grid Lines
                val intervalMs = grid.intervalMs
                if (intervalMs > 0.0 && durationMs > 0L) {
                    val startOffset = (grid.firstDownbeatMs + grid.gridOffsetMs) % intervalMs
                    var beatMs = startOffset
                    var beatNum = 0
                    while (beatMs < durationMs) {
                        if (beatMs >= 0L) {
                            val beatX = (beatMs.toFloat() / durationMs) * w
                            val isDownbeat = (beatNum % 4) == 0
                            val gridColor = if (isDownbeat) Color(0xFFFF8A00) else Color(0x55FFFFFF)
                            val strokeW = if (isDownbeat) 1.5f else 0.8f
                            drawLine(
                                color = gridColor,
                                start = Offset(beatX, phraseHeight),
                                end = Offset(beatX, h),
                                strokeWidth = strokeW
                            )
                        }
                        beatMs += intervalMs
                        beatNum++
                    }
                }

                // 4. Draw Hot Cue Flags
                prepData.hotCues.forEach { cue ->
                    val cueX = (cue.positionMs.toFloat() / durationMs) * w
                    val cueColor = Color(android.graphics.Color.parseColor(cue.colorHex))
                    // Flag line
                    drawLine(
                        color = cueColor,
                        start = Offset(cueX, 0f),
                        end = Offset(cueX, h),
                        strokeWidth = 2f
                    )
                    // Flag head
                    drawCircle(
                        color = cueColor,
                        radius = 5f,
                        center = Offset(cueX, 16f)
                    )
                }

                // 5. Draw Memory Cue Markers
                prepData.memoryCues.forEach { mem ->
                    val memX = (mem.positionMs.toFloat() / durationMs) * w
                    drawCircle(
                        color = Color(0xFFFFCC00),
                        radius = 4f,
                        center = Offset(memX, h - 10f)
                    )
                }

                // 6. Draw Current Playhead
                val playheadX = (currentPositionMs.toFloat() / durationMs) * w
                drawLine(
                    color = Color.White,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, h),
                    strokeWidth = 2.5f
                )
                drawCircle(
                    color = Color.White,
                    radius = 4f,
                    center = Offset(playheadX, h / 2f)
                )
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
                    .width(70.dp)
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
                border = androidx.compose.foundation.BorderStroke(
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

            // Metronome button
            OutlinedButton(
                onClick = onToggleMetronome,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(
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

        // Beat Jump Row
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
    onDoubleBpm: () -> Unit,
    onHalveBpm: () -> Unit,
    onBpmNudge: (Double) -> Unit,
    onSetFirstDownbeat: () -> Unit,
    onNudgeGrid: (Long) -> Unit,
    onResetGrid: () -> Unit,
    onOpenKeyDialog: () -> Unit
) {
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
                // BPM section
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.1f", prepData.bpm),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("BPM", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    OriginBadge(isManual = prepData.isManualBpm)
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

            // Row 3: Grid Align & Downbeat
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
            }
        }
    }
}

@Composable
fun OriginBadge(isManual: Boolean) {
    Surface(
        color = if (isManual) PioneerAmber.copy(alpha = 0.2f) else Color(0x33404652),
        shape = RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(
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
        border = androidx.compose.foundation.BorderStroke(
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
                    Box(modifier = Modifier.clickable { onClear() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear Cue",
                            tint = Color.Black.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
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
                prepData.memoryCues.forEachIndexed { index, cue ->
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
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFFFFCC00), CircleShape)
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
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { onDelete(cue.id) },
                                modifier = Modifier.size(20.dp)
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
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { onDeletePhrase(phrase.id) },
                                modifier = Modifier.size(20.dp)
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

// ── Dialogs: Key, Phrase, Edit Cue ────────────────────────────────────────────

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
fun EditCueDialog(
    cue: CuePoint,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var label by remember { mutableStateOf(cue.label) }
    var selectedColor by remember { mutableStateOf(cue.colorHex) }

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
            Button(onClick = { onSave(label, selectedColor) }) { Text("SAVE") }
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
    return String.format(java.util.Locale.US, "%02d:%02d.%02d", min, sec, millis)
}
