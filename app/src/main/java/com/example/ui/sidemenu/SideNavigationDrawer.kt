package com.example.ui.sidemenu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
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
import com.example.ui.theme.SpotifyGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.util.ExternalAppOpener

/**
 * SoundSync Hamburger Side Menu Drawer Content.
 * Organised strictly by the hierarchical category sketch:
 * ☰
 * ├── AI Generation (Suno, ACE Studio)
 * ├── DJ Tools (Metronome, Tap BPM, Key Converter, RMS Meter, Clipping Detector, DR Meter, EQ, Haas)
 * ├── Streaming (Spotify, SoundCloud)
 * ├── Playback & Audio (Crossfade, Playback Behaviour)
 * ├── Library & Metadata (Scanning, MusicBrainz, Storage Maintenance)
 * ├── Appearance (Themes, Dark Mode)
 * └── GitHub (SoundSync Repo, App Updates)
 */
@Composable
fun SideNavigationDrawerContent(
    onSelectDestination: (SideMenuDestination) -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Expanded state map for collapsible categories (DJ Tools and Playback expanded by default)
    val expandedMap = remember {
        mutableStateMapOf(
            "AI Generation" to false,
            "DJ Tools" to true,
            "Streaming" to false,
            "Playback & Audio" to true,
            "Library & Metadata" to false,
            "Appearance" to false,
            "GitHub" to false
        )
    }

    ModalDrawerSheet(
        drawerContainerColor = DjSurfaceDark,
        drawerContentColor = TextPrimary,
        modifier = modifier
            .widthIn(max = 320.dp)
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DjSurfaceDark)
        ) {
            // Drawer Header
            Surface(
                color = DjObsidian,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DeckACyan.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DeckACyan),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = DeckACyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = "SOUNDSYNC",
                                color = TextPrimary,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Navigation & DJ Studio Suite",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = DjSurfaceElevated
                    ) {
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            color = DeckACyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Scrollable Category Hierarchy
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                // ── 1. AI Generation ─────────────────────────────
                CategoryHeader(
                    title = "AI Generation",
                    icon = Icons.Default.AutoAwesome,
                    iconColor = NeonPurple,
                    isExpanded = expandedMap["AI Generation"] == true,
                    onToggle = { expandedMap["AI Generation"] = !(expandedMap["AI Generation"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["AI Generation"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 24.dp)) {
                        DrawerActionItem(
                            title = "Suno",
                            subtitle = "AI music creation & vocal generation",
                            icon = Icons.Default.OpenInNew,
                            accentColor = NeonPurple,
                            onClick = {
                                onCloseDrawer()
                                ExternalAppOpener.openSuno(context)
                            }
                        )
                        DrawerActionItem(
                            title = "ACE Studio",
                            subtitle = "AI singing synthesizer & voice modeling",
                            icon = Icons.Default.OpenInNew,
                            accentColor = NeonPurple,
                            onClick = {
                                onCloseDrawer()
                                ExternalAppOpener.openAceStudio(context)
                            }
                        )
                    }
                }

                HorizontalDivider(color = DjSurfaceBorder.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                // ── 2. DJ Tools ──────────────────────────────────
                CategoryHeader(
                    title = "DJ Tools",
                    icon = Icons.Default.Equalizer,
                    iconColor = DeckACyan,
                    badge = "8 TOOLS",
                    isExpanded = expandedMap["DJ Tools"] == true,
                    onToggle = { expandedMap["DJ Tools"] = !(expandedMap["DJ Tools"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["DJ Tools"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 24.dp)) {
                        DrawerActionItem(
                            title = "Metronome",
                            subtitle = "Sample-accurate rhythmic hardware click",
                            icon = Icons.Default.Timer,
                            accentColor = DeckACyan,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.Metronome)
                            }
                        )
                        DrawerActionItem(
                            title = "Tap BPM",
                            subtitle = "Manual tap tempo calculator with outlier rejection",
                            icon = Icons.Default.TouchApp,
                            accentColor = DeckACyan,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.TapBpm)
                            }
                        )
                        DrawerActionItem(
                            title = "Key Converter",
                            subtitle = "Standard notation to Camelot & harmonic mixing",
                            icon = Icons.Default.MusicNote,
                            accentColor = DeckBPink,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.KeyConverter)
                            }
                        )
                        DrawerActionItem(
                            title = "RMS Meter",
                            subtitle = "True PCM root-mean-square loudness meter (dBFS)",
                            icon = Icons.Default.GraphicEq,
                            accentColor = NeonGreen,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.RmsMeter)
                            }
                        )
                        DrawerActionItem(
                            title = "Clipping Detector",
                            subtitle = "Real PCM 0 dBFS saturation peak analysis",
                            icon = Icons.Default.Warning,
                            accentColor = NeonRed,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.ClippingDetector)
                            }
                        )
                        DrawerActionItem(
                            title = "Dynamic Range Meter",
                            subtitle = "Official TT DR crest factor & loudness range",
                            icon = Icons.Default.Speed,
                            accentColor = NeonAmber,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.DynamicRangeMeter)
                            }
                        )
                        DrawerActionItem(
                            title = "Multipoint EQ",
                            subtitle = "3-band parametric DSP tone controls",
                            icon = Icons.Default.Tune,
                            accentColor = DeckACyan,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.Eq)
                            }
                        )
                        DrawerActionItem(
                            title = "Haas Surround",
                            subtitle = "Binaural stereo-width acoustic delay effect",
                            icon = Icons.Default.SurroundSound,
                            accentColor = DeckBPink,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.HaasSurround)
                            }
                        )
                    }
                }

                HorizontalDivider(color = DjSurfaceBorder.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                // ── 3. Streaming ─────────────────────────────────
                CategoryHeader(
                    title = "Streaming",
                    icon = Icons.Default.Cloud,
                    iconColor = SpotifyGreen,
                    isExpanded = expandedMap["Streaming"] == true,
                    onToggle = { expandedMap["Streaming"] = !(expandedMap["Streaming"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["Streaming"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 24.dp)) {
                        DrawerActionItem(
                            title = "Spotify",
                            subtitle = "Launch Spotify app or web player",
                            icon = Icons.Default.OpenInNew,
                            accentColor = SpotifyGreen,
                            onClick = {
                                onCloseDrawer()
                                ExternalAppOpener.openSpotify(context)
                            }
                        )
                        DrawerActionItem(
                            title = "SoundCloud",
                            subtitle = "Launch SoundCloud app or streaming portal",
                            icon = Icons.Default.OpenInNew,
                            accentColor = Color(0xFFFF5500),
                            onClick = {
                                onCloseDrawer()
                                ExternalAppOpener.openSoundCloud(context)
                            }
                        )
                    }
                }

                HorizontalDivider(color = DjSurfaceBorder.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                // ── 4. Playback & Audio ──────────────────────────
                CategoryHeader(
                    title = "Playback & Audio",
                    icon = Icons.Default.PlayCircle,
                    iconColor = DeckACyan,
                    isExpanded = expandedMap["Playback & Audio"] == true,
                    onToggle = { expandedMap["Playback & Audio"] = !(expandedMap["Playback & Audio"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["Playback & Audio"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 24.dp)) {
                        DrawerActionItem(
                            title = "Crossfade & Transitions",
                            subtitle = "Track overlap duration (0-12s)",
                            icon = Icons.Default.Tune,
                            accentColor = DeckACyan,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.PlaybackSettings)
                            }
                        )
                        DrawerActionItem(
                            title = "Playback Behaviour",
                            subtitle = "Repeat mode, shuffle, and player preferences",
                            icon = Icons.Default.VolumeUp,
                            accentColor = DeckACyan,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.PlaybackSettings)
                            }
                        )
                    }
                }

                HorizontalDivider(color = DjSurfaceBorder.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                // ── 5. Library & Metadata ────────────────────────
                CategoryHeader(
                    title = "Library & Metadata",
                    icon = Icons.Default.LibraryMusic,
                    iconColor = NeonAmber,
                    isExpanded = expandedMap["Library & Metadata"] == true,
                    onToggle = { expandedMap["Library & Metadata"] = !(expandedMap["Library & Metadata"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["Library & Metadata"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 24.dp)) {
                        DrawerActionItem(
                            title = "Listening Statistics",
                            subtitle = "Play history, top tracks & artists, library stats",
                            icon = Icons.Default.Equalizer,
                            accentColor = DeckACyan,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.ListeningStats)
                            }
                        )
                        DrawerActionItem(
                            title = "Scanning & Storage Sources",
                            subtitle = "SAF mount points, MediaStore scan, maintenance",
                            icon = Icons.Default.LibraryMusic,
                            accentColor = NeonAmber,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.LibrarySettings)
                            }
                        )
                        DrawerActionItem(
                            title = "MusicBrainz Configuration",
                            subtitle = "Canonical catalogue, local BPM & Key analysis",
                            icon = Icons.Default.AutoAwesome,
                            accentColor = NeonAmber,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.MusicBrainzSettings)
                            }
                        )
                    }
                }

                HorizontalDivider(color = DjSurfaceBorder.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                // ── 6. Appearance ────────────────────────────────
                CategoryHeader(
                    title = "Appearance",
                    icon = Icons.Default.ColorLens,
                    iconColor = DeckBPink,
                    isExpanded = expandedMap["Appearance"] == true,
                    onToggle = { expandedMap["Appearance"] = !(expandedMap["Appearance"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["Appearance"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 24.dp)) {
                        DrawerActionItem(
                            title = "Themes & Dark Mode",
                            subtitle = "Cyan standard or black & blood-red dark palette",
                            icon = Icons.Default.ColorLens,
                            accentColor = DeckBPink,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.AppearanceSettings)
                            }
                        )
                    }
                }

                HorizontalDivider(color = DjSurfaceBorder.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                // ── 7. GitHub ────────────────────────────────────
                CategoryHeader(
                    title = "GitHub",
                    icon = Icons.Default.Code,
                    iconColor = TextPrimary,
                    isExpanded = expandedMap["GitHub"] == true,
                    onToggle = { expandedMap["GitHub"] = !(expandedMap["GitHub"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["GitHub"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 24.dp)) {
                        DrawerActionItem(
                            title = "SoundSync GitHub Repository",
                            subtitle = "Open jtmeaker-hash/Sound-sync in GitHub app / browser",
                            icon = Icons.Default.OpenInNew,
                            accentColor = DeckACyan,
                            onClick = {
                                onCloseDrawer()
                                ExternalAppOpener.openGitHub(context)
                            }
                        )
                        DrawerActionItem(
                            title = "Check for Updates",
                            subtitle = "In-app GitHub release checker & automated updates",
                            icon = Icons.Default.SystemUpdate,
                            accentColor = DeckACyan,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.GitHubUpdates)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    badge: String? = null,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(targetValue = if (isExpanded) 90f else 0f, label = "chevron_rotate")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        color = DjSurfaceDark
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (badge != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = iconColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = badge,
                            color = iconColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = TextMuted,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation)
            )
        }
    }
}

@Composable
private fun DrawerActionItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = DjSurfaceDark
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(accentColor, CircleShape)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = TextMuted,
                    fontSize = 9.5.sp,
                    maxLines = 1
                )
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
