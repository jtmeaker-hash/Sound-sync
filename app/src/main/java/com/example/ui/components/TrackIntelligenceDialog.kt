package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackIntelligenceDialog(
    track: Track,
    allTracks: List<Track>,
    onMixWithThis: () -> Unit,
    onInspectQuality: () -> Unit,
    onOpenLyrics: () -> Unit,
    onInspectSpectrogram: () -> Unit,
    onDismiss: () -> Unit
) {
    val intelligence = remember(track.id, allTracks.size) {
        SoundSyncIntelligenceEngine.getTrackIntelligence(track, allTracks)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("track_intelligence_dialog"),
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
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(22.dp))
                        }
                        Column {
                            Text("Track Intelligence", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                            Text("Acoustic & Library Diagnostics", fontSize = 11.sp, color = TextSecondary)
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Track Title / Artist Card
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DjSurfaceDark,
                    border = BorderStroke(1.dp, DjSurfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(track.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = DeckACyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, fontSize = 12.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Confidence & Trust Meters
                Text("INTELLIGENCE CONFIDENCE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextMuted, letterSpacing = 0.8.sp)
                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DjSurfaceCard,
                    border = BorderStroke(1.dp, DjSurfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Track Identity Confidence", fontSize = 11.sp, color = TextSecondary)
                                Text("${(intelligence.identityConfidence * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DeckACyan)
                            }
                            LinearProgressIndicator(
                                progress = { intelligence.identityConfidence },
                                color = DeckACyan,
                                trackColor = DjSurfaceElevated,
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Metadata Trust Score", fontSize = 11.sp, color = TextSecondary)
                                Text("${(intelligence.metadataTrustScore * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NeonGreen)
                            }
                            LinearProgressIndicator(
                                progress = { intelligence.metadataTrustScore },
                                color = NeonGreen,
                                trackColor = DjSurfaceElevated,
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Feature Status Grid
                Text("ANALYSIS STATUS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextMuted, letterSpacing = 0.8.sp)
                Spacer(modifier = Modifier.height(6.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StatusBadge("BPM & Key", intelligence.hasBpmKey, DeckACyan)
                    StatusBadge("Phrases", intelligence.hasPhraseAnalysis, DeckBPink)
                    StatusBadge("Fingerprint", intelligence.hasFingerprint, NeonGreen)
                    StatusBadge("Lossless", intelligence.isLossless, NeonGreen)
                    if (intelligence.isSuspiciousTranscode) {
                        StatusBadge("Fake Transcode", true, NeonAmber)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Library Discovery Insights
                Text("LIBRARY CONNECTIVITY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextMuted, letterSpacing = 0.8.sp)
                Spacer(modifier = Modifier.height(6.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DjSurfaceCard,
                        border = BorderStroke(1.dp, DjSurfaceBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("${intelligence.mixCompatibleTracksCount}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = DeckACyan)
                            Text("Mix-Compatible Tracks", fontSize = 10.sp, color = TextSecondary)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DjSurfaceCard,
                        border = BorderStroke(1.dp, DjSurfaceBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("${intelligence.similarTracksCount}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = DeckBPink)
                            Text("Genre-Similar Tracks", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quick Action Buttons
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            onDismiss()
                            onMixWithThis()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Mix With This (${intelligence.mixCompatibleTracksCount} compatible)", fontWeight = FontWeight.Bold)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onOpenLyrics()
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(38.dp)
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(14.dp), tint = DeckACyan)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Lyrics", fontSize = 12.sp, color = TextPrimary)
                        }

                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onInspectQuality()
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(38.dp)
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeonGreen)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Quality", fontSize = 12.sp, color = TextPrimary)
                        }

                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onInspectSpectrogram()
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(38.dp)
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(14.dp), tint = DeckBPink)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("FFT", fontSize = 12.sp, color = TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, isActive: Boolean, activeColor: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (isActive) activeColor.copy(alpha = 0.15f) else DjSurfaceElevated,
        border = BorderStroke(0.5.dp, if (isActive) activeColor.copy(alpha = 0.4f) else DjSurfaceBorder)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isActive) activeColor else TextMuted)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (isActive) activeColor else TextMuted
            )
        }
    }
}
