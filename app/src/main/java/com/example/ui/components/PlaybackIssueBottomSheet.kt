package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PlayabilityDiagnosticReport
import com.example.model.PlayabilityStatus
import com.example.model.Track
import com.example.ui.MainDjViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackIssueBottomSheet(
    track: Track,
    viewModel: MainDjViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val diagnosticReport by viewModel.activeDiagnosticReport.collectAsState()
    val isDiagnosing by viewModel.isDiagnosing.collectAsState()
    var isRepairing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }
            viewModel.manualLocateFileForTrack(track, uri.toString()) { success ->
                if (success) {
                    onDismiss()
                }
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onRemovableStorageFolderGranted(uri, track)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Playback Issue",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Playback Issue Detected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status Card
            val currentStatus = diagnosticReport?.status ?: track.playability
            val statusColor = when (currentStatus) {
                PlayabilityStatus.PLAYABLE, PlayabilityStatus.REPAIRED -> Color(0xFF4CAF50)
                PlayabilityStatus.MISSING_FILE, PlayabilityStatus.STALE_URI, PlayabilityStatus.MEDIASTORE_MISMATCH, PlayabilityStatus.VOLUME_UNAVAILABLE, PlayabilityStatus.SOURCE_STALE, PlayabilityStatus.RELOCATED -> MaterialTheme.colorScheme.error
                PlayabilityStatus.PERMISSION_DENIED, PlayabilityStatus.PERMISSION_REQUIRED -> Color(0xFFFF9800)
                PlayabilityStatus.UNSUPPORTED_FORMAT -> Color(0xFFE91E63)
                PlayabilityStatus.DECODER_ERROR, PlayabilityStatus.CORRUPTED_FILE, PlayabilityStatus.INVALID_CONTAINER, PlayabilityStatus.READ_ERROR -> MaterialTheme.colorScheme.error
                else -> Color(0xFFFFB300)
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = statusColor.copy(alpha = 0.12f),
                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currentStatus.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (isDiagnosing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = statusColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = diagnosticReport?.problemDescription
                            ?: track.playbackErrorMessage
                            ?: "This audio file could not be read or decoded by the media engine.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Diagnostic Technical Details Accordion
            Text(
                text = "DIAGNOSTIC DETAILS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    DiagnosticDetailRow("Track Artist", track.artist)
                    DiagnosticDetailRow("Track Album", track.album.ifBlank { "—" })
                    DiagnosticDetailRow("Stored Path", track.filePath, isMonospace = true)
                    diagnosticReport?.resolvedPath?.let {
                        if (it != track.filePath) {
                            DiagnosticDetailRow("Resolved URI", it, isMonospace = true)
                        }
                    }
                    diagnosticReport?.containerMime?.let {
                        DiagnosticDetailRow("Detected MIME", it)
                    }
                    diagnosticReport?.audioCodec?.let {
                        DiagnosticDetailRow("Audio Codec", it)
                    }
                    diagnosticReport?.sampleRate?.let {
                        if (it > 0) DiagnosticDetailRow("Sample Rate", "$it Hz (${diagnosticReport?.channelCount ?: 2} ch)")
                    }
                    diagnosticReport?.errorCode?.let {
                        DiagnosticDetailRow("Error Code", it)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            Text(
                text = "REPAIR & RECOVERY OPTIONS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Auto-repair button
            Button(
                onClick = {
                    isRepairing = true
                    viewModel.autoRepairTrack(track) { success ->
                        isRepairing = false
                        if (success) {
                            onDismiss()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRepairing && !isDiagnosing,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (isRepairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Attempting Auto-Repair...")
                } else {
                    Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Auto-Repair Track")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Manual locate file button
            OutlinedButton(
                onClick = {
                    filePickerLauncher.launch(arrayOf("audio/*", "*/*"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Locate Audio File")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Select containing folder for bulk SD card permission & recovery
            OutlinedButton(
                onClick = {
                    folderPickerLauncher.launch(null)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select Containing Folder (Bulk SD Recovery)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Re-validate and Remove actions
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.diagnoseTrack(track, forceFresh = true) },
                    modifier = Modifier.weight(1f),
                    enabled = !isDiagnosing
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Re-validate", maxLines = 1)
                }

                FilledTonalButton(
                    onClick = { viewModel.removeUnplayableTrackFromLibrary(track) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Remove", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticDetailRow(label: String, value: String, isMonospace: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
