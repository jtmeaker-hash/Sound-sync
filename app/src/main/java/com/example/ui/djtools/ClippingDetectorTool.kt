package com.example.ui.djtools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.example.audio.DjAudioEngine
import com.example.model.Track
import com.example.ui.theme.DeckACyan
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
 * DJ Tools - Digital Audio Clipping Detector.
 * Analyzes PCM audio sample peaks to detect samples reaching 0 dBFS digital saturation.
 * Distinguishes loudness/high RMS from true digital clipping.
 */
@Composable
fun ClippingDetectorTool(
    audioEngine: DjAudioEngine,
    selectedTrack: Track?,
    allTracks: List<Track> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isPlaying by audioEngine.isPlaying.collectAsState()
    val playingTrack by audioEngine.currentTrack.collectAsState()
    val liveClippingDetected by audioEngine.liveClippingDetected.collectAsState()
    val liveClippedCount by audioEngine.liveClippedSampleCount.collectAsState()
    val livePeakDb by audioEngine.livePeakDb.collectAsState()

    var activeTab by remember { mutableStateOf("LIVE") }
    var fileMetrics by remember { mutableStateOf<TrackAudioMetrics?>(null) }
    var isAnalyzingFile by remember { mutableStateOf(false) }

    val trackToAnalyze = selectedTrack ?: playingTrack ?: allTracks.firstOrNull()

    LaunchedEffect(activeTab, trackToAnalyze?.id) {
        if (activeTab == "FILE" && trackToAnalyze != null) {
            val cached = TrackAudioMetricsService.getCached(trackToAnalyze.id)
            if (cached != null) {
                fileMetrics = cached
            } else {
                isAnalyzingFile = true
                fileMetrics = TrackAudioMetricsService.analyzeTrack(context, trackToAnalyze)
                isAnalyzingFile = false
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
                        text = "CLIPPING DETECTOR",
                        color = DeckACyan,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Real PCM peak analysis at 0 dBFS ceiling",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }

                if (activeTab == "LIVE") {
                    OutlinedButton(
                        onClick = { audioEngine.resetClippingDetector() },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(12.dp), tint = TextSecondary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset", fontSize = 10.sp, color = TextSecondary)
                    }
                }
            }

            // Mode Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { activeTab = "LIVE" },
                    color = if (activeTab == "LIVE") DeckACyan.copy(alpha = 0.2f) else DjSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (activeTab == "LIVE") DeckACyan else DjSurfaceBorder),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "LIVE OUTPUT",
                        color = if (activeTab == "LIVE") DeckACyan else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { activeTab = "FILE" },
                    color = if (activeTab == "FILE") DeckACyan.copy(alpha = 0.2f) else DjSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (activeTab == "FILE") DeckACyan else DjSurfaceBorder),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "TRACK ANALYSIS",
                        color = if (activeTab == "FILE") DeckACyan else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            if (activeTab == "LIVE") {
                val hasClipped = liveClippingDetected || liveClippedCount > 0
                val statusColor = if (hasClipped) NeonRed else NeonGreen

                // Status Banner
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, statusColor)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (hasClipped) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = if (hasClipped) "Clipping detected!" else "No clipping detected",
                                color = statusColor,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (hasClipped) "$liveClippedCount samples reached digital 0 dBFS limit" else "Audio peak is within safe digital headroom",
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // Details Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DjObsidian, RoundedCornerShape(8.dp))
                        .border(1.dp, DjSurfaceBorder, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Current Peak Level", color = TextSecondary, fontSize = 11.sp)
                        Text(
                            text = String.format(Locale.US, "%.2f dBFS", livePeakDb),
                            color = if (livePeakDb >= -0.05f) NeonRed else DeckACyan,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Clipped Sample Count", color = TextSecondary, fontSize = 11.sp)
                        Text(
                            text = "$liveClippedCount",
                            color = if (hasClipped) NeonRed else NeonGreen,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Digital Headroom", color = TextSecondary, fontSize = 11.sp)
                        Text(
                            text = if (livePeakDb >= 0f) "0.0 dB (None)" else String.format(Locale.US, "%.1f dB", -livePeakDb),
                            color = if (livePeakDb >= -0.1f) NeonRed else NeonGreen,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                // File Analysis Mode
                if (trackToAnalyze == null) {
                    Text("No track selected.", color = TextMuted, fontSize = 11.sp)
                } else if (isAnalyzingFile) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = DeckACyan, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scanning PCM samples for 0 dBFS digital saturation...", color = TextSecondary, fontSize = 11.sp)
                    }
                } else if (fileMetrics != null) {
                    val m = fileMetrics!!
                    val statusColor = if (m.isClipping) NeonRed else NeonGreen

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, statusColor)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (m.isClipping) Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = if (m.isClipping) "Clipping detected in file" else "No clipping detected",
                                    color = statusColor,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = if (m.isClipping) "${m.clippedSampleCount} clipped samples (${String.format(Locale.US, "%.4f", m.clippingPercentage)}%)" else "Audio preserves full linear dynamic headroom",
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DjObsidian, RoundedCornerShape(8.dp))
                            .border(1.dp, DjSurfaceBorder, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Track Title", color = TextSecondary, fontSize = 10.sp)
                            Text(trackToAnalyze.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Peak Amplitude", color = TextSecondary, fontSize = 10.sp)
                            Text(String.format(Locale.US, "%.4f (%.2f dBFS)", m.peakAmplitude, m.peakDb), color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Samples Analyzed", color = TextSecondary, fontSize = 10.sp)
                            Text("${m.totalSamplesAnalyzed}", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
            }

            // Explanatory note
            Text(
                text = "Note: Heavy limiter mastering can yield high RMS without clipping. True clipping only triggers when sample peaks meet or exceed 0 dBFS full scale.",
                color = TextMuted,
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
        }
    }
}
