package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.lyrics.LyricLine
import com.example.lyrics.LyricsTimestampEditor
import com.example.lyrics.TrackLyrics
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun LyricsEditorDialog(
    track: Track,
    existingLyrics: TrackLyrics?,
    currentPlaybackPositionMs: Long,
    onSeekToPosition: (Long) -> Unit,
    onSave: (lines: List<LyricLine>, plainText: String, offsetMs: Long) -> Unit,
    onExportLrc: () -> Unit,
    onDismiss: () -> Unit
) {
    val editor = remember(existingLyrics) {
        LyricsTimestampEditor(
            initialLines = existingLyrics?.lines ?: emptyList(),
            initialPlainText = existingLyrics?.plainText ?: "",
            initialOffsetMs = existingLyrics?.offsetMs ?: 0L
        )
    }

    // Reactive trigger to recompose when editor state changes
    var version by remember { mutableIntStateOf(0) }
    var showRawImportDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Keep active item scrolled into view when stamping
    LaunchedEffect(editor.activeLineIndex) {
        if (editor.lines.isNotEmpty()) {
            listState.animateScrollToItem(editor.activeLineIndex.coerceIn(0, editor.lines.lastIndex))
        }
    }

    if (showRawImportDialog) {
        var rawInput by remember { mutableStateOf(editor.plainText) }
        AlertDialog(
            onDismissRequest = { showRawImportDialog = false },
            title = { Text("Import Raw Lyrics", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = rawInput,
                    onValueChange = { rawInput = it },
                    label = { Text("Paste lyrics line by line") },
                    modifier = Modifier.fillMaxWidth().height(250.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = DeckACyan
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        editor.parseRawText(rawInput)
                        version++
                        showRawImportDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian)
                ) {
                    Text("Parse Lines", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRawImportDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DjSurfaceElevated
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .testTag("lyrics_editor_dialog"),
            colors = CardDefaults.cardColors(containerColor = DjObsidian),
            border = BorderStroke(1.dp, DjSurfaceBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Header Toolbar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lyrics & Timestamp Editor",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeckACyan
                        )
                        Text(
                            text = "${track.title} • ${track.artist}",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (editor.undo()) version++ },
                            enabled = editor.canUndo,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo", tint = if (editor.canUndo) TextPrimary else TextMuted, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { if (editor.redo()) version++ },
                            enabled = editor.canRedo,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Redo, contentDescription = "Redo", tint = if (editor.canRedo) TextPrimary else TextMuted, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action Bar (Import text, Global Shift, Add line)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showRawImportDialog = true },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Paste Text", fontSize = 11.sp, color = DeckACyan)
                    }

                    OutlinedButton(
                        onClick = {
                            editor.shiftAllTimestamps(-500L)
                            version++
                        },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("-0.5s All", fontSize = 11.sp, color = TextPrimary)
                    }

                    OutlinedButton(
                        onClick = {
                            editor.shiftAllTimestamps(500L)
                            version++
                        },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("+0.5s All", fontSize = 11.sp, color = TextPrimary)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = {
                            editor.insertLine(editor.activeLineIndex + 1, "", currentPlaybackPositionMs)
                            version++
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Line", fontSize = 11.sp, color = TextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Live Player Sync Control Banner
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DjSurfaceDark,
                    border = BorderStroke(1.dp, DeckACyan.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val curSec = currentPlaybackPositionMs / 1000
                            val curMin = curSec / 60
                            val curS = curSec % 60
                            val curMs = (currentPlaybackPositionMs % 1000) / 10
                            Text(
                                text = String.format(Locale.US, "%02d:%02d.%02d", curMin, curS, curMs),
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DeckACyan
                            )
                            Text(
                                text = "(${editor.lines.size} lines)",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }

                        // Big prominent MARK / STAMP button
                        Button(
                            onClick = {
                                editor.stampCurrentTime(currentPlaybackPositionMs)
                                version++
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DeckBPink, contentColor = Color.White),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(38.dp).testTag("stamp_lyric_button")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("STAMP ACTIVE LINE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Lines List
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (editor.lines.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No lyrics lines yet. Tap 'Paste Text' or 'Add Line' to begin.", color = TextMuted, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(editor.lines) { index, line ->
                                val isActive = (index == editor.activeLineIndex)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isActive) DeckACyan.copy(alpha = 0.15f) else DjSurfaceCard,
                                    border = BorderStroke(if (isActive) 1.5.dp else 1.dp, if (isActive) DeckACyan else DjSurfaceBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            editor.activeLineIndex = index
                                            version++
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Active badge
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(if (isActive) DeckACyan else DjSurfaceElevated),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${index + 1}",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isActive) DjObsidian else TextMuted
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        // Timestamp & Nudge Buttons
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            IconButton(
                                                onClick = {
                                                    editor.adjustLineTimestamp(index, -100L)
                                                    version++
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Text("-", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            }

                                            Text(
                                                text = line.formatTimestamp(),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isActive) DeckACyan else TextPrimary,
                                                modifier = Modifier
                                                    .clickable { onSeekToPosition(line.timeMs) }
                                                    .padding(horizontal = 4.dp)
                                            )

                                            IconButton(
                                                onClick = {
                                                    editor.adjustLineTimestamp(index, 100L)
                                                    version++
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Text("+", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        // Editable Text Field
                                        OutlinedTextField(
                                            value = line.text,
                                            onValueChange = {
                                                editor.setLineText(index, it)
                                                version++
                                            },
                                            modifier = Modifier.weight(1f).height(44.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = TextPrimary,
                                                unfocusedTextColor = TextPrimary,
                                                focusedBorderColor = DeckACyan,
                                                unfocusedBorderColor = Color.Transparent
                                            ),
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                                        )

                                        // Seek to line preview
                                        IconButton(
                                            onClick = { onSeekToPosition(line.timeMs) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = "Preview", tint = DeckACyan, modifier = Modifier.size(16.dp))
                                        }

                                        // Delete Line
                                        IconButton(
                                            onClick = {
                                                editor.deleteLine(index)
                                                version++
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Footer Save Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onExportLrc,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export .LRC", fontSize = 12.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = TextSecondary)
                        }

                        Button(
                            onClick = {
                                onSave(editor.lines, editor.plainText, editor.offsetMs)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("save_lyrics_button")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Lyrics", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
