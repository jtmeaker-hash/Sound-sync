package com.example.ui.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.AppDatabase
import com.example.data.MetadataReviewItemEntity
import com.example.metadata.apple.AppleTrackResult
import com.example.metadata.review.MetadataReviewManager
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataReviewInboxScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }
    val reviewManager = remember { MetadataReviewManager(context, database) }

    val pendingItems by reviewManager.observePendingItems().collectAsState(initial = emptyList())
    val modifiedTracksCount by reviewManager.observeModifiedTracksCount().collectAsState(initial = 0)

    val selectedIds = remember { mutableStateListOf<String>() }
    var showRestoreDialog by remember { mutableStateOf(false) }

    val verifiedCount = pendingItems.count { it.matchStatus == "VERIFIED" || it.confidenceScore >= 95.0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MD Approval Tool", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = "${pendingItems.size} pending (${verifiedCount} verified)",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    // Restore Metadata Changes Button (Section 10)
                    IconButton(onClick = { showRestoreDialog = true }) {
                        Icon(Icons.Default.Restore, contentDescription = "Restore Metadata Changes", tint = DeckACyan)
                    }

                    // Section 7: Approve All Verified (ONLY applies verified matches)
                    if (verifiedCount > 0) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val count = reviewManager.approveAllVerified()
                                    Toast.makeText(context, "Applied $count verified matches", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.Verified, contentDescription = null, tint = DjObsidian, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Approve Verified ($verifiedCount)", color = DjObsidian, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DjObsidian)
            )
        },
        bottomBar = {
            if (pendingItems.isNotEmpty()) {
                Surface(
                    color = DjSurfaceDark,
                    tonalElevation = 6.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedIds.isNotEmpty() && selectedIds.size == pendingItems.size,
                                onCheckedChange = { checkAll ->
                                    selectedIds.clear()
                                    if (checkAll) {
                                        selectedIds.addAll(pendingItems.map { it.id })
                                    }
                                }
                            )
                            Text(
                                text = if (selectedIds.isEmpty()) "Select All" else "${selectedIds.size} Selected",
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                        }

                        if (selectedIds.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            val count = reviewManager.rejectMultiple(selectedIds.toList())
                                            selectedIds.clear()
                                            Toast.makeText(context, "Rejected $count items", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonRed),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Reject Selected", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            val count = reviewManager.approveMultiple(selectedIds.toList())
                                            selectedIds.clear()
                                            Toast.makeText(context, "Approved $count items", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Approve Selected", color = DjObsidian, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = DjObsidian
    ) { padding ->
        if (pendingItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Inbox is Clear!", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("No proposed metadata changes requiring manual review.", color = TextMuted, fontSize = 13.sp)
                    if (modifiedTracksCount > 0) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { showRestoreDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckACyan)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Restore Metadata Changes ($modifiedTracksCount tracks)")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pendingItems, key = { it.id }) { item ->
                    val isSelected = selectedIds.contains(item.id)
                    SafeReviewItemCard(
                        item = item,
                        isSelected = isSelected,
                        onToggleSelect = {
                            if (isSelected) selectedIds.remove(item.id) else selectedIds.add(item.id)
                        },
                        onApprove = {
                            coroutineScope.launch {
                                reviewManager.acceptAllProposed(item.id)
                            }
                        },
                        onReject = {
                            coroutineScope.launch {
                                reviewManager.rejectProposal(item.id)
                            }
                        },
                        onRestore = {
                            coroutineScope.launch {
                                reviewManager.restoreTrack(item.trackId)
                                Toast.makeText(context, "Track restored to original baseline", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }

        // Restore Dialog (Section 10)
        if (showRestoreDialog) {
            AlertDialog(
                onDismissRequest = { showRestoreDialog = false },
                title = { Text("Restore Metadata Changes", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Revert audio files and library entries back to their original state before SoundSync modified them. Restoration data is persistent across app restarts and rescans.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        if (selectedIds.isNotEmpty()) {
                            Text("• Selected tracks for restore: ${selectedIds.size}", color = DeckACyan, fontSize = 12.sp)
                        }
                        Text("• Total modified tracks on record: $modifiedTracksCount", color = TextMuted, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val count = reviewManager.restoreAll()
                                showRestoreDialog = false
                                Toast.makeText(context, "Restored $count tracks to original tags", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonAmber)
                    ) {
                        Text("Restore All Changes", color = DjObsidian, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    if (selectedIds.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    val count = reviewManager.restoreMultiple(selectedIds.toList())
                                    selectedIds.clear()
                                    showRestoreDialog = false
                                    Toast.makeText(context, "Restored $count selected tracks", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("Restore Selected", color = DeckACyan)
                        }
                    } else {
                        TextButton(onClick = { showRestoreDialog = false }) {
                            Text("Cancel", color = TextMuted)
                        }
                    }
                },
                containerColor = DjSurfaceDark
            )
        }
    }
}

@Composable
private fun SafeReviewItemCard(
    item: MetadataReviewItemEntity,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRestore: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) DeckACyan else DjSurfaceBorder
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header Row: Selection, Status Badge, Provider, Confidence
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.size(24.dp)
                    )

                    // Status Badge (Section 16)
                    val (badgeText, badgeColor) = when (item.matchStatus) {
                        "VERIFIED" -> "VERIFIED" to NeonGreen
                        "CONFLICTING_RESULTS" -> "MULTIPLE MATCHES" to DeckACyan
                        "REJECTED" -> "REJECTED" to NeonRed
                        else -> "REVIEW REQUIRED" to NeonAmber
                    }

                    Surface(
                        color = badgeColor.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text("${item.confidenceScore.toInt()}%", color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = item.provider,
                    color = TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }

            // Side-by-Side Artwork Preview (Section 12)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkThumbnail(label = "Current Art", pathOrUrl = item.originalArtworkUrl)
                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                ArtworkThumbnail(label = "Proposed Art", pathOrUrl = item.artworkCachePath ?: item.proposedArtworkUrl)
            }

            // Side-by-Side Metadata Comparison (Section 6: Highlight only fields that actually change)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DjSurfaceDark.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HighlightableComparisonRow("Title", item.originalTitle, item.proposedTitle)
                HighlightableComparisonRow("Artist", item.originalArtist, item.proposedArtist)
                HighlightableComparisonRow("Album", item.originalAlbum, item.proposedAlbum)
                if (item.proposedYear != null) {
                    HighlightableComparisonRow("Year", "—", item.proposedYear.toString())
                }
                if (item.proposedGenre != null) {
                    HighlightableComparisonRow("Genre", "—", item.proposedGenre)
                }
                if (item.proposedTrackNumber != null && item.proposedTrackNumber > 0) {
                    HighlightableComparisonRow("Track #", "—", item.proposedTrackNumber.toString())
                }
            }

            // Multiple Matches Candidate Warning / Info (Section 13)
            if (item.matchStatus == "CONFLICTING_RESULTS" && !item.candidatesJson.isNullOrBlank()) {
                val candidateList = remember(item.candidatesJson) {
                    AppleTrackResult.listFromJson(item.candidatesJson)
                }
                if (candidateList.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Multiple matches found (choose manually):", color = DeckACyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        candidateList.take(3).forEach { cand ->
                            Surface(
                                color = DjSurfaceDark,
                                shape = RoundedCornerShape(4.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(cand.trackName, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Text("${cand.artistName} • ${cand.collectionName ?: "Single"} (${cand.durationSeconds}s)", color = TextMuted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (item.evidenceSummary.isNotBlank()) {
                Text(
                    text = item.evidenceSummary,
                    color = TextMuted,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Action Buttons: Reject vs Approve (Section 7)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onReject,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonRed),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Reject", fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Text("Approve", color = DjObsidian, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ArtworkThumbnail(label: String, pathOrUrl: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DjSurfaceDark)
                .border(1.dp, DjSurfaceBorder, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (!pathOrUrl.isNullOrBlank()) {
                AsyncImage(
                    model = if (pathOrUrl.startsWith("/")) File(pathOrUrl) else pathOrUrl,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextMuted, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextMuted, fontSize = 9.sp)
    }
}

@Composable
private fun HighlightableComparisonRow(label: String, current: String, proposed: String) {
    val isChanged = !current.equals(proposed, ignoreCase = true) && proposed.isNotBlank() && proposed != "—"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.width(55.dp))
        Text(
            text = current.ifBlank { "(empty)" },
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            Icons.Default.ArrowForward,
            contentDescription = null,
            tint = if (isChanged) DeckACyan else TextMuted,
            modifier = Modifier.size(10.dp).padding(horizontal = 2.dp)
        )
        Text(
            text = proposed,
            color = if (isChanged) DeckACyan else TextPrimary,
            fontWeight = if (isChanged) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

