package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metadata.MetadataSettings
import com.example.metadata.PushMetadataPhase
import com.example.metadata.PushMetadataProgress
import com.example.metadata.PushMetadataReport
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun MetadataEnrichmentSettingsCard(
    settings: MetadataSettings,
    onSetEnrichmentEnabled: (Boolean) -> Unit,
    onSetAppleSearchEnabled: (Boolean) -> Unit,
    onSetTheAudioDbEnabled: (Boolean) -> Unit = {},
    onSetBpmAnalysisEnabled: (Boolean) -> Unit,
    onSetKeyAnalysisEnabled: (Boolean) -> Unit,
    onSetWriteToFileEnabled: (Boolean) -> Unit,
    onSetShowProvenanceBadges: (Boolean) -> Unit = {},
    onSetConcurrency: (Int) -> Unit,
    onSetBpmRange: (Int, Int) -> Unit,
    onSetBackgroundScanningEnabled: (Boolean) -> Unit = {},
    onSetAutoSearchEnabled: (Boolean) -> Unit = {},
    onSetAutoQueueVerified: (Boolean) -> Unit = {},
    onSetReplaceExistingTitle: (Boolean) -> Unit = {},
    onSetReplaceExistingArtist: (Boolean) -> Unit = {},
    onSetReplaceExistingArtwork: (Boolean) -> Unit = {},
    onSetWriteMetadataOnlyAfterApproval: (Boolean) -> Unit = {},
    onSetKeepOriginalMetadataBackup: (Boolean) -> Unit = {},
    isPushingMetadata: Boolean = false,
    pushProgress: PushMetadataProgress? = null,
    pushReport: PushMetadataReport? = null,
    onPushMetadataToFiles: () -> Unit = {},
    onCancelPushMetadata: () -> Unit = {}
) {
    var minText by remember(settings.bpmMin) { mutableStateOf(settings.bpmMin.toString()) }
    var maxText by remember(settings.bpmMax) { mutableStateOf(settings.bpmMax.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("metadata_enrichment_settings_card"),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Metadata Safety & Protection", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "Ensures correct titles/artists are never overwritten without explicit approval, and backups survive restarts.",
                color = TextSecondary, fontSize = 10.sp
            )
            SettingSwitch("Background metadata scanning", settings.backgroundScanningEnabled, onSetBackgroundScanningEnabled)
            SettingSwitch("Automatically search for metadata", settings.autoSearchEnabled, onSetAutoSearchEnabled)
            SettingSwitch("Automatically queue verified metadata", settings.autoQueueVerified, onSetAutoQueueVerified)
            SettingSwitch("Replace existing title (Default: OFF)", settings.replaceExistingTitle, onSetReplaceExistingTitle)
            SettingSwitch("Replace existing artist (Default: OFF)", settings.replaceExistingArtist, onSetReplaceExistingArtist)
            SettingSwitch("Replace existing artwork (Default: OFF)", settings.replaceExistingArtwork, onSetReplaceExistingArtwork)
            SettingSwitch("Write metadata only after approval (Default: ON)", settings.writeMetadataOnlyAfterApproval, onSetWriteMetadataOnlyAfterApproval)
            SettingSwitch("Keep original metadata backup (Default: ON)", settings.keepOriginalMetadataBackup, onSetKeepOriginalMetadataBackup)

            Spacer(Modifier.height(4.dp))
            Text("External Catalogue & DSP Engine", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            SettingSwitch("Enable enrichment engine", settings.enrichmentEnabled, onSetEnrichmentEnabled)
            SettingSwitch("Use Apple iTunes Search catalogue", settings.appleSearchEnabled, onSetAppleSearchEnabled)
            SettingSwitch("Use TheAudioDB for cover art", settings.theAudioDbEnabled, onSetTheAudioDbEnabled)
            SettingSwitch("Analyse BPM locally via PCM STFT", settings.bpmAnalysisEnabled, onSetBpmAnalysisEnabled)
            SettingSwitch("Analyse Musical Key locally via chroma", settings.keyAnalysisEnabled, onSetKeyAnalysisEnabled)
            SettingSwitch("Show Metadata Provenance Badges", settings.showProvenanceBadges, onSetShowProvenanceBadges)
            SettingSwitch("Write completed metadata to ID3 tags", settings.writeToFileEnabled, onSetWriteToFileEnabled)

            MetadataProvenanceLegend(modifier = Modifier.padding(vertical = 4.dp))

            Spacer(Modifier.height(4.dp))
            Text("Physical File Tag Embedding", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "Embeds library metadata (Title, Artist, Album, Genre, Year, BPM, Key, Artwork) directly into audio files so it persists in external DJ apps and file managers.",
                color = TextSecondary, fontSize = 10.sp
            )

            if (!isPushingMetadata && pushReport == null) {
                Button(
                    onClick = onPushMetadataToFiles,
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan.copy(alpha = 0.85f)),
                    modifier = Modifier.fillMaxWidth().testTag("push_metadata_to_files_button"),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Publish, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Black)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("Push Metadata to Files", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Bold)
                }
            } else if (isPushingMetadata) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DjSurfaceBorder.copy(alpha = 0.3f)),
                    border = BorderStroke(1.dp, DeckACyan),
                    modifier = Modifier.fillMaxWidth().testTag("push_metadata_progress_card")
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(
                                "Pushing Metadata to Files...",
                                color = DeckACyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            OutlinedButton(
                                onClick = onCancelPushMetadata,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Cancel", fontSize = 10.sp, color = NeonRed)
                            }
                        }

                        val progressFraction = if ((pushProgress?.total ?: 0) > 0) {
                            (pushProgress?.current ?: 0).toFloat() / (pushProgress?.total ?: 1).toFloat()
                        } else 0f

                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = DeckACyan,
                            trackColor = DjSurfaceBorder
                        )

                        Text(
                            "Processing track ${pushProgress?.current ?: 0} of ${pushProgress?.total ?: 0}",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )

                        if (!pushProgress?.trackTitle.isNullOrBlank()) {
                            Text(
                                "${pushProgress?.trackTitle} — ${pushProgress?.trackArtist}",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Status: ${pushProgress?.phase?.label ?: "Processing"}",
                                color = if (pushProgress?.phase == PushMetadataPhase.AWAITING_PERMISSION) NeonAmber else DeckACyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (pushProgress?.phase == PushMetadataPhase.AWAITING_PERMISSION) {
                            Text(
                                "Awaiting storage write permission approval...",
                                color = NeonAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState())
                        ) {
                            Text("Written: ${pushProgress?.writtenCount ?: 0}", color = NeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("In Sync: ${pushProgress?.syncedCount ?: 0}", color = DeckACyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            if ((pushProgress?.partialCount ?: 0) > 0) {
                                Text("Partial: ${pushProgress?.partialCount}", color = NeonAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            if ((pushProgress?.permissionRequiredCount ?: 0) > 0) {
                                Text("Perm Required: ${pushProgress?.permissionRequiredCount}", color = NeonAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            if ((pushProgress?.unsupportedCount ?: 0) > 0) {
                                Text("Unsupported: ${pushProgress?.unsupportedCount}", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            if ((pushProgress?.failedCount ?: 0) > 0) {
                                Text("Failed: ${pushProgress?.failedCount}", color = NeonRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (pushReport != null && !isPushingMetadata) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DjSurfaceBorder.copy(alpha = 0.2f)),
                    border = BorderStroke(1.dp, if (pushReport.wasCancelled || pushReport.failed > 0) NeonAmber else NeonGreen),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("push_metadata_report_card")
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Icon(
                                    if (pushReport.wasCancelled) Icons.Default.Close
                                    else if (pushReport.failed > 0) Icons.Default.Warning
                                    else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (pushReport.wasCancelled || pushReport.failed > 0) NeonAmber else NeonGreen
                                )
                                Text(
                                    if (pushReport.wasCancelled) "Push Operation Cancelled" else "Push Operation Summary",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Button(
                                onClick = onPushMetadataToFiles,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = DeckACyan.copy(alpha = 0.3f))
                            ) {
                                Text("Run Again", fontSize = 10.sp, color = DeckACyan)
                            }
                        }

                        Text("Total tracks examined: ${pushReport.totalExamined}", color = TextSecondary, fontSize = 11.sp)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            Text("Successfully written: ${pushReport.successfullyWritten}", color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text("Already in sync: ${pushReport.alreadySynchronized}", color = DeckACyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            if (pushReport.partial > 0) {
                                Text("Partial: ${pushReport.partial}", color = NeonAmber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (pushReport.permissionRequired > 0) {
                                Text("Perm Required: ${pushReport.permissionRequired}", color = NeonAmber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (pushReport.unsupported > 0) {
                                Text("Unsupported: ${pushReport.unsupported}", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (pushReport.failed > 0) {
                                Text("Failed: ${pushReport.failed}", color = NeonRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        if (pushReport.failureReasons.isNotEmpty()) {
                            var showAllIssues by remember { mutableStateOf(false) }
                            val displayList = if (showAllIssues) pushReport.failureReasons else pushReport.failureReasons.take(5)

                            Text("Issues (${pushReport.failureReasons.size}):", color = NeonRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                displayList.forEach { failure ->
                                    Text("• [${failure.category}] ${failure.title} (${failure.artist}): ${failure.reason}", color = TextSecondary, fontSize = 9.sp)
                                }
                                if (pushReport.failureReasons.size > 5) {
                                    TextButton(
                                        onClick = { showAllIssues = !showAllIssues },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 0.dp)
                                    ) {
                                        Text(
                                            if (showAllIssues) "Show less" else "Show all ${pushReport.failureReasons.size} issues",
                                            fontSize = 10.sp,
                                            color = DeckACyan
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Text("BPM analysis range: ${settings.bpmMin}-${settings.bpmMax} BPM", color = TextSecondary, fontSize = 10.sp)
            Slider(
                value = settings.bpmMax.toFloat(),
                onValueChange = { value ->
                    val max = value.toInt().coerceIn(settings.bpmMin, 260)
                    maxText = max.toString()
                    onSetBpmRange(settings.bpmMin, max)
                },
                valueRange = settings.bpmMin.toFloat()..260f,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = DeckACyan, activeTrackColor = DeckACyan)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = minText,
                    onValueChange = { value ->
                        minText = value.filter(Char::isDigit)
                        value.toIntOrNull()?.let { onSetBpmRange(it, settings.bpmMax) }
                    },
                    label = { Text("Min BPM") }, modifier = Modifier.weight(1f), singleLine = true
                )
                OutlinedTextField(
                    value = maxText,
                    onValueChange = { value ->
                        maxText = value.filter(Char::isDigit)
                        value.toIntOrNull()?.let { onSetBpmRange(settings.bpmMin, it.coerceAtMost(260)) }
                    },
                    label = { Text("Max BPM") }, modifier = Modifier.weight(1f), singleLine = true
                )
            }
            Text("Concurrent jobs: ${settings.concurrency}", color = TextSecondary, fontSize = 10.sp)
            Slider(
                value = settings.concurrency.toFloat(),
                onValueChange = { onSetConcurrency(it.toInt().coerceIn(1, MetadataSettings.MAX_CONCURRENCY)) },
                valueRange = 1f..MetadataSettings.MAX_CONCURRENCY.toFloat(),
                steps = MetadataSettings.MAX_CONCURRENCY - 2,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = DeckACyan, activeTrackColor = DeckACyan)
            )
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextPrimary, fontSize = 11.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = DeckACyan, checkedTrackColor = DeckACyan.copy(alpha = .4f))
        )
    }
}
