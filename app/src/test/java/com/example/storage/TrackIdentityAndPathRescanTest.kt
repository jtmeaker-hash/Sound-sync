package com.example.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TrackEntity
import com.example.model.AnalysisState
import com.example.model.MetadataScanState
import com.example.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrackIdentityAndPathRescanTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun createSampleTrack(
        id: String = "media_101",
        title: String = "Take The Risk (Original Mix)",
        artist: String = "DJ Producer",
        album: String = "Club Weapons Vol 1",
        filePath: String = "/storage/emulated/0/Music/Downloads/track101.wav",
        fingerprint: String = "fp_abcdef1234567890",
        fileSizeMb: Double = 45.2,
        durationSeconds: Int = 384,
        metadataScanState: String = MetadataScanState.COMPLETE.name,
        analysisState: String = AnalysisState.COMPLETE.name,
        bpm: Double = 128.0,
        musicalKey: String = "8A"
    ): TrackEntity {
        val track = Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            albumArtist = "DJ Producer",
            genre = "Tech House",
            subGenre = "Club",
            bpm = bpm,
            musicalKey = musicalKey,
            camelotKey = musicalKey,
            durationSeconds = durationSeconds,
            bitrateKbps = 1411,
            format = "WAV",
            fileSizeMb = fileSizeMb,
            filePath = filePath,
            directoryPath = filePath.substringBeforeLast('/'),
            isOfflineReady = true,
            syncState = com.example.model.SyncState.SYNCED,
            platforms = listOf(com.example.model.MusicPlatform.LOCAL),
            energyRating = 8,
            hotCues = listOf(0, 60, 120, 180),
            isAiTagged = false,
            qualityRating = com.example.model.AudioQualityRating.TRUE_LOSSLESS,
            dateAdded = 1690000000000L,
            crateId = "crate_all",
            sourceId = "local_storage",
            storageRelativePath = filePath.removePrefix("/storage/emulated/0/"),
            contentFingerprint = fingerprint,
            fingerprintAlgorithm = "SOUNDSYNC_SHA256",
            fingerprintTimestamp = 1690000000000L,
            fileModifiedTimestamp = 1690000000000L,
            metadataScanState = metadataScanState,
            metadataScanTimestamp = 1690000000000L,
            userConfirmedMetadata = false,
            metadataWriteState = "CLEAN",
            analysisState = com.example.model.AnalysisState.valueOf(analysisState),
            analysisVersion = 1,
            lastAnalysedAt = 1690000000000L,
            analysisFailureReason = null,
            analysisRetryCount = 0,
            bpmConfidence = 100.0,
            bpmAnalysisVersion = "v1",
            bpmLastAnalyzed = 1690000000000L,
            keyConfidence = 100.0,
            keyAnalysisVersion = "v1",
            keyLastAnalyzed = 1690000000000L,
            isManualBpm = false,
            isManualKey = false,
            rating = 5,
            customTags = "Peak Time, Bangers",
            notes = "Opening track for Friday set",
            composer = "DJ Producer",
            artworkCachePath = null,
            artworkUrl = null,
            artworkSource = null,
            appleTrackId = 12345678L,
            appleCollectionId = null,
            appleArtistId = null,
            theAudioDbAlbumId = null,
            theAudioDbArtistId = null,
            trackNumber = 1,
            discNumber = 1,
            releaseDate = "2023-08-01",
            releaseYear = 2023,
            recordLabel = "SoundSync Records",
            barcode = null,
            isrc = "GB-ABC-23-00101",
            originalArtist = "DJ Producer",
            resolvedArtist = "DJ Producer",
            metadataSource = "EMBEDDED",
            metadataConfidence = 100.0
        )
        return TrackEntity.fromTrack(track)
    }

    @Test
    fun test1_sameFileSameLocation_preservesMetadataScanStatus() {
        val original = createSampleTrack(
            metadataScanState = MetadataScanState.COMPLETE.name,
            analysisState = AnalysisState.COMPLETE.name
        )
        val indexes = TrackIdentityReconciler.buildIndexes(context, listOf(original))

        // Normal rescan discovers same file at same path
        val result = TrackIdentityReconciler.reconcileCandidate(
            candidatePathOrUri = original.filePath,
            candidateFingerprint = original.contentFingerprint,
            candidateSizeBytes = (original.fileSizeMb * 1024 * 1024).toLong(),
            candidateDurationSec = original.durationSeconds,
            candidateTitle = original.title,
            candidateArtist = original.artist,
            candidateAlbum = original.album,
            candidateIsrc = original.isrc,
            candidateModified = original.fileModifiedTimestamp,
            context = context,
            indexes = indexes
        )

        assertNotNull("Should find existing track", result.matchedTrack)
        assertFalse("Should not be relinked because location did not change", result.isRelinked)
        assertEquals(original.id, result.matchedTrack?.id)
        assertEquals(MetadataScanState.COMPLETE.name, result.matchedTrack?.metadataScanState)
        assertEquals(AnalysisState.COMPLETE.name, result.matchedTrack?.analysisState)
        assertTrue(TrackIdentityReconciler.isMetadataScanComplete(result.matchedTrack?.metadataScanState))
    }

    @Test
    fun test2_sameFileMovedToDifferentFolder_updatesPathAndPreservesCompletedMetadata() {
        val original = createSampleTrack(
            filePath = "/storage/emulated/0/Music/Downloads/track101.wav",
            fingerprint = "fp_abcdef1234567890",
            metadataScanState = MetadataScanState.COMPLETE.name,
            analysisState = AnalysisState.COMPLETE.name
        )
        val indexes = TrackIdentityReconciler.buildIndexes(context, listOf(original))

        val newPath = "/storage/emulated/0/Music/DJ_Sets/Take The Risk.wav"

        // Discovered moved file
        val result = TrackIdentityReconciler.reconcileCandidate(
            candidatePathOrUri = newPath,
            candidateFingerprint = original.contentFingerprint,
            candidateSizeBytes = (original.fileSizeMb * 1024 * 1024).toLong(),
            candidateDurationSec = original.durationSeconds,
            candidateTitle = "Take The Risk",
            candidateArtist = "DJ Producer",
            candidateAlbum = "Club Weapons Vol 1",
            candidateIsrc = original.isrc,
            candidateModified = original.fileModifiedTimestamp,
            context = context,
            indexes = indexes
        )

        assertTrue("Should detect relocated track", result.isRelinked)
        assertNotNull("Should provide relinked track entity", result.relinkedTrack)
        val relinked = result.relinkedTrack!!

        // Path is updated
        assertEquals(newPath, relinked.filePath)
        assertEquals("Music/DJ_Sets/Take The Risk.wav", relinked.storageRelativePath)

        // Identity & Analysis are strictly preserved
        assertEquals(original.id, relinked.id)
        assertEquals(MetadataScanState.COMPLETE.name, relinked.metadataScanState)
        assertEquals(AnalysisState.COMPLETE.name, relinked.analysisState)
        assertEquals(128.0, relinked.bpm, 0.001)
        assertEquals("8A", relinked.musicalKey)
        assertEquals("0,60,120,180", relinked.hotCuesString)
        assertEquals("Peak Time, Bangers", relinked.customTags)
        assertEquals(5, relinked.rating)
        assertEquals("GB-ABC-23-00101", relinked.isrc)
        assertTrue(TrackIdentityReconciler.isMetadataScanComplete(relinked.metadataScanState))
    }

    @Test
    fun test3_sameFileRenamed_updatesPathAndPreservesCompletedMetadata() {
        val original = createSampleTrack(
            filePath = "/storage/emulated/0/Music/track101.aiff",
            fingerprint = "fp_aiff_9988776655",
            metadataScanState = MetadataScanState.COMPLETE.name,
            analysisState = AnalysisState.COMPLETE.name
        )
        val indexes = TrackIdentityReconciler.buildIndexes(context, listOf(original))

        val renamedPath = "/storage/emulated/0/Music/DJ Producer - Take The Risk (Original Mix).aiff"

        val result = TrackIdentityReconciler.reconcileCandidate(
            candidatePathOrUri = renamedPath,
            candidateFingerprint = "fp_aiff_9988776655",
            candidateSizeBytes = (original.fileSizeMb * 1024 * 1024).toLong(),
            candidateDurationSec = original.durationSeconds,
            candidateTitle = "Take The Risk (Original Mix)",
            candidateArtist = "DJ Producer",
            candidateAlbum = "Club Weapons Vol 1",
            candidateIsrc = original.isrc,
            candidateModified = original.fileModifiedTimestamp,
            context = context,
            indexes = indexes
        )

        assertTrue("Should detect renamed file", result.isRelinked)
        val relinked = result.relinkedTrack!!
        assertEquals(renamedPath, relinked.filePath)
        assertEquals(original.id, relinked.id)
        assertEquals(MetadataScanState.COMPLETE.name, relinked.metadataScanState)
        assertEquals(AnalysisState.COMPLETE.name, relinked.analysisState)
        assertEquals(128.0, relinked.bpm, 0.001)
        assertEquals("8A", relinked.musicalKey)
    }

    @Test
    fun test4_brandNewFile_isIdentifiedAsNewTrack() {
        val existingTrack = createSampleTrack()
        val indexes = TrackIdentityReconciler.buildIndexes(context, listOf(existingTrack))

        val newFilePath = "/storage/emulated/0/Music/New_Song.flac"
        val newFingerprint = "fp_flac_brand_new_123"

        val result = TrackIdentityReconciler.reconcileCandidate(
            candidatePathOrUri = newFilePath,
            candidateFingerprint = newFingerprint,
            candidateSizeBytes = 30 * 1024 * 1024L,
            candidateDurationSec = 240,
            candidateTitle = "New Song",
            candidateArtist = "New Artist",
            candidateAlbum = "New Album",
            candidateIsrc = "US-NEW-24-00001",
            candidateModified = System.currentTimeMillis(),
            context = context,
            indexes = indexes
        )

        assertNull("Should not match any existing track", result.matchedTrack)
        assertFalse("Should not be relinked", result.isRelinked)
        assertEquals(TrackIdentityReconciler.MatchReason.NO_MATCH, result.matchReason)
    }

    @Test
    fun test5_isMetadataScanComplete_recognizesAllValidCompletionStates() {
        assertTrue(TrackIdentityReconciler.isMetadataScanComplete("COMPLETE"))
        assertTrue(TrackIdentityReconciler.isMetadataScanComplete("VERIFIED"))
        assertTrue(TrackIdentityReconciler.isMetadataScanComplete("REVIEW_REQUIRED"))
        assertTrue(TrackIdentityReconciler.isMetadataScanComplete("USER_CONFIRMED"))
        assertTrue(TrackIdentityReconciler.isMetadataScanComplete("RESTORED"))
        assertTrue(TrackIdentityReconciler.isMetadataScanComplete("APPROVED"))
        assertTrue(TrackIdentityReconciler.isMetadataScanComplete("APPLIED"))
        assertTrue(TrackIdentityReconciler.isMetadataScanComplete("IDENTIFIED"))

        assertFalse(TrackIdentityReconciler.isMetadataScanComplete("NOT_SCANNED"))
        assertFalse(TrackIdentityReconciler.isMetadataScanComplete("FAILED"))
        assertFalse(TrackIdentityReconciler.isMetadataScanComplete("QUEUED"))
        assertFalse(TrackIdentityReconciler.isMetadataScanComplete("SEARCHING"))
        assertFalse(TrackIdentityReconciler.isMetadataScanComplete(null))
        assertFalse(TrackIdentityReconciler.isMetadataScanComplete(""))
    }

    @Test
    fun test6_repeatedLibraryScans_produceZeroDuplicates() {
        val track1 = createSampleTrack(id = "media_1", filePath = "/storage/emulated/0/Music/t1.mp3", fingerprint = "fp_1")
        val track2 = createSampleTrack(id = "media_2", filePath = "/storage/emulated/0/Music/t2.mp3", fingerprint = "fp_2")
        val track3 = createSampleTrack(id = "media_3", filePath = "/storage/emulated/0/Music/t3.mp3", fingerprint = "fp_3")

        val indexes = TrackIdentityReconciler.buildIndexes(context, listOf(track1, track2, track3))

        val candidates = listOf(
            Triple(track1.filePath, track1.contentFingerprint, track1.title),
            Triple(track2.filePath, track2.contentFingerprint, track2.title),
            Triple(track3.filePath, track3.contentFingerprint, track3.title)
        )

        // Simulate 3 scan cycles
        for (cycle in 1..3) {
            for ((path, fp, title) in candidates) {
                val res = TrackIdentityReconciler.reconcileCandidate(
                    candidatePathOrUri = path,
                    candidateFingerprint = fp,
                    candidateSizeBytes = 10 * 1024 * 1024L,
                    candidateDurationSec = 300,
                    candidateTitle = title,
                    candidateArtist = "Artist",
                    candidateAlbum = "Album",
                    context = context,
                    indexes = indexes
                )
                assertNotNull("Track must be recognized in cycle $cycle", res.matchedTrack)
                assertFalse("Track must not be marked as new or relinked if unchanged", res.isRelinked)
            }
        }
    }

    @Test
    fun test7_mediaStoreIdRelocation_relinksAccurately() {
        val original = createSampleTrack(
            id = "media_45678",
            filePath = "/storage/emulated/0/Download/audio_45678.mp3",
            fingerprint = "" // Legacy track without fingerprint initially
        )
        val indexes = TrackIdentityReconciler.buildIndexes(context, listOf(original))

        val relocatedPath = "/storage/emulated/0/Music/SoundSync/audio_45678.mp3"

        val result = TrackIdentityReconciler.reconcileCandidate(
            candidatePathOrUri = relocatedPath,
            candidateFingerprint = "fp_newly_computed_45678",
            candidateSizeBytes = (original.fileSizeMb * 1024 * 1024).toLong(),
            candidateDurationSec = original.durationSeconds,
            candidateTitle = original.title,
            candidateArtist = original.artist,
            candidateAlbum = original.album,
            candidateMediaId = 45678L,
            context = context,
            indexes = indexes
        )

        assertTrue("Should reconcile relocated file by MediaStore ID", result.isRelinked)
        assertEquals(TrackIdentityReconciler.MatchReason.MEDIA_STORE_ID_MATCH, result.matchReason)
        assertEquals(relocatedPath, result.relinkedTrack?.filePath)
        assertEquals("media_45678", result.relinkedTrack?.id)
        assertEquals(MetadataScanState.COMPLETE.name, result.relinkedTrack?.metadataScanState)
    }
}
