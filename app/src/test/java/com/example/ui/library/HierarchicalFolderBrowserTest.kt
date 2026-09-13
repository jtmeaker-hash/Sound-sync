package com.example.ui.library

import com.example.data.SourceFolderEntity
import com.example.model.HierarchicalFolder
import com.example.model.Track
import com.example.storage.FolderHierarchyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HierarchicalFolderBrowserTest {

    private fun createDummyTrack(
        id: String,
        title: String,
        filePath: String,
        durationSeconds: Int = 180,
        storageRelativePath: String = ""
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = "Test Artist",
            filePath = filePath,
            storageRelativePath = storageRelativePath,
            directoryPath = if (filePath.contains('/')) filePath.substringBeforeLast('/') else "",
            durationSeconds = durationSeconds
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ACCEPTANCE TEST: The AssassinZ Archives with subfolders and nested DnB
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testAcceptance_theAssassinZArchivesHierarchyAndExpansion() {
        val rootPath = "/storage/AA44-8296/The AssassinZ Archives"

        val tracks = listOf(
            // Direct track in root
            createDummyTrack("t_root", "Intro", "$rootPath/Intro.mp3"),
            // Chill tracks
            createDummyTrack("t_chill1", "Chill 1", "$rootPath/Chill/Track01.mp3"),
            createDummyTrack("t_chill2", "Chill 2", "$rootPath/Chill/Track02.mp3"),
            // Country tracks
            createDummyTrack("t_country1", "Country 1", "$rootPath/Country/Track01.mp3"),
            // Hardstyle tracks
            createDummyTrack("t_hardstyle1", "Hardstyle 1", "$rootPath/Hardstyle/Track01.mp3"),
            // DnB direct track
            createDummyTrack("t_dnb_direct", "DnB Anthem", "$rootPath/DnB/Anthem.mp3"),
            // Nested DnB subfolders: Liquid & Neurofunk
            createDummyTrack("t_liquid1", "Liquid 1", "$rootPath/DnB/Liquid/LiquidTrack01.mp3"),
            createDummyTrack("t_neuro1", "Neuro 1", "$rootPath/DnB/Neurofunk/NeuroTrack01.mp3")
        )

        val sourceFolders = listOf(
            SourceFolderEntity(
                id = "sf_assassinz",
                label = "The AssassinZ Archives",
                path = rootPath,
                uriString = "",
                typeName = "SD_CARD",
                isOnline = true,
                trackCount = tracks.size,
                freeSpaceGb = 32.0,
                totalSpaceGb = 64.0,
                lastScanned = System.currentTimeMillis()
            )
        )

        val tree = FolderHierarchyEngine.buildTree(
            context = null,
            tracks = tracks,
            sourceFolders = sourceFolders
        )

        // 1. Initial State: Exactly 1 root node: "The AssassinZ Archives"
        assertEquals("Initial UI must have exactly 1 root folder", 1, tree.rootNodes.size)
        val rootNode = tree.rootNodes[0]
        assertEquals("Root name must match", "The AssassinZ Archives", rootNode.name)
        assertEquals("Total tracks across root tree must be 8", 8, rootNode.totalTrackCount)
        assertEquals("Direct tracks in root folder must be 1", 1, rootNode.directTrackCount)
        assertTrue("Root must indicate it has child folders", rootNode.hasChildFolders)

        // Initial visible list with NO folders expanded: Only the root is shown!
        val initialVisible = tree.buildVisibleList(expandedFolderIds = emptySet())
        assertEquals("Initial collapsed UI must show ONLY the root node", 1, initialVisible.size)
        assertEquals("Initial visible item must be The AssassinZ Archives", "The AssassinZ Archives", initialVisible[0].name)

        // 2. Expand Root: Reveals immediate children (Chill, Country, DnB, Hardstyle)
        val afterRootExpanded = tree.buildVisibleList(expandedFolderIds = setOf(rootNode.id))
        val namesAfterRoot = afterRootExpanded.map { it.name }
        assertTrue("Root must still be in visible list", namesAfterRoot.contains("The AssassinZ Archives"))
        assertTrue("Chill must be visible after expanding root", namesAfterRoot.contains("Chill"))
        assertTrue("Country must be visible after expanding root", namesAfterRoot.contains("Country"))
        assertTrue("DnB must be visible after expanding root", namesAfterRoot.contains("DnB"))
        assertTrue("Hardstyle must be visible after expanding root", namesAfterRoot.contains("Hardstyle"))

        // Subfolders of DnB (Liquid, Neurofunk) MUST STAY HIDDEN while DnB is collapsed!
        assertFalse("Liquid must stay hidden until DnB is expanded", namesAfterRoot.contains("Liquid"))
        assertFalse("Neurofunk must stay hidden until DnB is expanded", namesAfterRoot.contains("Neurofunk"))

        // Check DnB properties
        val dnbNode = tree.allNodesById.values.first { it.name == "DnB" }
        assertEquals("DnB depth must be 1", 1, dnbNode.depth)
        assertTrue("DnB has child folders (Liquid, Neurofunk)", dnbNode.hasChildFolders)
        assertEquals("DnB direct track count must be 1 (Anthem.mp3)", 1, dnbNode.directTrackCount)
        assertEquals("DnB total track count must be 3 (1 direct + 1 Liquid + 1 Neurofunk)", 3, dnbNode.totalTrackCount)

        // 3. Expand DnB: Now Liquid and Neurofunk are revealed
        val afterDnbExpanded = tree.buildVisibleList(expandedFolderIds = setOf(rootNode.id, dnbNode.id))
        val namesAfterDnb = afterDnbExpanded.map { it.name }
        assertTrue("Liquid must now be visible", namesAfterDnb.contains("Liquid"))
        assertTrue("Neurofunk must now be visible", namesAfterDnb.contains("Neurofunk"))

        val liquidNode = tree.allNodesById.values.first { it.name == "Liquid" }
        assertEquals("Liquid depth must be 2", 2, liquidNode.depth)
        assertEquals("Liquid parent must be DnB", dnbNode.id, liquidNode.parentId)
        assertFalse("Liquid has no subfolders", liquidNode.hasChildFolders)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST: Distinguish duplicate folder names under different roots
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testSameChildFolderNameUnderDifferentRootsRemainSeparate() {
        val rootMusic = "/storage/emulated/0/Music"
        val rootDownloads = "/storage/emulated/0/Download"

        val tracks = listOf(
            createDummyTrack("m1", "Song A", "$rootMusic/DnB/SongA.mp3"),
            createDummyTrack("d1", "Song B", "$rootDownloads/DnB/SongB.mp3")
        )

        val explicitRoots = listOf(rootMusic, rootDownloads)
        val tree = FolderHierarchyEngine.buildTree(
            context = null,
            tracks = tracks,
            explicitRootPaths = explicitRoots
        )

        assertEquals("Must have 2 root folders", 2, tree.rootNodes.size)

        val dnbFolders = tree.allNodesById.values.filter { it.name == "DnB" }
        assertEquals("Must have exactly 2 distinct DnB folder nodes", 2, dnbFolders.size)

        val dnb1 = dnbFolders[0]
        val dnb2 = dnbFolders[1]

        assertNotEquals("IDs must be distinct", dnb1.id, dnb2.id)
        assertNotEquals("Root IDs must be distinct", dnb1.rootId, dnb2.rootId)
        assertNotEquals("Full paths must be distinct", dnb1.fullPath, dnb2.fullPath)
        assertNotEquals("Parent IDs must be distinct", dnb1.parentId, dnb2.parentId)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST: Exclude unrelated Android system directories
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testExcludesAndroidSystemDirectoriesFromRoots() {
        val tracks = listOf(
            createDummyTrack("t_music", "Good Track", "/storage/emulated/0/Music/Album/Song.mp3"),
            // Stray MediaStore tracks in system folders:
            createDummyTrack("t_alarm", "Alarm Tone", "/storage/emulated/0/Alarms/zedge/loud.mp3"),
            createDummyTrack("t_ring", "Ringtone", "/storage/emulated/0/Ringtones/phone.mp3"),
            createDummyTrack("t_notif", "Notification", "/storage/emulated/0/Notifications/ding.mp3")
        )

        val tree = FolderHierarchyEngine.buildTree(
            context = null,
            tracks = tracks
        )

        val rootNames = tree.rootNodes.map { it.name.lowercase() }
        assertFalse("Alarms must NOT be a root", rootNames.contains("alarms"))
        assertFalse("Ringtones must NOT be a root", rootNames.contains("ringtones"))
        assertFalse("Notifications must NOT be a root", rootNames.contains("notifications"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST: Ancestor resolution for search navigation
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testAncestorResolutionForSearchSelection() {
        val rootPath = "/storage/emulated/0/Music"
        val tracks = listOf(
            createDummyTrack("t1", "Track", "$rootPath/EDM/Bass/Dubstep/Drop.mp3")
        )

        val tree = FolderHierarchyEngine.buildTree(
            context = null,
            tracks = tracks,
            explicitRootPaths = listOf(rootPath)
        )

        val dubstepNode = tree.allNodesById.values.first { it.name == "Dubstep" }
        assertNotNull(dubstepNode)

        val ancestorIds = tree.getAncestorIds(dubstepNode.id)
        assertEquals("Dubstep has 3 ancestors: Bass, EDM, Music", 3, ancestorIds.size)

        val ancestorNames = ancestorIds.mapNotNull { tree.allNodesById[it]?.name }
        assertEquals(listOf("Bass", "EDM", "Music"), ancestorNames)

        // Expanding ancestors causes Dubstep to become visible in the tree!
        val visible = tree.buildVisibleList(expandedFolderIds = ancestorIds.toSet())
        assertTrue("Dubstep must be in visible list when all ancestors are expanded", visible.any { it.id == dubstepNode.id })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST: Folder with both direct tracks and child subfolders
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testFolderWithDirectTracksAndChildFolders() {
        val rootPath = "/storage/emulated/0/Music"
        val tracks = listOf(
            createDummyTrack("t_direct1", "Direct 1", "$rootPath/House/DirectTrack1.mp3", durationSeconds = 120),
            createDummyTrack("t_direct2", "Direct 2", "$rootPath/House/DirectTrack2.mp3", durationSeconds = 180),
            createDummyTrack("t_sub", "Sub Track", "$rootPath/House/DeepHouse/SubTrack.mp3", durationSeconds = 200)
        )

        val tree = FolderHierarchyEngine.buildTree(
            context = null,
            tracks = tracks,
            explicitRootPaths = listOf(rootPath)
        )

        val houseNode = tree.allNodesById.values.first { it.name == "House" }
        assertEquals("Direct track count must be 2", 2, houseNode.directTrackCount)
        assertEquals("Total track count must be 3 (2 direct + 1 in DeepHouse)", 3, houseNode.totalTrackCount)
        assertEquals("Total duration must be 500s", 500, houseNode.totalDurationSeconds)
        assertTrue("House must have child folders", houseNode.hasChildFolders)
        assertEquals("House must have 1 child folder", 1, houseNode.childFolderIds.size)

        val deepHouseNode = tree.allNodesById[houseNode.childFolderIds[0]]
        assertNotNull(deepHouseNode)
        assertEquals("DeepHouse", deepHouseNode?.name)
        assertEquals(1, deepHouseNode?.directTrackCount)
        assertEquals(1, deepHouseNode?.totalTrackCount)
    }
}
