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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MetadataProvenance
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
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact pill/badge displayed on track cards, listings, and inspectors indicating
 * if metadata was analyzed locally via PCM DSP or resolved via the MusicBrainz catalogue.
 */
@Composable
fun MetadataProvenanceBadge(
    track: Track,
    compact: Boolean = true,
    modifier: Modifier = Modifier
) {
    val provenance = track.metadataProvenance

    val style = when (provenance) {
        MetadataProvenance.MUSICBRAINZ_CANONICAL -> ProvenanceStyle(
            bgColor = NeonPurple.copy(alpha = 0.18f),
            borderColor = NeonPurple.copy(alpha = 0.6f),
            textColor = NeonPurple,
            icon = Icons.Default.Language
        )
        MetadataProvenance.LOCAL_DSP_ANALYZED -> ProvenanceStyle(
            bgColor = NeonGreen.copy(alpha = 0.18f),
            borderColor = NeonGreen.copy(alpha = 0.6f),
            textColor = NeonGreen,
            icon = Icons.Default.GraphicEq
        )
        MetadataProvenance.VERIFIED_HYBRID -> ProvenanceStyle(
            bgColor = DeckACyan.copy(alpha = 0.18f),
            borderColor = DeckACyan.copy(alpha = 0.6f),
            textColor = DeckACyan,
            icon = Icons.Default.CheckCircle
        )
        MetadataProvenance.EMBEDDED_TAGS -> ProvenanceStyle(
            bgColor = DjSurfaceElevated,
            borderColor = DjSurfaceBorder,
            textColor = TextMuted,
            icon = Icons.Default.MusicNote
        )
    }

    Surface(
        modifier = modifier.testTag("metadata_provenance_badge_${track.id}"),
        shape = RoundedCornerShape(if (compact) 4.dp else 6.dp),
        color = style.bgColor,
        border = BorderStroke(0.75.dp, style.borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 4.dp else 6.dp, vertical = if (compact) 1.5.dp else 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = provenance.shortLabel,
                tint = style.textColor,
                modifier = Modifier.size(if (compact) 9.dp else 11.dp)
            )
            Text(
                text = if (compact) provenance.shortLabel else provenance.fullLabel,
                color = style.textColor,
                fontSize = if (compact) 8.5.sp else 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.3.sp
            )
        }
    }
}

private data class ProvenanceStyle(
    val bgColor: Color,
    val borderColor: Color,
    val textColor: Color,
    val icon: ImageVector
)

/**
 * Detailed metadata provenance card for properties dialogs, file inspectors, and deck views.
 */
