package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.model.UpdateInfo
import com.example.model.UpdateState
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
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

@Composable
fun UpdateDialog(
    updateState: UpdateState,
    onStartDownload: (UpdateInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onInstallApk: (java.io.File, UpdateInfo) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    when (updateState) {
        is UpdateState.UpdateAvailable -> {
            UpdateAvailableContent(
                info = updateState.info,
                onUpdate = { onStartDownload(updateState.info) },
                onDismiss = onDismiss,
                modifier = modifier
            )
        }

        is UpdateState.Downloading -> {
            UpdateDownloadingContent(
                info = updateState.info,
                progress = updateState.progress,
                onCancel = onCancelDownload,
                modifier = modifier
            )
        }

        is UpdateState.Downloaded -> {
            UpdateDownloadedContent(
                info = updateState.info,
                apkFile = updateState.apkFile,
                sha256Verified = updateState.sha256Verified,
                onInstall = { onInstallApk(updateState.apkFile, updateState.info) },
                onDismiss = onDismiss,
                modifier = modifier
            )
        }

        is UpdateState.Installing -> {
            UpdateInstallingContent(
                info = updateState.info,
                modifier = modifier
            )
        }

        is UpdateState.Error -> {
            if (updateState.isManual) {
                UpdateErrorContent(
                    message = updateState.message,
                    onRetry = onRetry,
                    onDismiss = onDismiss,
                    modifier = modifier
                )
            }
        }

        is UpdateState.UpToDate -> {
            if (updateState.isManual) {
                UpToDateDialog(
                    onDismiss = onDismiss,
                    modifier = modifier
                )
            }
        }

        is UpdateState.Checking -> {
            if (updateState.isManual) {
                CheckingUpdatesDialog(
                    modifier = modifier
                )
            }
        }

        else -> { /* Idle */ }
    }
}

@Composable
private fun UpdateAvailableContent(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = modifier.testTag("update_dialog"),
        shape = RoundedCornerShape(16.dp),
        containerColor = DjSurfaceCard,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(DeckACyan.copy(alpha = 0.15f), CircleShape)
                    .border(1.5.dp, DeckACyan, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = DeckACyan,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "SoundSync Update Available",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Installed: ${BuildConfig.VERSION_NAME}",
                        fontSize = 12.sp,
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "  ➔  ",
                        fontSize = 12.sp,
                        color = DeckACyan
                    )
                    Surface(
                        color = DeckACyan.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "v${info.versionName}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeckACyan,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Release Notes",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = info.formattedSize,
                        fontSize = 12.sp,
                        color = NeonAmber,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .background(DjSurfaceDark, RoundedCornerShape(8.dp))
                        .border(1.dp, DjSurfaceBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = info.releaseNotes.ifBlank { "Performance improvements and bug fixes." },
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Updating preserves your local music library, database, playlists, and settings.",
                    fontSize = 11.sp,
                    color = NeonGreen.copy(alpha = 0.9f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdate,
                colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("update_now_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = DjObsidian,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Update",
                    color = DjObsidian,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                modifier = Modifier.testTag("update_later_button")
            ) {
                Text(text = "Later")
            }
        }
    )
}

@Composable
private fun UpdateDownloadingContent(
    info: UpdateInfo,
    progress: com.example.model.DownloadProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = { /* Prevent dismiss during download */ },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        modifier = modifier.testTag("downloading_dialog"),
        shape = RoundedCornerShape(16.dp),
        containerColor = DjSurfaceCard,
        titleContentColor = TextPrimary,
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(DeckACyan.copy(alpha = 0.15f), CircleShape)
                    .border(1.5.dp, DeckACyan, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { progress.progressFraction },
                    color = DeckACyan,
                    trackColor = DjSurfaceBorder,
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 3.dp
                )
            }
        },
        title = {
            Text(
                text = "Downloading SoundSync v${info.versionName}",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = TextPrimary,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                LinearProgressIndicator(
                    progress = { progress.progressFraction },
                    color = DeckACyan,
                    trackColor = DjSurfaceDark,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = progress.formattedProgress,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${progress.progressPercent}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = DeckACyan,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonRed),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("cancel_download_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = null,
                    tint = NeonRed,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Cancel")
            }
        }
    )
}

