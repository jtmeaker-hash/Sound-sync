package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.audio.ParametricEqManager
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

@Composable
fun ParametricEqDialog(
    eqManager: ParametricEqManager,
    onDismiss: () -> Unit
) {
    val presets by eqManager.presets.collectAsState()
    val activePresetId by eqManager.activePresetId.collectAsState()
    val currentBands by eqManager.currentBands.collectAsState()
    val preampDb by eqManager.preampDb.collectAsState()
    val isEqEnabled by eqManager.isEqEnabled.collectAsState()

    var selectedBandIndex by remember { mutableIntStateOf(0) }
    var showSavePresetDialog by remember { mutableStateOf(false) }

    if (showSavePresetDialog) {
        var newPresetName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            containerColor = DjSurfaceElevated,
            title = { Text("Save Custom EQ Preset", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPresetName,
                    onValueChange = { newPresetName = it },
                    placeholder = { Text("Preset name (e.g. My Studio Tune)", color = TextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DeckACyan,
                        unfocusedBorderColor = DjSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPresetName.isNotBlank()) {
                            eqManager.saveCustomPreset(newPresetName)
                            showSavePresetDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
                ) {
                    Text("Save", color = DjObsidian, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .testTag("parametric_eq_dialog"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DjObsidian),
            border = BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxHeight()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(22.dp))
                        Text(
                            text = "PARAMETRIC EQUALIZER",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (isEqEnabled) "ON" else "BYPASS", color = if (isEqEnabled) NeonGreen else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Switch(
                            checked = isEqEnabled,
                            onCheckedChange = { eqManager.setEqEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DjObsidian,
                                checkedTrackColor = DeckACyan,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = DjSurfaceDark
                            )
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Presets Carousel
                Text("PRESETS", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    items(presets) { preset ->
                        val isSelected = preset.id == activePresetId
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { eqManager.applyPreset(preset.id) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) DeckACyan.copy(alpha = 0.2f) else DjSurfaceCard,
                            border = BorderStroke(1.dp, if (isSelected) DeckACyan else DjSurfaceBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = preset.name,
                                    color = if (isSelected) DeckACyan else TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (!preset.isBuiltIn) {
                                    IconButton(
                                        onClick = { eqManager.deleteCustomPreset(preset.id) },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = NeonRed, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Preamp Slider & Preset action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PREAMP: ${String.format(Locale.US, "%+.1f dB", preampDb)}",
                            color = if (preampDb > 0.0) NeonAmber else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Slider(
                            value = preampDb.toFloat(),
                            onValueChange = { eqManager.setPreamp(it.toDouble()) },
                            valueRange = -12.0f..12.0f,
                            colors = SliderDefaults.colors(thumbColor = DeckACyan, activeTrackColor = DeckACyan, inactiveTrackColor = DjSurfaceDark),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { showSavePresetDialog = true },
                            border = BorderStroke(1.dp, DeckACyan.copy(alpha = 0.5f)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save", color = DeckACyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { eqManager.resetToFlat() },
                            border = BorderStroke(1.dp, DjSurfaceBorder),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, tint = TextMuted, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Flat", color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bands Selection Strip
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(currentBands) { index, band ->
                        val isSelected = index == selectedBandIndex
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { selectedBandIndex = index },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) DeckACyan else DjSurfaceDark,
                            border = BorderStroke(1.dp, if (isSelected) DeckACyan else DjSurfaceBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = band.name,
                                    color = if (isSelected) DjObsidian else TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = String.format(Locale.US, "%+.1fdB", band.gainDb),
                                    color = if (isSelected) DjObsidian else (if (band.gainDb != 0.0) DeckACyan else TextMuted),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Selected Band Detailed Controls
                if (selectedBandIndex in currentBands.indices) {
                    val band = currentBands[selectedBandIndex]
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DjSurfaceCard,
                        border = BorderStroke(1.dp, DjSurfaceBorder),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        LazyColumn(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("${band.name} (${band.type.displayName})", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("Filter band #${band.id + 1}", color = TextMuted, fontSize = 10.sp)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(if (band.isEnabled) "ACTIVE" else "BYPASSED", color = if (band.isEnabled) NeonGreen else TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Switch(
                                            checked = band.isEnabled,
                                            onCheckedChange = {
                                                eqManager.updateBand(selectedBandIndex, band.frequencyHz, band.gainDb, band.q, it)
                                            },
                                            colors = SwitchDefaults.colors(checkedThumbColor = DjObsidian, checkedTrackColor = DeckACyan)
                                        )
                                    }
                                }
                            }

                            // Gain Slider
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("GAIN", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(String.format(Locale.US, "%+.1f dB", band.gainDb), color = DeckACyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                    Slider(
                                        value = band.gainDb.toFloat(),
                                        onValueChange = {
                                            eqManager.updateBand(selectedBandIndex, band.frequencyHz, it.toDouble(), band.q, band.isEnabled)
                                        },
                                        valueRange = -15.0f..15.0f,
                                        colors = SliderDefaults.colors(thumbColor = DeckACyan, activeTrackColor = DeckACyan, inactiveTrackColor = DjSurfaceDark)
                                    )
                                }
                            }

                            // Frequency Slider
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("FREQUENCY", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        val freqFormatted = if (band.frequencyHz >= 1000.0) String.format(Locale.US, "%.1f kHz", band.frequencyHz / 1000.0) else String.format(Locale.US, "%.0f Hz", band.frequencyHz)
                                        Text(freqFormatted, color = DeckACyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                    Slider(
                                        value = band.frequencyHz.toFloat(),
                                        onValueChange = {
                                            eqManager.updateBand(selectedBandIndex, it.toDouble(), band.gainDb, band.q, band.isEnabled)
                                        },
                                        valueRange = 20.0f..20000.0f,
                                        colors = SliderDefaults.colors(thumbColor = DeckACyan, activeTrackColor = DeckACyan, inactiveTrackColor = DjSurfaceDark)
                                    )
                                }
                            }

                            // Q / Bandwidth Slider
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Q / BANDWIDTH", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(String.format(Locale.US, "Q = %.2f", band.q), color = DeckACyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                    Slider(
                                        value = band.q.toFloat(),
                                        onValueChange = {
                                            eqManager.updateBand(selectedBandIndex, band.frequencyHz, band.gainDb, it.toDouble(), band.isEnabled)
                                        },
                                        valueRange = 0.2f..10.0f,
                                        colors = SliderDefaults.colors(thumbColor = DeckACyan, activeTrackColor = DeckACyan, inactiveTrackColor = DjSurfaceDark)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
