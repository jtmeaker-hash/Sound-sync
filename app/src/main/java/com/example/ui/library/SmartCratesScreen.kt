package com.example.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Track
import com.example.smartcrate.SmartCrate
import com.example.smartcrate.SmartCrateEngine
import com.example.smartcrate.SmartCrateManager
import com.example.smartcrate.SmartField
import com.example.smartcrate.SmartMatchMode
import com.example.smartcrate.SmartOperator
import com.example.smartcrate.SmartRule
import com.example.smartcrate.SmartSortField
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
import java.util.Locale

@Composable
fun SmartCratesScreen(
    smartCrateManager: SmartCrateManager,
    allTracks: List<Track>,
    onPlayTrack: (Track) -> Unit,
    onQueueTrack: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val crates by smartCrateManager.crates.collectAsState()
    var selectedCrate by remember { mutableStateOf<SmartCrate?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateSmartCrateDialog(
            onSave = { newCrate ->
                smartCrateManager.saveCrate(newCrate)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    if (selectedCrate != null) {
        // Detail View of tracks in this Smart Crate
        val evaluatedTracks = remember(selectedCrate, allTracks) {
            SmartCrateEngine.evaluate(selectedCrate!!, allTracks)
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(DjObsidian)
                .padding(16.dp)
                .testTag("smart_crate_detail_view")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { selectedCrate = null }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = DeckACyan)
                    }
                    Column {
                        Text(selectedCrate!!.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("${evaluatedTracks.size} dynamic tracks matching rules", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (evaluatedTracks.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No tracks currently match this Smart Crate's rules.", color = TextMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(evaluatedTracks) { track ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPlayTrack(track) },
                            shape = RoundedCornerShape(8.dp),
                            color = DjSurfaceCard,
                            border = BorderStroke(1.dp, DjSurfaceBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = DeckACyan.copy(alpha = 0.15f),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DeckACyan, modifier = Modifier.padding(6.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(track.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${track.artist} • ${track.genre}", color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    if (track.bpm > 0) {
                                        Text(String.format(Locale.US, "%.0f BPM", track.bpm), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    if (track.camelotKey.isNotBlank()) {
                                        Text(track.camelotKey, color = NeonAmber, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Crate List View
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(DjObsidian)
                .padding(16.dp)
                .testTag("smart_crates_list_view")
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(22.dp))
                    Text(
                        text = "SMART CRATES",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Button(
                    onClick = { showCreateDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = DjObsidian, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Crate", color = DjObsidian, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Rule-driven dynamic playlists. No file copies created.", color = TextMuted, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(crates) { crate ->
                    val matchCount = SmartCrateEngine.evaluate(crate, allTracks).size
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedCrate = crate },
                        shape = RoundedCornerShape(10.dp),
                        color = DjSurfaceCard,
                        border = BorderStroke(1.dp, DjSurfaceBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(crate.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = DeckACyan.copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, DeckACyan.copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = "$matchCount tracks",
                                            color = DeckACyan,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                val rulesSummary = if (crate.rules.isEmpty()) "All library tracks" else crate.rules.joinToString(", ") { "${it.field.displayName} ${it.operator.displayName} ${it.value}" }
                                Text(rulesSummary, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            IconButton(
                                onClick = { smartCrateManager.deleteCrate(crate.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Crate", tint = TextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSmartCrateDialog(
    onSave: (SmartCrate) -> Unit,
    onDismiss: () -> Unit
) {
    var crateName by remember { mutableStateOf("") }
    var matchMode by remember { mutableStateOf(SmartMatchMode.MATCH_ALL) }
    var sortField by remember { mutableStateOf(SmartSortField.BPM) }
    var sortAscending by remember { mutableStateOf(true) }
    var rules by remember {
        mutableStateOf(listOf(SmartRule(field = SmartField.GENRE, operator = SmartOperator.CONTAINS, value = "House")))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DjSurfaceElevated,
        title = { Text("Create Smart Crate", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = crateName,
                    onValueChange = { crateName = it },
                    placeholder = { Text("Crate name (e.g. Peak Time 126)") },
                    label = { Text("Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DeckACyan,
                        unfocusedBorderColor = DjSurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Match Mode Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Match Rules:", color = TextSecondary, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(SmartMatchMode.MATCH_ALL to "ALL (AND)", SmartMatchMode.MATCH_ANY to "ANY (OR)").forEach { (m, label) ->
                            val isSel = matchMode == m
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSel) DeckACyan else DjSurfaceDark,
                                border = BorderStroke(1.dp, if (isSel) DeckACyan else DjSurfaceBorder),
                                modifier = Modifier.clickable { matchMode = m }
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSel) DjObsidian else TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                // Rules List
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rules.forEachIndexed { idx, rule ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("${rule.field.displayName} ${rule.operator.displayName}:", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.width(110.dp))
                            OutlinedTextField(
                                value = rule.value,
                                onValueChange = { newVal ->
                                    val updated = rules.toMutableList()
                                    updated[idx] = rule.copy(value = newVal)
                                    rules = updated
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = DeckACyan,
                                    unfocusedBorderColor = DjSurfaceBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.weight(1f).height(48.dp)
                            )
                            if (rules.size > 1) {
                                IconButton(
                                    onClick = {
                                        val updated = rules.toMutableList()
                                        updated.removeAt(idx)
                                        rules = updated
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove Rule", tint = NeonRed, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    // Add rule button
                    TextButton(
                        onClick = {
                            rules = rules + SmartRule(field = SmartField.BPM, operator = SmartOperator.BETWEEN, value = "124", secondaryValue = "130")
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Rule", color = DeckACyan, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (crateName.isNotBlank()) {
                        val newCrate = SmartCrate(
                            name = crateName.trim(),
                            matchMode = matchMode,
                            rules = rules,
                            sortField = sortField,
                            sortAscending = sortAscending
                        )
                        onSave(newCrate)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
            ) {
                Text("Create", color = DjObsidian, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