@Composable
private fun UpdateDownloadedContent(
    info: UpdateInfo,
    apkFile: java.io.File,
    sha256Verified: Boolean?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = DjSurfaceCard,
        titleContentColor = TextPrimary,
        modifier = modifier.testTag("downloaded_dialog"),
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(NeonGreen.copy(alpha = 0.15f), CircleShape)
                    .border(1.5.dp, NeonGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = NeonGreen,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = "Update Ready to Install",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Text(
                    text = "SoundSync v${info.versionName} has been downloaded and verified.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )

                if (sha256Verified == true) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = NeonGreen.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "✓ SHA-256 Checksum Verified",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NeonGreen,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Tap Install to upgrade SoundSync. Android may ask for permission to install apps from this source.",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onInstall,
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("install_update_button")
            ) {
                Icon(
                    imageVector = Icons.Default.InstallMobile,
                    contentDescription = null,
                    tint = DjObsidian,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Install Now",
                    color = DjObsidian,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text(text = "Later")
            }
        }
    )
}

@Composable
private fun UpdateInstallingContent(
    info: UpdateInfo,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = { },
        shape = RoundedCornerShape(16.dp),
        containerColor = DjSurfaceCard,
        titleContentColor = TextPrimary,
        modifier = modifier,
        icon = {
            CircularProgressIndicator(
                color = DeckACyan,
                modifier = Modifier.size(36.dp),
                strokeWidth = 3.dp
            )
        },
        title = {
            Text(
                text = "Launching Installer...",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = TextPrimary
            )
        },
        text = {
            Text(
                text = "Handing over to Android Package Installer for v${info.versionName}. Follow system prompts to complete installation.",
                fontSize = 13.sp,
                color = TextSecondary
            )
        },
        confirmButton = { }
    )
}

@Composable
private fun UpdateErrorContent(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = DjSurfaceCard,
        titleContentColor = TextPrimary,
        modifier = modifier.testTag("update_error_dialog"),
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(NeonRed.copy(alpha = 0.15f), CircleShape)
                    .border(1.5.dp, NeonRed, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = NeonRed,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = "Update Check Failed",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = TextPrimary
            )
        },
        text = {
            Text(
                text = message,
                fontSize = 13.sp,
                color = TextSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = DjObsidian,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Retry", color = DjObsidian, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text(text = "Close")
            }
        }
    )
}

@Composable
private fun UpToDateDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = DjSurfaceCard,
        titleContentColor = TextPrimary,
        modifier = modifier.testTag("up_to_date_dialog"),
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(NeonGreen.copy(alpha = 0.15f), CircleShape)
                    .border(1.5.dp, NeonGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = NeonGreen,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = "SoundSync is Up to Date",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = TextPrimary
            )
        },
        text = {
            Text(
                text = "You are running the latest version (${BuildConfig.VERSION_NAME}). No updates are available on GitHub.",
                fontSize = 13.sp,
                color = TextSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(text = "OK", color = DjObsidian, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun CheckingUpdatesDialog(
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = { },
        shape = RoundedCornerShape(16.dp),
        containerColor = DjSurfaceCard,
        titleContentColor = TextPrimary,
        modifier = modifier.testTag("checking_updates_dialog"),
        icon = {
            CircularProgressIndicator(
                color = DeckACyan,
                modifier = Modifier.size(36.dp),
                strokeWidth = 3.dp
            )
        },
        title = {
            Text(
                text = "Checking for Updates...",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = TextPrimary
            )
        },
        text = {
            Text(
                text = "Connecting to GitHub repository jtmeaker-hash/Sound-sync to check for the latest releases.",
                fontSize = 13.sp,
                color = TextSecondary
            )
        },
        confirmButton = { }
    )
}
