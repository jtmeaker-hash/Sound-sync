package com.example

import com.example.audio.PlaybackGenerationGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Regression tests for the PlaybackGenerationGate used by DjAudioEngine to
 * guarantee that rapid track switching A -> B -> C leaves only C's playback
 * session alive.
 */
class PlaybackGenerationGateTest {

    @Test
    fun `initial generation is zero and considered current`() {
        val gate = PlaybackGenerationGate()
        assertEquals(0L, gate.latest)
        assertTrue(gate.isCurrent(0L))
    }

    @Test
    fun `next() mints a monotonically increasing generation and invalidates previous`() {
        val gate = PlaybackGenerationGate()
        val g1 = gate.next()
        val g2 = gate.next()
        val g3 = gate.next()

        assertEquals(1L, g1)
        assertEquals(2L, g2)
        assertEquals(3L, g3)

        // Older generations must never be treated as current after a newer load.
        assertFalse(gate.isCurrent(g1))
        assertFalse(gate.isCurrent(g2))
        assertTrue(gate.isCurrent(g3))
        assertEquals(3L, gate.latest)
    }

    @Test
    fun `rapid minting simulating A-B-C switching leaves only newest generation valid`() {
        val gate = PlaybackGenerationGate()

        val a = gate.next() // user selects track A
        val b = gate.next() // user selects track B while A loads
        val c = gate.next() // user selects track C while B loads

        assertFalse("A's loop must abort", gate.isCurrent(a))
        assertFalse("B's loop must abort", gate.isCurrent(b))
        assertTrue("Only C's loop may write audio", gate.isCurrent(c))
    }

    @Test
    fun `gate is thread-safe under concurrent minting`() {
        val gate = PlaybackGenerationGate()
        val threads = 8
        val mintsPerThread = 100
        val executor = Executors.newFixedThreadPool(threads)
        val allDone = CountDownLatch(threads)

        repeat(threads) {
            executor.execute {
                repeat(mintsPerThread) { gate.next() }
                allDone.countDown()
            }
        }
        allDone.await()

        executor.shutdown()
        // No lost updates: exactly threads * mintsPerThread generations minted.
        assertEquals(threads.toLong() * mintsPerThread.toLong(), gate.latest)
    }
}
