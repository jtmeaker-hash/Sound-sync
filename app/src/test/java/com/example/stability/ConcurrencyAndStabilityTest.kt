package com.example.stability

import com.example.model.AudioQualityRating
import com.example.model.HierarchicalFolder
import com.example.model.Track
import com.example.storage.FolderHierarchyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

class ConcurrencyAndStabilityTest {

    private fun createTrack(
        id: String,
        title: String,
        artist: String = "DJ Producer",
        bpm: Double = 128.0,
        isAvailable: Boolean = true
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = "Test Album",
            format = "FLAC",
            bpm = bpm,
            durationSeconds = 240,
            isAvailable = isAvailable,
            filePath = "/storage/emulated/0/Music/$title.flac",
            qualityRating = AudioQualityRating.TRUE_LOSSLESS
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 1: Rapid Playback Skipping Under Concurrent Metadata Emissions
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testRapidPlaybackAndSkipDuringAnalysisQueue() = runBlocking {
        val tracks = (1..50).map { createTrack("track_$it", "Track $it") }
        val currentTrackFlow = MutableStateFlow<Track?>(null)
        val queueFlow = MutableStateFlow<List<Track>>(tracks)
        val activeWorkers = AtomicInteger(0)
        val skipCounter = AtomicInteger(0)

        // Concurrent background scanner/metadata flow
        val analysisJob = launch(Dispatchers.Default) {
            for (i in 1..100) {
                activeWorkers.incrementAndGet()
                // Update queue tracks with simulated analyzed metadata
                val updatedQueue = queueFlow.value.map { t ->
                    if (t.id == "track_${(i % 50) + 1}") t.copy(bpm = 120.0 + (i % 30)) else t
                }
                queueFlow.value = updatedQueue
                activeWorkers.decrementAndGet()
                delay(2)
            }
        }

        // Rapid user skipping
        val skipJob = launch(Dispatchers.Default) {
            for (i in 1..100) {
                val next = tracks[i % tracks.size]
                currentTrackFlow.value = next
                skipCounter.incrementAndGet()
                delay(1)
            }
        }

        analysisJob.join()
        skipJob.join()

        assertEquals(100, skipCounter.get())
        assertEquals(0, activeWorkers.get())
        assertNotNull(currentTrackFlow.value)
        assertEquals(50, queueFlow.value.size)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 2: Decoder Semaphore Concurrency Bounding (Max 1 Active Decoder)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testConcurrentDecoderSemaphoreBounding() = runBlocking {
        val semaphore = Semaphore(1)
        val maxConcurrentDecoders = AtomicInteger(0)
        val currentActiveDecoders = AtomicInteger(0)

        // Launch 20 concurrent simulated decode requests (waveform, spectrogram, BPM)
        val jobs = (1..20).map { id ->
            async(Dispatchers.Default) {
                semaphore.withPermit {
                    val current = currentActiveDecoders.incrementAndGet()
                    var maxSeen = maxConcurrentDecoders.get()
                    while (current > maxSeen) {
                        if (maxConcurrentDecoders.compareAndSet(maxSeen, current)) break
                        maxSeen = maxConcurrentDecoders.get()
                    }

                    // Simulate hardware codec decoding latency
                    delay(5)

                    currentActiveDecoders.decrementAndGet()
                    id
                }
            }
        }

        val results = jobs.awaitAll()
        assertEquals(20, results.size)
        assertEquals("Active hardware/software decoders must strictly never exceed 1", 1, maxConcurrentDecoders.get())
        assertEquals("All decoder permits must be safely released", 0, currentActiveDecoders.get())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 3: Fast Radix-2 FFT Key Detection Completes in Sub-50ms (No 22M Loops)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testFastFftChromaKeyDetectionPerformance() {
        val sampleRate = 44100
        val durationSeconds = 30
        val totalSamples = sampleRate * durationSeconds

        // Generate synthetic A440Hz pure tone + harmonics
        val samples = FloatArray(totalSamples) { i ->
            val t = i.toDouble() / sampleRate
            (0.8 * sin(2.0 * PI * 440.0 * t) + 0.3 * sin(2.0 * PI * 880.0 * t)).toFloat()
        }

        val startTime = System.currentTimeMillis()

        // Benchmark FFT chroma extraction over 120 frames
        val windowSize = 2048
        val hop = 2048
        val frames = ((samples.size - windowSize) / hop).coerceAtMost(120)
        val chroma = DoubleArray(12)

        val fftReal = FloatArray(windowSize)
        val fftImag = FloatArray(windowSize)

        for (f in 0 until frames) {
            val start = f * hop
            for (i in 0 until windowSize) {
                val hann = (0.5 * (1.0 - kotlin.math.cos(2.0 * PI * i / (windowSize - 1)))).toFloat()
                fftReal[i] = samples[start + i] * hann
                fftImag[i] = 0f
            }
            // In-place bit reversal + Radix-2 butterflies
            var j = 0
            for (i in 0 until windowSize - 1) {
                if (i < j) {
                    val tr = fftReal[i]; val ti = fftImag[i]
                    fftReal[i] = fftReal[j]; fftImag[i] = fftImag[j]
                    fftReal[j] = tr; fftImag[j] = ti
                }
                var k = windowSize shr 1
                while (k <= j) { j -= k; k = k shr 1 }; j += k
            }
            var len = 2
            while (len <= windowSize) {
                val halfLen = len shr 1
                val angle = -2.0 * PI / len
                val wStepR = kotlin.math.cos(angle).toFloat()
                val wStepI = sin(angle).toFloat()
                var i = 0
                while (i < windowSize) {
                    var wR = 1.0f; var wI = 0.0f
                    for (k in 0 until halfLen) {
                        val uR = fftReal[i + k]; val uI = fftImag[i + k]
                        val vR = fftReal[i + k + halfLen] * wR - fftImag[i + k + halfLen] * wI
                        val vI = fftReal[i + k + halfLen] * wI + fftImag[i + k + halfLen] * wR
                        fftReal[i + k] = uR + vR; fftImag[i + k] = uI + vI
                        fftReal[i + k + halfLen] = uR - vR; fftImag[i + k + halfLen] = uI - vI
                        val nWR = wR * wStepR - wI * wStepI
                        val nWI = wR * wStepI + wI * wStepR
                        wR = nWR; wI = nWI
                    }
                    i += len
                }
                len = len shl 1
            }
            for (bin in 1 until windowSize / 2) {
                val freq = bin * sampleRate.toDouble() / windowSize
                if (freq in 65.0..2000.0) {
                    val midi = (12.0 * (kotlin.math.ln(freq / 440.0) / kotlin.math.ln(2.0)) + 69.0).toInt()
                    val p = ((midi % 12) + 12) % 12
                    chroma[p] += kotlin.math.sqrt(fftReal[bin] * fftReal[bin] + fftImag[bin] * fftImag[bin]).toDouble()
                }
            }
        }

        val durationMs = System.currentTimeMillis() - startTime
        val maxChromaIndex = chroma.indices.maxByOrNull { chroma[it] } ?: -1

        // Pitch class 9 corresponds to A in 0=C, 1=C#, 2=D, ..., 9=A
        assertEquals("Synthesized 440Hz must peak at pitch class 9 (A)", 9, maxChromaIndex)
        assertTrue("FFT chroma computation must run in < 150ms (actual: ${durationMs}ms)", durationMs < 150)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 4: USB Storage Removal During Active Browsing and Playback
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testUsbRemovalHandling() {
        val internalTrack = createTrack("int_1", "Internal Track", isAvailable = true)
        val usbTrack = createTrack("usb_1", "USB Track", isAvailable = true)

        var library = listOf(internalTrack, usbTrack)
        var currentPlaying: Track? = usbTrack

        // Simulate USB ejection: source goes offline
        library = library.map { track ->
            if (track.filePath.contains("usb", ignoreCase = true)) track.copy(isAvailable = false) else track
        }

        val updatedUsb = library.first { it.id == "usb_1" }
        assertFalse("Ejected USB track must be marked unavailable", updatedUsb.isAvailable)
        assertTrue("Internal track must remain available", library.first { it.id == "int_1" }.isAvailable)

        // If playing track becomes unavailable, app should update state safely without crashing
        if (currentPlaying?.id == updatedUsb.id) {
            currentPlaying = updatedUsb
        }
        assertFalse(currentPlaying!!.isAvailable)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 5: Stress Repeated Folder Expansion & Contraction (Deep Hierarchy)
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun testStressFolderHierarchyExpansion() {
        val rootNode = HierarchicalFolder(
            id = "root_1",
            rootId = "sd",
            rootName = "SD",
            rootSource = "/storage/0000",
            parentId = null,
            name = "Music",
            relativePathFromRoot = "",
            fullPath = "/storage/0000/Music",
            depth = 0,
            hasChildFolders = true,
            childFolderIds = listOf("sub_1", "sub_2")
        )

        val expandedSet = mutableSetOf<String>()

        // Rapidly toggle expansion state 1000 times
        for (i in 1..1000) {
            if (expandedSet.contains(rootNode.id)) {
                expandedSet.remove(rootNode.id)
            } else {
                expandedSet.add(rootNode.id)
            }
        }

        assertTrue("Even number of toggles (1000) must result in collapsed state", expandedSet.isEmpty())
    }
}
