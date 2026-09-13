package com.example.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.data.SourceFolderEntity
import com.example.data.WatchedFolderEntity
import com.example.model.FolderHierarchyTree
import com.example.model.HierarchicalFolder
import com.example.model.Track
import com.example.model.TrackFolder
import java.io.File
import java.util.Locale

/**
 * Authoritative Folder Hierarchy Engine for SoundSync.
 *
 * Replaces the legacy flattened folder-card grouping with a true recursive directory hierarchy
 * based on user-selected roots and actual storage paths.
 *
 * Rules:
 *  1. Top-level folders represent only user-selected / granted / imported music roots.
 *  2. Unrelated Android system directories (e.g. /Alarms, /Ringtones, /Notifications, /Android)
 *     are strictly excluded from being top-level roots.
 *  3. Distinguishes folders with the same name across different roots (e.g. /Music/DnB vs /Downloads/DnB).
 *  4. Preserves direct track contents alongside immediate child folders.
 *  5. Calculates recursive total track counts and durations accurately.
 */
object FolderHierarchyEngine {

    private val EXCLUDED_DIR_NAMES = setOf(
        "alarms", "ringtones", "notifications", "android", "lost.dir", ".trashed", "cache"
    )

    data class ResolvedStorageRoot(
        val id: String,
        val displayName: String,
        val rootPath: String,
        val canonicalPath: String,
        val isExplicit: Boolean = true
    )

    /**
     * Builds a complete FolderHierarchyTree from tracks and available root configurations.
     */
    fun buildTree(
        context: Context?,
        tracks: List<Track>,
        sourceFolders: List<SourceFolderEntity> = emptyList(),
        watchedFolders: List<WatchedFolderEntity> = emptyList(),
        persistedSafUris: List<Uri> = emptyList(),
        explicitRootPaths: List<String> = emptyList()
    ): FolderHierarchyTree {
        if (tracks.isEmpty() && sourceFolders.isEmpty() && watchedFolders.isEmpty() && explicitRootPaths.isEmpty()) {
            return FolderHierarchyTree()
        }

        // 1. Resolve candidate roots
        val roots = resolveStorageRoots(
            context = context,
            tracks = tracks,
            sourceFolders = sourceFolders,
            watchedFolders = watchedFolders,
            persistedSafUris = persistedSafUris,
            explicitRootPaths = explicitRootPaths
        )

        // 2. Intermediate node builder per folder key
        // Folder key format: "rootId::relativePathFromRoot" (e.g. "root_123::" for root, "root_123::DnB/Liquid" for subfolder)
        class NodeBuilder(
            val id: String,
            val rootId: String,
            val rootName: String,
            val rootSource: String,
            val parentId: String?,
            val name: String,
            val relativePathFromRoot: String,
            val fullPath: String,
            val depth: Int
        ) {
            val directTracks = mutableListOf<Track>()
            val childKeys = mutableSetOf<String>()
        }

        val nodeBuilders = mutableMapOf<String, NodeBuilder>()

        // Initialize root node builders
        for (root in roots) {
            val rootKey = "root_${root.id}"
            nodeBuilders[rootKey] = NodeBuilder(
                id = rootKey,
                rootId = root.id,
                rootName = root.displayName,
                rootSource = root.rootPath,
                parentId = null,
                name = root.displayName,
                relativePathFromRoot = "",
                fullPath = root.canonicalPath.ifBlank { root.rootPath },
                depth = 0
            )
        }

        // 3. Map tracks to roots and intermediate path hierarchy
        for (track in tracks) {
            val matchedRoot = findBestMatchingRoot(context, track, roots) ?: continue
            val rootKey = "root_${matchedRoot.id}"
            val relativeDirPath = extractRelativeDirPath(context, track, matchedRoot)

            if (relativeDirPath.isBlank()) {
                // Direct track in root folder
                nodeBuilders[rootKey]?.directTracks?.add(track)
            } else {
                // Nested under subfolders
                val segments = relativeDirPath.split('/').filter { it.isNotBlank() }
                var currentParentKey = rootKey
                var currentRelPath = ""

                for (depthIdx in segments.indices) {
                    val segment = segments[depthIdx]
                    currentRelPath = if (currentRelPath.isBlank()) segment else "$currentRelPath/$segment"
                    val currentKey = "dir_${matchedRoot.id}::$currentRelPath"

                    val fullSubPath = if (matchedRoot.canonicalPath.isNotBlank()) {
                        "${matchedRoot.canonicalPath.trimEnd('/')}/$currentRelPath"
                    } else {
                        "${matchedRoot.rootPath.trimEnd('/')}/$currentRelPath"
                    }

                    if (!nodeBuilders.containsKey(currentKey)) {
                        nodeBuilders[currentKey] = NodeBuilder(
                            id = currentKey,
                            rootId = matchedRoot.id,
                            rootName = matchedRoot.displayName,
                            rootSource = matchedRoot.rootPath,
                            parentId = currentParentKey,
                            name = segment,
                            relativePathFromRoot = currentRelPath,
                            fullPath = fullSubPath,
                            depth = depthIdx + 1
                        )
                    }

                    nodeBuilders[currentParentKey]?.childKeys?.add(currentKey)
                    currentParentKey = currentKey
                }

                // Add track to its immediate leaf directory
                nodeBuilders[currentParentKey]?.directTracks?.add(track)
            }
        }

        // 4. Calculate recursive total track counts and durations (post-order / bottom-up)
        val finalNodes = mutableMapOf<String, HierarchicalFolder>()

        fun buildFinalNode(key: String): HierarchicalFolder {
            val cached = finalNodes[key]
            if (cached != null) return cached

            val builder = nodeBuilders[key] ?: error("NodeBuilder missing for key $key")
            val sortedChildKeys = builder.childKeys.sortedBy { childKey ->
                nodeBuilders[childKey]?.name?.lowercase(Locale.ROOT) ?: ""
            }

            var recursiveTracksCount = builder.directTracks.size
            var recursiveDurationSec = builder.directTracks.sumOf { it.durationSeconds }

            for (childKey in sortedChildKeys) {
                val childNode = buildFinalNode(childKey)
                recursiveTracksCount += childNode.totalTrackCount
                recursiveDurationSec += childNode.totalDurationSeconds
            }

            val finalNode = HierarchicalFolder(
                id = builder.id,
                rootId = builder.rootId,
                rootName = builder.rootName,
                rootSource = builder.rootSource,
                parentId = builder.parentId,
                name = builder.name,
                relativePathFromRoot = builder.relativePathFromRoot,
                fullPath = builder.fullPath,
                depth = builder.depth,
                directTracks = builder.directTracks.sortedWith(
                    compareBy<Track> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                        .thenBy { it.title.lowercase(Locale.ROOT) }
                ),
                directTrackCount = builder.directTracks.size,
                totalTrackCount = recursiveTracksCount,
                totalDurationSeconds = recursiveDurationSec,
                hasChildFolders = sortedChildKeys.isNotEmpty(),
                childFolderIds = sortedChildKeys
            )

            finalNodes[key] = finalNode
            return finalNode
        }

        // Build from all roots
        val rootNodesList = mutableListOf<HierarchicalFolder>()
        for (root in roots) {
            val rootKey = "root_${root.id}"
            val rootNode = buildFinalNode(rootKey)
            // Show root if it contains tracks or is an explicitly configured root
            if (rootNode.totalTrackCount > 0 || root.isExplicit) {
                rootNodesList.add(rootNode)
            }
        }

        rootNodesList.sortBy { it.name.lowercase(Locale.ROOT) }

        return FolderHierarchyTree(
            rootNodes = rootNodesList,
            allNodesById = finalNodes,
            totalFoldersCount = finalNodes.size,
            totalTracksCount = rootNodesList.sumOf { it.totalTrackCount }
        )
    }

