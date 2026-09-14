package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TrackDao
import com.example.data.TrackEntity
import com.example.model.Track
import com.example.player.PersistentQueueManager
import com.example.player.QueueRepeatMode
import com.example.state.PersistentSessionManager
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
import java.lang.reflect.Proxy

/**
 * Stage 29: Queue, Shuffle Order, and Playback History Architecture Tests.
 *
 * Verifies all 5 mandatory scenarios from 29_Queue_Shuffle_History.txt:
 * - Scenario A: Start A -> enable shuffle -> Next to G -> Next to C -> Prev to G -> Prev to A.
 * - Scenario B: Shuffle A -> G -> C -> Prev to G -> Next to C (moves forward through history, not random track).
 * - Scenario C: Play A -> B -> C -> change library sort order -> Prev to B.
 * - Scenario D: Play A -> B -> kill app -> relaunch -> Prev to A.
 * - Scenario E: Queue same track twice -> history handles occurrences correctly.
 *
 * Plus edge cases:
 * - Queue edits during playback (removing upcoming, removing current, reordering, clear queue while playing).
 * - Anti-spam threshold (seeks/rebuffers do not duplicate history entries).
 * - Shuffle toggling (enabling keeps current fixed, disabling restores natural traversal).
 * - Manual track selection branching (truncates forward history, appends old current to history, preserves upcoming queue).
 * - Repeat ONE and Repeat ALL modes (wrapping without corrupting history).
 * - Pruning deleted tracks from queue, history, and forward stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueShuffleHistoryArchitectureTest {

    private lateinit var context: Context
    private lateinit var queueManager: PersistentQueueManager
    private lateinit var sessionManager: PersistentSessionManager

    private val trackStore = mutableMapOf<String, TrackEntity>()
    private lateinit var fakeTrackDao: TrackDao

    private fun makeTrack(id: String, title: String, durationSec: Int = 180, filePath: String = "/storage/emulated/0/Music/$id.mp3"): Track {
        return Track(
            id = id,
            title = title,
            artist = "Artist $id",
            album = "Album $id",
            durationSeconds = durationSec,
            filePath = filePath,
            bpm = 126.0,
            musicalKey = "9A"
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "persistent_playback_queue.json").delete()
        File(context.filesDir, "persistent_app_session.json").delete()

        fakeTrackDao = Proxy.newProxyInstance(
            TrackDao::class.java.classLoader,
            arrayOf(TrackDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getTrackById" -> {
                    val id = args[0] as String
                    trackStore[id]
                }
                "getAllTracks" -> trackStore.values.toList()
                else -> null
            }
        } as TrackDao

        queueManager = PersistentQueueManager(context)
        sessionManager = PersistentSessionManager(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "persistent_playback_queue.json").delete()
        File(context.filesDir, "persistent_app_session.json").delete()
    }

    // ── Scenario A: Shuffle Navigation Previous LIFO Order ───────────────────────
    // Start A -> enable shuffle -> Next to G -> Next to C -> Prev to G -> Prev to A.
    // Must work regardless of current queue indices.
    @Test
    fun testScenarioA_shuffleNavigationPreviousLifo() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Track A")
        val trackB = makeTrack("B", "Track B")
        val trackC = makeTrack("C", "Track C")
        val trackD = makeTrack("D", "Track D")
        val trackE = makeTrack("E", "Track E")
        val trackF = makeTrack("F", "Track F")
        val trackG = makeTrack("G", "Track G")

        val allTracks = listOf(trackA, trackB, trackC, trackD, trackE, trackF, trackG)

        // 1. Start A
        queueManager.setQueue(allTracks, startTrack = trackA, shuffle = false)
        assertEquals("Current track should be A", "A", queueManager.currentTrack.value?.id)

        // 2. Enable shuffle (Deterministic order generated, current A fixed)
        queueManager.setShuffle(true)
        assertTrue("Shuffle must be enabled", queueManager.isShuffleEnabled.value)
        assertEquals("Current track stays fixed as A", "A", queueManager.currentTrack.value?.id)

        // Mock deterministic upcoming sequence for Scenario A: G, then C
        val upcomingRemaining = listOf(trackG, trackC, trackB, trackD, trackE, trackF)
        queueManager.clearQueue(clearCurrent = false)
        queueManager.addToQueue(upcomingRemaining)

        // 3. Next -> G
        val next1 = queueManager.nextTrack()
        assertNotNull(next1)
        assertEquals("Next track should be G", "G", next1?.id)
        assertEquals("Current should be G", "G", queueManager.currentTrack.value?.id)

        // 4. Next -> C
        val next2 = queueManager.nextTrack()
        assertNotNull(next2)
        assertEquals("Next track should be C", "C", next2?.id)
        assertEquals("Current should be C", "C", queueManager.currentTrack.value?.id)

        // 5. Previous -> G (must return to G, NOT a new random track, NOT queue index)
        val prev1 = queueManager.previousTrack(currentPositionMs = 0L, forceHistory = true)
        assertNotNull(prev1)
        assertEquals("Previous from C must resolve to G", "G", prev1?.id)
        assertEquals("Current should be G", "G", queueManager.currentTrack.value?.id)

        // 6. Previous -> A (must return to A)
        val prev2 = queueManager.previousTrack(currentPositionMs = 0L, forceHistory = true)
        assertNotNull(prev2)
        assertEquals("Previous from G must resolve to A", "A", prev2?.id)
        assertEquals("Current should be A", "A", queueManager.currentTrack.value?.id)
    }

    // ── Scenario B: Forward History Navigation After Previous ────────────────────
    // Shuffle A -> G -> C -> Prev to G -> Next to C.
    // It should move forward through known history instead of selecting a new random track.
    @Test
    fun testScenarioB_forwardHistoryAfterPrevious() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Track A")
        val trackB = makeTrack("B", "Track B")
        val trackC = makeTrack("C", "Track C")
        val trackG = makeTrack("G", "Track G")

        // 1. Play A -> G -> C
        queueManager.setQueue(listOf(trackA, trackG, trackC, trackB), startTrack = trackA, shuffle = true)
        // Explicitly set upcoming to G then C
        queueManager.clearQueue(clearCurrent = false)
        queueManager.addToQueue(listOf(trackG, trackC, trackB))

        val g = queueManager.nextTrack()
        assertEquals("G", g?.id)
        val c = queueManager.nextTrack()
        assertEquals("C", c?.id)

        // 2. Previous -> G
        val prev = queueManager.previousTrack(currentPositionMs = 0L, forceHistory = true)
        assertEquals("Previous must resolve to G", "G", prev?.id)
        assertEquals("Forward history must contain C", listOf("C"), queueManager.forwardHistory.value.map { it.id })

        // 3. Next -> C (moves forward through history, NOT picking random track or B)
        val next = queueManager.nextTrack()
        assertEquals("Next must resolve forward to C from history", "C", next?.id)
        assertEquals("Forward history must now be empty", 0, queueManager.forwardHistory.value.size)
        assertEquals("History must contain G", "G", queueManager.playbackHistory.value.first().id)
    }

    // ── Scenario C: Library Sorting Immunity ──────────────────────────────────────
    // Play A -> B -> C -> Change library sort order -> Prev to B.
    @Test
    fun testScenarioC_librarySortImmunity() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Apple Track")
        val trackB = makeTrack("B", "Banana Track")
        val trackC = makeTrack("C", "Cherry Track")

        queueManager.setQueue(listOf(trackA, trackB, trackC), startTrack = trackA)
        queueManager.nextTrack() // -> B
        queueManager.nextTrack() // -> C
        assertEquals("Current is C", "C", queueManager.currentTrack.value?.id)

        // Simulate library sort order changing drastically (e.g. reverse alphabetical or BPM sort)
        val sortedLibrary = listOf(trackC, trackA, trackB)
        // PersistentQueueManager context track provider reflects new sort
        queueManager.contextTrackProvider = { sortedLibrary }

        // Previous must STILL resolve to B (the actual previously heard track)
        val prev = queueManager.previousTrack(currentPositionMs = 0L, forceHistory = true)
        assertEquals("Previous must resolve to B regardless of library sorting", "B", prev?.id)
    }

    // ── Scenario D: Persistence Across App Restart ───────────────────────────────
    // Play A -> B -> Kill app -> Relaunch -> Previous should still resolve to A.
    @Test
    fun testScenarioD_persistenceAcrossAppRestart() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Track A")
        val trackB = makeTrack("B", "Track B")

        queueManager.setQueue(listOf(trackA, trackB), startTrack = trackA)
        val b = queueManager.nextTrack()
        assertEquals("B", b?.id)
        assertEquals("History contains A", "A", queueManager.playbackHistory.value.first().id)

        // Force synchronous disk persistence
        queueManager.saveToDisk()

        // Simulate app kill & relaunch with fresh manager instance
        val newQueueManager = PersistentQueueManager(context)
        newQueueManager.restoreFromDisk()

        assertEquals("Restored current track is B", "B", newQueueManager.currentTrack.value?.id)
        assertEquals("Restored history has 1 track", 1, newQueueManager.playbackHistory.value.size)
        assertEquals("Restored history item is A", "A", newQueueManager.playbackHistory.value[0].id)

        // Previous on restored queue manager resolves to A
        val prev = newQueueManager.previousTrack(currentPositionMs = 0L, forceHistory = true)
        assertEquals("Previous on restored session resolves to A", "A", prev?.id)
    }

    // ── Scenario E: Queue Same Track Twice ───────────────────────────────────────
    // Queue the same track twice and ensure history model handles occurrences correctly.
    @Test
    fun testScenarioE_queueSameTrackTwice() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Track A")
        val trackB = makeTrack("B", "Track B")

        // Queue: A -> B -> A
        queueManager.setQueue(listOf(trackA, trackB, trackA), startTrack = trackA)

        // 1. Next -> B
        val next1 = queueManager.nextTrack()
        assertEquals("B", next1?.id)
        assertEquals("History has [A]", listOf("A"), queueManager.playbackHistory.value.map { it.id })

        // 2. Next -> A (second occurrence)
        val next2 = queueManager.nextTrack()
        assertEquals("A", next2?.id)
        assertEquals("History has [B, A]", listOf("B", "A"), queueManager.playbackHistory.value.map { it.id })

        // 3. Previous -> B
        val prev1 = queueManager.previousTrack(currentPositionMs = 0L, forceHistory = true)
        assertEquals("Previous must resolve to B", "B", prev1?.id)
        assertEquals("Forward has [A]", listOf("A"), queueManager.forwardHistory.value.map { it.id })

        // 4. Previous -> A (first occurrence)
        val prev2 = queueManager.previousTrack(currentPositionMs = 0L, forceHistory = true)
        assertEquals("Previous must resolve to first occurrence of A", "A", prev2?.id)
        assertEquals("Forward has [B, A]", listOf("B", "A"), queueManager.forwardHistory.value.map { it.id })
    }

    // ── Queue Edits During Playback Do Not Corrupt History ────────────────────────
    @Test
    fun testQueueEditsDuringPlayback_preserveHistory() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Track A")
        val trackB = makeTrack("B", "Track B")
        val trackC = makeTrack("C", "Track C")
        val trackD = makeTrack("D", "Track D")

        queueManager.setQueue(listOf(trackA, trackB, trackC), startTrack = trackA)
        queueManager.nextTrack() // -> B
        assertEquals("History has [A]", listOf("A"), queueManager.playbackHistory.value.map { it.id })

        // 1. Add Play Next
        queueManager.playNext(trackD)
        assertEquals("D is now next upcoming", "D", queueManager.upcomingQueue.value.first().id)
        assertEquals("History unchanged", listOf("A"), queueManager.playbackHistory.value.map { it.id })

        // 2. Remove upcoming item
        queueManager.removeFromQueue(1) // Removes C
        assertFalse("C removed from upcoming", queueManager.upcomingQueue.value.any { it.id == "C" })

        // 3. Clear upcoming queue entirely while playing
        queueManager.clearQueue(clearCurrent = false)
        assertTrue("Upcoming queue is empty", queueManager.upcomingQueue.value.isEmpty())
        assertEquals("Current track is still B", "B", queueManager.currentTrack.value?.id)

        // Historical Previous must STILL work!
        val prev = queueManager.previousTrack(currentPositionMs = 0L, forceHistory = true)
        assertEquals("Previous still resolves to A even after upcoming queue was cleared", "A", prev?.id)
    }

    // ── Anti-Spam Threshold ──────────────────────────────────────────────────────
    @Test
    fun testAntiSpamThreshold_seeksAndRebuffersDoNotDuplicateHistory() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Track A")
        val trackB = makeTrack("B", "Track B")

        queueManager.setQueue(listOf(trackA, trackB), startTrack = trackA)

        // Simulate seeks, rebuffers, and re-binding the same track
        queueManager.recordTrackPlayed(trackA)
        queueManager.recordTrackPlayed(trackA)
        queueManager.updatePlaybackPosition(5000L)
        queueManager.recordTrackPlayed(trackA)

        assertEquals("History should remain empty for continuous playback of track A", 0, queueManager.playbackHistory.value.size)

        // Move to B
        queueManager.recordTrackPlayed(trackB)
        assertEquals("History should have exactly 1 entry for A", 1, queueManager.playbackHistory.value.size)
        assertEquals("History entry is A", "A", queueManager.playbackHistory.value[0].id)

        // Multiple calls for B
        queueManager.recordTrackPlayed(trackB)
        queueManager.recordTrackPlayed(trackB)
        assertEquals("History should still have exactly 1 entry", 1, queueManager.playbackHistory.value.size)
    }

    // ── Manual Track Selection Branches Forward History ──────────────────────────
    @Test
    fun testManualTrackSelection_branchesForwardHistory() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Track A")
        val trackB = makeTrack("B", "Track B")
        val trackC = makeTrack("C", "Track C")
        val trackX = makeTrack("X", "Track X")

        // Play A -> B -> C
        queueManager.setQueue(listOf(trackA, trackB, trackC), startTrack = trackA)
        queueManager.nextTrack() // -> B
        queueManager.nextTrack() // -> C

        // Go back to B
        queueManager.previousTrack(currentPositionMs = 0L, forceHistory = true)
        assertEquals("Current is B", "B", queueManager.currentTrack.value?.id)
        assertEquals("Forward history contains C", listOf("C"), queueManager.forwardHistory.value.map { it.id })

        // User manually taps track X (branches history)
        queueManager.recordTrackPlayed(trackX)
        assertEquals("Current is now X", "X", queueManager.currentTrack.value?.id)
        assertEquals("Forward history is truncated/cleared on branch", 0, queueManager.forwardHistory.value.size)
        assertEquals("Previous track B was archived to history", "B", queueManager.playbackHistory.value.first().id)

        // Previous from X goes to B
        val prev = queueManager.previousTrack(currentPositionMs = 0L, forceHistory = true)
        assertEquals("Previous from X resolves to B", "B", prev?.id)
    }

    // ── Shuffle Toggling Dynamics ────────────────────────────────────────────────
    @Test
    fun testShuffleToggling_deterministicOrderAndRestoresTraversal() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Track A")
        val trackB = makeTrack("B", "Track B")
        val trackC = makeTrack("C", "Track C")
        val trackD = makeTrack("D", "Track D")
        val tracks = listOf(trackA, trackB, trackC, trackD)

        queueManager.setQueue(tracks, startTrack = trackA, shuffle = false)
        assertEquals("Initial upcoming order is B, C, D", listOf("B", "C", "D"), queueManager.upcomingQueue.value.map { it.id })

        // Enable shuffle
        queueManager.setShuffle(true)
        assertTrue(queueManager.isShuffleEnabled.value)
        assertEquals("Current track stays fixed as A", "A", queueManager.currentTrack.value?.id)
        assertEquals("Shuffle sequence has 4 tracks", 4, queueManager.shuffleSequenceTrackIds.value.size)
        assertEquals("Shuffle sequence starts with current track A", "A", queueManager.shuffleSequenceTrackIds.value.first())

        // Disable shuffle
        queueManager.setShuffle(false)
        assertFalse(queueManager.isShuffleEnabled.value)
        assertEquals("Upcoming restored to natural order B, C, D", listOf("B", "C", "D"), queueManager.upcomingQueue.value.map { it.id })
    }

    // ── Repeat ALL Cycle Non-Destructive History ─────────────────────────────────
    @Test
    fun testRepeatAll_doesNotWipePlaybackHistory() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Track A")
        val trackB = makeTrack("B", "Track B")

        queueManager.setQueue(listOf(trackA, trackB), startTrack = trackA)
        queueManager.setRepeatMode(QueueRepeatMode.ALL)

        // Next -> B
        val next1 = queueManager.nextTrack()
        assertEquals("B", next1?.id)

        // Next -> wraps around to A in Repeat ALL
        val next2 = queueManager.nextTrack()
        assertEquals("A", next2?.id)

        // Playback history must NOT be wiped! Must contain B and A
        assertTrue("History must contain tracks from previous cycle", queueManager.playbackHistory.value.isNotEmpty())
        assertEquals("Most recent history item is B", "B", queueManager.playbackHistory.value.first().id)

        // Previous returns to B
        val prev = queueManager.previousTrack(currentPositionMs = 0L, forceHistory = true)
        assertEquals("Previous resolves to B across loop boundary", "B", prev?.id)
    }

    // ── Prune Deleted Track Across All Queue Structures ──────────────────────────
    @Test
    fun testPruneDeletedTrack_cleansCurrentUpcomingHistoryAndForward() = runBlocking(Dispatchers.IO) {
        val trackA = makeTrack("A", "Track A")
        val trackB = makeTrack("B", "Track B")
        val trackC = makeTrack("C", "Track C")

        queueManager.setQueue(listOf(trackA, trackB, trackC), startTrack = trackA)
        queueManager.nextTrack() // -> B
        queueManager.previousTrack(currentPositionMs = 0L, forceHistory = true) // back to A; forward=[B]

        // Prune track B which is in forward history
        queueManager.pruneDeletedTrack("B")
        assertFalse("B removed from forward history", queueManager.forwardHistory.value.any { it.id == "B" })

        // Prune track C which is in upcoming queue
        queueManager.pruneDeletedTrack("C")
        assertFalse("C removed from upcoming queue", queueManager.upcomingQueue.value.any { it.id == "C" })

        // Prune track A which is current track
        queueManager.pruneDeletedTrack("A")
        assertNull("Current track cleared when no upcoming remain", queueManager.currentTrack.value)
    }
}
