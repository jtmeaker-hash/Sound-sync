package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.audio.EqBand
import com.example.audio.EqFilterType
import com.example.audio.ParametricEq
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
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

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
    val autoHeadroomEnabled by eqManager.autoHeadroomEnabled.collectAsState()

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
                    placeholder = { Text("Preset name (e.g. Master Clarity)", color = TextMuted) },
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
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .testTag("parametric_eq_dialog"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DjObsidian),
            border = BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .fillMaxHeight()
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(22.dp))
                        Text(
                            text = "PARAMETRIC EQUALIZER",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Auto-Headroom toggle
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (autoHeadroomEnabled) "AUTO HEADROOM" else "HEADROOM",
                                color = if (autoHeadroomEnabled) DeckACyan else TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Switch(
                                checked = autoHeadroomEnabled,
                                onCheckedChange = { eqManager.setAutoHeadroomEnabled(it) },
                                modifier = Modifier.size(width = 38.dp, height = 24.dp),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DjObsidian,
                                    checkedTrackColor = DeckACyan,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = DjSurfaceDark
                                )
                            )
                        }

                        // Master EQ toggle
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (isEqEnabled) "ON" else "BYPASS",
                                color = if (isEqEnabled) NeonGreen else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Switch(
                                checked = isEqEnabled,
                                onCheckedChange = { eqManager.setEqEnabled(it) },
                                modifier = Modifier.size(width = 44.dp, height = 24.dp),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DjObsidian,
                                    checkedTrackColor = DeckACyan,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = DjSurfaceDark
                                )
                            )
                        }

                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Interactive Frequency Response Curve Graph
                FrequencyResponseCurveGraph(
                    bands = currentBands,
                    preampDb = preampDb,
                    autoHeadroom = autoHeadroomEnabled,
                    isEqEnabled = isEqEnabled,
                    selectedBandIndex = selectedBandIndex,
                    onSelectBand = { selectedBandIndex = it },
                    onUpdateBand = { idx, freq, gain ->
                        val b = currentBands.getOrNull(idx) ?: return@FrequencyResponseCurveGraph
                        eqManager.updateBand(idx, freq, gain, b.q, b.isEnabled, b.type)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Presets Carousel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("PRESETS", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { showSavePresetDialog = true },
                            border = BorderStroke(1.dp, DeckACyan.copy(alpha = 0.5f)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(11.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Save", color = DeckACyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { eqManager.resetToFlat() },
                            border = BorderStroke(1.dp, DjSurfaceBorder),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, tint = TextMuted, modifier = Modifier.size(11.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Flat", color = TextSecondary, fontSize = 9.sp)
                        }
                    }
                }

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
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = preset.name,
                                    color = if (isSelected) DeckACyan else TextPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (!preset.isBuiltIn) {
                                    IconButton(
                                        onClick = { eqManager.deleteCustomPreset(preset.id) },
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = NeonRed, modifier = Modifier.size(11.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // Preamp Slider Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "PREAMP: ${String.format(Locale.US, "%+.1f dB", preampDb)}",
                        color = if (preampDb > 0.0) NeonAmber else TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(110.dp)
                    )
                    Slider(
                        value = preampDb.toFloat(),
                        onValueChange = { eqManager.setPreamp(it.toDouble()) },
                        valueRange = -24.0f..24.0f,
                        colors = SliderDefaults.colors(thumbColor = DeckACyan, activeTrackColor = DeckACyan, inactiveTrackColor = DjSurfaceDark),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Bands Selection Strip (all 8 bands)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(currentBands) { index, band ->
                        val isSelected = index == selectedBandIndex
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { selectedBandIndex = index },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) DeckACyan else (if (!band.isEnabled) DjSurfaceDark.copy(alpha = 0.5f) else DjSurfaceDark),
                            border = BorderStroke(1.dp, if (isSelected) DeckACyan else DjSurfaceBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "${index + 1}",
                                        color = if (isSelected) DjObsidian else DeckACyan,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = band.type.shortCode,
                                        color = if (isSelected) DjObsidian else TextMuted,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Text(
                                    text = band.name,
                                    color = if (isSelected) DjObsidian else (if (band.isEnabled) TextPrimary else TextMuted),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (band.type == EqFilterType.HIGH_PASS || band.type == EqFilterType.LOW_PASS || band.type == EqFilterType.NOTCH) {
                                        formatFreq(band.frequencyHz)
                                    } else {
                                        String.format(Locale.US, "%+.1fdB", band.gainDb)
                                    },
                                    color = if (isSelected) DjObsidian else (if (band.gainDb != 0.0 && band.isEnabled) DeckACyan else TextMuted),
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Selected Band Detailed Controls
                if (selectedBandIndex in currentBands.indices) {
                    val band = currentBands[selectedBandIndex]
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DjSurfaceCard,
                        border = BorderStroke(1.dp, DjSurfaceBorder),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Band Title & On/Off / Reset Row
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Band #${band.id + 1}: ${band.name}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text("Frequency: ${formatFreq(band.frequencyHz)} • Filter: ${band.type.displayName}", color = TextMuted, fontSize = 10.sp)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = { eqManager.resetBand(selectedBandIndex) },
                                            border = BorderStroke(1.dp, DjSurfaceBorder),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                            modifier = Modifier.height(24.dp)
                                        ) {
                                            Text("Reset", color = TextSecondary, fontSize = 9.sp)
                                        }

                                        Text(if (band.isEnabled) "ON" else "OFF", color = if (band.isEnabled) NeonGreen else TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        Switch(
                                            checked = band.isEnabled,
                                            onCheckedChange = {
                                                eqManager.updateBand(selectedBandIndex, band.frequencyHz, band.gainDb, band.q, it, band.type)
                                            },
                                            modifier = Modifier.size(width = 38.dp, height = 22.dp),
                                            colors = SwitchDefaults.colors(checkedThumbColor = DjObsidian, checkedTrackColor = DeckACyan)
                                        )
                                    }
                                }
                            }

                            // Filter Type Selector (Chips)
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("FILTER TYPE", color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        items(EqFilterType.values()) { type ->
                                            val isCurrentType = band.type == type
                                            Surface(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable { eqManager.updateBandType(selectedBandIndex, type) },
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isCurrentType) DeckACyan else DjSurfaceDark,
                                                border = BorderStroke(1.dp, if (isCurrentType) DeckACyan else DjSurfaceBorder)
                                            ) {
                                                Text(
                                                    text = type.displayName,
                                                    color = if (isCurrentType) DjObsidian else TextSecondary,
                                                    fontSize = 9.sp,
                                                    fontWeight = if (isCurrentType) FontWeight.Bold else FontWeight.Normal,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Frequency Slider (Logarithmic mapping 20 Hz to 20 kHz)
                            item {
                                val logMin = log10(20.0)
                                val logMax = log10(20000.0)
                                val currentLog = log10(band.frequencyHz.coerceIn(20.0, 20000.0))
                                val sliderPos = ((currentLog - logMin) / (logMax - logMin)).toFloat().coerceIn(0f, 1f)

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("FREQUENCY", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Text(formatFreq(band.frequencyHz), color = DeckACyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                    Slider(
                                        value = sliderPos,
                                        onValueChange = { pos ->
                                            val newFreq = 10.0.pow(logMin + pos * (logMax - logMin))
                                            eqManager.updateBand(selectedBandIndex, newFreq, band.gainDb, band.q, band.isEnabled, band.type)
                                        },
                                        valueRange = 0f..1f,
                                        colors = SliderDefaults.colors(thumbColor = DeckACyan, activeTrackColor = DeckACyan, inactiveTrackColor = DjSurfaceDark)
                                    )
                                }
                            }

                            // Gain Slider (Only applicable for PEAKING, LOW_SHELF, HIGH_SHELF)
                            if (band.type == EqFilterType.PEAKING || band.type == EqFilterType.LOW_SHELF || band.type == EqFilterType.HIGH_SHELF) {
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("GAIN", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            Text(String.format(Locale.US, "%+.1f dB", band.gainDb), color = DeckACyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                        }
                                        Slider(
                                            value = band.gainDb.toFloat(),
                                            onValueChange = {
                                                eqManager.updateBand(selectedBandIndex, band.frequencyHz, it.toDouble(), band.q, band.isEnabled, band.type)
                                            },
                                            valueRange = -24.0f..24.0f,
                                            colors = SliderDefaults.colors(thumbColor = DeckACyan, activeTrackColor = DeckACyan, inactiveTrackColor = DjSurfaceDark)
                                        )
                                    }
                                }
                            }

                            // Q / Bandwidth Slider (Power-law curve for fine touch control around 0.5 - 2.0)
                            item {
                                val qSliderPos = (ln(band.q.coerceIn(0.1, 20.0) / 0.1) / ln(200.0)).toFloat().coerceIn(0f, 1f)

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Q / BANDWIDTH", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Text(String.format(Locale.US, "Q = %.2f", band.q), color = DeckACyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                    Slider(
                                        value = qSliderPos,
                                        onValueChange = { pos ->
                                            val newQ = 0.1 * (200.0.pow(pos.toDouble()))
                                            eqManager.updateBand(selectedBandIndex, band.frequencyHz, band.gainDb, newQ, band.isEnabled, band.type)
                                        },
                                        valueRange = 0f..1f,
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

/**
 * Renders the exact mathematical frequency response curve calculated from RBJ biquad formulas.
 * Includes interactive frequency/gain touch manipulation of individual band nodes.
 */
@Composable
private fun FrequencyResponseCurveGraph(
    bands: List<EqBand>,
    preampDb: Double,
    autoHeadroom: Boolean,
    isEqEnabled: Boolean,
    selectedBandIndex: Int,
    onSelectBand: (Int) -> Unit,
    onUpdateBand: (Int, Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val sampleRate = 44100
    val numPoints = 120

    // Precalculate frequency points logarithmically from 20 Hz to 20 kHz
    val frequencies = remember {
        val fArray = FloatArray(numPoints)
        val logMin = log10(20.0)
        val logMax = log10(20000.0)
        for (i in 0 until numPoints) {
            val frac = i.toDouble() / (numPoints - 1)
            fArray[i] = 10.0.pow(logMin + frac * (logMax - logMin)).toFloat()
        }
        fArray
    }

    val responseDb = remember(bands, preampDb, autoHeadroom, isEqEnabled) {
        val outDb = FloatArray(numPoints)
        if (!isEqEnabled) {
            outDb.fill(0f)
        } else {
            ParametricEq.computeFrequencyResponseDb(sampleRate, bands, preampDb, autoHeadroom, frequencies, outDb)
        }
        outDb
    }

    var draggingBandIndex by remember { mutableIntStateOf(-1) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DjSurfaceDark)
            .pointerInput(bands) {
                detectTapGestures { offset ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val logMin = log10(20.0)
                    val logMax = log10(20000.0)

                    var closestIdx = -1
                    var closestDistSq = Float.MAX_VALUE
                    for (i in bands.indices) {
                        val b = bands[i]
                        val bx = (((log10(b.frequencyHz) - logMin) / (logMax - logMin)) * w).toFloat()
                        val by = ((0.5f - (b.gainDb.toFloat() / 48f)) * h)
                        val distSq = (offset.x - bx) * (offset.x - bx) + (offset.y - by) * (offset.y - by)
                        if (distSq < closestDistSq && distSq < 1600f) { // ~40px radius
                            closestDistSq = distSq
                            closestIdx = i
                        }
                    }
                    if (closestIdx >= 0) {
                        onSelectBand(closestIdx)
                    }
                }
            }
            .pointerInput(bands) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val logMin = log10(20.0)
                        val logMax = log10(20000.0)

                        var closestIdx = -1
                        var closestDistSq = Float.MAX_VALUE
                        for (i in bands.indices) {
                            val b = bands[i]
                            val bx = (((log10(b.frequencyHz) - logMin) / (logMax - logMin)) * w).toFloat()
                            val by = ((0.5f - (b.gainDb.toFloat() / 48f)) * h)
                            val distSq = (startOffset.x - bx) * (startOffset.x - bx) + (startOffset.y - by) * (startOffset.y - by)
                            if (distSq < closestDistSq && distSq < 2500f) { // ~50px radius
                                closestDistSq = distSq
                                closestIdx = i
                            }
                        }
                        if (closestIdx >= 0) {
                            draggingBandIndex = closestIdx
                            onSelectBand(closestIdx)
                        }
                    },
                    onDragEnd = { draggingBandIndex = -1 },
                    onDragCancel = { draggingBandIndex = -1 },
                    onDrag = { change, _ ->
                        if (draggingBandIndex in bands.indices) {
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val logMin = log10(20.0)
                            val logMax = log10(20000.0)

                            val xNorm = (change.position.x / w).coerceIn(0f, 1f).toDouble()
                            val newFreq = 10.0.pow(logMin + xNorm * (logMax - logMin))
                            val yNorm = (change.position.y / h).coerceIn(0f, 1f)
                            val newGain = ((0.5f - yNorm) * 48f).toDouble().coerceIn(-24.0, 24.0)

                            onUpdateBand(draggingBandIndex, newFreq, newGain)
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val logMin = log10(20.0)
            val logMax = log10(20000.0)

            // Grid frequency vertical lines: 100 Hz, 1 kHz, 10 kHz
            val gridFreqs = floatArrayOf(50f, 100f, 250f, 500f, 1000f, 2500f, 5000f, 10000f)
            for (gf in gridFreqs) {
                val gx = (((log10(gf.toDouble()) - logMin) / (logMax - logMin)) * w).toFloat()
                drawLine(
                    color = DjSurfaceBorder.copy(alpha = 0.5f),
                    start = Offset(gx, 0f),
                    end = Offset(gx, h),
                    strokeWidth = 1f
                )
            }

            // Grid dB horizontal lines: +12dB, 0dB, -12dB
            val yPlus12 = (0.5f - 12f / 48f) * h
            val yZero = 0.5f * h
            val yMinus12 = (0.5f + 12f / 48f) * h

            drawLine(color = DjSurfaceBorder.copy(alpha = 0.4f), start = Offset(0f, yPlus12), end = Offset(w, yPlus12), strokeWidth = 1f)
            drawLine(color = DjSurfaceBorder.copy(alpha = 0.8f), start = Offset(0f, yZero), end = Offset(w, yZero), strokeWidth = 1.5f)
            drawLine(color = DjSurfaceBorder.copy(alpha = 0.4f), start = Offset(0f, yMinus12), end = Offset(w, yMinus12), strokeWidth = 1f)

            // Build response curve path
            val curvePath = Path()
            val fillPath = Path()

            for (i in 0 until numPoints) {
                val x = (i.toFloat() / (numPoints - 1)) * w
                val dbVal = responseDb[i].coerceIn(-24f, 24f)
                val y = (0.5f - (dbVal / 48f)) * h

                if (i == 0) {
                    curvePath.moveTo(x, y)
                    fillPath.moveTo(x, yZero)
                    fillPath.lineTo(x, y)
                } else {
                    curvePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            fillPath.lineTo(w, yZero)
            fillPath.close()

            // Draw filled area under the curve
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(DeckACyan.copy(alpha = 0.25f), Color.Transparent),
                    startY = 0f,
                    endY = h
                )
            )

            // Draw glowing response curve
            drawPath(
                path = curvePath,
                color = if (isEqEnabled) DeckACyan else TextMuted,
                style = Stroke(width = 2.5f)
            )

            // Draw band marker nodes
            for (i in bands.indices) {
                val b = bands[i]
                val bx = (((log10(b.frequencyHz) - logMin) / (logMax - logMin)) * w).toFloat()
                val by = (0.5f - (b.gainDb.toFloat().coerceIn(-24f, 24f) / 48f)) * h
                val isSelected = i == selectedBandIndex

                if (isSelected) {
                    // Outer glow ring for selected band
                    drawCircle(
                        color = DeckACyan.copy(alpha = 0.35f),
                        radius = 16f,
                        center = Offset(bx, by)
                    )
                    drawCircle(
                        color = DeckACyan,
                        radius = 9f,
                        center = Offset(bx, by)
                    )
                    drawCircle(
                        color = DjObsidian,
                        radius = 6f,
                        center = Offset(bx, by)
                    )
                } else {
                    drawCircle(
                        color = if (b.isEnabled) DeckACyan.copy(alpha = 0.8f) else TextMuted.copy(alpha = 0.5f),
                        radius = 6.5f,
                        center = Offset(bx, by)
                    )
                    drawCircle(
                        color = DjObsidian,
                        radius = 3.5f,
                        center = Offset(bx, by)
                    )
                }
            }
        }
    }
}

private fun formatFreq(freqHz: Double): String {
    return if (freqHz >= 1000.0) {
        String.format(Locale.US, "%.1f kHz", freqHz / 1000.0)
    } else {
        String.format(Locale.US, "%.0f Hz", freqHz)
    }
}
