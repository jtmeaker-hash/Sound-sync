package com.example.ui.djtools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.TrackAudioMetrics
import com.example.analysis.TrackAudioMetricsService
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

/**
 * DJ Tools - Dynamic Range Meter (TT DR Meter / Crest Factor).
 * Decodes PCM audio away from the audio thread, measures peak vs top-20% RMS distribution,
 * and caches results per track for instant retrieval.
 */
@Composable
fun DynamicRangeMeterTool(
    selectedTrack: Track?,
    allTracks: List<Track> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val trackToAnalyze = selectedTrack ?: allTracks.firstOrNull()

    var metrics by remember { mutableStateOf<TrackAudioMetrics?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }

    LaunchedEffect(trackToAnalyze?.id) {
        if (trackToAnalyze != null) {
            val cached = TrackAudioMetricsService.getCached(trackToAnalyze.id)
            if (cached != null) {
                metrics = cached
            } else {
                isAnalyzing = true
                metrics = TrackAudioMetricsService.analyzeTrack(context, trackToAnalyze)
                isAnalyzing = false
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DYNAMIC RANGE METER",
                        color = DeckACyan,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Standard TT DR Crest Factor & Loudness Analysis",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DeckACyan.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan)
                ) {
                    Text(
                        text = "OFFICIAL DR",
                        color = DeckACyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (trackToAnalyze == null) {
                Text("No track available to analyze.", color = TextMuted, fontSize = 11.sp)
            } else if (isAnalyzing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = DeckACyan, strokeWidth = 2.5.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Analyzing PCM Audio...", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Computing block RMS and peak-to-loudness delta", color = TextSecondary, fontSize = 10.sp)
                    }
                }
            } else if (metrics != null) {
                val m = metrics!!
                val drColor = when {
                    m.dynamicRangeScore <= 6 -> NeonRed
                    m.dynamicRangeScore in 7..9 -> NeonAmber
                    m.dynamicRangeScore in 10..12 -> DeckACyan
                    else -> NeonGreen
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DjObsidian, RoundedCornerShape(10.dp))
                        .border(1.dp, DjSurfaceBorder, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(trackToAnalyze.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // DR Badge
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = drColor.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(2.dp, drColor)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text("DR SCORE", color = drColor, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                Text(
                                    text = "DR ${m.dynamicRangeScore}",
                                    color = drColor,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Summary readings
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Peak Level:", color = TextMuted, fontSize = 11.sp)
                                Text(
                                    String.format(Locale.US, "%.1f dBFS", m.peakDb),
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("RMS Level:", color = TextMuted, fontSize = 11.sp)
                                Text(
                                    String.format(Locale.US, "%.1f dBFS", m.rmsDb),
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Crest Factor:", color = TextMuted, fontSize = 11.sp)
                                Text(
                                    String.format(Locale.US, "%.1f dB", m.crestFactorDb),
                                    color = NeonAmber,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Assessment description
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = DjSurfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = m.dynamicRangeDescription,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}
