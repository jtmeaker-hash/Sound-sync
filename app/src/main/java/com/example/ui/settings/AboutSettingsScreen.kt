package com.example.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
import com.example.diagnostics.DeveloperModeManager
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBOrange
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.util.ExternalAppOpener

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToSelfTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val devManager = remember { DeveloperModeManager.getInstance(context) }
    val isDeveloperMode by devManager.isDeveloperModeEnabled.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
    ) {
        // Top Bar
        Surface(
            color = DjSurfaceDark,
            border = BorderStroke(0.5.dp, DjSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Column {
                    Text(
                        "ABOUT SOUNDSYNC",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "Workstation build, licenses & diagnostics",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // App Header Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DjSurfaceElevated,
                            border = BorderStroke(1.dp, DeckACyan),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(id = R.drawable.soundsync_logo),
                                    contentDescription = "SoundSync Logo",
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "SOUNDSYNC",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp
                            )
                            Text(
                                text = "Professional Dual-Deck Mobile DJ Workstation",
                                color = DeckACyan,
                                fontSize = 11.sp
                            )
                        }

                        // Version badge with 7-tap and long-press listener
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DjSurfaceElevated,
                            border = BorderStroke(1.dp, if (isDeveloperMode) DeckACyan else DjSurfaceBorder),
                            modifier = Modifier.combinedClickable(
                                onClick = { devManager.registerTap() },
                                onLongClick = {
                                    devManager.setDeveloperModeEnabled(true)
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME}",
                                    color = if (isDeveloperMode) DeckACyan else TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "(code ${BuildConfig.VERSION_CODE})",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (isDeveloperMode) {
                                    Surface(
                                        shape = RoundedCornerShape(3.dp),
                                        color = DeckACyan.copy(alpha = 0.2f),
                                        border = BorderStroke(0.5.dp, DeckACyan)
                                    ) {
                                        Text(
                                            "DEV",
                                            color = DeckACyan,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = if (isDeveloperMode) "Developer Mode unlocked! Diagnostics and Self-Test available." else "Tap version number 7 times to unlock Developer Mode.",
                            color = if (isDeveloperMode) DeckACyan else TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Self-Test Card (Always visible under Settings -> About)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(DeckACyan.copy(alpha = 0.15f), CircleShape)
                                    .border(1.dp, DeckACyan, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = DeckACyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text("SoundSync Self-Test", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Automated 11-module diagnostic verification", color = TextSecondary, fontSize = 10.sp)
                            }
                        }

                        Text(
                            "Validate database integrity, Android media permissions, audio decoders, background Library Brain workers, and network metadata connectivity in real-time.",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )

                        Button(
                            onClick = onNavigateToSelfTest,
                            colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Run SoundSync Self-Test", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Developer Diagnostics Card (Visible when unlocked)
            if (isDeveloperMode) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, DeckACyan)
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(DeckBOrange.copy(alpha = 0.15f), CircleShape)
                                            .border(1.dp, DeckBOrange, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.BugReport,
                                            contentDescription = null,
                                            tint = DeckBOrange,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Column {
                                        Text("Developer Diagnostics", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text("Live subsystem metrics & error logging", color = TextSecondary, fontSize = 10.sp)
                                    }
                                }

                                Switch(
                                    checked = isDeveloperMode,
                                    onCheckedChange = { devManager.setDeveloperModeEnabled(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = DeckACyan,
                                        checkedThumbColor = DjObsidian
                                    )
                                )
                            }

                            Text(
                                "Inspect real-time decoder states, sample rates, buffer sizes, waveform sync drift, Bluetooth routes, Library Brain workers, and export sanitized reports.",
                                color = TextSecondary,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )

                            Button(
                                onClick = onNavigateToDiagnostics,
                                colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = DeckACyan),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open Developer Diagnostics", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Open Source & Repository Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(DeckACyan.copy(alpha = 0.15f), CircleShape)
                                    .border(1.dp, DeckACyan, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Code,
                                    contentDescription = null,
                                    tint = DeckACyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text("GitHub Repository", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("jtmeaker-hash / Sound-sync", color = DeckACyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }

                        Button(
                            onClick = { ExternalAppOpener.openGitHub(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = TextPrimary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open GitHub Repository", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
