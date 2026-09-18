package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.Track
import com.example.player.PersistentQueueManager
import com.example.player.QueueRepeatMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContinuousPlaybackRegressionTest {

    private lateinit var context: Context
    private lateinit var queueManager: PersistentQueueManager

    private fun makeTrack(id: String, title: String): Track {
        return Track(
            id = id,
            title = title,
            artist = "Artist $id",
            album = "Album $id",
            durationSeconds = 200,
            filePath = "/storage/emulated/0/Music/$id.mp3",
            bpm = 128.0,
            musicalKey = "8A"
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "persistent_playback_queue.json").delete()
        File(context.filesDir, "persistent_app_session.json").delete()
        queueManager = PersistentQueueManager(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "persistent_playback_queue.json").delete()
        File(context.filesDir, "persistent_app_session.json").delete()
    }

    @Test
    fun testQueueAdvancement_advancesSequentiallyThroughQueue() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Song A")
        val trackB = makeTrack("B", "Song B")
        val trackC = makeTrack("C", "Song C")

        queueManager.setQueue(listOf(trackA, trackB, trackC), startTrack = trackA, shuffle = false)
        queueManager.setRepeatMode(QueueRepeatMode.OFF)

        assertEquals("Current track should be A", "A", queueManager.currentTrack.value?.id)
        assertEquals("Upcoming queue should have [B, C]", listOf("B", "C"), queueManager.upcomingQueue.value.map { it.id })

        // Peek next
        val peek1 = queueManager.peekNextTrack()
        assertEquals("Peek next should be B", "B", peek1?.id)

        // Advance to B
        val next1 = queueManager.nextTrack()
        assertEquals("Should advance to B", "B", next1?.id)
        assertEquals("Current track is now B", "B", queueManager.currentTrack.value?.id)
        assertEquals("Upcoming queue should have [C]", listOf("C"), queueManager.upcomingQueue.value.map { it.id })
        assertEquals("History has [A]", listOf("A"), queueManager.playbackHistory.value.map { it.id })

        // Peek next
        val peek2 = queueManager.peekNextTrack()
        assertEquals("Peek next should be C", "C", peek2?.id)

        // Advance to C
        val next2 = queueManager.nextTrack()
        assertEquals("Should advance to C", "C", next2?.id)
        assertEquals("Current track is now C", "C", queueManager.currentTrack.value?.id)
        assertTrue("Upcoming queue should now be empty", queueManager.upcomingQueue.value.isEmpty())
        assertEquals("History has [B, A]", listOf("B", "A"), queueManager.playbackHistory.value.map { it.id })
    }

    @Test
    fun testRepeatOff_stopsAtQueueEnd_neverRepeatsSameTrack() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Song A")
        val trackB = makeTrack("B", "Song B")

        queueManager.setQueue(listOf(trackA, trackB), startTrack = trackA, shuffle = false)
        queueManager.setRepeatMode(QueueRepeatMode.OFF)

        // Advance to B (the last track)
        val next1 = queueManager.nextTrack()
        assertEquals("Should be B", "B", next1?.id)
        assertTrue("Queue is empty after moving to B", queueManager.upcomingQueue.value.isEmpty())

        // Peek when at end of queue in Repeat OFF
        val peekAtEnd = queueManager.peekNextTrack()
        assertNull("When repeat is OFF and queue exhausted, peekNextTrack must return NULL (never repeat current track!)", peekAtEnd)

        // Natural end of track B: advanceAfterNaturalEnd calls nextTrack()
        val next2 = queueManager.nextTrack()
        assertNull("When repeat is OFF and queue exhausted, nextTrack must return NULL (stop playback cleanly, never repeat!)", next2)
        assertNull("Current track should be null after playback ends", queueManager.currentTrack.value)

        // Second attempt at end should still be null
        val next3 = queueManager.nextTrack()
        assertNull("Subsequent calls to nextTrack must remain null", next3)
    }

    @Test
    fun testRepeatAll_wrapsAroundFromEndSeamlessly() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Song A")
        val trackB = makeTrack("B", "Song B")

        queueManager.setQueue(listOf(trackA, trackB), startTrack = trackA, shuffle = false)
        queueManager.setRepeatMode(QueueRepeatMode.ALL)

        // Move to B
        val next1 = queueManager.nextTrack()
        assertEquals("B", next1?.id)

        // In Repeat ALL, reaching the end loops back to A
        val next2 = queueManager.nextTrack()
        assertEquals("In Repeat ALL, should wrap around to A", "A", next2?.id)
        assertEquals("Current track is now A", "A", queueManager.currentTrack.value?.id)

        // Move to B again
        val next3 = queueManager.nextTrack()
        assertEquals("Next cycle advances to B", "B", next3?.id)
    }

    @Test
    fun testRepeatOne_repeatsCurrentTrackOnly() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Song A")
        val trackB = makeTrack("B", "Song B")

        queueManager.setQueue(listOf(trackA, trackB), startTrack = trackA, shuffle = false)
        queueManager.setRepeatMode(QueueRepeatMode.ONE)

        // In Repeat ONE, peek returns current track
        val peek = queueManager.peekNextTrack()
        assertEquals("A", peek?.id)

        // In Repeat ONE, next returns current track
        val next = queueManager.nextTrack()
        assertEquals("In Repeat ONE, nextTrack must return current track A", "A", next?.id)
        assertEquals("Current track remains A", "A", queueManager.currentTrack.value?.id)
        assertEquals("Upcoming queue must still preserve [B]", listOf("B"), queueManager.upcomingQueue.value.map { it.id })
    }

    @Test
    fun testContextSequenceFallback_advancesPastCurrent_neverLoopsSelf() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Song A")
        val trackB = makeTrack("B", "Song B")
        val trackC = makeTrack("C", "Song C")
        val albumTracks = listOf(trackA, trackB, trackC)

        // Provide album tracks via context provider
        queueManager.contextTrackProvider = { albumTracks }

        // Start playback on B with an empty upcoming queue
        queueManager.setQueue(listOf(trackB), startTrack = trackB, shuffle = false)
        queueManager.setRepeatMode(QueueRepeatMode.OFF)

        // When queue is empty, peek should look ahead in context to C, NOT return B!
        val peek = queueManager.peekNextTrack()
        assertEquals("Peek should look ahead in context to C", "C", peek?.id)

        // Next should advance to C
        val next1 = queueManager.nextTrack()
        assertEquals("Should advance to C from context", "C", next1?.id)

        // On C (last track of album) with Repeat OFF, peek must return null
        val peekAtAlbumEnd = queueManager.peekNextTrack()
        assertNull("At end of album with Repeat OFF, peek must be null", peekAtAlbumEnd)

        // Next at end of album must return null (stop)
        val nextAtAlbumEnd = queueManager.nextTrack()
        assertNull("At end of album with Repeat OFF, next must be null (never loop B or C!)", nextAtAlbumEnd)
    }
}
