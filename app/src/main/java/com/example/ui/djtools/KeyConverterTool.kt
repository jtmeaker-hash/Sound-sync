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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metadata.CamelotKey
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

data class MusicalKeyInfo(
    val rootName: String,
    val isMinor: Boolean,
    val camelot: String,
    val openKey: String,
    val relativeKey: String,
    val plusOneKey: String,
    val minusOneKey: String,
    val notesInScale: List<String>
)

object KeyConverterData {
    val roots = listOf(
        "C", "C# / Db", "D", "Eb", "E", "F", "F# / Gb", "G", "Ab", "A", "Bb", "B"
    )

    private val pitchClasses = mapOf(
        0 to ("C" to listOf("C", "D", "E", "F", "G", "A", "B")),
        1 to ("C# / Db" to listOf("C#", "D#", "E#", "F#", "G#", "A#", "B#")),
        2 to ("D" to listOf("D", "E", "F#", "G", "A", "B", "C#")),
        3 to ("Eb" to listOf("Eb", "F", "G", "Ab", "Bb", "C", "D")),
        4 to ("E" to listOf("E", "F#", "G#", "A", "B", "C#", "D#")),
        5 to ("F" to listOf("F", "G", "A", "Bb", "C", "D", "E")),
        6 to ("F# / Gb" to listOf("F#", "G#", "A#", "B", "C#", "D#", "E#")),
        7 to ("G" to listOf("G", "A", "B", "C", "D", "E", "F#")),
        8 to ("Ab" to listOf("Ab", "Bb", "C", "Db", "Eb", "F", "G")),
        9 to ("A" to listOf("A", "B", "C#", "D", "E", "F#", "G#")),
        10 to ("Bb" to listOf("Bb", "C", "D", "Eb", "F", "G", "A")),
        11 to ("B" to listOf("B", "C#", "D#", "E", "F#", "G#", "A#"))
    )

    fun getKeyInfo(pitchClass: Int, isMinor: Boolean): MusicalKeyInfo {
        val root = roots[pitchClass]
        val camelot = CamelotKey.fromPitchClass(pitchClass, isMinor)
        val camelotNumber = camelot.dropLast(1).toInt()
        val letter = camelot.last()

        val plusNumber = if (camelotNumber == 12) 1 else camelotNumber + 1
        val minusNumber = if (camelotNumber == 1) 12 else camelotNumber - 1

        val relativeLetter = if (letter == 'A') 'B' else 'A'
        val relativeCamelot = "$camelotNumber$relativeLetter"

        val openKey = "${camelotNumber}${if (isMinor) "m" else "d"}"

        val scale = pitchClasses[pitchClass]?.second ?: emptyList()

        return MusicalKeyInfo(
            rootName = "$root ${if (isMinor) "Minor" else "Major"}",
            isMinor = isMinor,
            camelot = camelot,
            openKey = openKey,
            relativeKey = relativeCamelot,
            plusOneKey = "$plusNumber$letter",
            minusOneKey = "$minusNumber$letter",
            notesInScale = scale
        )
    }
}

/**
 * DJ Tools - Key Converter & Harmonic Mixing Assistant.
 * Maps standard notation (C, D, E, F, G, A, B, #, b, Maj, Min) to Camelot wheel and harmonic mixing rules.
 */
@Composable
fun KeyConverterTool(
    modifier: Modifier = Modifier
) {
    var selectedPitchClass by remember { mutableIntStateOf(9) } // Default A
    var isMinor by remember { mutableStateOf(true) } // Default A Minor (8A)

    val keyInfo = remember(selectedPitchClass, isMinor) {
        KeyConverterData.getKeyInfo(selectedPitchClass, isMinor)
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
                        text = "MUSICAL KEY CONVERTER",
                        color = DeckACyan,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Harmonic mixing & Camelot Wheel mapping",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DeckACyan.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan)
                ) {
                    Text(
                        text = "CIRCLE OF 5THS",
                        color = DeckACyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Mode Selector (Major vs Minor)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { isMinor = false },
                    color = if (!isMinor) DeckACyan.copy(alpha = 0.25f) else DjSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (!isMinor) DeckACyan else DjSurfaceBorder),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "MAJOR (Camelot B)",
                        color = if (!isMinor) DeckACyan else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { isMinor = true },
                    color = if (isMinor) DeckBPink.copy(alpha = 0.25f) else DjSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isMinor) DeckBPink else DjSurfaceBorder),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "MINOR (Camelot A)",
                        color = if (isMinor) DeckBPink else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // Root Key Selector Grid (12 roots)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Select Root Note:", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (i in 0..5) {
                        val selected = selectedPitchClass == i
                        val name = listOf("C", "C#", "D", "Eb", "E", "F")[i]
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { selectedPitchClass = i },
                            color = if (selected) DeckACyan else DjSurfaceElevated,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) DeckACyan else DjSurfaceBorder),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = name,
                                color = if (selected) DjObsidian else TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 7.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (i in 6..11) {
                        val selected = selectedPitchClass == i
                        val name = listOf("F#", "G", "Ab", "A", "Bb", "B")[i - 6]
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { selectedPitchClass = i },
                            color = if (selected) DeckACyan else DjSurfaceElevated,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) DeckACyan else DjSurfaceBorder),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = name,
                                color = if (selected) DjObsidian else TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 7.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Results Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DjObsidian, RoundedCornerShape(10.dp))
                    .border(1.dp, DjSurfaceBorder, RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("STANDARD NOTATION", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(keyInfo.rootName, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = (if (isMinor) DeckBPink else DeckACyan).copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, if (isMinor) DeckBPink else DeckACyan)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("CAMELOT", color = if (isMinor) DeckBPink else DeckACyan, fontSize = 8.sp, fontWeight = FontWeight.Black)
                            Text(
                                text = keyInfo.camelot,
                                color = if (isMinor) DeckBPink else DeckACyan,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Harmonic Mixing Compatibility Grid
                Text(
                    text = "HARMONIC MIXING COMPATIBILITY",
                    color = NeonAmber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HarmonicTile(
                        title = "Energy -1",
                        key = keyInfo.minusOneKey,
                        desc = "Smoother / Wind-down",
                        modifier = Modifier.weight(1f)
                    )
                    HarmonicTile(
                        title = "Same Key",
                        key = keyInfo.camelot,
                        desc = "Perfect Match",
                        isHighlight = true,
                        modifier = Modifier.weight(1f)
                    )
                    HarmonicTile(
                        title = "Energy +1",
                        key = keyInfo.plusOneKey,
                        desc = "Build / Energy Up",
                        modifier = Modifier.weight(1f)
                    )
                    HarmonicTile(
                        title = "Relative",
                        key = keyInfo.relativeKey,
                        desc = if (isMinor) "Major mood" else "Minor mood",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HarmonicTile(
    title: String,
    key: String,
    desc: String,
    isHighlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = if (isHighlight) DeckACyan.copy(alpha = 0.15f) else DjSurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isHighlight) DeckACyan else DjSurfaceBorder
        )
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, color = TextMuted, fontSize = 8.5.sp, fontWeight = FontWeight.Medium)
            Text(
                key,
                color = if (isHighlight) DeckACyan else TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(desc, color = TextSecondary, fontSize = 7.5.sp, maxLines = 1)
        }
    }
}
