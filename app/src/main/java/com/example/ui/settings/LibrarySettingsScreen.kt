package com.example.ui.settings

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
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metadata.MetadataSettings
import com.example.model.FileOperationType
import com.example.model.MusicPlatform
import com.example.model.OperationJournalItem
import com.example.model.StorageSource
import com.example.model.StorageSourceType
import com.example.service.AudioScanState
import com.example.sync.CloudSyncManager
import com.example.ui.components.MetadataEnrichmentSettingsCard
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeonRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibrarySettingsScreen(
    storageSources: List<StorageSource>,
    operationJournal: List<OperationJournalItem>,
    scanServiceState: AudioScanState = AudioScanState(),
    metadataSettings: MetadataSettings = MetadataSettings(),
    focusMetadataOnly: Boolean = false,
    onSetEnrichmentEnabled: (Boolean) -> Unit = {},
    onSetAppleSearchEnabled: (Boolean) -> Unit = {},
    onSetTheAudioDbEnabled: (Boolean) -> Unit = {},
    onSetBpmAnalysisEnabled: (Boolean) -> Unit = {},
    onSetKeyAnalysisEnabled: (Boolean) -> Unit = {},
    onSetWriteToFileEnabled: (Boolean) -> Unit = {},
    onSetShowProvenanceBadges: (Boolean) -> Unit = {},
    onSetConcurrency: (Int) -> Unit = {},
    onSetBpmRange: (Int, Int) -> Unit = { _, _ -> },
    onTriggerSync: () -> Unit = {},
    onUndoOperation: (String) -> Unit = {},
    onMountSaf: () -> Unit = {},
    onPickAudioFiles: () -> Unit = {},
    onScanMediaStore: () -> Unit = {},
    onCleanMissingFiles: () -> Unit = {},
    onLoadDemoTracks: () -> Unit = {},
    onClearLibrary: () -> Unit = {},
    onPauseScan: () -> Unit = {},
    onResumeScan: () -> Unit = {},
    onCancelScan: () -> Unit = {},
    onOpenGoogleDrive: () -> Unit = {},
    onConnectGoogleDrive: () -> Unit = {},
    onDisconnectGoogleDrive: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val platformStatuses by CloudSyncManager.platformStatuses.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Metadata & Artwork Settings Card
        item {
            MetadataEnrichmentSettingsCard(
                settings = metadataSettings,
                onSetEnrichmentEnabled = onSetEnrichmentEnabled,
                onSetAppleSearchEnabled = onSetAppleSearchEnabled,
                onSetTheAudioDbEnabled = onSetTheAudioDbEnabled,
                onSetBpmAnalysisEnabled = onSetBpmAnalysisEnabled,
                onSetKeyAnalysisEnabled = onSetKeyAnalysisEnabled,
                onSetWriteToFileEnabled = onSetWriteToFileEnabled,
                onSetShowProvenanceBadges = onSetShowProvenanceBadges,
                onSetConcurrency = onSetConcurrency,
                onSetBpmRange = onSetBpmRange
            )
        }

        if (!focusMetadataOnly) {
            // Background DocumentFile Scanner Live Card
            item {
                BackgroundScannerStatusCard(
                    scanState = scanServiceState,
                    onPauseScan = onPauseScan,
                    onResumeScan = onResumeScan,
                    onCancelScan = onCancelScan,
                    onMountFolder = onMountSaf
                )
            }

            // Storage Sources Header & Actions
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "STORAGE SOURCES & SAF MOUNT POINTS",
                        color = DeckACyan,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = onScanMediaStore,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Scan Phone", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = onMountSaf,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = NeonAmber),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("+ Pick Folder", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            items(storageSources) { source ->
                StorageSourceCard(source = source)
            }

            // Storage Management & Maintenance Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Library Storage Maintenance", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Manage audio cache, clean references to deleted files, or load demo tracks.", color = TextSecondary, fontSize = 10.sp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onCleanMissingFiles,
                                colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = TextPrimary),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CleaningServices, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clean Missing Files", fontSize = 10.sp)
                            }

                            Button(
                                onClick = onPickAudioFiles,
                                colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = DeckACyan),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.AudioFile, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Import Files", fontSize = 10.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onLoadDemoTracks,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Load Demo Tracks", fontSize = 10.sp, color = TextSecondary)
                            }

                            OutlinedButton(
                                onClick = onClearLibrary,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.DeleteForever, contentDescription = null, tint = NeonRed, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear Library", fontSize = 10.sp, color = NeonRed)
                            }
                        }
                    }
                }
            }

            // Operation Journal Card
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SAFE FILE OPERATION JOURNAL",
                        color = DeckACyan,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "${operationJournal.size} entries",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (operationJournal.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = DjSurfaceDark,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                    ) {
                        Text(
                            text = "No batch file operations recorded yet. File movements, tag updates, and safe trashes will be logged here.",
                            color = TextMuted,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            } else {
                items(operationJournal) { item ->
                    OperationJournalCard(
                        item = item,
                        onUndo = { onUndoOperation(item.id) }
                    )
                }
            }

            // Cloud Platforms Sync Card
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "STORAGE & PLATFORMS SYNC",
                        color = DeckACyan,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )
                    Button(
                        onClick = onTriggerSync,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple.copy(alpha = 0.3f), contentColor = NeonPurple),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Sync Vault", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            items(platformStatuses) { status ->
                PlatformStatusCard(
                    status = status,
                    onToggle = {
                        if (status.platform == MusicPlatform.GOOGLE_DRIVE) {
                            if (status.isConnected) {
                                onOpenGoogleDrive()
                            } else {
                                onConnectGoogleDrive()
                            }
                        } else {
                            CloudSyncManager.togglePlatformConnection(status.platform)
                        }
                    },
                    onOpenDrive = onOpenGoogleDrive,
                    onConnectDrive = onConnectGoogleDrive,
                    onDisconnectDrive = onDisconnectGoogleDrive
                )
            }
        }
    }
}

