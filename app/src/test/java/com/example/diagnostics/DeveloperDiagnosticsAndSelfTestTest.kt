package com.example.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.model.Track
import com.example.ui.RepeatMode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeveloperDiagnosticsAndSelfTestTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        DiagnosticLogger.getInstance().clear()
    }

    @Test
    fun testDiagnosticLoggerBufferLimit() {
        val logger = DiagnosticLogger.getInstance()
        logger.clear()

        // Insert 150 entries
        for (i in 1..150) {
            logger.info(DiagnosticSubsystem.SYSTEM, "CODE_$i", "Message $i")
        }

        val entries = logger.getEntries()
        assertEquals("Buffer must be capped at MAX_LOG_ENTRIES (100)", DiagnosticLogger.MAX_LOG_ENTRIES, entries.size)
        // Newest should be first
        assertEquals("Newest entry should be at the front", "CODE_150", entries.first().code)
    }

    @Test
    fun testDiagnosticLoggerRedaction() {
        val secretInput = "Connecting to service with api_key=SECRET_KEY_12345 and bearer token_abc987654321 and ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ1234"
        val sanitized = DiagnosticLogger.sanitize(secretInput)

        assertFalse("Raw api key must be redacted", sanitized.contains("SECRET_KEY_12345"))
        assertFalse("Raw bearer token must be redacted", sanitized.contains("token_abc987654321"))
        assertFalse("GitHub token must be redacted", sanitized.contains("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ1234"))
        assertTrue("Redaction tag must be present", sanitized.contains("[REDACTED]"))
    }

    @Test
    fun testDiagnosticLoggerTestEntryLifecycle() {
        val logger = DiagnosticLogger.getInstance()
        logger.clear()

        val testId = logger.addTestEntry()
        val entries = logger.getEntries()
        assertTrue("Test entry should be present in buffer", entries.any { it.id == testId && it.code == DiagnosticLogger.TEST_ENTRY_CODE })

        logger.removeTestEntry(testId)
        val entriesAfter = logger.getEntries()
        assertFalse("Test entry should be removed from buffer", entriesAfter.any { it.id == testId })
    }

    @Test
    fun testDiagnosticLoggerErrorCount() {
        val logger = DiagnosticLogger.getInstance()
        logger.clear()

        logger.info(DiagnosticSubsystem.PLAYBACK, "PLAY_INFO", "Info msg")
        logger.warn(DiagnosticSubsystem.WAVEFORM, "WAVE_WARN", "Warning msg")
        logger.error(DiagnosticSubsystem.DECODER, "DEC_ERR_1", "Error msg 1")
        logger.critical(DiagnosticSubsystem.DATABASE, "DB_CRIT", "Critical msg")

        assertEquals(2, logger.getRecentErrorCount())
    }

    @Test
    fun testDeveloperModeManagerTapUnlock() {
        val devManager = DeveloperModeManager.getInstance(context)
        devManager.setDeveloperModeEnabled(false)
        assertFalse("Should start disabled", devManager.isDeveloperModeEnabled.value)

        for (tap in 1..6) {
            val unlocked = devManager.registerTap()
            assertFalse("Should not unlock before 7 taps (tap $tap)", unlocked)
        }

        val seventhTap = devManager.registerTap()
        assertTrue("7th tap must unlock Developer Mode", seventhTap)
        assertTrue("StateFlow must reflect enabled status", devManager.isDeveloperModeEnabled.value)

        // Toggling back off
        val toggled = devManager.toggleDeveloperMode()
        assertFalse("Toggle should disable", toggled)
        assertFalse(devManager.isDeveloperModeEnabled.value)
    }

    @Test
    fun testAudioOutputTrackerEventRecording() {
        val tracker = AudioOutputTracker.getInstance(context)
        tracker.recordConnect("Sony WH-1000XM4")
        tracker.recordAudioFocusEvent("AUDIOFOCUS_LOSS_TRANSIENT")
        tracker.recordNoisyEvent()
        tracker.recordDisconnect("Sony WH-1000XM4", didAutoPause = true)

        val snapshot = tracker.buildSnapshot()
        assertNotNull("Connected event should be recorded", snapshot.lastConnectEvent)
        assertTrue("Connected event should mention device", snapshot.lastConnectEvent!!.contains("Sony WH-1000XM4"))
        assertNotNull("Disconnect event should be recorded", snapshot.lastDisconnectEvent)
        assertTrue("Auto-pause fired flag should be true", snapshot.autoPauseOnDisconnectFired)
        assertNotNull("Audio focus event should be recorded", snapshot.lastAudioFocusEvent)
        assertTrue(snapshot.lastAudioFocusEvent!!.contains("AUDIOFOCUS_LOSS_TRANSIENT"))
    }

    @Test
    fun testDiagnosticReportExporterTextAndRedaction() {
        val logger = DiagnosticLogger.getInstance()
        logger.clear()
        logger.error(DiagnosticSubsystem.METADATA, "API_ERR", "Failed request to endpoint with secret=SUPER_SECRET_TOKEN")

        val report = DiagnosticReportExporter.generateTextReport(context, redactFilePaths = true)

        assertTrue("Report must contain header", report.contains("SOUNDSYNC DEVELOPER DIAGNOSTICS REPORT"))
        assertTrue("Report must contain application section", report.contains("1. APPLICATION & ENVIRONMENT"))
        assertTrue("Report must contain storage & memory section", report.contains("2. STORAGE & MEMORY"))
        assertTrue("Report must contain playback section", report.contains("4. PLAYBACK SUBSYSTEM"))
        assertTrue("Report must contain waveform section", report.contains("5. WAVEFORM SUBSYSTEM"))
        assertTrue("Report must contain recent logs section", report.contains("10. RECENT DIAGNOSTIC LOGS"))
        assertFalse("Report must redact secrets", report.contains("SUPER_SECRET_TOKEN"))
    }

    @Test
    fun testDiagnosticReportExporterJson() {
        val jsonString = DiagnosticReportExporter.generateJsonReport(context)
        val json = JSONObject(jsonString)

        assertEquals("SoundSync", json.getString("appName"))
        assertEquals(18, json.getInt("schemaVersion"))
        assertTrue("Must have device object", json.has("device"))
        assertTrue("Must have playback object", json.has("playback"))
        assertTrue("Must have libraryBrain object", json.has("libraryBrain"))
        assertTrue("Must have recentLogs array", json.has("recentLogs"))
    }

    @Test
    fun testSelfTestRunnerModuleDefinitions() {
        assertEquals("SelfTestRunner must contain all 11 diagnostic modules", 11, SelfTestRunner.ALL_MODULES.size)
        assertTrue(SelfTestRunner.ALL_MODULES.contains(SelfTestRunner.MOD_DATABASE))
        assertTrue(SelfTestRunner.ALL_MODULES.contains(SelfTestRunner.MOD_MEDIA_PERMISSIONS))
        assertTrue(SelfTestRunner.ALL_MODULES.contains(SelfTestRunner.MOD_STORAGE_ACCESS))
        assertTrue(SelfTestRunner.ALL_MODULES.contains(SelfTestRunner.MOD_BACKGROUND_JOBS))
        assertTrue(SelfTestRunner.ALL_MODULES.contains(SelfTestRunner.MOD_INTERNET))
        assertTrue(SelfTestRunner.ALL_MODULES.contains(SelfTestRunner.MOD_METADATA_LOOKUP))
        assertTrue(SelfTestRunner.ALL_MODULES.contains(SelfTestRunner.MOD_ARTWORK))
        assertTrue(SelfTestRunner.ALL_MODULES.contains(SelfTestRunner.MOD_AUDIO_DECODER))
        assertTrue(SelfTestRunner.ALL_MODULES.contains(SelfTestRunner.MOD_GITHUB_UPDATE))
        assertTrue(SelfTestRunner.ALL_MODULES.contains(SelfTestRunner.MOD_LIBRARY_BRAIN))
        assertTrue(SelfTestRunner.ALL_MODULES.contains(SelfTestRunner.MOD_ERROR_REPORTING))
    }

    @Test
    fun testSelfTestRunnerExecutionAndSummaryExport() = runBlocking {
        val runner = SelfTestRunner(context)
        val initial = runner.suiteState.value
        assertEquals("Initial state should not be running", false, initial.isRunning)
        assertEquals(11, initial.results.size)

        // Run single test
        runner.runSingleTest(SelfTestRunner.MOD_ERROR_REPORTING)
        val errorReportingResult = runner.suiteState.value.results[SelfTestRunner.MOD_ERROR_REPORTING]
        assertNotNull(errorReportingResult)
        assertEquals(SelfTestStatus.PASS, errorReportingResult?.status)

        // Summary export
        val summary = runner.exportSelfTestSummary()
        assertTrue("Summary should contain header", summary.contains("SoundSync Self-Test Results"))
        assertTrue("Summary should contain Error reporting module", summary.contains("Error reporting"))
        assertTrue("Summary should contain Overall Health", summary.contains("Overall Health:"))
    }
}
