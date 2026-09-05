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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.analysis.AudioQualityInspector
import com.example.analysis.AudioQualityReport
import com.example.analysis.QualityClassification
import com.example.model.Track
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun AudioQualityDialog(
    track: Track,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<AudioQualityReport?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    fun runInspection(forceRefresh: Boolean = false) {
        isLoading = true
        scope.launch {
            report = AudioQualityInspector.inspectTrack(context, track, forceRefresh = forceRefresh)
            isLoading = false
        }
    }

    LaunchedEffect(track.id) {
        runInspection(forceRefresh = false)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .testTag("audio_quality_dialog"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DjObsidian),
            border = BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(20.dp))
                        Text("AUDIO QUALITY INSPECTOR", color = TextPrimary, fontWeight = FontWeight.Black, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }

                // Track Title & Artist
                Column {
                    Text(track.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(track.artist, color = TextSecondary, fontSize = 12.sp)
                }

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(color = DeckACyan, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                            Text("Analyzing acoustic bitstream & spectral shelf...", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                } else if (report != null) {
                    val r = report!!

                    // Classification Badge
                    val classColor = when (r.classification) {
                        QualityClassification.HIGH_RES_LOSSLESS, QualityClassification.TRUE_LOSSLESS -> NeonGreen
                        QualityClassification.HIGH_QUALITY_LOSSY -> DeckACyan
                        QualityClassification.STANDARD_LOSSY -> NeonAmber
                        QualityClassification.LOW_QUALITY_LOSSY, QualityClassification.UNKNOWN -> NeonRed
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = classColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, classColor)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (r.isSuspiciousTranscode) Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (r.isSuspiciousTranscode) NeonRed else classColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = r.classification.name.replace("_", " "),
                                    color = if (r.isSuspiciousTranscode) NeonRed else classColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = if (r.isSuspiciousTranscode) "Suspicious transcode detected" else "Verified acoustic profile",
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    // Suspicious transcode warning card
                    if (r.isSuspiciousTranscode && !r.transcodeWarningReason.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = NeonRed.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, NeonRed)
                        ) {
                            Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = NeonRed, modifier = Modifier.size(16.dp))
                                Text(r.transcodeWarningReason, color = TextPrimary, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }
                    }

                    // Audio Specification Grid
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DjSurfaceCard,
                        border = BorderStroke(1.dp, DjSurfaceBorder)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SpecRow("Container", r.container)
                            SpecRow("Codec", r.codec)
                            SpecRow("Bitrate", "${r.bitrateKbps} kbps" + if (r.bitrateMode != null) " (${r.bitrateMode.name})" else "")
                            SpecRow("Sample Rate", "${r.sampleRateHz} Hz (${r.sampleRateHz / 1000.0} kHz)")
                            if (r.bitDepth != null) {
                                SpecRow("Bit Depth", "${r.bitDepth}-bit")
                            }
                            SpecRow("Channels", if (r.channelCount == 1) "Mono (1 ch)" else "Stereo (${r.channelCount} ch)")
                            if (r.spectralCutoffKhz != null) {
                                SpecRow("Acoustic Cutoff", "~${String.format(Locale.US, "%.1f", r.spectralCutoffKhz)} kHz")
                            }
                            SpecRow("File Size", String.format(Locale.US, "%.2f MB", r.fileSizeBytes / (1024.0 * 1024.0)))
                            SpecRow("Duration", "${r.durationSeconds / 60}:${String.format(Locale.US, "%02d", r.durationSeconds % 60)}")
                        }
                    }

                    // Summary Readout
                    Text("Summary: ${r.summary}", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

                    // Re-run Button
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { runInspection(forceRefresh = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = DjObsidian, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Re-analyze", color = DjObsidian, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Text(value, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}
