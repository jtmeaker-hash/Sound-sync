package com.example.ui.sidemenu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.res.painterResource
import com.example.R
import com.example.diagnostics.DeveloperModeManager
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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material.icons.filled.Verified
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.ui.theme.SoundSyncTheme
import com.example.util.ExternalAppOpener

/**
 * SoundSync Workstation sidebar navigation drawer.
 * Grouped into professional categories:
 * - MUSIC: DJ Prep, Listening Stats, Car Mode, MD Approval Tool
 * - AUDIO: Multipoint EQ, HAAS Surround, Crossfade and Transitions, Playback & Behaviour
 * - STREAMING: Spotify, SoundCloud
 * - AI ASSISTANTS: Suno AI Music, ACE Studio
 * - TOOLS: Metronome, Tap BPM, Key Converter, RMS Meter, Clipping Detector, DR Meter
 * - SETTINGS: Library Doctor, Backup & Restore, Appearance & Density, Car Mode & Bluetooth, Metadata & Artwork, Storage & Storage Services
 * - SYSTEM: GitHub Repository, Check for Updates, About SoundSync, Developer Diagnostics, SoundSync Self-Test
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SideNavigationDrawerContent(
    onSelectDestination: (SideMenuDestination) -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val theme = SoundSyncTheme.current
    val devManager = remember { DeveloperModeManager.getInstance(context) }
    val isDeveloperMode by devManager.isDeveloperModeEnabled.collectAsState()

    val expandedMap = remember {
        mutableStateMapOf(
            "MUSIC" to true,
            "AUDIO" to true,
            "STREAMING" to true,
            "AI ASSISTANTS" to true,
            "TOOLS" to true,
            "SETTINGS" to true,
            "SYSTEM" to false
        )
    }

    ModalDrawerSheet(
        drawerContainerColor = theme.surface,
        drawerContentColor = theme.textPrimary,
        modifier = modifier
            .widthIn(max = 320.dp)
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.surface)
        ) {
            // Pro Header
            Surface(
                color = theme.surfaceSunken,
                border = BorderStroke(0.5.dp, theme.divider),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = theme.surfaceRaised,
                            border = BorderStroke(0.5.dp, theme.accent),
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(id = R.drawable.soundsync_logo),
                                    contentDescription = "SoundSync Logo",
                                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(3.dp))
                                )
                            }
                        }

                        Column {
                            Text(
                                text = "SOUNDSYNC",
                                color = theme.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "PRO DJ WORKSTATION",
                                color = theme.textMuted,
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = theme.surfaceRaised,
                        border = BorderStroke(0.5.dp, if (isDeveloperMode) theme.accent else theme.divider),
                        modifier = Modifier.combinedClickable(
                            onClick = { devManager.registerTap() },
                            onLongClick = { devManager.setDeveloperModeEnabled(true) }
                        )
                    ) {
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}${if (isDeveloperMode) " DEV" else ""}",
                            color = if (isDeveloperMode) theme.accent else theme.textSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Scrollable Groups
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                // ── 1. MUSIC ─────────────────────────────────────
                ProCategoryHeader(
                    title = "MUSIC",
                    badge = "4 ITEMS",
                    isExpanded = expandedMap["MUSIC"] == true,
                    onToggle = { expandedMap["MUSIC"] = !(expandedMap["MUSIC"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["MUSIC"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        ProDrawerItem(
                            title = "DJ Prep",
                            subtitle = "Track preparation, beat grid, cues & key lock",
                            icon = Icons.Default.GraphicEq,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.DjPrep)
                            }
                        )
                        ProDrawerItem(
                            title = "Listening Stats",
                            subtitle = "Play history, top tracks & artists",
                            icon = Icons.Default.Equalizer,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.ListeningStats)
                            }
                        )
                        ProDrawerItem(
                            title = "Car Mode",
                            subtitle = "Distraction-free, glanceable vehicle dashboard",
                            icon = Icons.Default.DirectionsCar,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.CarMode)
                            }
                        )
                        ProDrawerItem(
                            title = "MD Approval Tool",
                            subtitle = "Review & approve proposed metadata changes",
                            icon = Icons.Default.Verified,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.MetadataReviewInbox)
                            }
                        )
                    }
                }

                HorizontalDivider(color = theme.divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

                // ── 2. AUDIO ─────────────────────────────────────
                ProCategoryHeader(
                    title = "AUDIO",
                    badge = "4 ITEMS",
                    isExpanded = expandedMap["AUDIO"] == true,
                    onToggle = { expandedMap["AUDIO"] = !(expandedMap["AUDIO"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["AUDIO"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        ProDrawerItem(
                            title = "Multipoint EQ",
                            subtitle = "3-band parametric DSP tone controls",
                            icon = Icons.Default.Tune,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.Eq)
                            }
                        )
                        ProDrawerItem(
                            title = "HAAS Surround",
                            subtitle = "Binaural stereo-width acoustic delay effect",
                            icon = Icons.Default.SurroundSound,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.HaasSurround)
                            }
                        )
                        ProDrawerItem(
                            title = "Crossfade and Transitions",
                            subtitle = "Track overlap duration (0-12s)",
                            icon = Icons.Default.Tune,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.PlaybackSettings)
                            }
                        )
                        ProDrawerItem(
                            title = "Playback & Behaviour",
                            subtitle = "Repeat mode, shuffle, and player preferences",
                            icon = Icons.Default.VolumeUp,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.PlaybackSettings)
                            }
                        )
                    }
                }

                HorizontalDivider(color = theme.divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

                // ── 3. STREAMING ─────────────────────────────────
                ProCategoryHeader(
                    title = "STREAMING",
                    badge = "2 APPS",
                    isExpanded = expandedMap["STREAMING"] == true,
                    onToggle = { expandedMap["STREAMING"] = !(expandedMap["STREAMING"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["STREAMING"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        ProDrawerItem(
                            title = "Spotify",
                            subtitle = "Launch external Spotify client",
                            icon = Icons.Default.Cloud,
                            onClick = {
                                onCloseDrawer()
                                ExternalAppOpener.openSpotify(context)
                            }
                        )
                        ProDrawerItem(
                            title = "Soundcloud",
                            subtitle = "Launch SoundCloud streaming",
                            icon = Icons.Default.Cloud,
                            onClick = {
                                onCloseDrawer()
                                ExternalAppOpener.openSoundCloud(context)
                            }
                        )
                    }
                }

                HorizontalDivider(color = theme.divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

                // ── 4. AI ASSISTANTS ─────────────────────────────
                ProCategoryHeader(
                    title = "AI ASSISTANTS",
                    badge = "2 APPS",
                    isExpanded = expandedMap["AI ASSISTANTS"] == true,
                    onToggle = { expandedMap["AI ASSISTANTS"] = !(expandedMap["AI ASSISTANTS"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["AI ASSISTANTS"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        ProDrawerItem(
                            title = "Suno AI Music",
                            subtitle = "AI music creation platform",
                            icon = Icons.Default.AutoAwesome,
                            onClick = {
                                onCloseDrawer()
                                ExternalAppOpener.openSuno(context)
                            }
                        )
                        ProDrawerItem(
                            title = "ACE Studio",
                            subtitle = "AI singing synthesizer & voice modeling",
                            icon = Icons.Default.Mic,
                            onClick = {
                                onCloseDrawer()
                                ExternalAppOpener.openAceStudio(context)
                            }
                        )
                    }
                }

                HorizontalDivider(color = theme.divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

                // ── 5. TOOLS ─────────────────────────────────────
                ProCategoryHeader(
                    title = "TOOLS",
                    badge = "6 TOOLS",
                    isExpanded = expandedMap["TOOLS"] == true,
                    onToggle = { expandedMap["TOOLS"] = !(expandedMap["TOOLS"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["TOOLS"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        ProDrawerItem(
                            title = "Metronome",
                            subtitle = "Sample-accurate rhythmic hardware click",
                            icon = Icons.Default.Timer,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.Metronome)
                            }
                        )
                        ProDrawerItem(
                            title = "Tap BPM",
                            subtitle = "Real-time tempo calculator with statistical outlier rejection",
                            icon = Icons.Default.TouchApp,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.TapBpm)
                            }
                        )
                        ProDrawerItem(
                            title = "Key Converter",
                            subtitle = "Camelot, Open Key & Traditional harmonic circle",
                            icon = Icons.Default.MusicNote,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.KeyConverter)
                            }
                        )
                        ProDrawerItem(
                            title = "RMS Meter",
                            subtitle = "Calibrated dual-deck signal loudness monitor",
                            icon = Icons.Default.GraphicEq,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.RmsMeter)
                            }
                        )
                        ProDrawerItem(
                            title = "Clipping Detector",
                            subtitle = "Real PCM 0 dBFS saturation peak analysis",
                            icon = Icons.Default.Warning,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.ClippingDetector)
                            }
                        )
                        ProDrawerItem(
                            title = "Dynamic Range Meter",
                            subtitle = "Official TT DR crest factor & loudness range",
                            icon = Icons.Default.Speed,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.DynamicRangeMeter)
                            }
                        )
                    }
                }

                HorizontalDivider(color = theme.divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

                // ── 6. SETTINGS ──────────────────────────────────
                ProCategoryHeader(
                    title = "SETTINGS",
                    badge = "6 ITEMS",
                    isExpanded = expandedMap["SETTINGS"] == true,
                    onToggle = { expandedMap["SETTINGS"] = !(expandedMap["SETTINGS"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["SETTINGS"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        ProDrawerItem(
                            title = "Library Doctor",
                            subtitle = "Audit health, diagnose issues & safe automated repairs",
                            icon = Icons.Default.Healing,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.LibraryDoctor)
                            }
                        )
                        ProDrawerItem(
                            title = "Backup & Restore",
                            subtitle = "Survives app uninstall, auto-backup, export/import",
                            icon = Icons.Default.Backup,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.BackupRestore)
                            }
                        )
                        ProDrawerItem(
                            title = "Appearance & Density",
                            subtitle = "Default / Pro theme & Compact / Comfortable",
                            icon = Icons.Default.ColorLens,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.AppearanceSettings)
                            }
                        )
                        ProDrawerItem(
                            title = "Car Mode & Bluetooth",
                            subtitle = "Vehicle pairing, driving profiles, and audio tuning",
                            icon = Icons.Default.DirectionsCar,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.CarModeSettings)
                            }
                        )
                        ProDrawerItem(
                            title = "Metadata & Artwork",
                            subtitle = "Apple iTunes Search, TheAudioDB & local DSP",
                            icon = Icons.Default.AutoAwesome,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.MetadataSettings)
                            }
                        )
                        ProDrawerItem(
                            title = "Storage & Storage Services",
                            subtitle = "Mount points, MediaStore index, USB",
                            icon = Icons.Default.LibraryMusic,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.LibrarySettings)
                            }
                        )
                    }
                }

                HorizontalDivider(color = theme.divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))

                // ── 7. SYSTEM ────────────────────────────────────
                ProCategoryHeader(
                    title = "SYSTEM",
                    badge = if (isDeveloperMode) "5 ITEMS" else "3 ITEMS",
                    isExpanded = expandedMap["SYSTEM"] == true,
                    onToggle = { expandedMap["SYSTEM"] = !(expandedMap["SYSTEM"] ?: false) }
                )
                AnimatedVisibility(
                    visible = expandedMap["SYSTEM"] == true,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        ProDrawerItem(
                            title = "GitHub Repository",
                            subtitle = "jtmeaker-hash/Sound-sync repository",
                            icon = Icons.Default.Code,
                            onClick = {
                                onCloseDrawer()
                                ExternalAppOpener.openGitHub(context)
                            }
                        )
                        ProDrawerItem(
                            title = "Check for Updates",
                            subtitle = "In-app GitHub release checker",
                            icon = Icons.Default.SystemUpdate,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.GitHubUpdates)
                            }
                        )
                        ProDrawerItem(
                            title = "About SoundSync",
                            subtitle = "Build info, licenses, and diagnostics",
                            icon = Icons.Default.Info,
                            onClick = {
                                onCloseDrawer()
                                onSelectDestination(SideMenuDestination.AboutSoundSync)
                            }
                        )
                        if (isDeveloperMode) {
                            ProDrawerItem(
                                title = "Developer Diagnostics",
                                subtitle = "Live audio specs, drift monitor, and error logs",
                                icon = Icons.Default.BugReport,
                                badge = "DEV",
                                onClick = {
                                    onCloseDrawer()
                                    onSelectDestination(SideMenuDestination.DeveloperDiagnostics)
                                }
                            )
                            ProDrawerItem(
                                title = "SoundSync Self-Test",
                                subtitle = "Automated 11-module subsystem validation",
                                icon = Icons.Default.CheckCircle,
                                badge = "TEST",
                                onClick = {
                                    onCloseDrawer()
                                    onSelectDestination(SideMenuDestination.SoundSyncSelfTest)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProCategoryHeader(
    title: String,
    badge: String? = null,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val theme = SoundSyncTheme.current
    val rotation by animateFloatAsState(targetValue = if (isExpanded) 90f else 0f, label = "pro_chevron_rotate")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        color = theme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    color = theme.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                if (badge != null) {
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = theme.surfaceRaised,
                        border = BorderStroke(0.5.dp, theme.divider)
                    ) {
                        Text(
                            text = badge,
                            color = theme.textMuted,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = theme.textMuted,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation)
            )
        }
    }
}

@Composable
private fun ProDrawerItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badge: String? = null,
    onClick: () -> Unit
) {
    val theme = SoundSyncTheme.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = theme.surface,
        shape = RoundedCornerShape(theme.cornerSmall)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = theme.textSecondary,
                modifier = Modifier.size(16.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = theme.textPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    color = theme.textMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (badge != null) {
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = theme.surfaceRaised,
                    border = BorderStroke(0.5.dp, theme.accent)
                ) {
                    Text(
                        text = badge,
                        color = theme.accent,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

