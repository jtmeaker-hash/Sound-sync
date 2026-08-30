package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ApiConfigDialog(
    initialSpotifyClientId: String,
    initialSoundCloudClientId: String,
    onSaveSpotifyClientId: (String) -> Unit,
    onSaveSoundCloudClientId: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var spotifyId by remember { mutableStateOf(initialSpotifyClientId) }
    var soundcloudId by remember { mutableStateOf(initialSoundCloudClientId) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DjSurfaceElevated),
            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
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
                        Icon(Icons.Default.Settings, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(20.dp))
                        Text(
                            text = "Cloud Integration Settings",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Text(
                    text = "Configure your API client credentials for Spotify and SoundCloud OAuth PKCE authentication. Client secrets are never required or embedded in the app.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                // Spotify Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SPOTIFY CLIENT ID", color = Color(0xFF1DB954), fontSize = 10.sp, fontWeight = FontWeight.Black)
                    OutlinedTextField(
                        value = spotifyId,
                        onValueChange = { spotifyId = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Enter Spotify App Client ID", color = TextMuted, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF1DB954),
                            unfocusedBorderColor = DjSurfaceBorder,
                            focusedContainerColor = DjSurfaceCard,
                            unfocusedContainerColor = DjSurfaceCard,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Text("Redirect URI: soundsync://spotify-callback", color = TextMuted, fontSize = 9.sp)
                }

                // SoundCloud Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SOUNDCLOUD CLIENT ID", color = Color(0xFFFF5500), fontSize = 10.sp, fontWeight = FontWeight.Black)
                    OutlinedTextField(
                        value = soundcloudId,
                        onValueChange = { soundcloudId = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Enter SoundCloud Client ID", color = TextMuted, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFF5500),
                            unfocusedBorderColor = DjSurfaceBorder,
                            focusedContainerColor = DjSurfaceCard,
                            unfocusedContainerColor = DjSurfaceCard,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Text("Redirect URI: soundsync://soundcloud-callback", color = TextMuted, fontSize = 9.sp)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            onSaveSpotifyClientId(spotifyId)
                            onSaveSoundCloudClientId(soundcloudId)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Save Credentials", color = DjObsidian, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
