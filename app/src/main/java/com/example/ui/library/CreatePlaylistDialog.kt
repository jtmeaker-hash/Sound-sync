package com.example.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun CreatePlaylistDialog(
    initialName: String = "",
    confirmButtonText: String = "Create",
    titleText: String = "New Playlist",
    onConfirm: (name: String, exportToRockbox: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var playlistName by remember { mutableStateOf(initialName) }
    var exportToRockbox by remember { mutableStateOf(true) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DjSurfaceCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("create_playlist_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = DeckACyan
                    )
                    Text(
                        text = titleText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Playlist Name", color = TextSecondary) },
                    placeholder = { Text("e.g. Summer Festival Mix", color = TextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DeckACyan,
                        unfocusedBorderColor = DjSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = DeckACyan
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_name_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = exportToRockbox,
                        onCheckedChange = { exportToRockbox = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = DeckACyan,
                            checkmarkColor = DjObsidian,
                            uncheckedColor = DjSurfaceBorder
                        ),
                        modifier = Modifier.testTag("rockbox_export_checkbox")
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "Save as Rockbox M3U8 file",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Stored in /Playlists/ with relative pathing",
                            fontSize = 11.sp,
                            color = NeonGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("dialog_cancel_button")
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = {
                            if (playlistName.isNotBlank()) {
                                onConfirm(playlistName.trim(), exportToRockbox)
                            }
                        },
                        enabled = playlistName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeckACyan,
                            contentColor = DjObsidian
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("dialog_confirm_button")
                    ) {
                        Text(confirmButtonText, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