@Composable
fun MetadataProvenanceCard(
    track: Track,
    onEnrich: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val provenance = track.metadataProvenance
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("metadata_provenance_card"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
        border = BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header with badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "METADATA PROVENANCE",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
                MetadataProvenanceBadge(track = track, compact = false)
            }

            Text(
                text = provenance.description,
                color = TextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )

            // MusicBrainz Section if active
            if (track.isMusicBrainzEnriched) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DjSurfaceDark,
                    border = BorderStroke(0.5.dp, NeonPurple.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("MUSICBRAINZ CATALOGUE", color = NeonPurple, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.weight(1f))
                            if (track.musicBrainzMatchConfidence > 0) {
                                Text(
                                    text = "${(track.musicBrainzMatchConfidence * 100).toInt()}% Match Confidence",
                                    color = NeonGreen,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        track.musicBrainzRecordingId?.let {
                            ProvenanceField("Recording MBID", it, isMono = true)
                        }
                        track.musicBrainzReleaseId?.let {
                            ProvenanceField("Release MBID", it, isMono = true)
                        }
                        track.isrc?.let {
                            ProvenanceField("ISRC", it, isMono = true)
                        }
                        track.releaseDate?.let {
                            ProvenanceField("Release Date", it)
                        }
                        track.musicBrainzLastChecked?.let {
                            ProvenanceField("Last Verified", dateFormat.format(Date(it)))
                        }
                    }
                }
            }

            // Local Audio DSP Section if active
            if (track.isLocallyAnalyzed) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DjSurfaceDark,
                    border = BorderStroke(0.5.dp, NeonGreen.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("LOCAL AUDIO DSP ANALYSIS", color = NeonGreen, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("PCM Autocorrelation & STFT", color = TextMuted, fontSize = 8.5.sp)
                        }

                        if (track.bpm > 0) {
                            val bpmConf = if (track.bpmConfidence > 0) " (${(track.bpmConfidence * 100).toInt()}% conf)" else ""
                            ProvenanceField("Detected BPM", "${String.format(Locale.US, "%.1f", track.bpm)} BPM$bpmConf")
                        }
                        if (track.musicalKey.isNotBlank()) {
                            val keyConf = if (track.keyConfidence > 0) " (${(track.keyConfidence * 100).toInt()}% conf)" else ""
                            val camelot = if (track.camelotKey.isNotBlank()) " [${track.camelotKey}]" else ""
                            ProvenanceField("Detected Key", "${track.musicalKey}$camelot$keyConf")
                        }
                        track.bpmAnalysisVersion?.let {
                            ProvenanceField("DSP Engine", it)
                        }
                        val lastAnalyzed = track.bpmLastAnalyzed ?: track.keyLastAnalyzed
                        if (lastAnalyzed != null && lastAnalyzed > 0L) {
                            ProvenanceField("Last Analyzed", dateFormat.format(Date(lastAnalyzed)))
                        }
                    }
                }
            }

            // Embedded tags notice
            if (!track.isMusicBrainzEnriched && !track.isLocallyAnalyzed) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DjSurfaceDark,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Track contains default container ID3 tags. Tap 'Enrich Metadata' to resolve canonical MusicBrainz catalog details and compute local high-precision BPM & Key.",
                        color = TextMuted,
                        fontSize = 9.5.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // Re-analyze / Enrich Button
            if (onEnrich != null) {
                Button(
                    onClick = onEnrich,
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().height(32.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "Enrich", tint = DjObsidian, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Re-Analyze & Verify with MusicBrainz", color = DjObsidian, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ProvenanceField(label: String, value: String, isMono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextMuted, fontSize = 9.sp)
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 9.5.sp,
            fontFamily = if (isMono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Visual legend displayed inside Metadata Settings to explain each provenance type.
 */
@Composable
fun MetadataProvenanceLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = DjSurfaceDark,
        border = BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "PROVENANCE BADGE LEGEND",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )

            LegendItem(
                badge = "MB CANONICAL",
                badgeColor = NeonPurple,
                title = "MusicBrainz Canonical Catalogue",
                desc = "Metadata matched and disambiguated against official MusicBrainz IDs and ISRC."
            )

            LegendItem(
                badge = "LOCAL DSP",
                badgeColor = NeonGreen,
                title = "Locally Analyzed Audio DSP",
                desc = "BPM, Musical Key, and Camelot Key computed from local PCM audio decoding."
            )

            LegendItem(
                badge = "HYBRID",
                badgeColor = DeckACyan,
                title = "Hybrid Verified (MB + Local DSP)",
                desc = "Both MusicBrainz canonical catalog metadata and local DSP audio analysis attached."
            )

            LegendItem(
                badge = "EMBEDDED",
                badgeColor = TextMuted,
                title = "Embedded ID3 / Container Tags",
                desc = "Standard local file tags without external catalog or DSP analysis."
            )
        }
    }
}

@Composable
private fun LegendItem(badge: String, badgeColor: Color, title: String, desc: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = badgeColor.copy(alpha = 0.2f),
            border = BorderStroke(0.5.dp, badgeColor.copy(alpha = 0.6f))
        ) {
            Text(
                text = badge,
                color = badgeColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        Column {
            Text(text = title, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(text = desc, color = TextMuted, fontSize = 9.sp, lineHeight = 12.sp)
        }
    }
}
