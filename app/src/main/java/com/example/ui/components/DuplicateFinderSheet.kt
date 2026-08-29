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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioQualityRating
import com.example.model.DuplicateMatch
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
import com.example.ui.theme.NeonRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun DuplicateFinderSheet(
    duplicateMatches: List<DuplicateMatch>,
    onResolveKeepBest: (DuplicateMatch) -> Unit,
    onInspectSpectrogram: (Track) -> Unit,
    onLoadToDeck: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Banner with AI Matching Summary
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("duplicate_summary_banner"),
            shape = RoundedCornerShape(12.dp),
            color = DjSurfaceCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, tint = DeckBPink, modifier = Modifier.size(20.dp))
                        Text(
                            text = "FUZZY DUPLICATE DETECTOR",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (duplicateMatches.isEmpty()) "Your DJ library is 100% clean of duplicates." else "${duplicateMatches.size} fuzzy candidate pairs found across platforms.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                if (duplicateMatches.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .background(DeckBPink.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .border(1.dp, DeckBPink, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${duplicateMatches.size} TO RESOLVE",
                            color = DeckBPink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        if (duplicateMatches.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(48.dp))
                    Text("No Duplicates Found!", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("All tracks have unique acoustic fingerprints and metadata.", color = TextSecondary, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(duplicateMatches) { match ->
                    DuplicatePairCard(
                        match = match,
                        onResolveKeepBest = { onResolveKeepBest(match) },
                        onInspectSpectrogram = onInspectSpectrogram,
                        onLoadToDeck = onLoadToDeck
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplicatePairCard(
    match: DuplicateMatch,
    onResolveKeepBest: () -> Unit,
    onInspectSpectrogram: (Track) -> Unit,
    onLoadToDeck: (Track) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("duplicate_pair_card_${match.trackA.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, DeckBPink.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Match Score Header & Fuzzy Detection Reason
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .background(DeckBPink, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${match.similarityScore}% SIMILARITY",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "Fuzzy Title Match",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Text(
                text = match.reason,
                color = TextPrimary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            // Side-by-side comparison boxes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrackComparisonBox(
                    label = "TRACK A",
                    track = match.trackA,
                    modifier = Modifier.weight(1f),
                    onInspect = { onInspectSpectrogram(match.trackA) },
                    onPlay = { onLoadToDeck(match.trackA) }
                )

                TrackComparisonBox(
                    label = "TRACK B",
                    track = match.trackB,
                    modifier = Modifier.weight(1f),
                    onInspect = { onInspectSpectrogram(match.trackB) },
                    onPlay = { onLoadToDeck(match.trackB) }
                )
            }

            // Recommendation Bar & Smart Resolve Button
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DjSurfaceCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(16.dp))
                        Text(
                            text = "SMART RESOLUTION",
                            color = NeonGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Text(
                        text = match.recommendedAction,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Button(
                        onClick = onResolveKeepBest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .testTag("resolve_best_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DjObsidian),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("AUTO RESOLVE (KEEP BEST QUALITY)", fontWeight = FontWeight.Black, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackComparisonBox(
    label: String,
    track: Track,
    onInspect: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFake = track.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED
    val isLossless = track.qualityRating.isLossless

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = DjSurfaceCard,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isFake) NeonRed else if (isLossless) NeonGreen else DjSurfaceBorder
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Black)
                QualityPill(track.qualityRating)
            }

            Text(
                text = track.title,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2
            )
            Text(
                text = track.artist,
                color = TextSecondary,
                fontSize = 10.sp,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Specs
            Text(
                text = "${track.format} • ${track.bitrateKbps} kbps • ${track.fileSizeMb} MB",
                color = if (isFake) NeonRed else DeckACyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "${track.musicalKey} • ${track.bpm} BPM",
                color = TextSecondary,
                fontSize = 10.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier.size(28.dp).background(DjSurfaceElevated, RoundedCornerShape(4.dp))
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = DeckACyan, modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = onInspect,
                    modifier = Modifier.size(28.dp).background(DjSurfaceElevated, RoundedCornerShape(4.dp))
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = "Inspect Spectrogram", tint = DeckBPink, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
