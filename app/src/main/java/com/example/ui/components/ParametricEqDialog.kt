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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.audio.EqBand
import com.example.audio.EqFilterType
import com.example.audio.EqUiMode
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
import kotlin.math.roundToInt

private val BandColors = listOf(
    Color(0xFFFF5252), // Red - Sub Bass
    Color(0xFFFF7A00), // Orange - Bass
    Color(0xFFFFD600), // Yellow - Low Mid
    Color(0xFF00E676), // Green - Mid
    Color(0xFF00E5FF), // Cyan - Vocal Presence
    Color(0xFF2979FF), // Blue - High Mid
    Color(0xFF7C4DFF), // Purple - Treble
    Color(0xFFD500F9), // Magenta - High Treble
    Color(0xFFFF4081), // Pink - Air
    Color(0xFF00B0FF), // Light Blue - Ultra Air
    Color(0xFFAEEA00), // Lime
    Color(0xFFFF6D00)  // Amber
)

fun getBandColor(index: Int): Color = BandColors[index % BandColors.size]

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
    val uiMode by eqManager.uiMode.collectAsState()
    val abMode by eqManager.abMode.collectAsState()
    val isSpectrumEnabled by eqManager.isSpectrumEnabled.collectAsState()
    val liveSpectrum by eqManager.liveSpectrum.collectAsState()
    val soloBandIndex by eqManager.soloBandIndex.collectAsState()

    var selectedBandIndex by remember { mutableIntStateOf(0) }
    var showSavePresetDialog by remember { mutableStateOf(false) }

    if (selectedBandIndex !in currentBands.indices && currentBands.isNotEmpty()) {
        selectedBandIndex = 0
    }

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
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.96f)
                .testTag("parametric_eq_dialog"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DjObsidian),
            border = BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxHeight()
            ) {
                // Header Bar: Title + UI Modes + EQ Master Switch + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(20.dp))
                        Text(
                            text = "PARAMETRIC EQ",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }

                    // Mode selector pills: BASIC / ADVANCED / EXPERT
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = DjSurfaceDark,
                        border = BorderStroke(1.dp, DjSurfaceBorder)
                    ) {
                        Row(modifier = Modifier.padding(2.dp)) {
                            EqUiMode.values().forEach { mode ->
                                val isSelected = uiMode == mode
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { eqManager.setUiMode(mode) },
                                    color = if (isSelected) DeckACyan else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = mode.displayName.uppercase(),
                                        color = if (isSelected) DjObsidian else TextSecondary,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Master EQ toggle
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (isEqEnabled) "ON" else "BYPASS",
                                color = if (isEqEnabled) NeonGreen else TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Switch(
                                checked = isEqEnabled,
                                onCheckedChange = { eqManager.setEqEnabled(it) },
                                modifier = Modifier.size(width = 40.dp, height = 22.dp),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DjObsidian,
                                    checkedTrackColor = DeckACyan,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = DjSurfaceDark
                                )
                            )
                        }

                        IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Toolbar: A/B Comparison, Spectrum Toggle, Auto-Headroom
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // A/B Instant Comparison
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("A/B", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { if (abMode != "A") eqManager.toggleAb() },
                            shape = RoundedCornerShape(4.dp),
                            color = if (abMode == "A") DeckACyan else DjSurfaceDark,
                            border = BorderStroke(1.dp, if (abMode == "A") DeckACyan else DjSurfaceBorder)
                        ) {
                            Text("A", color = if (abMode == "A") DjObsidian else TextSecondary, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { if (abMode != "B") eqManager.toggleAb() },
                            shape = RoundedCornerShape(4.dp),
                            color = if (abMode == "B") DeckACyan else DjSurfaceDark,
                            border = BorderStroke(1.dp, if (abMode == "B") DeckACyan else DjSurfaceBorder)
                        ) {
                            Text("B", color = if (abMode == "B") DjObsidian else TextSecondary, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                        OutlinedButton(
                            onClick = { eqManager.copyAtoB() },
                            border = BorderStroke(1.dp, DjSurfaceBorder),
                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 1.dp),
                            modifier = Modifier.height(22.dp)
                        ) {
                            Text("A→B", color = TextMuted, fontSize = 8.sp)
                        }
                        OutlinedButton(
                            onClick = { eqManager.copyBtoA() },
                            border = BorderStroke(1.dp, DjSurfaceBorder),
                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 1.dp),
                            modifier = Modifier.height(22.dp)
                        ) {
                            Text("B→A", color = TextMuted, fontSize = 8.sp)
                        }
                    }

                    // Spectrum & Auto-Headroom
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Spectrum Analyzer toggle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { eqManager.setSpectrumEnabled(!isSpectrumEnabled) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "RTA",
                                color = if (isSpectrumEnabled) DeckACyan else TextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isSpectrumEnabled) DeckACyan else DjSurfaceBorder)
                            )
                        }

                        // Auto-Headroom toggle
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "HEADROOM",
                                color = if (autoHeadroomEnabled) DeckACyan else TextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Switch(
                                checked = autoHeadroomEnabled,
                                onCheckedChange = { eqManager.setAutoHeadroomEnabled(it) },
                                modifier = Modifier.size(width = 34.dp, height = 20.dp),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DjObsidian,
                                    checkedTrackColor = DeckACyan,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = DjSurfaceDark
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Interactive Frequency Response Curve Graph (with 32-bin spectrum analyzer & solo banner)
                FrequencyResponseCurveGraph(
                    bands = currentBands,
                    preampDb = preampDb,
                    autoHeadroom = autoHeadroomEnabled,
                    isEqEnabled = isEqEnabled,
                    selectedBandIndex = selectedBandIndex,
                    soloBandIndex = soloBandIndex,
                    isSpectrumEnabled = isSpectrumEnabled,
                    liveSpectrum = liveSpectrum,
                    onSelectBand = { selectedBandIndex = it },
                    onUpdateBand = { idx, freq, gain ->
                        val b = currentBands.getOrNull(idx) ?: return@FrequencyResponseCurveGraph
                        eqManager.updateBand(idx, freq, gain, b.q, b.isEnabled, b.type)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(135.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Presets Carousel & Add/Remove Band Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("PRESETS (${presets.size})", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (uiMode != EqUiMode.BASIC) {
                            if (currentBands.size < 16) {
                                OutlinedButton(
                                    onClick = {
                                        eqManager.addBand()
                                        selectedBandIndex = currentBands.size
                                    },
                                    border = BorderStroke(1.dp, DeckACyan.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
                                    modifier = Modifier.height(23.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(11.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("+ Band", color = DeckACyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (currentBands.size > 1) {
                                OutlinedButton(
                                    onClick = {
                                        val idx = selectedBandIndex.coerceIn(0, currentBands.size - 1)
                                        eqManager.removeBand(idx)
                                        if (selectedBandIndex >= currentBands.size - 1) {
                                            selectedBandIndex = maxOf(0, currentBands.size - 2)
                                        }
                                    },
                                    border = BorderStroke(1.dp, NeonRed.copy(alpha = 0.4f)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
                                    modifier = Modifier.height(23.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = NeonRed, modifier = Modifier.size(11.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("- Band", color = NeonRed, fontSize = 9.sp)
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { showSavePresetDialog = true },
                            border = BorderStroke(1.dp, DeckACyan.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
                            modifier = Modifier.height(23.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(11.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("Save", color = DeckACyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { eqManager.resetToFlat() },
                            border = BorderStroke(1.dp, DjSurfaceBorder),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
                            modifier = Modifier.height(23.dp)
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, tint = TextMuted, modifier = Modifier.size(11.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("Flat", color = TextSecondary, fontSize = 9.sp)
                        }
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(vertical = 3.dp)
                ) {
                    items(presets) { preset ->
                        val isSelected = preset.id == activePresetId
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { eqManager.applyPreset(preset.id) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) DeckACyan.copy(alpha = 0.2f) else DjSurfaceCard,
                            border = BorderStroke(1.dp, if (isSelected) DeckACyan else DjSurfaceBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = preset.name,
                                    color = if (isSelected) DeckACyan else TextPrimary,
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (!preset.isBuiltIn) {
                                    IconButton(
                                        onClick = { eqManager.deleteCustomPreset(preset.id) },
                                        modifier = Modifier.size(15.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = NeonRed, modifier = Modifier.size(10.dp))
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
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "PREAMP: ${String.format(Locale.US, "%+.1f dB", preampDb)}",
                        color = if (preampDb > 0.0) NeonAmber else TextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(105.dp)
                    )
                    Slider(
                        value = preampDb.toFloat(),
                        onValueChange = { eqManager.setPreamp(it.toDouble()) },
                        valueRange = -24.0f..24.0f,
                        colors = SliderDefaults.colors(thumbColor = DeckACyan, activeTrackColor = DeckACyan, inactiveTrackColor = DjSurfaceDark),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Main body switches depending on UI mode
                when (uiMode) {
                    EqUiMode.BASIC -> {
                        BasicDjEqView(
                            currentBands = currentBands,
                            onUpdateBandGain = { idx, gain ->
                                val b = currentBands.getOrNull(idx) ?: return@BasicDjEqView
                                eqManager.updateBand(idx, b.frequencyHz, gain, b.q, b.isEnabled, b.type)
                            },
                            onResetBand = { idx -> eqManager.resetBand(idx) },
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                    EqUiMode.ADVANCED, EqUiMode.EXPERT -> {
                        AdvancedExpertEqView(
                            currentBands = currentBands,
                            selectedBandIndex = selectedBandIndex,
                            soloBandIndex = soloBandIndex,
                            preampDb = preampDb,
                            autoHeadroomEnabled = autoHeadroomEnabled,
                            isExpertMode = (uiMode == EqUiMode.EXPERT),
                            onSelectBand = { selectedBandIndex = it },
                            onToggleSolo = { idx ->
                                eqManager.setSoloBand(if (soloBandIndex == idx) null else idx)
                            },
                            onUpdateBand = { idx, freq, gain, q, isEnabled, type ->
                                eqManager.updateBand(idx, freq, gain, q, isEnabled, type)
                            },
                            onUpdateBandType = { idx, type ->
                                eqManager.updateBandType(idx, type)
                            },
                            onResetBand = { idx -> eqManager.resetBand(idx) },
                            onEnableAutoHeadroom = { eqManager.setAutoHeadroomEnabled(true) },
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Clean, tactile 3-Band DJ style view (Low, Mid, High).
 */
@Composable
private fun BasicDjEqView(
    currentBands: List<EqBand>,
    onUpdateBandGain: (Int, Double) -> Unit,
    onResetBand: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val lowIdx = currentBands.indexOfFirst { it.frequencyHz in 40.0..250.0 }.let { if (it >= 0) it else 0 }
    val midIdx = currentBands.indexOfFirst { it.frequencyHz in 700.0..1800.0 }.let { if (it >= 0) it else (currentBands.size / 2) }
    val highIdx = currentBands.indexOfLast { it.frequencyHz in 5000.0..16000.0 }.let { if (it >= 0) it else (currentBands.size - 1) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DjSurfaceCard,
        border = BorderStroke(1.dp, DjSurfaceBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                "3-BAND DJ QUICK CONTROL",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            val djBands = listOf(
                Triple("LOW (BASS)", lowIdx, getBandColor(lowIdx)),
                Triple("MID (VOCALS)", midIdx, getBandColor(midIdx)),
                Triple("HIGH (TREBLE)", highIdx, getBandColor(highIdx))
            )

            djBands.forEach { (label, idx, color) ->
                val band = currentBands.getOrNull(idx)
                if (band != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                                Text(label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text("(${formatFreq(band.frequencyHz)})", color = TextMuted, fontSize = 9.sp)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = String.format(Locale.US, "%+.1f dB", band.gainDb),
                                    color = if (band.gainDb != 0.0) color else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                                OutlinedButton(
                                    onClick = { onResetBand(idx) },
                                    border = BorderStroke(1.dp, DjSurfaceBorder),
                                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                                    modifier = Modifier.height(20.dp)
                                ) {
                                    Text("0", color = TextMuted, fontSize = 8.sp)
                                }
                            }
                        }
                        Slider(
                            value = band.gainDb.toFloat(),
                            onValueChange = { onUpdateBandGain(idx, it.toDouble()) },
                            valueRange = -24.0f..24.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = color,
                                activeTrackColor = color,
                                inactiveTrackColor = DjSurfaceDark
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Advanced & Expert EQ View:
 * Full multi-band strip, band controls, solo audition, filter types, and in Expert mode: direct numeric inputs and clipping risk warnings.
 */
@Composable
private fun AdvancedExpertEqView(
    currentBands: List<EqBand>,
    selectedBandIndex: Int,
    soloBandIndex: Int?,
    preampDb: Double,
    autoHeadroomEnabled: Boolean,
    isExpertMode: Boolean,
    onSelectBand: (Int) -> Unit,
    onToggleSolo: (Int) -> Unit,
    onUpdateBand: (Int, Double, Double, Double, Boolean, EqFilterType) -> Unit,
    onUpdateBandType: (Int, EqFilterType) -> Unit,
    onResetBand: (Int) -> Unit,
    onEnableAutoHeadroom: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    Column(modifier = modifier) {
        // Bands Selection Strip (colored pills, type, freq, gain, solo)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(currentBands) { index, band ->
                val isSelected = index == selectedBandIndex
                val isSolo = soloBandIndex == index
                val bandColor = getBandColor(index)

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelectBand(index) },
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSelected) bandColor.copy(alpha = 0.25f) else (if (!band.isEnabled) DjSurfaceDark.copy(alpha = 0.4f) else DjSurfaceDark),
                    border = BorderStroke(
                        if (isSelected) 1.5.dp else 1.dp,
                        if (isSolo) NeonAmber else (if (isSelected) bandColor else DjSurfaceBorder)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(bandColor))
                            Text(
                                text = "${index + 1}",
                                color = if (isSelected) bandColor else TextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = band.type.shortCode,
                                color = if (isSelected) TextPrimary else TextMuted,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            // Solo chip indicator
                            if (isSolo) {
                                Surface(
                                    shape = RoundedCornerShape(2.dp),
                                    color = NeonAmber,
                                    modifier = Modifier.padding(start = 1.dp)
                                ) {
                                    Text("S", color = DjObsidian, fontSize = 7.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 2.dp))
                                }
                            }
                        }
                        Text(
                            text = formatFreq(band.frequencyHz),
                            color = if (isSelected) TextPrimary else TextSecondary,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (band.type == EqFilterType.HIGH_PASS || band.type == EqFilterType.LOW_PASS || band.type == EqFilterType.NOTCH || band.type == EqFilterType.BAND_PASS) {
                                band.type.shortCode
                            } else {
                                String.format(Locale.US, "%+.1fdB", band.gainDb)
                            },
                            color = if (isSelected) bandColor else (if (band.gainDb != 0.0 && band.isEnabled) DeckACyan else TextMuted),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Selected Band Detailed Controls
        if (selectedBandIndex in currentBands.indices) {
            val band = currentBands[selectedBandIndex]
            val bandColor = getBandColor(selectedBandIndex)
            val isSolo = soloBandIndex == selectedBandIndex

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DjSurfaceCard,
                border = BorderStroke(1.dp, DjSurfaceBorder),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Expert Mode Clipping Warning
                    if (isExpertMode) {
                        val totalBoost = currentBands.filter { it.isEnabled && it.gainDb > 0.0 }.sumOf { it.gainDb } + preampDb
                        if (totalBoost > 6.0 && !autoHeadroomEnabled) {
                            item {
                                Surface(
                                    color = NeonAmber.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, NeonAmber),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(Icons.Default.Warning, contentDescription = null, tint = NeonAmber, modifier = Modifier.size(14.dp))
                                            Text(
                                                text = "Clipping Risk: +${String.format(Locale.US, "%.1f", totalBoost)} dB boost without Auto-Headroom",
                                                color = NeonAmber,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        TextButton(
                                            onClick = onEnableAutoHeadroom,
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(22.dp)
                                        ) {
                                            Text("Fix Headroom", color = DeckACyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Band Title, Solo Audition, Reset, and Enable Switch
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(bandColor))
                                Column {
                                    Text("Band #${band.id + 1}: ${band.name}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text("${formatFreq(band.frequencyHz)} • ${band.type.displayName}", color = TextMuted, fontSize = 9.sp)
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                // Solo button
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onToggleSolo(selectedBandIndex) },
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSolo) NeonAmber else DjSurfaceDark,
                                    border = BorderStroke(1.dp, if (isSolo) NeonAmber else DjSurfaceBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Headphones,
                                            contentDescription = null,
                                            tint = if (isSolo) DjObsidian else TextMuted,
                                            modifier = Modifier.size(10.dp)
                                        )
                                        Text(
                                            "SOLO",
                                            color = if (isSolo) DjObsidian else TextSecondary,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = { onResetBand(selectedBandIndex) },
                                    border = BorderStroke(1.dp, DjSurfaceBorder),
                                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 1.dp),
                                    modifier = Modifier.height(22.dp)
                                ) {
                                    Text("Reset", color = TextSecondary, fontSize = 8.sp)
                                }

                                Text(if (band.isEnabled) "ON" else "OFF", color = if (band.isEnabled) NeonGreen else TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                Switch(
                                    checked = band.isEnabled,
                                    onCheckedChange = {
                                        onUpdateBand(selectedBandIndex, band.frequencyHz, band.gainDb, band.q, it, band.type)
                                    },
                                    modifier = Modifier.size(width = 34.dp, height = 20.dp),
                                    colors = SwitchDefaults.colors(checkedThumbColor = DjObsidian, checkedTrackColor = bandColor)
                                )
                            }
                        }
                    }

                    // Filter Type Selector (Chips)
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("FILTER TYPE", color = TextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(EqFilterType.values()) { type ->
                                    val isCurrentType = band.type == type
                                    Surface(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { onUpdateBandType(selectedBandIndex, type) },
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isCurrentType) bandColor else DjSurfaceDark,
                                        border = BorderStroke(1.dp, if (isCurrentType) bandColor else DjSurfaceBorder)
                                    ) {
                                        Text(
                                            text = type.displayName,
                                            color = if (isCurrentType) DjObsidian else TextSecondary,
                                            fontSize = 8.sp,
                                            fontWeight = if (isCurrentType) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Expert Mode Direct Numeric Input Fields
                    if (isExpertMode) {
                        item {
                            Surface(
                                color = DjSurfaceDark,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, DjSurfaceBorder),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Freq input
                                    var freqText by remember(band.frequencyHz) { mutableStateOf(band.frequencyHz.roundToInt().toString()) }
                                    OutlinedTextField(
                                        value = freqText,
                                        onValueChange = {
                                            freqText = it
                                            val parsed = it.toDoubleOrNull()
                                            if (parsed != null && parsed in 20.0..20000.0) {
                                                onUpdateBand(selectedBandIndex, parsed, band.gainDb, band.q, band.isEnabled, band.type)
                                            }
                                        },
                                        label = { Text("Freq (Hz)", fontSize = 8.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = bandColor,
                                            unfocusedBorderColor = DjSurfaceBorder,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        )
                                    )

                                    // Gain input
                                    var gainText by remember(band.gainDb) { mutableStateOf(String.format(Locale.US, "%.1f", band.gainDb)) }
                                    OutlinedTextField(
                                        value = gainText,
                                        onValueChange = {
                                            gainText = it
                                            val parsed = it.toDoubleOrNull()
                                            if (parsed != null && parsed in -24.0..24.0) {
                                                onUpdateBand(selectedBandIndex, band.frequencyHz, parsed, band.q, band.isEnabled, band.type)
                                            }
                                        },
                                        enabled = (band.type == EqFilterType.PEAKING || band.type == EqFilterType.LOW_SHELF || band.type == EqFilterType.HIGH_SHELF),
                                        label = { Text("Gain (dB)", fontSize = 8.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = bandColor,
                                            unfocusedBorderColor = DjSurfaceBorder,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        )
                                    )

                                    // Q input
                                    var qText by remember(band.q) { mutableStateOf(String.format(Locale.US, "%.2f", band.q)) }
                                    OutlinedTextField(
                                        value = qText,
                                        onValueChange = {
                                            qText = it
                                            val parsed = it.toDoubleOrNull()
                                            if (parsed != null && parsed in 0.1..20.0) {
                                                onUpdateBand(selectedBandIndex, band.frequencyHz, band.gainDb, parsed, band.isEnabled, band.type)
                                            }
                                        },
                                        label = { Text("Q Factor", fontSize = 8.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = bandColor,
                                            unfocusedBorderColor = DjSurfaceBorder,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        )
                                    )
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

                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("FREQUENCY", color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text(formatFreq(band.frequencyHz), color = bandColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = sliderPos,
                                onValueChange = { pos ->
                                    val newFreq = 10.0.pow(logMin + pos * (logMax - logMin))
                                    onUpdateBand(selectedBandIndex, newFreq, band.gainDb, band.q, band.isEnabled, band.type)
                                },
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(thumbColor = bandColor, activeTrackColor = bandColor, inactiveTrackColor = DjSurfaceDark)
                            )
                        }
                    }

                    // Gain Slider (Only applicable for PEAKING, LOW_SHELF, HIGH_SHELF)
                    if (band.type == EqFilterType.PEAKING || band.type == EqFilterType.LOW_SHELF || band.type == EqFilterType.HIGH_SHELF) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("GAIN", color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Text(String.format(Locale.US, "%+.1f dB", band.gainDb), color = bandColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = band.gainDb.toFloat(),
                                    onValueChange = {
                                        onUpdateBand(selectedBandIndex, band.frequencyHz, it.toDouble(), band.q, band.isEnabled, band.type)
                                    },
                                    valueRange = -24.0f..24.0f,
                                    colors = SliderDefaults.colors(thumbColor = bandColor, activeTrackColor = bandColor, inactiveTrackColor = DjSurfaceDark)
                                )
                            }
                        }
                    }

                    // Q / Bandwidth Slider (Power-law curve for fine touch control around 0.5 - 2.0)
                    item {
                        val qSliderPos = (ln(band.q.coerceIn(0.1, 20.0) / 0.1) / ln(200.0)).toFloat().coerceIn(0f, 1f)

                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Q / BANDWIDTH", color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text(String.format(Locale.US, "Q = %.2f", band.q), color = bandColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = qSliderPos,
                                onValueChange = { pos ->
                                    val newQ = 0.1 * (200.0.pow(pos.toDouble()))
                                    onUpdateBand(selectedBandIndex, band.frequencyHz, band.gainDb, newQ, band.isEnabled, band.type)
                                },
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(thumbColor = bandColor, activeTrackColor = bandColor, inactiveTrackColor = DjSurfaceDark)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the exact mathematical frequency response curve calculated from RBJ biquad formulas.
 * Includes interactive frequency/gain touch manipulation, colored band nodes, real-time 32-bin spectrum analyzer bars, and solo audition indication.
 */
@Composable
private fun FrequencyResponseCurveGraph(
    bands: List<EqBand>,
    preampDb: Double,
    autoHeadroom: Boolean,
    isEqEnabled: Boolean,
    selectedBandIndex: Int,
    soloBandIndex: Int?,
    isSpectrumEnabled: Boolean,
    liveSpectrum: FloatArray,
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

    val responseDb = remember(bands, preampDb, autoHeadroom, isEqEnabled, soloBandIndex) {
        val outDb = FloatArray(numPoints)
        if (!isEqEnabled) {
            outDb.fill(0f)
        } else {
            ParametricEq.computeFrequencyResponseDb(sampleRate, bands, preampDb, autoHeadroom, frequencies, outDb, soloBandIndex)
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
                        if (distSq < closestDistSq && distSq < 1600f) {
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
                            if (distSq < closestDistSq && distSq < 2500f) {
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

            // Grid frequency vertical lines: 50, 100, 250, 500, 1k, 2.5k, 5k, 10k
            val gridFreqs = floatArrayOf(50f, 100f, 250f, 500f, 1000f, 2500f, 5000f, 10000f)
            for (gf in gridFreqs) {
                val gx = (((log10(gf.toDouble()) - logMin) / (logMax - logMin)) * w).toFloat()
                drawLine(
                    color = DjSurfaceBorder.copy(alpha = 0.4f),
                    start = Offset(gx, 0f),
                    end = Offset(gx, h),
                    strokeWidth = 1f
                )
            }

            // Grid dB horizontal lines: +12dB, 0dB, -12dB
            val yPlus12 = (0.5f - 12f / 48f) * h
            val yZero = 0.5f * h
            val yMinus12 = (0.5f + 12f / 48f) * h

            drawLine(color = DjSurfaceBorder.copy(alpha = 0.35f), start = Offset(0f, yPlus12), end = Offset(w, yPlus12), strokeWidth = 1f)
            drawLine(color = DjSurfaceBorder.copy(alpha = 0.7f), start = Offset(0f, yZero), end = Offset(w, yZero), strokeWidth = 1.2f)
            drawLine(color = DjSurfaceBorder.copy(alpha = 0.35f), start = Offset(0f, yMinus12), end = Offset(w, yMinus12), strokeWidth = 1f)

            // Draw Real-time 32-bin Spectrum Analyzer Bars behind the curve
            if (isSpectrumEnabled && liveSpectrum.isNotEmpty()) {
                val barCount = liveSpectrum.size
                val barSpacing = w / barCount
                for (k in 0 until barCount) {
                    val f = 20.0 * 10.0.pow(k * 3.0 / (barCount - 1).coerceAtLeast(1))
                    val bx = (((log10(f) - logMin) / (logMax - logMin)) * w).toFloat()
                    val mag = liveSpectrum[k].coerceIn(0f, 1f)
                    if (mag > 0.01f) {
                        val barH = mag * h * 0.85f
                        val barW = (barSpacing * 0.7f).coerceAtLeast(2f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(DeckACyan.copy(alpha = 0.35f), DeckACyan.copy(alpha = 0.05f)),
                                startY = h - barH,
                                endY = h
                            ),
                            topLeft = Offset(bx - barW / 2, h - barH),
                            size = Size(barW, barH)
                        )
                    }
                }
            }

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
            val curveAccentColor = if (soloBandIndex != null) NeonAmber else DeckACyan
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(curveAccentColor.copy(alpha = 0.22f), Color.Transparent),
                    startY = 0f,
                    endY = h
                )
            )

            // Draw glowing response curve
            drawPath(
                path = curvePath,
                color = if (isEqEnabled) curveAccentColor else TextMuted,
                style = Stroke(width = 2.2f)
            )

            // Draw band marker nodes with distinct colors per band
            for (i in bands.indices) {
                val b = bands[i]
                val bx = (((log10(b.frequencyHz) - logMin) / (logMax - logMin)) * w).toFloat()
                val by = (0.5f - (b.gainDb.toFloat().coerceIn(-24f, 24f) / 48f)) * h
                val isSelected = i == selectedBandIndex
                val isSolo = soloBandIndex == i
                val nodeColor = getBandColor(i)

                if (isSelected || isSolo) {
                    // Outer glow ring
                    drawCircle(
                        color = (if (isSolo) NeonAmber else nodeColor).copy(alpha = 0.35f),
                        radius = 15f,
                        center = Offset(bx, by)
                    )
                    drawCircle(
                        color = if (isSolo) NeonAmber else nodeColor,
                        radius = 8.5f,
                        center = Offset(bx, by)
                    )
                    drawCircle(
                        color = DjObsidian,
                        radius = 5f,
                        center = Offset(bx, by)
                    )
                } else {
                    drawCircle(
                        color = if (b.isEnabled) nodeColor.copy(alpha = 0.85f) else TextMuted.copy(alpha = 0.4f),
                        radius = 6f,
                        center = Offset(bx, by)
                    )
                    drawCircle(
                        color = DjObsidian,
                        radius = 3f,
                        center = Offset(bx, by)
                    )
                }
            }
        }

        // Solo Audition banner overlay
        if (soloBandIndex != null && soloBandIndex in bands.indices) {
            val soloBand = bands[soloBandIndex]
            Surface(
                color = NeonAmber.copy(alpha = 0.88f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
            ) {
                Text(
                    text = "AUDITIONING BAND #${soloBandIndex + 1}: ${soloBand.name} (${formatFreq(soloBand.frequencyHz)})",
                    color = DjObsidian,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
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
