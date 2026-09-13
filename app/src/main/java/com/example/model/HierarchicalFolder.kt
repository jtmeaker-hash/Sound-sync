package com.example.model

/**
 * Authoritative Hierarchical Folder Model for SoundSync.
 *
 * Represents a directory node within a user-selected or discovered storage root.
 * Supports arbitrary recursive nesting depth, path-aware identity, and explicit
 * parent-child relationships.
 */
data class HierarchicalFolder(
    val id: String,                         // Stable unique ID: e.g. "root_${rootId}::${relativePathFromRoot}"
    val rootId: String,                     // Stable root ID (e.g. source folder id or volume UUID)
    val rootName: String,                   // Display name of root folder (e.g. "THE ASSASSINZ ARCHIVES")
    val rootSource: String,                 // SAF tree / volume / configured path
    val parentId: String?,                  // Parent directory ID (null for root)
    val name: String,                       // Display name of this folder (e.g. "DnB")
    val relativePathFromRoot: String,       // Relative path from root ("" for root, "DnB" for immediate child)
    val fullPath: String,                   // Full filesystem path or document URI
    val depth: Int = 0,                     // Indentation depth in hierarchy (0 = root, 1 = immediate child, etc.)
    val directTracks: List<Track> = emptyList(), // Tracks directly inside this folder
    val directTrackCount: Int = directTracks.size,
    val totalTrackCount: Int = directTracks.size, // Recursive track count including all descendants
    val totalDurationSeconds: Int = directTracks.sumOf { it.durationSeconds }, // Recursive duration
    val hasChildFolders: Boolean = false,
    val childFolderIds: List<String> = emptyList()
) {
    /**
     * Converts to legacy TrackFolder for interoperability with FolderDetailScreen and playback.
     */
    fun toTrackFolder(allDescendantTracks: List<Track>? = null): TrackFolder {
        val tracksToUse = allDescendantTracks ?: directTracks
        return TrackFolder(
            id = id,
            name = name,
            path = fullPath,
            trackCount = if (allDescendantTracks != null) totalTrackCount else directTrackCount,
            totalDurationSeconds = totalDurationSeconds,
            tracks = tracksToUse
        )
    }
}

/**
 * Container representing the complete hierarchical tree of folders.
 */
data class FolderHierarchyTree(
    val rootNodes: List<HierarchicalFolder> = emptyList(),
    val allNodesById: Map<String, HierarchicalFolder> = emptyMap(),
    val totalFoldersCount: Int = allNodesById.size,
    val totalTracksCount: Int = rootNodes.sumOf { it.totalTrackCount }
) {
    fun getChildren(parentId: String): List<HierarchicalFolder> {
        val parent = allNodesById[parentId] ?: return emptyList()
        return parent.childFolderIds.mapNotNull { allNodesById[it] }
    }

    fun getAncestorIds(folderId: String): List<String> {
        val ancestors = mutableListOf<String>()
        var curr = allNodesById[folderId]
        while (curr?.parentId != null) {
            ancestors.add(curr.parentId)
            curr = allNodesById[curr.parentId]
        }
        return ancestors
    }

    fun getAllDescendantTracks(folderId: String): List<Track> {
        val folder = allNodesById[folderId] ?: return emptyList()
        val result = mutableListOf<Track>()
        result.addAll(folder.directTracks)
        for (childId in folder.childFolderIds) {
            result.addAll(getAllDescendantTracks(childId))
        }
        return result
    }

    /**
     * Builds a flattened pre-order list of visible folder nodes based on current expansion state.
     * Roots are always visible; children are visible only if their parent is in expandedFolderIds.
     */
    fun buildVisibleList(expandedFolderIds: Set<String>): List<HierarchicalFolder> {
        val visible = mutableListOf<HierarchicalFolder>()
        for (root in rootNodes) {
            collectVisible(root, expandedFolderIds, visible)
        }
        return visible
    }

    private fun collectVisible(
        node: HierarchicalFolder,
        expandedFolderIds: Set<String>,
        accumulator: MutableList<HierarchicalFolder>
    ) {
        accumulator.add(node)
        if (node.hasChildFolders && node.id in expandedFolderIds) {
            for (childId in node.childFolderIds) {
                val child = allNodesById[childId]
                if (child != null) {
                    collectVisible(child, expandedFolderIds, accumulator)
                }
            }
        }
    }
}
