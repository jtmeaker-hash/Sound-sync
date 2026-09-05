package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.intelligence.SoundSyncIntelligenceEngine
import com.example.model.Track
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun LibraryInsightsDialog(
    allTracks: List<Track>,
    onOpenSmartCrates: () -> Unit,
    onDismiss: () -> Unit
) {
    val report = remember(allTracks.size) {
        SoundSyncIntelligenceEngine.getLibraryHealthInsights(allTracks)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("library_insights_dialog"),
            colors = CardDefaults.cardColors(containerColor = DjObsidian),
            border = BorderStroke(1.dp, DjSurfaceBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(DeckACyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Insights, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text("Library Insights", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                            Text("${report.totalTracks} indexed tracks", fontSize = 11.sp, color = TextSecondary)
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Factual Health Cards Grid
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    InsightRow("Missing Artist Tag", "${report.tracksMissingArtist} tracks", report.tracksMissingArtist > 0)
                    InsightRow("Missing Artwork", "${report.tracksMissingArtwork} tracks", report.tracksMissingArtwork > 0)
                    InsightRow("Missing BPM / Key", "${report.tracksMissingBpmOrKey} tracks", report.tracksMissingBpmOrKey > 0)
                    InsightRow("Never Played", "${report.neverPlayedCount} tracks", false)
                    InsightRow("Unplayed in > 1 Year", "${report.unplayedOverOneYearCount} tracks", false)
                    if (report.suspiciousTranscodeCount > 0) {
                        InsightRow("Suspicious Lossy Transcodes", "${report.suspiciousTranscodeCount} tracks", true)
                    }
                    InsightRow("Audiophile Lossless Audio", "${report.losslessTracksCount} tracks", false)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Musical & BPM Patterns
                Text("MUSICAL CLUSTERS & DISTRIBUTION", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextMuted, letterSpacing = 0.8.sp)
                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DjSurfaceCard,
                    border = BorderStroke(1.dp, DjSurfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Primary BPM Cluster:", fontSize = 12.sp, color = TextSecondary)
                            Text(report.topBpmCluster, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DeckACyan)
                        }

                        if (report.topMusicalKeys.isNotEmpty()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Top Camelot Keys:", fontSize = 12.sp, color = TextSecondary)
                                Text(report.topMusicalKeys.take(3).joinToString(", ") { "${it.first} (${it.second})" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DeckBPink)
                            }
                        }

                        if (report.topGenres.isNotEmpty()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Top Genres:", fontSize = 12.sp, color = TextSecondary)
                                Text(report.topGenres.take(3).joinToString(", ") { it.first }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NeonGreen)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        onDismiss()
                        onOpenSmartCrates()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Explore Suggested Smart Crates", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun InsightRow(label: String, value: String, isWarning: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isWarning) NeonAmber.copy(alpha = 0.08f) else DjSurfaceCard,
        border = BorderStroke(0.5.dp, if (isWarning) NeonAmber.copy(alpha = 0.3f) else DjSurfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 12.sp, color = if (isWarning) NeonAmber else TextPrimary)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = if (isWarning) NeonAmber else DeckACyan)
        }
    }
}