@Composable
private fun StorageSourceCard(source: StorageSource) {
    val icon = when (source.type) {
        StorageSourceType.INTERNAL -> Icons.Default.Folder
        StorageSourceType.USB_SSD -> Icons.Default.Usb
        StorageSourceType.SD_CARD -> Icons.Default.SdCard
        StorageSourceType.DOWNLOADS -> Icons.Default.Download
        StorageSourceType.CLOUD_VAULT -> Icons.Default.CloudDone
    }

    val usedGb = source.totalSpaceGb - source.freeSpaceGb
    val usedRatio = if (source.totalSpaceGb > 0) (usedGb / source.totalSpaceGb).toFloat().coerceIn(0f, 1f) else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(18.dp))
                    Column {
                        Text(source.label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        if (source.path.isNotBlank()) {
                            Text(source.path, color = TextSecondary, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = NeonGreen.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen)
                ) {
                    Text(
                        text = "${source.trackCount} tracks",
                        color = NeonGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { usedRatio },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = if (usedRatio > 0.85f) NeonRed else DeckACyan,
                trackColor = DjSurfaceElevated
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${String.format(Locale.US, "%.1f", source.freeSpaceGb)} GB Free",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Total ${String.format(Locale.US, "%.0f", source.totalSpaceGb)} GB",
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun OperationJournalCard(
    item: OperationJournalItem,
    onUndo: () -> Unit
) {
    val dateStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(item.timestamp))
    val opIcon = when (item.operationType) {
        FileOperationType.MOVE -> Icons.AutoMirrored.Filled.DriveFileMove
        FileOperationType.COPY -> Icons.Default.Folder
        FileOperationType.RENAME -> Icons.Default.Folder
        FileOperationType.TRASH -> Icons.Default.Delete
        FileOperationType.AUTO_TAG -> Icons.Default.AutoAwesome
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (item.isUndone) DjSurfaceDark.copy(alpha = 0.5f) else DjSurfaceDark),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (item.isUndone) DjSurfaceBorder.copy(alpha = 0.5f) else DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(opIcon, contentDescription = null, tint = if (item.isUndone) TextMuted else DeckACyan, modifier = Modifier.size(18.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = item.operationType.name,
                            color = if (item.isUndone) TextMuted else TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = dateStr,
                            color = TextMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = if (item.isUndone) "[UNDONE] ${item.summary}" else item.summary,
                        color = if (item.isUndone) TextMuted else TextSecondary,
                        fontSize = 10.sp
                    )
                }
            }

            if (item.canUndo && !item.isUndone) {
                Button(
                    onClick = onUndo,
                    colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = NeonAmber),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Undo", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BackgroundScannerStatusCard(
    scanState: AudioScanState,
    onPauseScan: () -> Unit,
    onResumeScan: () -> Unit,
    onCancelScan: () -> Unit,
    onMountFolder: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (scanState.isScanning) DjSurfaceDark else DjSurfaceDark.copy(alpha = 0.85f)
        ),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (scanState.isScanning) DeckACyan.copy(alpha = 0.7f) else if (scanState.isPaused) NeonAmber.copy(alpha = 0.7f) else DjSurfaceBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (scanState.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = DeckACyan,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = null,
                            tint = if (scanState.isPaused) NeonAmber else DeckACyan,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    Text(
                        text = "DOCUMENTFILE BACKGROUND INDEXER",
                        color = if (scanState.isScanning) DeckACyan else if (scanState.isPaused) NeonAmber else TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }

                Surface(
                    color = when {
                        scanState.isScanning -> DeckACyan.copy(alpha = 0.15f)
                        scanState.isPaused -> NeonAmber.copy(alpha = 0.15f)
                        scanState.isCompleted -> NeonGreen.copy(alpha = 0.15f)
                        else -> DjSurfaceElevated
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = when {
                            scanState.isScanning -> "RECURSIVE SCAN ACTIVE"
                            scanState.isPaused -> "PAUSED"
                            scanState.isCompleted -> "COMPLETED"
                            else -> "IDLE / READY"
                        },
                        color = when {
                            scanState.isScanning -> DeckACyan
                            scanState.isPaused -> NeonAmber
                            scanState.isCompleted -> NeonGreen
                            else -> TextSecondary
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (scanState.isScanning || scanState.isPaused) {
                Text(
                    text = "Directory: ${scanState.currentDirectory}",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )

                Text(
                    text = "Processing: ${scanState.currentFile}",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )

                LinearProgressIndicator(
                    progress = { if (scanState.filesDiscovered > 0) scanState.progressFraction else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = DeckACyan,
                    trackColor = DjSurfaceElevated
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Indexed: ${scanState.filesIndexed} / ${scanState.filesDiscovered} tracks",
                        color = TextPrimary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Text(
                        text = "${scanState.scanSpeedFilesPerSec} files/s • ${scanState.directoriesScanned} dirs",
                        color = NeonGreen,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (scanState.isPaused) {
                        Button(
                            onClick = onResumeScan,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonAmber, contentColor = DjObsidian),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume Scan", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onPauseScan,
                            colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = NeonAmber),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause Scan", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = onCancelScan,
                        colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = NeonRed),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f).height(32.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Cancel", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop / Cancel", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Text(
                    text = "Android DocumentFile background service recursively traverses music directory trees, parses ID3 & Vorbis acoustic metadata, and indexes tracks into Room DB.",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )

                if (scanState.isCompleted && scanState.totalIndexedInLastRun > 0) {
                    Surface(
                        color = NeonGreen.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "Last scan indexed ${scanState.totalIndexedInLastRun} audio tracks in ${(scanState.elapsedTimeMs / 1000)}s.",
                            color = NeonGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Button(
                    onClick = onMountFolder,
                    colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = DeckACyan),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().height(32.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Select Directory for Recursive SAF Scan", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PlatformStatusCard(
    status: com.example.sync.PlatformConnectionStatus,
    onToggle: () -> Unit,
    onOpenDrive: () -> Unit = {},
    onConnectDrive: () -> Unit = {},
    onDisconnectDrive: () -> Unit = {}
) {
    val isDrive = status.platform == MusicPlatform.GOOGLE_DRIVE

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("platform_card_${status.platform.name.lowercase()}"),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isDrive && status.isConnected) Color(0xFF4285F4).copy(alpha = 0.5f) else DjSurfaceBorder
        )
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            .size(32.dp)
                            .background(
                                when (status.platform) {
                                    MusicPlatform.GOOGLE_DRIVE -> Color(0xFF4285F4).copy(alpha = 0.2f)
                                    MusicPlatform.SPOTIFY -> Color(0xFF1DB954).copy(alpha = 0.2f)
                                    MusicPlatform.SOUNDCLOUD -> Color(0xFFFF5500).copy(alpha = 0.2f)
                                    MusicPlatform.LOCAL -> DeckACyan.copy(alpha = 0.2f)
                                },
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (status.platform) {
                                MusicPlatform.GOOGLE_DRIVE -> Icons.Default.Cloud
                                MusicPlatform.SPOTIFY -> Icons.Default.LibraryMusic
                                MusicPlatform.SOUNDCLOUD -> Icons.Default.CloudQueue
                                MusicPlatform.LOCAL -> Icons.Default.Folder
                            },
                            contentDescription = null,
                            tint = when (status.platform) {
                                MusicPlatform.GOOGLE_DRIVE -> Color(0xFF4285F4)
                                MusicPlatform.SPOTIFY -> Color(0xFF1DB954)
                                MusicPlatform.SOUNDCLOUD -> Color(0xFFFF5500)
                                MusicPlatform.LOCAL -> DeckACyan
                            },
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = if (isDrive) {
                                if (status.isConnected) "Google Drive — Connected" else "Google Drive — Not Connected"
                            } else {
                                status.platform.displayName
                            },
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = status.accountName,
                            color = if (status.isConnected) NeonGreen else TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }

                if (!isDrive) {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onToggle),
                        shape = RoundedCornerShape(6.dp),
                        color = if (status.isConnected) NeonGreen.copy(alpha = 0.2f) else DjSurfaceElevated,
                        border = if (status.isConnected) androidx.compose.foundation.BorderStroke(1.dp, NeonGreen) else null
                    ) {
                        Text(
                            text = if (status.isConnected) "Connected (${status.syncedTracksCount})" else "Connect",
                            color = if (status.isConnected) NeonGreen else TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    if (status.isConnected) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = NeonGreen.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = "${status.syncedTracksCount} synced",
                                color = NeonGreen,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Button(
                            onClick = onConnectDrive,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4), contentColor = Color.White),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp).testTag("connect_drive_button")
                        ) {
                            Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Connect", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (isDrive && status.isConnected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenDrive,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4), contentColor = Color.White),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f).height(30.dp).testTag("browse_google_drive_button")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Browse Google Drive", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onDisconnectDrive,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonRed),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, NeonRed.copy(alpha = 0.5f)),
                        modifier = Modifier.height(30.dp).testTag("disconnect_google_drive_button")
                    ) {
                        Text("Disconnect", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
