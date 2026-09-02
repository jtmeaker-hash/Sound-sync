package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.SongFind
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
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

private enum class FindsFilter(val label: String) {
    ALL("All"),
    PENDING("To Review"),
    COMPLETED("Done")
}

/**
 * Dedicated, visible view in SoundSync displaying saved song finds captured from
 * external apps via Android's Share menu or added manually.
 * Functions as a music discovery to-do and inbox list.
 */
@Composable
fun SongFindsView(
    songFinds: List<SongFind>,
    onAddNewFind: () -> Unit,
    onToggleCompleted: (id: String, completed: Boolean) -> Unit,
    onDeleteFind: (id: String) -> Unit,
    onClearCompleted: () -> Unit,
    onSearchInLibrary: (query: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf(FindsFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredList = remember(songFinds, selectedFilter, searchQuery) {
        songFinds.filter { item ->
            val matchesFilter = when (selectedFilter) {
                FindsFilter.ALL -> true
                FindsFilter.PENDING -> !item.isCompleted
                FindsFilter.COMPLETED -> item.isCompleted
            }
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                val q = searchQuery.trim().lowercase(Locale.ROOT)
                item.title.lowercase(Locale.ROOT).contains(q) ||
                        item.url.lowercase(Locale.ROOT).contains(q) ||
                        item.sourceAppName.lowercase(Locale.ROOT).contains(q) ||
                        item.notes.lowercase(Locale.ROOT).contains(q)
            }
            matchesFilter && matchesSearch
        }
    }

    val totalCount = songFinds.size
    val pendingCount = songFinds.count { !it.isCompleted }
    val completedCount = songFinds.count { it.isCompleted }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
            .padding(12.dp)
            .testTag("song_finds_view")
    ) {
        // =========================================================================
        // TOP HEADER: Title, Stats & Add Button
        // =========================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = DeckACyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "SONG FINDS & INBOX",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    if (pendingCount > 0) {
                        Surface(
                            shape = CircleShape,
                            color = DeckBPink,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "$pendingCount",
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "Saved music links from Instagram, TikTok, YouTube & web",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }

            Button(
                onClick = onAddNewFind,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("add_song_find_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("+ New Find", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // =========================================================================
        // SEARCH & FILTER BAR
        // =========================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter finds by title, platform or note...", fontSize = 12.sp, color = TextMuted) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = DeckACyan,
                        modifier = Modifier.size(16.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(14.dp))
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DeckACyan,
                    unfocusedBorderColor = DjSurfaceBorder,
                    focusedContainerColor = DjSurfaceDark,
                    unfocusedContainerColor = DjSurfaceDark,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("song_finds_search_input")
            )

            if (completedCount > 0) {
                OutlinedButton(
                    onClick = onClearCompleted,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonAmber),
                    border = BorderStroke(1.dp, NeonAmber.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("clear_completed_finds_button")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear Done", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Segmented Filter Tabs: All (X) | To Review (Y) | Done (Z)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DjSurfaceDark)
                .border(1.dp, DjSurfaceBorder, RoundedCornerShape(10.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FindsFilter.values().forEach { filter ->
                val isSelected = selectedFilter == filter
                val count = when (filter) {
                    FindsFilter.ALL -> totalCount
                    FindsFilter.PENDING -> pendingCount
                    FindsFilter.COMPLETED -> completedCount
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) DeckACyan else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clickable { selectedFilter = filter }
                        .testTag("filter_finds_${filter.name.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "${filter.label} ($count)",
                            color = if (isSelected) Color.Black else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // =========================================================================
        // LIST OF SAVED SONG FINDS OR EMPTY STATE
        // =========================================================================
        if (filteredList.isEmpty()) {
            EmptySongFindsState(
                totalSaved = songFinds.size,
                selectedFilter = selectedFilter,
                onAddNewFind = onAddNewFind
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("song_finds_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredList, key = { it.id }) { item ->
                    SongFindCard(
                        item = item,
                        onToggleCompleted = { onToggleCompleted(item.id, !item.isCompleted) },
                        onDelete = { onDeleteFind(item.id) },
                        onSearchInLibrary = { onSearchInLibrary(item.displayTitle) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(30.dp))
                }
            }
        }
    }
}

/**
 * Individual Song Find Card in the Inbox list.
 */
@Composable
private fun SongFindCard(
    item: SongFind,
    onToggleCompleted: () -> Unit,
    onDelete: () -> Unit,
    onSearchInLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val platformColor = when (item.sourceAppName.lowercase()) {
        "instagram" -> DeckBPink
        "tiktok" -> NeonGreen
        "youtube" -> Color(0xFFFF4E4E)
        "spotify" -> NeonGreen
        "soundcloud" -> NeonAmber
        else -> DeckACyan
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (item.isCompleted) DjSurfaceBorder.copy(alpha = 0.5f) else DjSurfaceBorder,
                RoundedCornerShape(12.dp)
            )
            .testTag("song_find_card_${item.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isCompleted) DjSurfaceDark.copy(alpha = 0.6f) else DjSurfaceDark
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header Row: Checkbox, Platform Badge, Title & Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onToggleCompleted,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("toggle_find_done_${item.id}")
                    ) {
                        Icon(
                            imageVector = if (item.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = if (item.isCompleted) "Mark Pending" else "Mark Done",
                            tint = if (item.isCompleted) NeonGreen else TextMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = platformColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, platformColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = item.sourceAppName.uppercase(),
                            color = platformColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = item.relativeTimeSpan,
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("delete_find_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Find",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Title
            Text(
                text = item.displayTitle,
                color = if (item.isCompleted) TextMuted else TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textDecoration = if (item.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 36.dp)
                    .testTag("find_title_${item.id}")
            )

            // URL Domain snippet
            Row(
                modifier = Modifier
                    .padding(start = 36.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = item.url,
                    color = DeckACyan.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // Notes if available
            if (item.notes.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DjSurfaceCard,
                    border = BorderStroke(0.5.dp, DjSurfaceBorder),
                    modifier = Modifier
                        .padding(start = 36.dp, top = 6.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = item.notes,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action buttons row: Open Link | Copy Link | Search in SoundSync
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Open Link
                Button(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open link: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DjSurfaceElevated,
                        contentColor = DeckACyan
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(28.dp)
                        .testTag("open_find_link_${item.id}")
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Open Link", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                // Copy Link
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("SoundSync Song Find", item.url)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = BorderStroke(1.dp, DjSurfaceBorder),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(28.dp)
                        .testTag("copy_find_link_${item.id}")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy", fontSize = 11.sp)
                }

                // Search in SoundSync library/streaming
                OutlinedButton(
                    onClick = onSearchInLibrary,
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckBPink),
                    border = BorderStroke(1.dp, DeckBPink.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(28.dp)
                        .testTag("search_find_in_app_${item.id}")
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = DeckBPink, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Search", fontSize = 11.sp, color = DeckBPink)
                }
            }
        }
    }
}

/**
 * Helpful empty state explaining how Android Share integration works.
 */
@Composable
private fun EmptySongFindsState(
    totalSaved: Int,
    selectedFilter: FindsFilter,
    onAddNewFind: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
            border = BorderStroke(1.dp, DjSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = DeckACyan.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, DeckACyan.copy(alpha = 0.5f)),
                    modifier = Modifier.size(60.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = DeckACyan,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = if (totalSaved == 0) "No Song Finds Yet" else "No matching finds in '${selectedFilter.label}'",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "When you discover a great song or DJ clip in Instagram, TikTok, YouTube, Spotify, or browser:",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DjSurfaceCard,
                    border = BorderStroke(1.dp, DjSurfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        InstructionStep(number = "1", text = "Tap Share in Instagram, TikTok, YouTube, etc.")
                        InstructionStep(number = "2", text = "Select More → SoundSync")
                        InstructionStep(number = "3", text = "SoundSync saves the link to this inbox!")
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = onAddNewFind,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                    modifier = Modifier.height(40.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("+ Paste / Add Link Manually", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun InstructionStep(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = DeckACyan.copy(alpha = 0.2f),
            modifier = Modifier.size(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number,
                    color = DeckACyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 12.sp
        )
    }
}
