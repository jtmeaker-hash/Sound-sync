package com.example.ui.diagnostics

import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.audio.DjAudioEngine
import com.example.brain.LibraryBrain
import com.example.diagnostics.AudioOutputTracker
import com.example.diagnostics.DeveloperModeManager
import com.example.diagnostics.DiagnosticLogger
import com.example.diagnostics.DiagnosticReportExporter
import com.example.diagnostics.DiagnosticSeverity
import com.example.diagnostics.DiagnosticSubsystem
import com.example.ui.MainDjViewModel
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBOrange
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DeveloperDiagnosticsScreen(
    viewModel: MainDjViewModel,
    audioEngine: DjAudioEngine,
    onBack: () -> Unit,
    onNavigateToSelfTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val devManager = remember { DeveloperModeManager.getInstance(context) }
    val isDevMode by devManager.isDeveloperModeEnabled.collectAsState()

    val currentTrack by audioEngine.currentTrack.collectAsState()
    val isPlaying by audioEngine.isPlaying.collectAsState()
    val posMs by audioEngine.currentPositionMs.collectAsState()
    val waveformData by audioEngine.waveformData.collectAsState()
    val playbackProgress by audioEngine.playbackProgress.collectAsState()

    val queue by viewModel.playbackQueue.collectAsState()
    val queueIndex by viewModel.queueIndex.collectAsState()
    val isShuffle by viewModel.isShuffleEnabled.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()

    val brain = remember { LibraryBrain.getInstance(context) }
    val brainSummary by brain.brainSummary.collectAsState()

    val audioTracker = remember { AudioOutputTracker.getInstance(context) }
    val audioOutputDiag by audioTracker.diagnosticsFlow.collectAsState()

    val logger = remember { DiagnosticLogger.getInstance() }
    val logEntries by logger.entriesFlow.collectAsState()

    val playbackDiag = audioEngine.getPlaybackDiagnostics()

    var showExportDialog by remember { mutableStateOf(false) }
    var selectedSeverityFilter by remember { mutableStateOf<DiagnosticSeverity?>(null) }

    // Section expanded states
    var expPlayback by remember { mutableStateOf(true) }
    var expWaveform by remember { mutableStateOf(true) }
    var expQueue by remember { mutableStateOf(false) }
    var expAudioOutput by remember { mutableStateOf(false) }
    var expBrain by remember { mutableStateOf(true) }
    var expMetadata by remember { mutableStateOf(false) }
    var expDeviceHealth by remember { mutableStateOf(false) }
    var expLogs by remember { mutableStateOf(true) }

    val durationMs = currentTrack?.durationSeconds?.toLong()?.times(1000L) ?: 0L
    val expectedWaveformMs = if (durationMs > 0) (playbackProgress * durationMs).toLong() else 0L
    val driftMs = Math.abs(posMs - expectedWaveformMs)
    val hasDriftWarning = isPlaying && durationMs > 0 && driftMs > 250L

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
    ) {
        // ── TOP ACTION BAR ───────────────────────────────────────────────────
        Surface(
            color = DjSurfaceDark,
            border = BorderStroke(0.5.dp, DjSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "DEVELOPER DIAGNOSTICS",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = DeckACyan.copy(alpha = 0.2f),
                                border = BorderStroke(0.5.dp, DeckACyan)
                            ) {
                                Text(
                                    "DEV",
                                    color = DeckACyan,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            "Real-time subsystem metrics & telemetry",
                            color = TextMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = onNavigateToSelfTest,
                        colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(28.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Self-Test", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { showExportDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = TextPrimary),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(28.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── SCROLLABLE DIAGNOSTIC CARDS ──────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── 1. PLAYBACK SUBSYSTEM ────────────────────────────────────────
            item {
                DiagnosticSectionCard(
                    title = "1. PLAYBACK SUBSYSTEM",
                    subtitle = if (isPlaying) "PLAYING · ${playbackDiag.sampleRate}Hz" else "PAUSED / IDLE",
                    icon = Icons.Default.PlayArrow,
                    isExpanded = expPlayback,
                    statusBadge = if (isPlaying) "ACTIVE" else "IDLE",
                    statusColor = if (isPlaying) DeckACyan else TextMuted,
                    onToggle = { expPlayback = !expPlayback }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DiagRow("Playback State", if (isPlaying) "PLAYING" else if (currentTrack != null) "PAUSED" else "IDLE", isHighlight = isPlaying)
                        DiagRow("Current Track ID", currentTrack?.id ?: "None")
                        DiagRow("Title / Artist", "${currentTrack?.title ?: "None"} — ${currentTrack?.artist ?: "None"}")
                        DiagRow("File Path / URI", currentTrack?.filePath ?: "None")
                        val fileExists = currentTrack?.filePath?.let { File(it).exists() } ?: false
                        DiagRow("File Exists on Disk", if (fileExists) "YES" else "NO / Content URI", isHighlight = !fileExists)
                        HorizontalDivider(color = DjSurfaceBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                        DiagRow("Decoder In Use", playbackDiag.decoderName, isHighlight = true)
                        DiagRow("Container / Format", playbackDiag.containerFormat)
                        DiagRow("Codec MIME Type", playbackDiag.mimeType)
                        DiagRow("Sample Rate", "${playbackDiag.sampleRate} Hz")
                        DiagRow("Bit Depth / Channels", "${playbackDiag.bitDepth}-bit · ${playbackDiag.channelCount} ch (${if (playbackDiag.channelCount == 1) "Mono" else "Stereo"})")
                        DiagRow("Reported Bitrate", "${playbackDiag.bitrateKbps} kbps")
                        DiagRow("Duration", "${formatMs(durationMs)} ($durationMs ms)")
                        DiagRow("Playback Position", "${formatMs(posMs)} ($posMs ms)")
                        DiagRow("Playback Speed / Pitch", String.format(Locale.US, "%.2fx (%+.1f%%)", playbackDiag.playbackSpeed, audioEngine.pitchPercent.value))
                        DiagRow("Audio Session ID", "${playbackDiag.audioSessionId}")
                        DiagRow("Audio Focus State", if (playbackDiag.hasAudioFocus) "HELD (Granted)" else "RELEASED / NONE", isHighlight = !playbackDiag.hasAudioFocus)
                        DiagRow("Repeat / Shuffle", "${repeatMode.name} / ${if (isShuffle) "ON" else "OFF"}")
                    }
                }
            }

            // ── 2. WAVEFORM SUBSYSTEM ────────────────────────────────────────
            item {
                DiagnosticSectionCard(
                    title = "2. WAVEFORM & AUDIO SYNC",
                    subtitle = if (hasDriftWarning) "DRIFT WARNING (${driftMs}ms)" else "Sync drift: ${driftMs}ms",
                    icon = Icons.Default.GraphicEq,
                    isExpanded = expWaveform,
                    statusBadge = if (hasDriftWarning) "WARN" else if (waveformData != null) "IN-SYNC" else "NONE",
                    statusColor = if (hasDriftWarning) DeckBOrange else if (waveformData != null) DeckACyan else TextMuted,
                    onToggle = { expWaveform = !expWaveform }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DiagRow("Waveform Generated", if (waveformData != null) "YES" else "NO", isHighlight = waveformData != null)
                        DiagRow("Waveform Source/Ver", if (waveformData != null) "Peak Amplitudes v2 (DSP)" else "None")
                        DiagRow("Audio Duration", "$durationMs ms")
                        DiagRow("Playback Position", "$posMs ms")
                        DiagRow("Expected Waveform Pos", "$expectedWaveformMs ms")
                        DiagRow("Position Drift", "$driftMs ms", isHighlight = hasDriftWarning)
                        if (hasDriftWarning) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = DeckBOrange.copy(alpha = 0.15f),
                                border = BorderStroke(0.5.dp, DeckBOrange),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = DeckBOrange, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Sync Drift Warning: Playback and waveform position differ by >250ms.",
                                        color = DeckBOrange,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 3. QUEUE SUBSYSTEM ───────────────────────────────────────────
            item {
                DiagnosticSectionCard(
                    title = "3. QUEUE SUBSYSTEM",
                    subtitle = "Items: ${queue.size} · Index: $queueIndex",
                    icon = Icons.Default.QueueMusic,
                    isExpanded = expQueue,
                    onToggle = { expQueue = !expQueue }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DiagRow("Queue Size", "${queue.size}")
                        DiagRow("Current Queue Index", "$queueIndex")
                        DiagRow("Current Track", queue.getOrNull(queueIndex)?.title ?: "None")
                        DiagRow("Previous Track", queue.getOrNull(queueIndex - 1)?.title ?: "None")
                        DiagRow("Next Track", queue.getOrNull(queueIndex + 1)?.title ?: "None")
                        DiagRow("Shuffle Enabled", if (isShuffle) "YES" else "NO")
                        DiagRow("Repeat Mode", repeatMode.name)
                        DiagRow("Transition Armed", if (posMs > 0 && durationMs > 0 && durationMs - posMs <= 5000) "YES (Crossfade Window)" else "NO")
                    }
                }
            }

            // ── 4. BLUETOOTH & AUDIO OUTPUT ──────────────────────────────────
            item {
                DiagnosticSectionCard(
                    title = "4. BLUETOOTH & AUDIO OUTPUT",
                    subtitle = audioOutputDiag.activeRoute,
                    icon = Icons.Default.Bluetooth,
                    isExpanded = expAudioOutput,
                    statusBadge = audioOutputDiag.bluetoothState,
                    statusColor = if (audioOutputDiag.bluetoothState == "ENABLED") DeckACyan else TextMuted,
                    onToggle = { expAudioOutput = !expAudioOutput }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DiagRow("Bluetooth State", audioOutputDiag.bluetoothState)
                        DiagRow("Active Route", audioOutputDiag.activeRoute, isHighlight = true)
                        DiagRow("Connected Peripherals", audioOutputDiag.connectedDevices.joinToString(", "))
                        DiagRow("Last Device Connect", audioOutputDiag.lastConnectEvent ?: "None")
                        DiagRow("Last Device Disconnect", audioOutputDiag.lastDisconnectEvent ?: "None")
                        DiagRow("Auto-Pause On Disconnect", if (audioOutputDiag.autoPauseOnDisconnectFired) "FIRED" else "READY")
                        DiagRow("Last Audio Focus Event", audioOutputDiag.lastAudioFocusEvent ?: "None")
                        DiagRow("Last Becoming Noisy Event", audioOutputDiag.lastNoisyEvent ?: "None")
                    }
                }
            }

            // ── 5. LIBRARY BRAIN ─────────────────────────────────────────────
            item {
                val brainStateStr = if (brainSummary.isRunning) "RUNNING" else if (brainSummary.isPaused) "PAUSED" else "IDLE"
                DiagnosticSectionCard(
                    title = "5. LIBRARY BRAIN & WORKER",
                    subtitle = "$brainStateStr · ${brainSummary.completeCount}/${brainSummary.totalTracks} analyzed",
                    icon = Icons.Default.Memory,
                    isExpanded = expBrain,
                    statusBadge = brainStateStr,
                    statusColor = if (brainSummary.isRunning) DeckACyan else TextMuted,
                    onToggle = { expBrain = !expBrain }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DiagRow("Worker State", brainStateStr, isHighlight = brainSummary.isRunning)
                        DiagRow("Total Tracked", "${brainSummary.totalTracks}")
                        DiagRow("Completed Tracks", "${brainSummary.completeCount}")
                        DiagRow("Pending Queue", "${brainSummary.pendingCount}")
                        DiagRow("Failed Tracks", "${brainSummary.failedCount}", isHighlight = brainSummary.failedCount > 0)
                        DiagRow("Needs Review", "${brainSummary.needsReviewCount}")
                        DiagRow("Missing Files", "${brainSummary.missingFilesCount}")
                        HorizontalDivider(color = DjSurfaceBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                        DiagRow("Current Job", brainSummary.currentJobDescription)
                        DiagRow("Active Track Title", brainSummary.currentTrackTitle.ifEmpty { "None" })
                        DiagRow("Queue Length", "${brainSummary.queueLength}")
                        DiagRow("Analysing Count", "${brainSummary.analysingCount}")
                    }
                }
            }

            // ── 6. METADATA & ENRICHMENT ─────────────────────────────────────
            item {
                DiagnosticSectionCard(
                    title = "6. METADATA & ENRICHMENT",
                    subtitle = "Providers: Apple iTunes, TheAudioDB, ID3",
                    icon = Icons.Default.Info,
                    isExpanded = expMetadata,
                    onToggle = { expMetadata = !expMetadata }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DiagRow("Providers Supported", "Apple iTunes Search, TheAudioDB, Embedded ID3/Vorbis")
                        DiagRow("Artwork Cache Location", "${context.cacheDir.absolutePath}/artwork_cache")
                        DiagRow("Current Track Artwork Source", currentTrack?.artworkSource ?: "None")
                        DiagRow("User Confirmed Tags", if (currentTrack?.userConfirmedMetadata == true) "YES" else "NO")
                        DiagRow("Security Redaction", "API Secrets / Tokens masked from all diagnostic exports")
                    }
                }
            }

            // ── 7. APP & DEVICE HEALTH ───────────────────────────────────────
            item {
                val statFs = try { StatFs(Environment.getDataDirectory().path) } catch (_: Exception) { null }
                val freeStorageMb = statFs?.let { it.availableBlocksLong * it.blockSizeLong / (1024 * 1024) } ?: -1L
                val rt = Runtime.getRuntime()
                val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                val maxHeapMb = rt.maxMemory() / (1024 * 1024)

                DiagnosticSectionCard(
                    title = "7. APP & DEVICE HEALTH",
                    subtitle = "v${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    icon = Icons.Default.BugReport,
                    isExpanded = expDeviceHealth,
                    onToggle = { expDeviceHealth = !expDeviceHealth }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DiagRow("SoundSync Version", "v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
                        DiagRow("Build Type", BuildConfig.BUILD_TYPE)
                        DiagRow("Room Database Version", "18")
                        DiagRow("Android OS Version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        DiagRow("Device Model", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                        DiagRow("Available Storage", "$freeStorageMb MB")
                        DiagRow("JVM Heap Usage", "$usedHeapMb MB used / $maxHeapMb MB max")
                        DiagRow("Native Heap Allocated", "${android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)} MB")
                        DiagRow("Developer Mode", if (isDevMode) "ENABLED" else "DISABLED", isHighlight = isDevMode)
                    }
                }
            }

            // ── 8. RECENT DIAGNOSTIC LOGS ────────────────────────────────────
            item {
                val errorCount = logEntries.count { it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.CRITICAL }
                DiagnosticSectionCard(
                    title = "8. RECENT DIAGNOSTIC LOGS",
                    subtitle = "Capped rolling buffer: ${logEntries.size}/100 · Errors: $errorCount",
                    icon = Icons.Default.BugReport,
                    isExpanded = expLogs,
                    statusBadge = "$errorCount ERR",
                    statusColor = if (errorCount > 0) DeckBOrange else TextMuted,
                    onToggle = { expLogs = !expLogs }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Filters and Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = selectedSeverityFilter == null,
                                    onClick = { selectedSeverityFilter = null },
                                    label = { Text("All (${logEntries.size})", fontSize = 9.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = DeckACyan,
                                        selectedLabelColor = DjObsidian
                                    )
                                )
                                FilterChip(
                                    selected = selectedSeverityFilter == DiagnosticSeverity.ERROR,
                                    onClick = {
                                        selectedSeverityFilter = if (selectedSeverityFilter == DiagnosticSeverity.ERROR) null else DiagnosticSeverity.ERROR
                                    },
                                    label = { Text("Errors ($errorCount)", fontSize = 9.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = DeckBOrange,
                                        selectedLabelColor = DjObsidian
                                    )
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        logger.addTestEntry()
                                        Toast.makeText(context, "Test diagnostic log added", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.height(26.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text("+ Test Log", fontSize = 9.sp)
                                }
                                IconButton(onClick = { logger.clear() }, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Clear logs", tint = TextMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        val filteredLogs = if (selectedSeverityFilter != null) {
                            logEntries.filter { it.severity == selectedSeverityFilter }
                        } else logEntries

                        if (filteredLogs.isEmpty()) {
                            Text(
                                "No logs match current filter.",
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                            for (entry in filteredLogs.take(25)) {
                                Surface(
                                    color = DjSurfaceElevated,
                                    shape = RoundedCornerShape(4.dp),
                                    border = BorderStroke(0.5.dp, when (entry.severity) {
                                        DiagnosticSeverity.CRITICAL, DiagnosticSeverity.ERROR -> DeckBOrange.copy(alpha = 0.5f)
                                        DiagnosticSeverity.WARN -> Color.Yellow.copy(alpha = 0.5f)
                                        DiagnosticSeverity.INFO -> DjSurfaceBorder
                                    }),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "[${entry.subsystem}][${entry.code}]",
                                                color = when (entry.severity) {
                                                    DiagnosticSeverity.CRITICAL, DiagnosticSeverity.ERROR -> DeckBOrange
                                                    DiagnosticSeverity.WARN -> Color.Yellow
                                                    DiagnosticSeverity.INFO -> DeckACyan
                                                },
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                dateFormat.format(Date(entry.timestamp)),
                                                color = TextMuted,
                                                fontSize = 8.5.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        Text(
                                            entry.message,
                                            color = TextPrimary,
                                            fontSize = 9.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                        if (!entry.stackTrace.isNullOrBlank()) {
                                            Text(
                                                entry.stackTrace.lines().take(2).joinToString("\n"),
                                                color = TextMuted,
                                                fontSize = 8.sp,
                                                fontFamily = FontFamily.Monospace,
                                                modifier = Modifier.padding(top = 2.dp)
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
    }

    // ── EXPORT REPORT DIALOG ─────────────────────────────────────────────────
    if (showExportDialog) {
        var redactPaths by remember { mutableStateOf(false) }
        var exportFormatJson by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            containerColor = DjSurfaceDark,
            title = {
                Text("Export Diagnostic Report", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Generate a sanitized diagnostic report containing hardware specs, audio metrics, and error logs. Passwords and credentials are automatically redacted.",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Redact personal folder paths", color = TextPrimary, fontSize = 11.sp)
                        Switch(
                            checked = redactPaths,
                            onCheckedChange = { redactPaths = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = DeckACyan)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("JSON format (structured)", color = TextPrimary, fontSize = 11.sp)
                        Switch(
                            checked = exportFormatJson,
                            onCheckedChange = { exportFormatJson = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = DeckACyan)
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            val report = if (exportFormatJson) {
                                DiagnosticReportExporter.generateJsonReport(context, audioEngine, viewModel, redactPaths)
                            } else {
                                DiagnosticReportExporter.generateTextReport(context, audioEngine, viewModel, redactPaths)
                            }
                            DiagnosticReportExporter.copyToClipboard(context, report)
                            Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
                            showExportDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = TextPrimary),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            val report = if (exportFormatJson) {
                                DiagnosticReportExporter.generateJsonReport(context, audioEngine, viewModel, redactPaths)
                            } else {
                                DiagnosticReportExporter.generateTextReport(context, audioEngine, viewModel, redactPaths)
                            }
                            DiagnosticReportExporter.shareReport(context, report)
                            showExportDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }
}

@Composable
private fun DiagnosticSectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isExpanded: Boolean,
    statusBadge: String? = null,
    statusColor: Color = DeckACyan,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        border = BorderStroke(0.5.dp, DjSurfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(statusColor.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(14.dp))
                    }
                    Column {
                        Text(
                            text = title,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = subtitle,
                            color = TextMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (statusBadge != null) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = statusColor.copy(alpha = 0.15f),
                            border = BorderStroke(0.5.dp, statusColor)
                        ) {
                            Text(
                                text = statusBadge,
                                color = statusColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(if (isExpanded) 90f else 0f)
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    HorizontalDivider(color = DjSurfaceBorder, thickness = 0.5.dp, modifier = Modifier.padding(bottom = 8.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun DiagRow(
    label: String,
    value: String,
    isHighlight: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = TextMuted,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = value,
            color = if (isHighlight) DeckBOrange else TextPrimary,
            fontSize = 9.5.sp,
            fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.55f)
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}
