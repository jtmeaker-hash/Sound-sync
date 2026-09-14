package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.backup.SoundSyncBackupManager
import com.example.metadata.artist.ArtistCollaborationParser
import com.example.metadata.artist.ArtistIndexManager
import com.example.model.Album
import com.example.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage1LibraryMetadataSettingsTest {

    private lateinit var context: Context
    private lateinit var backupManager: SoundSyncBackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SoundSyncBackupManager.resetInstance()
        context.getSharedPreferences("soundsync_backup_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        backupManager = SoundSyncBackupManager(context)
    }

    // ==========================================
    // 1. AUTO BACKUP DEFAULTS TO OFF & LIFECYCLE
    // ==========================================

    @Test
    fun `auto backup defaults to OFF on fresh install or unconfigured state`() {
        assertFalse(
            "Auto backup must default to false/OFF on fresh installs",
            backupManager.isAutoBackupEnabled()
        )
    }

    @Test
    fun `auto backup can be explicitly enabled and persists`() {
        backupManager.setAutoBackupEnabled(true)
        assertTrue(
            "Auto backup must be true after user explicitly enables it",
            backupManager.isAutoBackupEnabled()
        )

        // Verify explicit set flag is true
        val prefs = context.getSharedPreferences("soundsync_backup_prefs", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("auto_backup_explicit_set", false))
        assertTrue(prefs.getBoolean("auto_backup_enabled", false))
    }

    @Test
    fun `auto backup can be explicitly disabled and cancels future work without deleting backups`() {
        // First enable
        backupManager.setAutoBackupEnabled(true)
        assertTrue(backupManager.isAutoBackupEnabled())

        // Create a dummy backup file
        val backupDir = File(context.filesDir, "backups").apply { mkdirs() }
        val dummyBackup = File(backupDir, "SoundSync_Backup_test.json").apply {
            writeText("{\"version\":1}")
        }
        assertTrue(dummyBackup.exists())

        // Now disable
        backupManager.setAutoBackupEnabled(false)
        assertFalse(
            "Auto backup must be false after user explicitly disables it",
            backupManager.isAutoBackupEnabled()
        )

        // Verify existing backup files remain intact
        assertTrue("Disabling auto backup must NOT delete existing backup files", dummyBackup.exists())
    }

    @Test
    fun `legacy preference without explicit set flag migrates conservatively to OFF`() {
        val prefs = context.getSharedPreferences("soundsync_backup_prefs", Context.MODE_PRIVATE)
        // Simulate legacy state where old default may have set auto_backup_enabled = true but without explicit flag
        prefs.edit()
            .putBoolean("auto_backup_enabled", true)
            .remove("auto_backup_explicit_set")
            .commit()

        val manager = SoundSyncBackupManager(context)
        assertFalse(
            "Legacy implicit default must migrate conservatively to OFF",
            manager.isAutoBackupEnabled()
        )
    }

    @Test
    fun `legacy preference WITH explicit set flag preserves user explicit choice`() {
        val prefs = context.getSharedPreferences("soundsync_backup_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("auto_backup_enabled", true)
            .putBoolean("auto_backup_explicit_set", true)
            .commit()

        val manager = SoundSyncBackupManager(context)
        assertTrue(
            "Legacy preference with explicit user choice must be preserved",
            manager.isAutoBackupEnabled()
        )
    }

    // ==========================================
    // 2. ARTIST COLLABORATION PARSER TESTS
    // ==========================================

    @Test
    fun `ArtistCollaborationParser splits ampersand collaboration into distinct artists`() {
        val result = ArtistCollaborationParser.parseCollaborators("240 KM/H & Adrian Mills")
        assertEquals(listOf("240 KM/H", "Adrian Mills"), result)
    }

    @Test
    fun `ArtistCollaborationParser splits feat collaboration`() {
        val result1 = ArtistCollaborationParser.parseCollaborators("Drake feat. Rihanna")
        assertEquals(listOf("Drake", "Rihanna"), result1)

        val result2 = ArtistCollaborationParser.parseCollaborators("Skrillex ft Fred again..")
        assertEquals(listOf("Skrillex", "Fred again.."), result2)

        val result3 = ArtistCollaborationParser.parseCollaborators("Calvin Harris featuring Ellie Goulding")
        assertEquals(listOf("Calvin Harris", "Ellie Goulding"), result3)
    }

    @Test
    fun `ArtistCollaborationParser splits multiple comma and ampersand delimiters`() {
        val result = ArtistCollaborationParser.parseCollaborators("Artist A, Artist B & Artist C")
        assertEquals(listOf("Artist A", "Artist B", "Artist C"), result)
    }

    @Test
    fun `ArtistCollaborationParser splits mixed delimiters including x`() {
        val result = ArtistCollaborationParser.parseCollaborators("Fisher x Chris Lake ft. Gotye")
        assertEquals(listOf("Fisher", "Chris Lake", "Gotye"), result)
    }

    @Test
    fun `ArtistCollaborationParser preserves protected band names intact`() {
        assertEquals(listOf("Above & Beyond"), ArtistCollaborationParser.parseCollaborators("Above & Beyond"))
        assertEquals(listOf("Earth, Wind & Fire"), ArtistCollaborationParser.parseCollaborators("Earth, Wind & Fire"))
        assertEquals(listOf("Simon & Garfunkel"), ArtistCollaborationParser.parseCollaborators("Simon & Garfunkel"))
        assertEquals(listOf("AC/DC"), ArtistCollaborationParser.parseCollaborators("AC/DC"))
        assertEquals(listOf("Crosby, Stills, Nash & Young"), ArtistCollaborationParser.parseCollaborators("Crosby, Stills, Nash & Young"))
        assertEquals(listOf("Kool & The Gang"), ArtistCollaborationParser.parseCollaborators("Kool & The Gang"))
        assertEquals(listOf("Hall & Oates"), ArtistCollaborationParser.parseCollaborators("Hall & Oates"))
    }

    @Test
    fun `ArtistCollaborationParser correctly splits protected band collaborating with another artist`() {
        val result = ArtistCollaborationParser.parseCollaborators("Above & Beyond feat. Richard Bedford")
        assertEquals(listOf("Above & Beyond", "Richard Bedford"), result)
    }

    @Test
    fun `ArtistCollaborationParser deduplicates case-insensitively while preserving proper casing`() {
        val result = ArtistCollaborationParser.parseCollaborators("Bicep & bicep")
        assertEquals(listOf("Bicep"), result)
    }

    // ==========================================
    // 3. ARTIST INDEX MANAGER & LIBRARY GROUPING
    // ==========================================

    @Test
    fun `buildArtistsFromTracks links collaboration tracks to both distinct artists`() {
        val track1 = createTestTrack(
            id = "t1",
            title = "Phantom",
            artist = "240 KM/H & Adrian Mills",
            album = "Rave Dimension"
        )

        val artists = ArtistIndexManager.buildArtistsFromTracks(listOf(track1), emptyList())
        val artistNames = artists.map { it.name }.sorted()

        assertEquals(listOf("240 KM/H", "Adrian Mills"), artistNames)

        val kmh = artists.first { it.name == "240 KM/H" }
        val mills = artists.first { it.name == "Adrian Mills" }

        assertEquals(1, kmh.songCount)
        assertEquals(1, mills.songCount)
        assertEquals("Phantom", kmh.songs.first().title)
        assertEquals("Phantom", mills.songs.first().title)
    }

    @Test
    fun `buildArtistsFromTracks accurately computes song and album counts for solo and collab tracks`() {
        val track1 = createTestTrack(id = "t1", title = "Solo 1", artist = "Artist A", album = "Album 1")
        val track2 = createTestTrack(id = "t2", title = "Collab 1", artist = "Artist A & Artist B", album = "Album 2")
        val track3 = createTestTrack(id = "t3", title = "Collab 2", artist = "Artist B feat. Artist C", album = "Album 2")
        val track4 = createTestTrack(id = "t4", title = "Solo 2", artist = "Artist A", album = "Album 1")

        val albums: List<Album> = listOf(
            Album(
                id = "alb1",
                title = "Album 1",
                artist = "Artist A",
                trackCount = 2,
                totalDurationSeconds = 360,
                tracks = listOf(track1, track4)
            ),
            Album(
                id = "alb2",
                title = "Album 2",
                artist = "Artist A & Artist B",
                trackCount = 2,
                totalDurationSeconds = 360,
                tracks = listOf(track2, track3)
            )
        )

        val artists = ArtistIndexManager.buildArtistsFromTracks(
            listOf(track1, track2, track3, track4),
            albums
        )

        val artistA = artists.first { it.name == "Artist A" }
        val artistB = artists.first { it.name == "Artist B" }
        val artistC = artists.first { it.name == "Artist C" }

        // Artist A has 3 tracks (Solo 1, Solo 2, Collab 1) across 2 albums
        assertEquals(3, artistA.songCount)
        assertEquals(2, artistA.albumCount)

        // Artist B has 2 tracks (Collab 1, Collab 2) across 1 album
        assertEquals(2, artistB.songCount)
        assertEquals(1, artistB.albumCount)

        // Artist C has 1 track (Collab 2) across 1 album
        assertEquals(1, artistC.songCount)
        assertEquals(1, artistC.albumCount)
    }

    @Test
    fun `buildArtistsFromTracks is idempotent and does not inflate counts on re-indexing`() {
        val track1 = createTestTrack(id = "t1", title = "Track 1", artist = "Artist X & Artist Y", album = "Album XY")
        val track2 = createTestTrack(id = "t2", title = "Track 2", artist = "Artist X", album = "Album X")
        val tracks = listOf(track1, track2)

        val firstRun = ArtistIndexManager.buildArtistsFromTracks(tracks, emptyList())
        val secondRun = ArtistIndexManager.buildArtistsFromTracks(tracks, emptyList())

        assertEquals(firstRun.size, secondRun.size)
        assertEquals(
            firstRun.map { it.name to it.songCount }.sortedBy { it.first },
            secondRun.map { it.name to it.songCount }.sortedBy { it.first }
        )
    }

    private fun createTestTrack(
        id: String,
        title: String,
        artist: String,
        album: String = "Test Album"
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationSeconds = 180,
            filePath = "/storage/emulated/0/Music/$title.mp3",
            bpm = 128.0,
            camelotKey = "8A"
        )
    }
}