    /**
     * Builds a FolderHierarchyTree directly from legacy TrackFolder items for backwards-compatibility and tests.
     */
    fun buildTreeFromTrackFolders(folders: List<TrackFolder>): FolderHierarchyTree {
        if (folders.isEmpty()) return FolderHierarchyTree()

        // Synthesize tracks with directory paths from the TrackFolders
        val allTracks = folders.flatMap { folder ->
            folder.tracks.map { track ->
                if (track.directoryPath.isBlank()) track.copy(directoryPath = folder.path) else track
            }
        }

        // Distinct root paths: find top-most directory paths
        val allPaths = folders.map { it.path.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        val rootPaths = findTopLevelRootPaths(allPaths)

        val explicitRoots = rootPaths.mapIndexed { idx, p ->
            val name = p.substringAfterLast('/').ifBlank { p }
            ResolvedStorageRoot(
                id = "root_tf_$idx",
                displayName = name,
                rootPath = p,
                canonicalPath = p,
                isExplicit = true
            )
        }

        return buildTree(
            context = null,
            tracks = allTracks,
            explicitRootPaths = explicitRoots.map { it.rootPath }
        )
    }

    // ── Root Resolution Logic ──────────────────────────────────────────────────

    private fun resolveStorageRoots(
        context: Context?,
        tracks: List<Track>,
        sourceFolders: List<SourceFolderEntity>,
        watchedFolders: List<WatchedFolderEntity>,
        persistedSafUris: List<Uri>,
        explicitRootPaths: List<String>
    ): List<ResolvedStorageRoot> {
        val roots = mutableListOf<ResolvedStorageRoot>()
        val seenPaths = mutableSetOf<String>()

        // 1. Explicit root paths (e.g. from tests or callers)
        for (p in explicitRootPaths) {
            val clean = p.trim().trimEnd('/')
            if (clean.isBlank() || seenPaths.contains(clean.lowercase(Locale.ROOT))) continue
            val name = clean.substringAfterLast('/').ifBlank { clean }
            roots.add(
                ResolvedStorageRoot(
                    id = "exp_${clean.hashCode()}",
                    displayName = name,
                    rootPath = clean,
                    canonicalPath = clean,
                    isExplicit = true
                )
            )
            seenPaths.add(clean.lowercase(Locale.ROOT))
        }

        // 2. Persisted SourceFolder entities
        for (sf in sourceFolders) {
            val p = sf.path.ifBlank { sf.uriString }.trim().trimEnd('/')
            if (p.isBlank() || seenPaths.contains(p.lowercase(Locale.ROOT))) continue
            val can = CanonicalStorageHelper.toCanonicalPath(context, p).trimEnd('/')
            val label = sf.label.ifBlank { can.substringAfterLast('/').ifBlank { "Storage" } }
            roots.add(
                ResolvedStorageRoot(
                    id = sf.id,
                    displayName = label,
                    rootPath = p,
                    canonicalPath = can,
                    isExplicit = true
                )
            )
            seenPaths.add(p.lowercase(Locale.ROOT))
            if (can.isNotBlank()) seenPaths.add(can.lowercase(Locale.ROOT))
        }

        // 3. Persisted WatchedFolder entities
        for (wf in watchedFolders) {
            val p = wf.folderPathOrUri.trim().trimEnd('/')
            if (p.isBlank() || seenPaths.contains(p.lowercase(Locale.ROOT))) continue
            val can = CanonicalStorageHelper.toCanonicalPath(context, p).trimEnd('/')
            val label = wf.displayName.ifBlank { can.substringAfterLast('/').ifBlank { "Folder" } }
            roots.add(
                ResolvedStorageRoot(
                    id = wf.id,
                    displayName = label,
                    rootPath = p,
                    canonicalPath = can,
                    isExplicit = true
                )
            )
            seenPaths.add(p.lowercase(Locale.ROOT))
            if (can.isNotBlank()) seenPaths.add(can.lowercase(Locale.ROOT))
        }

        // 4. Persisted SAF permissions
        for (uri in persistedSafUris) {
            val uriStr = uri.toString().trim().trimEnd('/')
            if (uriStr.isBlank() || seenPaths.contains(uriStr.lowercase(Locale.ROOT))) continue
            val docId = CanonicalStorageHelper.extractDocumentIdFromUri(uriStr) ?: ""
            val name = docId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Music Root" }
            val can = CanonicalStorageHelper.toCanonicalPath(context, uriStr).trimEnd('/')
            roots.add(
                ResolvedStorageRoot(
                    id = "saf_${uriStr.hashCode()}",
                    displayName = name,
                    rootPath = uriStr,
                    canonicalPath = can,
                    isExplicit = true
                )
            )
            seenPaths.add(uriStr.lowercase(Locale.ROOT))
            if (can.isNotBlank()) seenPaths.add(can.lowercase(Locale.ROOT))
        }

        // 5. If no roots exist, detect natural music roots from tracks or default Music directory
        if (roots.isEmpty()) {
            val inferredRoots = inferRootsFromTracks(context, tracks)
            for (inferred in inferredRoots) {
                if (!seenPaths.contains(inferred.rootPath.lowercase(Locale.ROOT))) {
                    roots.add(inferred)
                    seenPaths.add(inferred.rootPath.lowercase(Locale.ROOT))
                }
            }
        }

        // If still empty, add default Internal Music
        if (roots.isEmpty()) {
            val defaultMusicPath = "/storage/emulated/0/Music"
            roots.add(
                ResolvedStorageRoot(
                    id = "internal_music",
                    displayName = "Internal Music",
                    rootPath = defaultMusicPath,
                    canonicalPath = defaultMusicPath,
                    isExplicit = false
                )
            )
        }

        return roots
    }

    /**
     * Infers top-level music roots from track file paths while strictly excluding system directories.
     */
    private fun inferRootsFromTracks(context: Context?, tracks: List<Track>): List<ResolvedStorageRoot> {
        val candidatePaths = tracks.mapNotNull { track ->
            val p = CanonicalStorageHelper.toCanonicalPath(context, track.filePath)
            if (p.isNotBlank() && !p.startsWith("content://") && !p.startsWith("demo://")) {
                p.substringBeforeLast('/')
            } else {
                val rel = track.storageRelativePath.ifBlank {
                    CanonicalStorageHelper.toStorageRelativePath(context, track.filePath)
                }
                if (rel.isNotBlank() && rel.contains('/')) {
                    rel.substringBeforeLast('/')
                } else null
            }
        }.distinct()

        if (candidatePaths.isEmpty()) return emptyList()

        val topRoots = findTopLevelRootPaths(candidatePaths)
        val validRoots = mutableListOf<ResolvedStorageRoot>()

        for (topPath in topRoots) {
            val folderName = topPath.substringAfterLast('/').lowercase(Locale.ROOT)
            if (folderName in EXCLUDED_DIR_NAMES) continue

            val displayName = topPath.substringAfterLast('/').ifBlank { "Music" }
            validRoots.add(
                ResolvedStorageRoot(
                    id = "inferred_${topPath.hashCode()}",
                    displayName = displayName,
                    rootPath = topPath,
                    canonicalPath = topPath,
                    isExplicit = false
                )
            )
        }

        return validRoots
    }

    /**
     * Given a list of paths, returns only the ancestor paths that are not subdirectories of any other path in the list.
     */
    fun findTopLevelRootPaths(paths: List<String>): List<String> {
        val sorted = paths.filter { it.isNotBlank() }.map { it.trimEnd('/') }.distinct().sortedBy { it.length }
        val result = mutableListOf<String>()

        for (p in sorted) {
            val isSub = result.any { root ->
                p == root || p.startsWith("$root/")
            }
            if (!isSub) {
                result.add(p)
            }
        }
        return result
    }

    // ── Track-to-Root Matching & Relative Path Extraction ─────────────────────

    private fun findBestMatchingRoot(
        context: Context?,
        track: Track,
        roots: List<ResolvedStorageRoot>
    ): ResolvedStorageRoot? {
        val canTrackPath = CanonicalStorageHelper.toCanonicalPath(context, track.filePath).lowercase(Locale.ROOT)
        val rawTrackPath = track.filePath.lowercase(Locale.ROOT)
        val relTrackPath = (track.storageRelativePath.ifBlank {
            CanonicalStorageHelper.toStorageRelativePath(context, track.filePath)
        }).lowercase(Locale.ROOT)

        // 1. Longest canonical path prefix match
        val matchByCanonical = roots
            .filter { it.canonicalPath.isNotBlank() && canTrackPath.startsWith(it.canonicalPath.lowercase(Locale.ROOT)) }
            .maxByOrNull { it.canonicalPath.length }

        if (matchByCanonical != null) return matchByCanonical

        // 2. Raw path or URI prefix match
        val matchByRaw = roots
            .filter { it.rootPath.isNotBlank() && rawTrackPath.startsWith(it.rootPath.lowercase(Locale.ROOT)) }
            .maxByOrNull { it.rootPath.length }

        if (matchByRaw != null) return matchByRaw

        // 3. Storage relative path prefix match (e.g. "The AssassinZ Archives/DnB/..." matching root "The AssassinZ Archives")
        val matchByRelative = roots
            .filter { relTrackPath.startsWith(it.displayName.lowercase(Locale.ROOT)) || relTrackPath.startsWith(it.rootPath.lowercase(Locale.ROOT)) }
            .maxByOrNull { it.displayName.length }

        if (matchByRelative != null) return matchByRelative

        // 4. Default: first root that is not excluded
        return roots.firstOrNull()
    }

    private fun extractRelativeDirPath(
        context: Context?,
        track: Track,
        root: ResolvedStorageRoot
    ): String {
        val canTrack = CanonicalStorageHelper.toCanonicalPath(context, track.filePath).trimEnd('/')
        val rootCan = root.canonicalPath.trimEnd('/')

        if (rootCan.isNotBlank() && canTrack.startsWith(rootCan, ignoreCase = true)) {
            val rel = canTrack.substring(rootCan.length).trimStart('/')
            return if (rel.contains('/')) rel.substringBeforeLast('/') else ""
        }

        val rawTrack = track.filePath.trimEnd('/')
        val rootRaw = root.rootPath.trimEnd('/')
        if (rootRaw.isNotBlank() && rawTrack.startsWith(rootRaw, ignoreCase = true)) {
            val rel = rawTrack.substring(rootRaw.length).trimStart('/')
            return if (rel.contains('/')) rel.substringBeforeLast('/') else ""
        }

        // Relative path fallback
        val relTrack = track.storageRelativePath.ifBlank {
            CanonicalStorageHelper.toStorageRelativePath(context, track.filePath)
        }.trimStart('/')

        val rootName = root.displayName.trim()
        if (relTrack.startsWith(rootName, ignoreCase = true)) {
            val sub = relTrack.substring(rootName.length).trimStart('/')
            return if (sub.contains('/')) sub.substringBeforeLast('/') else ""
        }

        if (relTrack.contains('/')) {
            return relTrack.substringBeforeLast('/')
        }

        return ""
    }
}
