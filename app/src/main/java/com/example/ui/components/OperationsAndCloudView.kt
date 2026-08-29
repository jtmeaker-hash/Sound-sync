package com.example.ui.components

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FileOperationType
import com.example.model.MusicPlatform
import com.example.model.OperationJournalItem
import com.example.model.StorageSource
import com.example.model.StorageSourceType
import com.example.sync.CloudSyncManager
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OperationsAndCloudView(
    storageSources: List<StorageSource>,
    operationJournal: List<OperationJournalItem>,
    onTriggerSync: () -> Unit,
    onExportRekordbox: () -> Unit,
    onUndoOperation: (String) -> Unit,
    onMountSaf: () -> Unit,
    modifier: Modifier = Modifier
) {
    val platformStatuses by CloudSyncManager.platformStatuses.collectAsState()
    val quota by CloudSyncManager.storageQuota.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .padding(12.dp)
            .testTag("operations_and_cloud_view"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Storage Sources Section
        item {
            Text(
                text = "STORAGE SOURCES & SAF MOUNT POINTS",
                color = DeckACyan,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
        }

        items(storageSources) { source ->
            StorageSourceCard(source = source)
        }

        // Operation History Journal with Undo
        item {
            Spacer(modifier = Modifier.height(4.dp))
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

        items(operationJournal) { item ->
            OperationJournalCard(
                item = item,
                onUndo = { onUndoOperation(item.id) }
            )
        }

        // DJ Hardware Export & Serato / Rekordbox
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "DJ CRATE & HARDWARE EXPORTS",
                color = DeckACyan,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Export Verified Keys & Hot Cues", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Export full Camelot keys, BPM grids, and cue metadata formatted for Pioneer Rekordbox XML, Engine DJ, or Serato Crate files.", color = TextSecondary, fontSize = 10.sp)
                    Button(
                        onClick = onExportRekordbox,
                        colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export Rekordbox XML & Serato Crate", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }

        // Cloud Platforms Sync
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CLOUD PLATFORMS SYNC",
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
                onToggle = { CloudSyncManager.togglePlatformConnection(status.platform) }
            )
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
    val usedRatio = (usedGb / source.totalSpaceGb).toFloat().coerceIn(0f, 1f)

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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(18.dp))
                    Column {
                        Text(source.label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(source.path, color = TextSecondary, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
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

            // Storage bar
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
        FileOperationType.MOVE -> Icons.Default.DriveFileMove
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
                    Icon(Icons.Default.Undo, contentDescription = "Undo", modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Undo", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PlatformStatusCard(
    status: com.example.sync.PlatformConnectionStatus,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(status.platform.displayName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(status.accountName, color = TextSecondary, fontSize = 10.sp)
            }

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
        }
    }
}
