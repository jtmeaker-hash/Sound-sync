package com.example.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FolderHierarchyTree
import com.example.model.HierarchicalFolder
import com.example.model.TrackFolder
import com.example.storage.FolderHierarchyEngine
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

/**
 * Authoritative Hierarchical Folder Browser for SoundSync.
 *
 * Renders a compact, recursive folder tree rooted in user-selected music locations.
 * Distinguishes folders with identical names across distinct roots, lazily expands
 * nested directories without audio playback, and provides path-aware search.
 */
@Composable
fun FoldersScreen(
    folderTree: FolderHierarchyTree,
    onSelectFolder: (TrackFolder) -> Unit,
    onPlayFolder: (TrackFolder, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    expandedFolderIdsState: Set<String>? = null,
    onToggleFolderExpanded: ((String) -> Unit)? = null
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var localExpandedIds by rememberSaveable { mutableStateOf(setOf<String>()) }

    val effectiveExpandedIds = expandedFolderIdsState ?: localExpandedIds
    val toggleExpand: (String) -> Unit = onToggleFolderExpanded ?: { folderId ->
        localExpandedIds = if (localExpandedIds.contains(folderId)) {
            localExpandedIds - folderId
        } else {
            localExpandedIds + folderId
        }
    }

    val isSearching = searchQuery.trim().isNotBlank()

    // 1. In regular mode: pre-order list of visible folders based on expansion state
    val visibleFolders = remember(folderTree, effectiveExpandedIds, isSearching) {
        if (isSearching) emptyList() else folderTree.buildVisibleList(effectiveExpandedIds)
    }

    // 2. In search mode: filtered matching folders across all nodes with path context
    val searchResults = remember(folderTree, searchQuery, isSearching) {
        if (!isSearching) emptyList() else {
            val q = searchQuery.trim().lowercase(Locale.ROOT)
            folderTree.allNodesById.values.filter { node ->
                node.name.lowercase(Locale.ROOT).contains(q) ||
                node.relativePathFromRoot.lowercase(Locale.ROOT).contains(q) ||
                node.fullPath.lowercase(Locale.ROOT).contains(q)
            }.sortedWith(
                compareBy<HierarchicalFolder> { !it.name.lowercase(Locale.ROOT).startsWith(q) }
                    .thenBy { it.depth }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("folders_screen")
    ) {
        // Search Header
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search folders or paths...", fontSize = 13.sp, color = TextMuted) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = DeckACyan,
                unfocusedBorderColor = DjSurfaceBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = DeckACyan,
                unfocusedContainerColor = DjSurfaceDark,
                focusedContainerColor = DjSurfaceDark
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Summary Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val summaryText = if (isSearching) {
                "${searchResults.size} Matching Folders"
            } else {
                "${folderTree.totalFoldersCount} Folders · ${folderTree.totalTracksCount} Tracks"
            }
            Text(
                text = summaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary
            )
        }

        // Body: Empty, Search, or Hierarchical Tree
        if (!isSearching && folderTree.rootNodes.isEmpty()) {
            EmptyFoldersPlaceholder(searchQuery = searchQuery)
        } else if (isSearching && searchResults.isEmpty()) {
            EmptyFoldersPlaceholder(searchQuery = searchQuery)
        } else if (isSearching) {
            // Search Results List
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(searchResults, key = { it.id }) { folder ->
                    SearchResultFolderCard(
                        folder = folder,
                        onClick = {
                            // Expand ancestor chain so user can navigate into it
                            val ancestors = folderTree.getAncestorIds(folder.id)
                            for (anc in ancestors) {
                                toggleExpand(anc)
                            }
                            toggleExpand(folder.id)
                            onSelectFolder(folder.toTrackFolder())
                        },
                        onPlayClick = {
                            val tracks = folderTree.getAllDescendantTracks(folder.id)
                            onPlayFolder(folder.toTrackFolder(tracks), false)
                        }
                    )
                }
            }
        } else {
            // Hierarchical Tree List
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(visibleFolders, key = { it.id }) { folder ->
                    val isExpanded = effectiveExpandedIds.contains(folder.id)
                    FolderTreeRow(
                        folder = folder,
                        isExpanded = isExpanded,
                        onToggleExpand = { toggleExpand(folder.id) },
                        onSelectFolder = { onSelectFolder(folder.toTrackFolder()) },
                        onPlayClick = {
                            val tracks = folderTree.getAllDescendantTracks(folder.id)
                            onPlayFolder(folder.toTrackFolder(tracks), false)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Legacy overload accepting List<TrackFolder> to maintain full backwards-compatibility with existing tests and callers.
 */
@Composable
fun FoldersScreen(
    folders: List<TrackFolder>,
    onSelectFolder: (TrackFolder) -> Unit,
    onPlayFolder: (TrackFolder, Boolean) -> Unit
) {
    val tree = remember(folders) {
        FolderHierarchyEngine.buildTreeFromTrackFolders(folders)
    }
    FoldersScreen(
        folderTree = tree,
        onSelectFolder = onSelectFolder,
        onPlayFolder = onPlayFolder
    )
}

/**
 * Compact Tree Row representing a single folder in the hierarchy.
 */
@Composable
private fun FolderTreeRow(
    folder: HierarchicalFolder,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectFolder: () -> Unit,
    onPlayClick: () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        label = "folder_arrow_rotation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (folder.depth * 20).dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSelectFolder)
            .border(1.dp, if (folder.depth == 0) DjSurfaceBorder else DjSurfaceBorder.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .testTag("folder_row_${folder.name}"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (folder.depth == 0) DjSurfaceCard else DjSurfaceDark
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Expand / Collapse disclosure arrow (only present if child folders exist)
            if (folder.hasChildFolders) {
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("folder_arrow_${folder.name}")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse ${folder.name}" else "Expand ${folder.name}",
                        tint = DeckACyan,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(arrowRotation)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(28.dp))
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Folder Icon Box
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DjSurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = DeckACyan,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Folder Info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = folder.name,
                    fontSize = 14.sp,
                    fontWeight = if (folder.depth == 0) FontWeight.Bold else FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (folder.depth > 0 && folder.relativePathFromRoot.contains('/')) {
                    Text(
                        text = folder.relativePathFromRoot,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Track count badge
            Surface(
                color = DjSurfaceElevated,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "${folder.totalTrackCount} ${if (folder.totalTrackCount == 1) "track" else "tracks"}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DeckACyan,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            val durationMinutes = folder.totalDurationSeconds / 60
            if (durationMinutes > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (durationMinutes >= 60) {
                        "${durationMinutes / 60}h ${durationMinutes % 60}m"
                    } else {
                        "${durationMinutes}m"
                    },
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Quick Play Button
            IconButton(
                onClick = onPlayClick,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(DjSurfaceElevated)
                    .testTag("folder_play_${folder.name}")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play folder",
                    tint = DeckACyan,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Path-aware card displayed for search results.
 */
@Composable
private fun SearchResultFolderCard(
    folder: HierarchicalFolder,
    onClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .border(1.dp, DjSurfaceBorder, RoundedCornerShape(10.dp))
            .testTag("folder_search_result_${folder.name}"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DjSurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = DeckACyan,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val pathContext = if (folder.relativePathFromRoot.isNotBlank()) {
                    "${folder.rootName} / ${folder.relativePathFromRoot}"
                } else {
                    folder.rootName
                }

                Text(
                    text = pathContext,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "${folder.totalTrackCount} tracks" + if (folder.directTrackCount != folder.totalTrackCount) " (${folder.directTrackCount} direct)" else "",
                    fontSize = 10.sp,
                    color = DeckACyan
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onPlayClick,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(DjSurfaceElevated)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play folder",
                    tint = DeckACyan,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyFoldersPlaceholder(searchQuery: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (searchQuery.isNotBlank()) "No matching folders found" else "No audio folders indexed",
                fontSize = 14.sp,
                color = TextMuted
            )
        }
    }
}
