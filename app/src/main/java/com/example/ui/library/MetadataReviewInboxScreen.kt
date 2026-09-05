package com.example.ui.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppDatabase
import com.example.data.MetadataReviewItemEntity
import com.example.metadata.review.MetadataReviewManager
import com.example.ui.theme.*
import kotlinx.coroutines.launch

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Metadata Review Inbox", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    if (pendingItems.any { it.confidenceScore >= 80.0 }) {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    val count = reviewManager.bulkAcceptHighConfidence(80.0)
                                    Toast.makeText(context, "Accepted $count high-confidence items", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("Bulk Accept (80%+)", color = NeonGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DjObsidian)
            )
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
                    Text("No ambiguous metadata matches requiring manual review.", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pendingItems, key = { it.id }) { item ->
                    ReviewItemCard(
                        item = item,
                        onAcceptAll = {
                            coroutineScope.launch {
                                reviewManager.acceptAllProposed(item.id)
                            }
                        },
                        onReject = {
                            coroutineScope.launch {
                                reviewManager.rejectProposal(item.id)
                            }
                        },
                        onIgnore = {
                            coroutineScope.launch {
                                reviewManager.ignoreTrack(item.id)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewItemCard(
    item: MetadataReviewItemEntity,
    onAcceptAll: () -> Unit,
    onReject: () -> Unit,
    onIgnore: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header: Source and Confidence Score
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.provider, color = DeckACyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("•", color = TextMuted)
                    val confColor = if (item.confidenceScore >= 80) NeonGreen else if (item.confidenceScore >= 65) NeonAmber else NeonRed
                    Text("${item.confidenceScore.toInt()}% Match", color = confColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onIgnore) {
                    Text("Ignore", color = TextMuted, fontSize = 11.sp)
                }
            }

            // Comparison Rows
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ComparisonRow("Title", item.originalTitle, item.proposedTitle)
                ComparisonRow("Artist", item.originalArtist, item.proposedArtist)
                ComparisonRow("Album", item.originalAlbum, item.proposedAlbum)
                if (item.proposedGenre != null) {
                    ComparisonRow("Genre", "—", item.proposedGenre)
                }
                if (item.proposedYear != null) {
                    ComparisonRow("Year", "—", item.proposedYear.toString())
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

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onReject,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonRed)
                ) {
                    Text("Reject")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onAcceptAll,
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
                ) {
                    Text("Accept Match", color = DjObsidian, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ComparisonRow(label: String, current: String, proposed: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.width(50.dp))
        Text(
            text = current.ifBlank { "(empty)" },
            color = TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(Icons.Default.ArrowForward, contentDescription = null, tint = TextMuted, modifier = Modifier.size(12.dp).padding(horizontal = 4.dp))
        Text(
            text = proposed,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
