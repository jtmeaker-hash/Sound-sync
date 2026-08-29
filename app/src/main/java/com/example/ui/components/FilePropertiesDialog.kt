package com.example.ui.components

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.AudioQualityRating
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
import com.example.ui.theme.NeonRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun FilePropertiesDialog(
    track: Track,
    onDismiss: () -> Unit,
    onSave: (Track) -> Unit,
    onAutoTag: (Track) -> Unit,
    onInspectSpectrogram: (Track) -> Unit,
    onDelete: (Track) -> Unit
) {
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.album) }
    var genre by remember { mutableStateOf(track.genre) }
    var subGenre by remember { mutableStateOf(track.subGenre) }
    var bpmString by remember { mutableStateOf(track.bpm.toString()) }
    var musicalKey by remember { mutableStateOf(track.musicalKey) }
    var energyRating by remember { mutableIntStateOf(track.energyRating) }

    val isLossless = track.qualityRating.isLossless
    val isSuspicious = track.qualityRating == AudioQualityRating.SUSPICIOUS_UPSCALED

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .testTag("file_properties_dialog"),
            colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "FILE INSPECTOR & TAGS",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = track.filePath.substringAfterLast('/'),
                            color = DeckACyan,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                // File System & Audio Tech Specs Box
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = DjSurfaceCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "AUDIO SPECS & SYSTEM PATH",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )

                        // Path
                        Row(verticalAlignment = Alignment.Top) {
                            Text("Path: ", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text(track.filePath, color = TextPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }

                        // Quality pill row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isLossless) NeonGreen.copy(alpha = 0.2f) else if (isSuspicious) NeonRed.copy(alpha = 0.2f) else DjSurfaceElevated,
                                border = if (isLossless) androidx.compose.foundation.BorderStroke(1.dp, NeonGreen) else if (isSuspicious) androidx.compose.foundation.BorderStroke(1.dp, NeonRed) else null
                            ) {
                                Text(
                                    text = track.qualityRating.label,
                                    color = if (isLossless) NeonGreen else if (isSuspicious) NeonRed else NeonAmber,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            Text(
                                text = "${track.fileSizeMb} MB · ${track.durationSeconds / 60}:${String.format(Locale.US, "%02d", track.durationSeconds % 60)}",
                                color = TextSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Text(
                            text = track.qualityRating.description,
                            color = TextSecondary,
                            fontSize = 9.5.sp,
                            lineHeight = 13.sp
                        )
                    }
                }

                // Metadata Fields
                Text(
                    text = "ID3 & DJ TAGS",
                    color = DeckACyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Track Title", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = DeckACyan,
                        unfocusedBorderColor = DjSurfaceBorder
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = artist,
                        onValueChange = { artist = it },
                        label = { Text("Artist", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = DeckACyan,
                            unfocusedBorderColor = DjSurfaceBorder
                        )
                    )

                    OutlinedTextField(
                        value = album,
                        onValueChange = { album = it },
                        label = { Text("Album", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = DeckACyan,
                            unfocusedBorderColor = DjSurfaceBorder
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = genre,
                        onValueChange = { genre = it },
                        label = { Text("Genre", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = DeckACyan,
                            unfocusedBorderColor = DjSurfaceBorder
                        )
                    )

                    OutlinedTextField(
                        value = subGenre,
                        onValueChange = { subGenre = it },
                        label = { Text("Subgenre", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = DeckACyan,
                            unfocusedBorderColor = DjSurfaceBorder
                        )
                    )
                }

                // BPM & Key Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = bpmString,
                        onValueChange = { bpmString = it },
                        label = { Text("BPM", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = NeonAmber,
                            unfocusedTextColor = NeonAmber,
                            focusedBorderColor = NeonAmber,
                            unfocusedBorderColor = DjSurfaceBorder
                        )
                    )

                    OutlinedTextField(
                        value = musicalKey,
                        onValueChange = { musicalKey = it },
                        label = { Text("Camelot Key (e.g. 8A)", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = DeckACyan,
                            unfocusedTextColor = DeckACyan,
                            focusedBorderColor = DeckACyan,
                            unfocusedBorderColor = DjSurfaceBorder
                        )
                    )
                }

                // Energy Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("DJ Energy Rating", color = TextSecondary, fontSize = 11.sp)
                        Text("$energyRating / 10", color = DeckBPink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = energyRating.toFloat(),
                        onValueChange = { energyRating = it.toInt() },
                        valueRange = 1f..10f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = DeckBPink,
                            activeTrackColor = DeckBPink,
                            inactiveTrackColor = DjSurfaceElevated
                        )
                    )
                }

                // Action Tools
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // AI Tag Button
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onAutoTag(track) },
                        shape = RoundedCornerShape(8.dp),
                        color = NeonPurple.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonPurple)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("AI Auto-Tag", color = NeonPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Spectrum Button
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onInspectSpectrogram(track) },
                        shape = RoundedCornerShape(8.dp),
                        color = DeckACyan.copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Spectrogram", color = DeckACyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Save & Delete actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Safe Delete / Trash Button
                    Button(
                        onClick = {
                            onDelete(track)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonRed.copy(alpha = 0.2f), contentColor = NeonRed),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonRed)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Trash", modifier = Modifier.size(16.dp))
                    }

                    // Save Button
                    Button(
                        onClick = {
                            val parsedBpm = bpmString.toDoubleOrNull() ?: track.bpm
                            val updated = track.copy(
                                title = title,
                                artist = artist,
                                album = album,
                                genre = genre,
                                subGenre = subGenre,
                                bpm = parsedBpm,
                                musicalKey = musicalKey,
                                energyRating = energyRating
                            )
                            onSave(updated)
                        },
                        modifier = Modifier.weight(1f).testTag("save_track_properties_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SAVE METADATA", fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
