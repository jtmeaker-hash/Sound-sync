package com.example.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.metadata.artwork.ArtworkCandidateItem
import com.example.model.Track
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Manual cover art search and selection dialog.
 * Enables searching Apple iTunes and TheAudioDB catalog, previewing high-res candidate
 * artwork, inspecting match confidence, and applying chosen artwork to the track.
 */
@Composable
fun FindCoverDialog(
    track: Track,
    onDismiss: () -> Unit,
    onSearchCandidates: suspend (query: String) -> List<ArtworkCandidateItem>,
    onApplyCandidate: suspend (ArtworkCandidateItem) -> Boolean
) {
    val coroutineScope = rememberCoroutineScope()
    val initialQuery = remember(track) {
        val cleanArtist = if (track.artist.isNotBlank() && !track.artist.equals("Unknown Artist", ignoreCase = true)) {
            track.artist
        } else ""
        if (cleanArtist.isNotBlank()) "$cleanArtist ${track.title}".trim() else track.title.trim()
    }

    var searchQuery by remember { mutableStateOf(initialQuery) }
    var isLoading by remember { mutableStateOf(true) }
    var isApplying by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<ArtworkCandidateItem>>(emptyList()) }
    var selectedCandidateId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun runSearch(q: String) {
        coroutineScope.launch {
            isLoading = true
            statusMessage = null
            try {
                candidates = onSearchCandidates(q)
                if (candidates.isEmpty()) {
                    statusMessage = "No covers found. Try refining the artist or title."
                }
            } catch (e: Exception) {
                statusMessage = "Search error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        runSearch(initialQuery)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .height(640.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, DjSurfaceBorder, RoundedCornerShape(14.dp))
                .testTag("find_cover_dialog"),
            color = DjSurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = DeckBPink,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "Find Cover Artwork",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "${track.artist.ifBlank { "Unknown Artist" }} — ${track.title}",
                                fontSize = 12.sp,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Search Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Search artist, track, or album...", fontSize = 13.sp, color = TextMuted) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { runSearch(searchQuery) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DeckACyan,
                            unfocusedBorderColor = DjSurfaceBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = DjObsidian,
                            unfocusedContainerColor = DjObsidian
                        )
                    )
                    Button(
                        onClick = { runSearch(searchQuery) },
                        colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                        modifier = Modifier.height(52.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = DjObsidian)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Search", color = DjObsidian, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Status Banner / Feedback
                if (statusMessage != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = DjSurfaceCard,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                    ) {
                        Text(
                            text = statusMessage ?: "",
                            fontSize = 12.sp,
                            color = NeonAmber,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                HorizontalDivider(color = DjSurfaceBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))

                // Candidate List
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(color = DeckACyan, modifier = Modifier.size(36.dp))
                            Text(
                                text = "Searching Apple iTunes & TheAudioDB...",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    } else if (candidates.isEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                            Text(
                                text = "No artwork matches discovered",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = "Try modifying the artist name or song title in the search bar above.",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(candidates, key = { it.id }) { candidate ->
                                CandidateArtworkCard(
                                    candidate = candidate,
                                    isSelected = selectedCandidateId == candidate.id,
                                    isApplying = isApplying && selectedCandidateId == candidate.id,
                                    onSelect = {
                                        selectedCandidateId = candidate.id
                                        coroutineScope.launch {
                                            isApplying = true
                                            val success = onApplyCandidate(candidate)
                                            isApplying = false
                                            if (success) {
                                                onDismiss()
                                            } else {
                                                statusMessage = "Failed to apply artwork. Please try another candidate."
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateArtworkCard(
    candidate: ArtworkCandidateItem,
    isSelected: Boolean,
    isApplying: Boolean,
    onSelect: () -> Unit
) {
    val borderColor = when {
        isSelected -> DeckACyan
        candidate.confidence >= 90.0 -> NeonGreen.copy(alpha = 0.6f)
        candidate.confidence >= 70.0 -> NeonAmber.copy(alpha = 0.5f)
        else -> DjSurfaceBorder
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) DjSurfaceCard.copy(alpha = 0.9f) else DjSurfaceCard,
        border = androidx.compose.foundation.BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isApplying, onClick = onSelect)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DjObsidian)
                    .border(0.5.dp, DjSurfaceBorder, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = candidate.thumbnailUrl,
                    contentDescription = "Cover preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // Info
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = candidate.title ?: candidate.album ?: "Release Cover",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = candidate.artist,
                    fontSize = 12.sp,
                    color = DeckACyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!candidate.album.isNullOrBlank()) {
                    Text(
                        text = candidate.album,
                        fontSize = 11.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Badges: Provider & Confidence
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    // Provider badge
                    Surface(
                        color = DjObsidian,
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, DjSurfaceBorder)
                    ) {
                        Text(
                            text = candidate.provider,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    // Confidence Score badge
                    val confidenceColor = when {
                        candidate.confidence >= 90.0 -> NeonGreen
                        candidate.confidence >= 70.0 -> NeonAmber
                        else -> DeckACyan
                    }
                    Surface(
                        color = confidenceColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, confidenceColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "${candidate.confidence.toInt()}% MATCH",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = confidenceColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    if (candidate.releaseYear != null && candidate.releaseYear > 0) {
                        Text(
                            text = "${candidate.releaseYear}",
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                    }
                }
            }

            // Action Button
            if (isApplying) {
                CircularProgressIndicator(
                    color = DeckACyan,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp
                )
            } else {
                Button(
                    onClick = onSelect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) NeonGreen else DeckACyan
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.Check else Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = DjObsidian,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isSelected) "Selected" else "Apply",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DjObsidian
                    )
                }
            }
        }
    }
}
