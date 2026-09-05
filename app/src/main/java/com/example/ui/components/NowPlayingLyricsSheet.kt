package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lyrics.TrackLyrics
import com.example.model.Track
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.SoundSyncTheme
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingLyricsSheet(
    track: Track,
    lyrics: TrackLyrics?,
    isLoading: Boolean,
    currentPlaybackPositionMs: Long,
    onSeekToPosition: (Long) -> Unit,
    onOpenEditor: () -> Unit,
    onRefreshLyrics: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var autoFollowEnabled by remember { mutableStateOf(true) }
    var lastActiveIndex by remember { mutableIntStateOf(-1) }

    val activeIndex by remember(lyrics, currentPlaybackPositionMs) {
        derivedStateOf {
            if (lyrics != null && lyrics.isSynced) {
                lyrics.getLineAtPosition(currentPlaybackPositionMs)
            } else {
                -1
            }
        }
    }

    // Scroll to active line only if auto-follow is active
    LaunchedEffect(activeIndex, autoFollowEnabled) {
        if (autoFollowEnabled && activeIndex >= 0 && activeIndex != lastActiveIndex) {
            lastActiveIndex = activeIndex
            val targetScroll = (activeIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(targetScroll)
        }
    }

    // Detect user manual dragging to disengage auto-follow without fighting the user
    val isUserScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    LaunchedEffect(isUserScrolling) {
        if (isUserScrolling) {
            autoFollowEnabled = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DjObsidian,
        dragHandle = null,
        modifier = Modifier.fillMaxHeight(0.90f).testTag("now_playing_lyrics_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "LYRICS",
                            color = DeckACyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        if (lyrics != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (lyrics.isSynced) NeonGreen.copy(alpha = 0.15f) else DjSurfaceElevated
                            ) {
                                Text(
                                    text = if (lyrics.isSynced) "SYNCED" else "PLAIN",
                                    color = if (lyrics.isSynced) NeonGreen else TextMuted,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "• ${lyrics.source.label}",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                    Text(
                        text = track.title,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefreshLyrics, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onOpenEditor, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = DeckACyan, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Lyrics Area
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(color = DeckACyan, modifier = Modifier.size(32.dp))
                                Text("Fetching lyrics...", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }

                    lyrics == null || (lyrics.lines.isEmpty() && lyrics.plainText.isBlank()) -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                                Text("No lyrics found for this track", color = TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Button(
                                    onClick = onOpenEditor,
                                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add & Sync Lyrics", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    lyrics.isInstrumental -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = DeckBPink, modifier = Modifier.size(56.dp))
                                Text("Instrumental Track", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text("This track has no vocal lyrics.", color = TextMuted, fontSize = 13.sp)
                            }
                        }
                    }

                    lyrics.isSynced -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(lyrics.lines) { index, line ->
                                val isActive = (index == activeIndex)
                                val isPast = (index < activeIndex)

                                Text(
                                    text = line.text,
                                    fontSize = if (isActive) 20.sp else 16.sp,
                                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
                                    color = when {
                                        isActive -> DeckACyan
                                        isPast -> TextSecondary.copy(alpha = 0.6f)
                                        else -> TextMuted
                                    },
                                    textAlign = TextAlign.Start,
                                    lineHeight = if (isActive) 28.sp else 24.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isActive) DeckACyan.copy(alpha = 0.08f) else Color.Transparent)
                                        .clickable {
                                            onSeekToPosition(line.timeMs)
                                            autoFollowEnabled = true
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    else -> {
                        // Plain un-synced lyrics
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Text(
                                    text = lyrics.plainText,
                                    fontSize = 15.sp,
                                    color = TextPrimary,
                                    lineHeight = 24.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = onOpenEditor,
                                    colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated, contentColor = DeckACyan),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add Time Synchronization", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Floating Re-Center / Auto-Follow Button when manually scrolled away
                androidx.compose.animation.AnimatedVisibility(
                    visible = lyrics != null && lyrics.isSynced && !autoFollowEnabled,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                ) {
                    Button(
                        onClick = {
                            autoFollowEnabled = true
                            if (activeIndex >= 0) {
                                scope.launch {
                                    listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian),
                        shape = RoundedCornerShape(20.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                        modifier = Modifier.height(36.dp).testTag("resume_lyrics_autofollow_button")
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Auto-Follow", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
