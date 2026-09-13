package com.example.brain

import com.example.data.TrackBrainDao
import com.example.data.TrackBrainStatusEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class LibraryBrainTest {

    private val statusMap = ConcurrentHashMap<String, TrackBrainStatusEntity>()
    private val knownTracks = ConcurrentHashMap.newKeySet<String>()
    private lateinit var brainDao: TrackBrainDao

    @Before
    fun setup() {
        statusMap.clear()
        knownTracks.clear()

        brainDao = Proxy.newProxyInstance(
            TrackBrainDao::class.java.classLoader,
            arrayOf(TrackBrainDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "upsert" -> {
                    val status = args[0] as TrackBrainStatusEntity
                    statusMap[status.trackId] = status
                    null
                }
                "upsertAll" -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = args[0] as List<TrackBrainStatusEntity>
                    list.forEach { statusMap[it.trackId] = it }
                    null
                }
                "insertIgnoreAll" -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = args[0] as List<TrackBrainStatusEntity>
                    list.forEach { statusMap.putIfAbsent(it.trackId, it) }
                    null
                }
                "getStatusForTrack" -> {
                    statusMap[args[0] as String]
                }
                "observeStatusForTrack" -> {
                    flowOf(statusMap[args[0] as String])
                }
                "getStatusesForTracks" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = args[0] as List<String>
                    ids.mapNotNull { statusMap[it] }
                }
                "getAllStatuses" -> {
                    statusMap.values.toList()
                }
                "getTotalCount" -> {
                    statusMap.size
                }
                "getCountByStatus" -> {
                    val st = args[0] as String
                    statusMap.values.count { it.overallStatus == st }
                }
                "getTracksNeedingAnalysis" -> {
                    val limit = (args[0] as? Int) ?: 50
                    statusMap.values.filter {
                        it.overallStatus in listOf("PENDING", "QUEUED", "PARTIALLY_COMPLETE")
                    }.take(limit)
                }
                "getFailedTracks" -> {
                    val limit = (args[0] as? Int) ?: 100
                    statusMap.values.filter { it.overallStatus == "FAILED" }.take(limit)
                }
                "getTracksByStatus" -> {
                    val st = args[0] as String
                    val limit = (args[1] as? Int) ?: 100
                    statusMap.values.filter { it.overallStatus == st }.take(limit)
                }
                "resetFailedTracks" -> {
                    var count = 0
                    statusMap.forEach { (k, v) ->
                        if (v.overallStatus == "FAILED") {
                            statusMap[k] = v.copy(overallStatus = "PENDING", retryCount = 0, errorCode = null, errorMessage = null)
                            count++
                        }
                    }
                    count
                }
                "markAllPending" -> {
                    var count = 0
                    statusMap.forEach { (k, v) ->
                        statusMap[k] = v.copy(overallStatus = "PENDING", retryCount = 0)
                        count++
                    }
                    count
                }
                "markBpmKeyForReanalysis" -> {
                    var count = 0
                    statusMap.forEach { (k, v) ->
                        if (v.overallStatus != "ANALYSING") {
                            statusMap[k] = v.copy(overallStatus = "PENDING", bpmStatus = "NOT_STARTED", keyStatus = "NOT_STARTED")
                            count++
                        }
                    }
                    count
                }
                "markWaveformForReanalysis" -> {
                    var count = 0
                    statusMap.forEach { (k, v) ->
                        if (v.overallStatus != "ANALYSING") {
                            statusMap[k] = v.copy(overallStatus = "PENDING", waveformStatus = "NOT_STARTED")
                            count++
                        }
                    }
                    count
                }
                "markArtworkForReanalysis" -> {
                    var count = 0
                    statusMap.forEach { (k, v) ->
                        if (v.overallStatus != "ANALYSING") {
                            statusMap[k] = v.copy(overallStatus = "PENDING", artworkStatus = "NOT_STARTED")
                            count++
                        }
                    }
                    count
                }
                "markMetadataForReanalysis" -> {
                    var count = 0
                    statusMap.forEach { (k, v) ->
                        if (v.overallStatus != "ANALYSING") {
                            statusMap[k] = v.copy(overallStatus = "PENDING", metadataStatus = "NOT_STARTED")
                            count++
                        }
                    }
                    count
                }
                "markQualityForReanalysis" -> {
                    var count = 0
                    statusMap.forEach { (k, v) ->
                        if (v.overallStatus != "ANALYSING") {
                            statusMap[k] = v.copy(overallStatus = "PENDING", qualityStatus = "NOT_STARTED")
                            count++
                        }
                    }
                    count
                }
                "markReplayGainForReanalysis" -> {
                    var count = 0
                    statusMap.forEach { (k, v) ->
                        if (v.overallStatus != "ANALYSING") {
                            statusMap[k] = v.copy(overallStatus = "PENDING", replayGainStatus = "NOT_STARTED")
                            count++
                        }
                    }
                    count
                }
                "markLyricsForReanalysis" -> {
                    var count = 0
                    statusMap.forEach { (k, v) ->
                        if (v.overallStatus != "ANALYSING") {
                            statusMap[k] = v.copy(overallStatus = "PENDING", lyricsStatus = "NOT_STARTED")
                            count++
                        }
                    }
                    count
                }
                "deleteForTrack" -> {
                    statusMap.remove(args[0] as String)
                    null
                }
                "deleteOrphans" -> {
                    val toRemove = statusMap.keys.filter { it !in knownTracks }
                    toRemove.forEach { statusMap.remove(it) }
                    toRemove.size
                }
                "getAllKnownTrackIds" -> {
                    statusMap.keys.toList()
                }
                else -> null
            }
        } as TrackBrainDao
    }

    @Test
    fun testBrainProcessingStateEnums() {
        assertTrue(BrainProcessingState.QUEUED.isActive)
        assertTrue(BrainProcessingState.ANALYSING.isActive)
        assertFalse(BrainProcessingState.PENDING.isActive)
        assertFalse(BrainProcessingState.COMPLETE.isActive)

        assertTrue(BrainProcessingState.COMPLETE.isTerminal)
        assertTrue(BrainProcessingState.FAILED.isTerminal)
        assertTrue(BrainProcessingState.MISSING_FILE.isTerminal)
        assertTrue(BrainProcessingState.IGNORED.isTerminal)
        assertFalse(BrainProcessingState.ANALYSING.isTerminal)
    }

    @Test
    fun testBrainSubStatusFinished() {
        assertTrue(BrainSubStatus.COMPLETE.isFinished)
        assertTrue(BrainSubStatus.SKIPPED.isFinished)
        assertFalse(BrainSubStatus.RUNNING.isFinished)
        assertFalse(BrainSubStatus.NOT_STARTED.isFinished)
        assertFalse(BrainSubStatus.FAILED.isFinished)
    }

    @Test
    fun testBrainCategoriesCoverAllDomains() {
        val categories = BrainCategory.entries
        assertEquals(9, categories.size)
        assertTrue(categories.contains(BrainCategory.BPM_KEY))
        assertTrue(categories.contains(BrainCategory.WAVEFORM))
        assertTrue(categories.contains(BrainCategory.ARTWORK))
        assertTrue(categories.contains(BrainCategory.METADATA))
        assertTrue(categories.contains(BrainCategory.QUALITY))
        assertTrue(categories.contains(BrainCategory.REPLAY_GAIN))
        assertTrue(categories.contains(BrainCategory.LYRICS))
        assertTrue(categories.contains(BrainCategory.DUPLICATES))
        assertTrue(categories.contains(BrainCategory.FILE_VALIDATION))
    }

    @Test
    fun testBrainSummaryCalculations() {
        val summary = BrainSummary(
            totalTracks = 100,
            completeCount = 80,
            analysingCount = 1,
            pendingCount = 15,
            partiallyCompleteCount = 2,
            needsReviewCount = 1,
            failedCount = 1,
            missingFilesCount = 0,
            queueLength = 17,
            isRunning = true,
            isPaused = false
        )

        assertEquals(100, summary.totalTracks)
        assertEquals(80, summary.completeCount)
        assertEquals(17, summary.queueLength)
        assertTrue(summary.isRunning)
        assertFalse(summary.isPaused)
    }

    @Test
    fun testTrackBrainDaoUpsertAndQuery() = runBlocking {
        val brainStatus = TrackBrainStatusEntity(
            trackId = "track_test_1",
            overallStatus = BrainProcessingState.PENDING.name,
            metadataStatus = BrainSubStatus.NOT_STARTED.name,
            bpmStatus = BrainSubStatus.NOT_STARTED.name,
            keyStatus = BrainSubStatus.NOT_STARTED.name,
            waveformStatus = BrainSubStatus.NOT_STARTED.name
        )
        brainDao.upsert(brainStatus)

        val retrieved = brainDao.getStatusForTrack("track_test_1")
        assertNotNull(retrieved)
        assertEquals("track_test_1", retrieved?.trackId)
        assertEquals(BrainProcessingState.PENDING.name, retrieved?.overallStatus)
        assertEquals(BrainSubStatus.NOT_STARTED.name, retrieved?.bpmStatus)
        assertEquals(2, retrieved?.bpmVersion)
        assertEquals(2, retrieved?.keyVersion)
    }

    @Test
    fun testTrackBrainDaoCountsAndStatusFilters() = runBlocking {
        val statuses = listOf(
            TrackBrainStatusEntity(trackId = "t1", overallStatus = BrainProcessingState.COMPLETE.name),
            TrackBrainStatusEntity(trackId = "t2", overallStatus = BrainProcessingState.COMPLETE.name),
            TrackBrainStatusEntity(trackId = "t3", overallStatus = BrainProcessingState.FAILED.name, errorCode = "ERR_CORRUPT"),
            TrackBrainStatusEntity(trackId = "t4", overallStatus = BrainProcessingState.PENDING.name),
            TrackBrainStatusEntity(trackId = "t5", overallStatus = BrainProcessingState.QUEUED.name)
        )
        brainDao.upsertAll(statuses)

        assertEquals(5, brainDao.getTotalCount())
        assertEquals(2, brainDao.getCountByStatus(BrainProcessingState.COMPLETE.name))
        assertEquals(1, brainDao.getCountByStatus(BrainProcessingState.FAILED.name))
        assertEquals(1, brainDao.getCountByStatus(BrainProcessingState.PENDING.name))
        assertEquals(1, brainDao.getCountByStatus(BrainProcessingState.QUEUED.name))

        val needingAnalysis = brainDao.getTracksNeedingAnalysis(limit = 10)
        assertEquals(2, needingAnalysis.size) // t4 and t5

        val failed = brainDao.getFailedTracks(limit = 10)
        assertEquals(1, failed.size)
        assertEquals("t3", failed.first().trackId)
    }

    @Test
    fun testResetFailedTracks() = runBlocking {
        val failed = TrackBrainStatusEntity(
            trackId = "fail_1",
            overallStatus = BrainProcessingState.FAILED.name,
            retryCount = 3,
            errorCode = "ERR_DECODE",
            errorMessage = "Codec error"
        )
        brainDao.upsert(failed)

        assertEquals(1, brainDao.getCountByStatus(BrainProcessingState.FAILED.name))

        val resetCount = brainDao.resetFailedTracks()
        assertEquals(1, resetCount)

        val updated = brainDao.getStatusForTrack("fail_1")
        assertEquals(BrainProcessingState.PENDING.name, updated?.overallStatus)
        assertEquals(0, updated?.retryCount)
        assertNull(updated?.errorCode)
        assertNull(updated?.errorMessage)
    }

    @Test
    fun testCategoryReanalysisQueries() = runBlocking {
        val status = TrackBrainStatusEntity(
            trackId = "cat_1",
            overallStatus = BrainProcessingState.COMPLETE.name,
            bpmStatus = BrainSubStatus.COMPLETE.name,
            keyStatus = BrainSubStatus.COMPLETE.name,
            waveformStatus = BrainSubStatus.COMPLETE.name,
            artworkStatus = BrainSubStatus.COMPLETE.name
        )
        brainDao.upsert(status)

        brainDao.markBpmKeyForReanalysis()

        val afterBpmReset = brainDao.getStatusForTrack("cat_1")
        assertEquals(BrainProcessingState.PENDING.name, afterBpmReset?.overallStatus)
        assertEquals(BrainSubStatus.NOT_STARTED.name, afterBpmReset?.bpmStatus)
        assertEquals(BrainSubStatus.NOT_STARTED.name, afterBpmReset?.keyStatus)
        assertEquals(BrainSubStatus.COMPLETE.name, afterBpmReset?.waveformStatus) // preserved
        assertEquals(BrainSubStatus.COMPLETE.name, afterBpmReset?.artworkStatus) // preserved

        brainDao.markWaveformForReanalysis()
        val afterWaveReset = brainDao.getStatusForTrack("cat_1")
        assertEquals(BrainSubStatus.NOT_STARTED.name, afterWaveReset?.waveformStatus)
    }

    @Test
    fun testOrphanCleaning() = runBlocking {
        knownTracks.add("real_track")

        brainDao.upsert(TrackBrainStatusEntity(trackId = "real_track"))
        brainDao.upsert(TrackBrainStatusEntity(trackId = "deleted_track"))

        assertEquals(2, brainDao.getTotalCount())
        val removed = brainDao.deleteOrphans()
        assertEquals(1, removed)
        assertEquals(1, brainDao.getTotalCount())
        assertNotNull(brainDao.getStatusForTrack("real_track"))
        assertNull(brainDao.getStatusForTrack("deleted_track"))
    }
}
