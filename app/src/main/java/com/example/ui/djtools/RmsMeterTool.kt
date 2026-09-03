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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
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
 * DJ Tools - Real RMS Audio Meter.
 * Computes root-mean-square loudness from actual PCM audio samples.
 * Supports monitoring live playing audio or analyzing selected tracks.
 */
@Composable
fun RmsMeterTool(
    audioEngine: DjAudioEngine,
    selectedTrack: Track?,
    allTracks: List<Track> = emptyList(),
    onSelectTrack: (Track) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isPlaying by audioEngine.isPlaying.collectAsState()
    val playingTrack by audioEngine.currentTrack.collectAsState()
    val liveRmsDb by audioEngine.liveRmsDb.collectAsState()
    val livePeakDb by audioEngine.livePeakDb.collectAsState()

    var activeTab by remember { mutableStateOf("LIVE") } // "LIVE" or "FILE"
    var fileAnalysisMetrics by remember { mutableStateOf<TrackAudioMetrics?>(null) }
    var isAnalyzingFile by remember { mutableStateOf(false) }

    val trackToAnalyze = selectedTrack ?: playingTrack ?: allTracks.firstOrNull()

    // Analyze selected track when on FILE tab
    LaunchedEffect(activeTab, trackToAnalyze?.id) {
        if (activeTab == "FILE" && trackToAnalyze != null) {
            val cached = TrackAudioMetricsService.getCached(trackToAnalyze.id)
            if (cached != null) {
                fileAnalysisMetrics = cached
            } else {
                isAnalyzingFile = true
                fileAnalysisMetrics = TrackAudioMetricsService.analyzeTrack(context, trackToAnalyze)
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
                        text = "RMS AUDIO METER",
                        color = DeckACyan,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "True PCM Root-Mean-Square signal energy (dBFS)",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isPlaying && activeTab == "LIVE") NeonGreen.copy(alpha = 0.2f) else DjSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isPlaying && activeTab == "LIVE") NeonGreen else DjSurfaceBorder)
                ) {
                    Text(
                        text = if (isPlaying && activeTab == "LIVE") "MONITORING" else "READY",
                        color = if (isPlaying && activeTab == "LIVE") NeonGreen else TextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Mode Selector: Live Audio vs File Analysis
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
                        text = "LIVE PLAYBACK",
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
                // Live Stream Mode
                val rms = if (isPlaying) liveRmsDb else -60f
                val peak = if (isPlaying) livePeakDb else -60f
                val rmsProgress = ((rms + 60f) / 60f).coerceIn(0f, 1f)
                val peakProgress = ((peak + 60f) / 60f).coerceIn(0f, 1f)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DjObsidian, RoundedCornerShape(10.dp))
                        .border(1.dp, DjSurfaceBorder, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (playingTrack != null) "Track: ${playingTrack?.title}" else "No track currently playing",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = if (isPlaying) "Playing" else "Paused",
                            color = if (isPlaying) NeonGreen else TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // RMS Meter Bar
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("RMS LEVEL", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = String.format(Locale.US, "%.1f dBFS", rms),
                                color = when {
                                    rms > -6f -> NeonRed
                                    rms > -12f -> NeonAmber
                                    else -> NeonGreen
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        LinearProgressIndicator(
                            progress = { rmsProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            color = when {
                                rms > -6f -> NeonRed
                                rms > -12f -> NeonAmber
                                else -> NeonGreen
                            },
                            trackColor = DjSurfaceElevated
                        )
                    }

                    // Peak Meter Bar
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("PEAK LEVEL", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = String.format(Locale.US, "%.1f dBFS", peak),
                                color = if (peak >= -0.1f) NeonRed else DeckACyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        LinearProgressIndicator(
                            progress = { peakProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (peak >= -0.1f) NeonRed else DeckACyan,
                            trackColor = DjSurfaceElevated
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("-60 dBFS", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("-30 dBFS", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("-12 dBFS", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text("0 dBFS", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            } else {
                // File Analysis Mode
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DjObsidian, RoundedCornerShape(10.dp))
                        .border(1.dp, DjSurfaceBorder, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (trackToAnalyze == null) {
                        Text("No track selected for analysis.", color = TextMuted, fontSize = 11.sp)
                    } else if (isAnalyzingFile) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = DeckACyan, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Decoding PCM and computing RMS...", color = TextSecondary, fontSize = 11.sp)
                        }
                    } else if (fileAnalysisMetrics != null) {
                        val m = fileAnalysisMetrics!!
                        Text(trackToAnalyze.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Average RMS", color = TextMuted, fontSize = 9.sp)
                                Text(
                                    "${String.format(Locale.US, "%.1f", m.rmsDb)} dBFS",
                                    color = NeonGreen,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Column {
                                Text("True Peak", color = TextMuted, fontSize = 9.sp)
                                Text(
                                    "${String.format(Locale.US, "%.1f", m.peakDb)} dBFS",
                                    color = if (m.peakDb >= -0.1f) NeonRed else DeckACyan,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Column {
                                Text("Crest Factor", color = TextMuted, fontSize = 9.sp)
                                Text(
                                    "${String.format(Locale.US, "%.1f", m.crestFactorDb)} dB",
                                    color = NeonAmber,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
