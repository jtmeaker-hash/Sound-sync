package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.model.UpdateState
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.util.ExternalAppOpener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GitHubSettingsScreen(
    updateState: UpdateState,
    lastCheckedTimestamp: Long,
    isAutoCheckEnabled: Boolean,
    onCheckForUpdates: () -> Unit,
    onToggleAutoCheck: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isChecking = updateState is UpdateState.Checking

    val formattedLastChecked = if (lastCheckedTimestamp > 0) {
        val dateFormat = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        dateFormat.format(Date(lastCheckedTimestamp))
    } else {
        "Never checked"
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Repository Link Card
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
                                Text("SoundSync GitHub Repository", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("jtmeaker-hash / Sound-sync", color = DeckACyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    Text(
                        "Visit the official open-source repository on GitHub to view releases, commit logs, and project documentation.",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )

                    Button(
                        onClick = { ExternalAppOpener.openGitHub(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = DeckACyan),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(36.dp)
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open SoundSync Repository", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Updates & Release Management Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("update_section_card"),
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
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = DeckACyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text("App Releases & Updates", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Automated GitHub release checks", color = TextSecondary, fontSize = 10.sp)
                            }
                        }

                        Surface(
                            color = DjSurfaceElevated,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Version details
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DjSurfaceElevated, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Installed Build", color = TextMuted, fontSize = 10.sp)
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("Last Checked", color = TextMuted, fontSize = 10.sp)
                            Text(
                                text = formattedLastChecked,
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Auto-update switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic update checks", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Check GitHub Releases for signed APK builds in the background.",
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = isAutoCheckEnabled,
                            onCheckedChange = onToggleAutoCheck,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DjObsidian,
                                checkedTrackColor = DeckACyan,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = DjSurfaceElevated
                            )
                        )
                    }

                    // Check button
                    Button(
                        onClick = onCheckForUpdates,
                        enabled = !isChecking,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeckACyan,
                            contentColor = DjObsidian,
                            disabledContainerColor = DjSurfaceElevated,
                            disabledContentColor = TextMuted
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .testTag("check_for_updates_button")
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = DjObsidian, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Checking GitHub...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Check for Updates Now", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
