package com.example.ui.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.backup.BackupFileInfo
import com.example.backup.BackupSummary
import com.example.backup.RestoreResult
import com.example.backup.SoundSyncBackupManager
import com.example.ui.theme.BloodRedPrimary
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.SoundSyncTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupSettingsScreen(
    backupManager: SoundSyncBackupManager,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme = SoundSyncTheme.current

    val summary by backupManager.summaryFlow.collectAsState()
    val isBackingUp by backupManager.isBackingUp.collectAsState()
    val isRestoring by backupManager.isRestoring.collectAsState()

    var availableBackups by remember { mutableStateOf<List<BackupFileInfo>>(emptyList()) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var selectedRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restoreResultMessage by remember { mutableStateOf<String?>(null) }

    fun refreshBackups() {
        availableBackups = backupManager.findAvailableBackups()
    }

    LaunchedEffect(Unit) {
        refreshBackups()
    }

    // SAF launchers
    val chooseFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                backupManager.setCustomBackupTreeUri(uri)
                Toast.makeText(context, "Backup directory updated", Toast.LENGTH_SHORT).show()
                refreshBackups()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to persist folder permission: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val result = backupManager.createBackup(targetUri = uri)
                if (result.isSuccess) {
                    Toast.makeText(context, "Backup exported successfully", Toast.LENGTH_SHORT).show()
                    refreshBackups()
                } else {
                    Toast.makeText(context, "Export failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val importJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedRestoreUri = uri
            showRestoreConfirmDialog = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = theme.surface),
            shape = RoundedCornerShape(theme.cornerMedium),
            border = BorderStroke(1.dp, theme.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = if (SoundSyncTheme.isPro) Color.White else DeckACyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Persistent Backup & Restore",
                        color = theme.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Text(
                    text = "SoundSync automatically archives your Song Finds, local track analysis (BPM, Camelot keys, beatgrids), and repaired artist metadata to persistent storage. If you uninstall or reinstall SoundSync, your data remains completely safe.",
                    color = theme.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = theme.surface),
            shape = RoundedCornerShape(theme.cornerMedium),
            border = BorderStroke(1.dp, theme.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "BACKUP STATUS",
                        color = theme.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Box(
                        modifier = Modifier
                            .background(
                                if (summary.lastBackupTimestamp != null) NeonGreen.copy(alpha = 0.15f)
                                else BloodRedPrimary.copy(alpha = 0.15f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (summary.lastBackupTimestamp != null) "READY" else "NO BACKUP",
                            color = if (summary.lastBackupTimestamp != null) NeonGreen else BloodRedPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                HorizontalDivider(color = theme.divider, thickness = 0.5.dp)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Tracks Analyzed", color = theme.textSecondary, fontSize = 11.sp)
                        Text("${summary.trackCount}", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Column {
                        Text("Song Finds", color = theme.textSecondary, fontSize = 11.sp)
                        Text("${summary.songFindCount}", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Column {
                        Text("Last Archived", color = theme.textSecondary, fontSize = 11.sp)
                        val lastDateStr = summary.lastBackupTimestamp?.let {
                            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
                        } ?: "Never"
                        Text(lastDateStr, color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                Text(
                    text = "Storage: ${summary.backupLocation}",
                    color = theme.textSecondary.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }

        // Automatic Backup Setting Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = theme.surface),
            shape = RoundedCornerShape(theme.cornerMedium),
            border = BorderStroke(1.dp, theme.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Automatic Background Backup",
                            color = theme.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Automatically saves a debounced backup 5s after tracks or Song Finds are modified.",
                            color = theme.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = summary.isAutoBackupEnabled,
                        onCheckedChange = { backupManager.setAutoBackupEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = if (SoundSyncTheme.isPro) Color.White else DeckACyan
                        )
                    )
                }
            }
        }

        // Actions Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = theme.surface),
            shape = RoundedCornerShape(theme.cornerMedium),
            border = BorderStroke(1.dp, theme.divider)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "BACKUP CONTROLS",
                    color = theme.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val result = backupManager.createBackup()
                                if (result.isSuccess) {
                                    Toast.makeText(context, "Backup created successfully", Toast.LENGTH_SHORT).show()
                                    refreshBackups()
                                } else {
                                    Toast.makeText(context, "Backup failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !isBackingUp && !isRestoring,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (SoundSyncTheme.isPro) Color.White else DeckACyan,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(theme.cornerSmall)
                    ) {
                        if (isBackingUp) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Back Up Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            selectedRestoreUri = null
                            showRestoreConfirmDialog = true
                        },
                        enabled = !isBackingUp && !isRestoring && summary.lastBackupTimestamp != null,
                        border = BorderStroke(1.dp, theme.divider),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.textPrimary),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(theme.cornerSmall)
                    ) {
                        if (isRestoring) {
                            CircularProgressIndicator(color = theme.textPrimary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restore Latest", fontSize = 12.sp)
                    }
                }

                HorizontalDivider(color = theme.divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                // Storage and Portable Export/Import options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val defaultName = "soundsync_backup_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())}.json"
                            exportJsonLauncher.launch(defaultName)
                        },
                        border = BorderStroke(1.dp, theme.divider),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.textPrimary),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(theme.cornerSmall)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export JSON", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            importJsonLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        border = BorderStroke(1.dp, theme.divider),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.textPrimary),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(theme.cornerSmall)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import JSON", fontSize = 11.sp)
                    }
                }

                OutlinedButton(
                    onClick = { chooseFolderLauncher.launch(null) },
                    border = BorderStroke(1.dp, theme.divider),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.textPrimary),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(theme.cornerSmall)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Select Persistent Storage Folder (SAF)", fontSize = 11.sp)
                }
            }
        }

        // Available Backups Found on Device
        if (availableBackups.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = theme.surface),
                shape = RoundedCornerShape(theme.cornerMedium),
                border = BorderStroke(1.dp, theme.divider)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AVAILABLE BACKUP FILES",
                            color = theme.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text("${availableBackups.size} files", color = theme.textSecondary, fontSize = 10.sp)
                    }

                    availableBackups.forEach { backupFile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(theme.surfaceElevated, RoundedCornerShape(theme.cornerSmall))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = backupFile.displayName,
                                    color = theme.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                                val dateStr = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(backupFile.lastModified))
                                val sizeKb = (backupFile.sizeBytes / 1024).coerceAtLeast(1)
                                Text(
                                    text = "$dateStr • ${sizeKb} KB",
                                    color = theme.textSecondary,
                                    fontSize = 10.sp
                                )
                            }
                            Button(
                                onClick = {
                                    selectedRestoreUri = backupFile.uri
                                    showRestoreConfirmDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (SoundSyncTheme.isPro) Color.White.copy(alpha = 0.15f) else DeckACyan.copy(alpha = 0.15f),
                                    contentColor = if (SoundSyncTheme.isPro) Color.White else DeckACyan
                                ),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("Restore", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation Dialog
    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = {
                Text("Restore SoundSync Backup", color = theme.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "This will restore your Song Finds and merge verified metadata, BPMs, Camelot keys, and cue points into your library. Existing music files and playlists are preserved non-destructively.",
                    color = theme.textSecondary,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirmDialog = false
                        scope.launch {
                            val result = backupManager.restoreBackup(selectedRestoreUri)
                            when (result) {
                                is RestoreResult.Success -> {
                                    restoreResultMessage = result.message
                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                }
                                is RestoreResult.Error -> {
                                    restoreResultMessage = "Error: ${result.message}"
                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (SoundSyncTheme.isPro) Color.White else DeckACyan,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Restore", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = false }) {
                    Text("Cancel", color = theme.textSecondary)
                }
            },
            containerColor = theme.surface,
            shape = RoundedCornerShape(theme.cornerMedium)
        )
    }

    // Result notification dialog
    if (restoreResultMessage != null) {
        AlertDialog(
            onDismissRequest = { restoreResultMessage = null },
            title = {
                Text("Restore Completed", color = theme.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(restoreResultMessage ?: "", color = theme.textSecondary, fontSize = 12.sp)
            },
            confirmButton = {
                Button(
                    onClick = { restoreResultMessage = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (SoundSyncTheme.isPro) Color.White else DeckACyan,
                        contentColor = Color.Black
                    )
                ) {
                    Text("OK")
                }
            },
            containerColor = theme.surface,
            shape = RoundedCornerShape(theme.cornerMedium)
        )
    }
}
