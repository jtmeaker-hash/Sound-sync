package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.RepeatMode
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun PlaybackSettingsScreen(
    crossfadeSeconds: Int,
    onCrossfadeSecondsChange: (Int) -> Unit,
    repeatMode: RepeatMode,
    onToggleRepeat: () -> Unit,
    isShuffleEnabled: Boolean,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Crossfade card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Crossfade Transitions", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Overlap the end of one track with the next track smoothly", color = TextSecondary, fontSize = 11.sp)
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (crossfadeSeconds > 0) DeckACyan.copy(alpha = 0.2f) else DjSurfaceElevated,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (crossfadeSeconds > 0) DeckACyan else DjSurfaceBorder)
                        ) {
                            Text(
                                text = if (crossfadeSeconds == 0) "OFF" else "${crossfadeSeconds}s",
                                color = if (crossfadeSeconds > 0) DeckACyan else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Slider(
                        value = crossfadeSeconds.toFloat(),
                        onValueChange = { onCrossfadeSecondsChange(it.toInt().coerceIn(0, 12)) },
                        valueRange = 0f..12f,
                        steps = 11,
                        colors = SliderDefaults.colors(
                            thumbColor = DeckACyan,
                            activeTrackColor = DeckACyan,
                            inactiveTrackColor = DjSurfaceElevated
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("OFF", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("3s", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("6s", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("9s", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("12s", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Playback Behaviour (Repeat & Shuffle)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Playback Modes & Behaviour", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    // Repeat Mode Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = when (repeatMode) {
                                    RepeatMode.ONE -> Icons.Default.RepeatOne
                                    else -> Icons.Default.Repeat
                                },
                                contentDescription = null,
                                tint = if (repeatMode != RepeatMode.OFF) DeckACyan else TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text("Repeat Mode", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    when (repeatMode) {
                                        RepeatMode.OFF -> "Repeat is disabled"
                                        RepeatMode.ALL -> "Repeat entire queue / crate"
                                        RepeatMode.ONE -> "Repeat current track in loop"
                                    },
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onToggleRepeat),
                            color = if (repeatMode != RepeatMode.OFF) DeckACyan.copy(alpha = 0.2f) else DjSurfaceElevated,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (repeatMode != RepeatMode.OFF) DeckACyan else DjSurfaceBorder),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = repeatMode.name,
                                color = if (repeatMode != RepeatMode.OFF) DeckACyan else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Shuffle Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = null,
                                tint = if (isShuffleEnabled) DeckBPink else TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text("Shuffle Playback", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text("Play tracks in random order without repeats", color = TextSecondary, fontSize = 10.sp)
                            }
                        }

                        Switch(
                            checked = isShuffleEnabled,
                            onCheckedChange = { onToggleShuffle() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DjObsidian,
                                checkedTrackColor = DeckBPink,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = DjSurfaceElevated
                            )
                        )
                    }
                }
            }
        }
    }
}
