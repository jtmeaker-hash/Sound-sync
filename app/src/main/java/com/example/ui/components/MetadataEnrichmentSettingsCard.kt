package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun MetadataEnrichmentSettingsCard(
    settings: MetadataSettings,
    onSetEnrichmentEnabled: (Boolean) -> Unit,
    onSetMusicBrainzEnabled: (Boolean) -> Unit,
    onSetBpmAnalysisEnabled: (Boolean) -> Unit,
    onSetKeyAnalysisEnabled: (Boolean) -> Unit,
    onSetWriteToFileEnabled: (Boolean) -> Unit,
    onSetConcurrency: (Int) -> Unit,
    onSetBpmRange: (Int, Int) -> Unit,
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
            Text("Metadata Enrichment", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "MusicBrainz identifies the recording. BPM and key are measured from the local audio file.",
                color = TextSecondary, fontSize = 10.sp
            )
            SettingSwitch("Enable enrichment", settings.enrichmentEnabled, onSetEnrichmentEnabled)
            SettingSwitch("Use MusicBrainz catalog", settings.musicBrainzEnabled, onSetMusicBrainzEnabled)
            SettingSwitch("Analyse BPM locally", settings.bpmAnalysisEnabled, onSetBpmAnalysisEnabled)
            SettingSwitch("Analyse key locally", settings.keyAnalysisEnabled, onSetKeyAnalysisEnabled)
            SettingSwitch("Write completed metadata to files", settings.writeToFileEnabled, onSetWriteToFileEnabled)

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
