package com.example.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.audio.DjAudioEngine
import com.example.brain.LibraryBrain
import com.example.ui.MainDjViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compiles, sanitizes, and exports comprehensive technical diagnostic reports.
 * Redacts secrets, tokens, passwords, and private API keys.
 */
object DiagnosticReportExporter {

    /**
     * Generates a human-readable plain text diagnostic report.
     */
    fun generateTextReport(
        context: Context,
        audioEngine: DjAudioEngine? = null,
        viewModel: MainDjViewModel? = null,
        redactFilePaths: Boolean = false
    ): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())

        sb.appendLine("=================================================================")
        sb.appendLine("SOUNDSYNC DEVELOPER DIAGNOSTICS REPORT")
        sb.appendLine("Generated: $now")
        sb.appendLine("=================================================================")
        sb.appendLine()

        // 1. APPLICATION & SYSTEM ENVIRONMENT
        sb.appendLine("── 1. APPLICATION & ENVIRONMENT ──────────────────────────────────")
        sb.appendLine("App Version       : v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        sb.appendLine("Build Type        : ${BuildConfig.BUILD_TYPE}")
        sb.appendLine("Room Schema Ver   : 18")
        sb.appendLine("Android OS        : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Device Model      : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Hardware Board    : ${Build.BOARD}")
        sb.appendLine("Fingerprint       : ${DiagnosticLogger.sanitize(Build.FINGERPRINT)}")
        sb.appendLine()

        // 2. STORAGE & MEMORY
        sb.appendLine("── 2. STORAGE & MEMORY ──────────────────────────────────────────")
        val statFs = try { StatFs(Environment.getDataDirectory().path) } catch (_: Exception) { null }
        val freeStorageMb = statFs?.let { it.availableBlocksLong * it.blockSizeLong / (1024 * 1024) } ?: -1L
        val totalStorageMb = statFs?.let { it.blockCountLong * it.blockSizeLong / (1024 * 1024) } ?: -1L
        val rt = Runtime.getRuntime()
        val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxHeapMb = rt.maxMemory() / (1024 * 1024)
        val nativeHeapMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

        sb.appendLine("Storage Available : $freeStorageMb MB / $totalStorageMb MB")
        sb.appendLine("JVM Heap Used     : $usedHeapMb MB / $maxHeapMb MB max")
        sb.appendLine("Native Heap Alloc : $nativeHeapMb MB")
        sb.appendLine("Cache Dir Writable: ${context.cacheDir.canWrite()}")
        sb.appendLine("Files Dir Writable: ${context.filesDir.canWrite()}")
        sb.appendLine()

        // 3. PERMISSIONS & RESTRICTIONS
        sb.appendLine("── 3. PERMISSIONS & RESTRICTIONS ────────────────────────────────")
        val mediaAudioPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val notifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val batteryOptimized = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false

        sb.appendLine("Media Audio Access: ${if (mediaAudioPerm) "GRANTED" else "DENIED"}")
        sb.appendLine("Notification Perm : ${if (notifPerm) "GRANTED" else "DENIED"}")
        sb.appendLine("Battery Opt Ignored: $batteryOptimized")
        sb.appendLine()

        // 4. PLAYBACK SUBSYSTEM
        sb.appendLine("── 4. PLAYBACK SUBSYSTEM ────────────────────────────────────────")
        val engine = audioEngine ?: DjAudioEngine.getInstance(context)
        val currentTrack = engine.currentTrack.value
        val isPlaying = engine.isPlaying.value
        val posMs = engine.currentPositionMs.value
        val diag = engine.getPlaybackDiagnostics()

        sb.appendLine("Playback State    : ${if (isPlaying) "PLAYING" else if (currentTrack != null) "PAUSED" else "IDLE"}")
        sb.appendLine("Current Track ID  : ${currentTrack?.id ?: "None"}")
        sb.appendLine("Title             : ${currentTrack?.title ?: "None"}")
        sb.appendLine("Artist            : ${currentTrack?.artist ?: "None"}")
        val displayPath = currentTrack?.filePath?.let { if (redactFilePaths) redactPath(it) else it } ?: "None"
        sb.appendLine("File Path / URI   : $displayPath")
        val fileExists = currentTrack?.filePath?.let { File(it).exists() } ?: false
        sb.appendLine("File Exists       : $fileExists")
        sb.appendLine("Decoder in Use    : ${diag.decoderName}")
        sb.appendLine("Format / Container: ${diag.containerFormat}")
        sb.appendLine("Codec MIME        : ${diag.mimeType}")
        sb.appendLine("Sample Rate       : ${diag.sampleRate} Hz")
        sb.appendLine("Bit Depth         : ${diag.bitDepth}-bit")
        sb.appendLine("Channel Count     : ${diag.channelCount} (${if (diag.channelCount == 1) "Mono" else "Stereo"})")
        sb.appendLine("Reported Bitrate  : ${diag.bitrateKbps} kbps")
        val durationMs = currentTrack?.durationSeconds?.toLong()?.times(1000L) ?: 0L
        sb.appendLine("Duration          : ${formatMs(durationMs)} ($durationMs ms)")
        sb.appendLine("Position          : ${formatMs(posMs)} ($posMs ms)")
        sb.appendLine("Playback Speed    : ${String.format(Locale.US, "%.2fx", diag.playbackSpeed)}")
        sb.appendLine("Audio Session ID  : ${diag.audioSessionId}")
        sb.appendLine("Audio Focus       : ${if (diag.hasAudioFocus) "HELD" else "RELEASED/LOST"}")
        sb.appendLine()

        // 5. WAVEFORM SUBSYSTEM
        sb.appendLine("── 5. WAVEFORM SUBSYSTEM ────────────────────────────────────────")
        val waveformData = engine.waveformData.value
        val isWaveformGen = waveformData != null
        val expectedPosMs = if (durationMs > 0) (engine.playbackProgress.value * durationMs).toLong() else 0L
        val driftMs = Math.abs(posMs - expectedPosMs)
        val hasDriftWarning = isPlaying && durationMs > 0 && driftMs > 250L

        sb.appendLine("Waveform Generated: $isWaveformGen")
        sb.appendLine("Waveform Version  : ${if (isWaveformGen) "Peak Amplitudes v2" else "None"}")
        sb.appendLine("Playback Position : $posMs ms")
        sb.appendLine("Expected Position : $expectedPosMs ms")
        sb.appendLine("Position Drift    : $driftMs ms")
        sb.appendLine("Sync Drift Alert  : ${if (hasDriftWarning) "WARNING (>250ms drift)" else "OK (in-sync)"}")
        sb.appendLine()

        // 6. QUEUE SUBSYSTEM
        sb.appendLine("── 6. QUEUE SUBSYSTEM ───────────────────────────────────────────")
        val queue = viewModel?.playbackQueue?.value ?: emptyList()
        val queueIdx = viewModel?.queueIndex?.value ?: 0
        sb.appendLine("Queue Size        : ${queue.size}")
        sb.appendLine("Current Index     : $queueIdx")
        sb.appendLine("Current Item      : ${queue.getOrNull(queueIdx)?.title ?: "None"}")
        sb.appendLine("Previous Item     : ${queue.getOrNull(queueIdx - 1)?.title ?: "None"}")
        sb.appendLine("Next Item         : ${queue.getOrNull(queueIdx + 1)?.title ?: "None"}")
        sb.appendLine("Shuffle Enabled   : ${viewModel?.isShuffleEnabled?.value ?: false}")
        sb.appendLine("Repeat Mode       : ${viewModel?.repeatMode?.value?.name ?: "OFF"}")
        sb.appendLine()

        // 7. BLUETOOTH & AUDIO OUTPUT
        sb.appendLine("── 7. BLUETOOTH & AUDIO OUTPUT ──────────────────────────────────")
        val audioTracker = AudioOutputTracker.getInstance(context)
        val audioDiag = audioTracker.buildSnapshot()
        sb.appendLine("Bluetooth State   : ${audioDiag.bluetoothState}")
        sb.appendLine("Active Route      : ${audioDiag.activeRoute}")
        sb.appendLine("Connected Devices : ${audioDiag.connectedDevices.joinToString(", ")}")
        sb.appendLine("Last Connect      : ${audioDiag.lastConnectEvent ?: "None"}")
        sb.appendLine("Last Disconnect   : ${audioDiag.lastDisconnectEvent ?: "None"}")
        sb.appendLine("Auto-Pause Fired  : ${audioDiag.autoPauseOnDisconnectFired}")
        sb.appendLine("Last Focus Event  : ${audioDiag.lastAudioFocusEvent ?: "None"}")
        sb.appendLine("Last BecomingNoisy: ${audioDiag.lastNoisyEvent ?: "None"}")
        sb.appendLine()

        // 8. LIBRARY BRAIN
        sb.appendLine("── 8. LIBRARY BRAIN ─────────────────────────────────────────────")
        val brain = LibraryBrain.getInstance(context)
        val brainSummary = brain.brainSummary.value
        sb.appendLine("Worker State      : ${if (brainSummary.isRunning) "RUNNING" else if (brainSummary.isPaused) "PAUSED" else "IDLE"}")
        sb.appendLine("Total Tracks      : ${brainSummary.totalTracks}")
        sb.appendLine("Completed Tracks  : ${brainSummary.completeCount}")
        sb.appendLine("Pending Tracks    : ${brainSummary.pendingCount}")
        sb.appendLine("Failed Tracks     : ${brainSummary.failedCount}")
        sb.appendLine("Needs Review      : ${brainSummary.needsReviewCount}")
        sb.appendLine("Missing Files     : ${brainSummary.missingFilesCount}")
        sb.appendLine("Current Job       : ${brainSummary.currentJobDescription}")
        sb.appendLine("Active Track      : ${brainSummary.currentTrackTitle.ifEmpty { "None" }}")
        sb.appendLine("Queue Length      : ${brainSummary.queueLength}")
        sb.appendLine()

        // 9. METADATA & ENRICHMENT
        sb.appendLine("── 9. METADATA & ENRICHMENT ─────────────────────────────────────")
        sb.appendLine("Last Provider     : Apple iTunes Search API / TheAudioDB / Embedded ID3")
        sb.appendLine("Artwork Source    : ${currentTrack?.artworkSource ?: "None"}")
        sb.appendLine("User Confirmed    : ${currentTrack?.userConfirmedMetadata ?: false}")
        sb.appendLine()

        // 10. RECENT DIAGNOSTIC ERRORS
        sb.appendLine("── 10. RECENT DIAGNOSTIC LOGS (Newest First) ───────────────────")
        val logger = DiagnosticLogger.getInstance()
        val entries = logger.getEntries()
        if (entries.isEmpty()) {
            sb.appendLine("No diagnostic errors recorded in current session.")
        } else {
            val logDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            for (entry in entries) {
                val timeStr = logDateFormat.format(Date(entry.timestamp))
                sb.appendLine("[$timeStr][${entry.severity}][${entry.subsystem}][${entry.code}] ${entry.message}")
                if (entry.trackId != null) sb.appendLine("   Track ID: ${entry.trackId}")
                if (entry.filePath != null) {
                    val p = if (redactFilePaths) redactPath(entry.filePath) else entry.filePath
                    sb.appendLine("   File: $p")
                }
                if (!entry.stackTrace.isNullOrBlank()) {
                    val firstLines = entry.stackTrace.lines().take(4).joinToString("\n      ")
                    sb.appendLine("      $firstLines")
                }
            }
        }

        sb.appendLine()
        sb.appendLine("=================================================================")
        sb.appendLine("END OF SOUNDSYNC DIAGNOSTIC REPORT")
        sb.appendLine("=================================================================")

        return DiagnosticLogger.sanitize(sb.toString())
    }

    /**
     * Generates a structured JSON diagnostic report.
     */
    fun generateJsonReport(
        context: Context,
        audioEngine: DjAudioEngine? = null,
        viewModel: MainDjViewModel? = null,
        redactFilePaths: Boolean = false
    ): String {
        val root = JSONObject()
        root.put("reportTimestamp", System.currentTimeMillis())
        root.put("appName", "SoundSync")
        root.put("versionName", BuildConfig.VERSION_NAME)
        root.put("versionCode", BuildConfig.VERSION_CODE)
        root.put("buildType", BuildConfig.BUILD_TYPE)
        root.put("schemaVersion", 18)

        val deviceObj = JSONObject()
        deviceObj.put("androidRelease", Build.VERSION.RELEASE)
        deviceObj.put("sdkInt", Build.VERSION.SDK_INT)
        deviceObj.put("manufacturer", Build.MANUFACTURER)
        deviceObj.put("model", Build.MODEL)
        root.put("device", deviceObj)

        val rt = Runtime.getRuntime()
        val memObj = JSONObject()
        memObj.put("usedHeapMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))
        memObj.put("maxHeapMb", rt.maxMemory() / (1024 * 1024))
        memObj.put("nativeHeapMb", android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024))
        root.put("memory", memObj)

        val engine = audioEngine ?: DjAudioEngine.getInstance(context)
        val diag = engine.getPlaybackDiagnostics()
        val playbackObj = JSONObject()
        playbackObj.put("isPlaying", diag.isPlaying)
        playbackObj.put("currentTrackId", diag.currentTrack?.id)
        playbackObj.put("trackTitle", diag.currentTrack?.title)
        val p = diag.currentTrack?.filePath?.let { if (redactFilePaths) redactPath(it) else it }
        playbackObj.put("filePath", p)
        playbackObj.put("decoder", diag.decoderName)
        playbackObj.put("format", diag.containerFormat)
        playbackObj.put("mime", diag.mimeType)
        playbackObj.put("sampleRate", diag.sampleRate)
        playbackObj.put("bitrateKbps", diag.bitrateKbps)
        playbackObj.put("channels", diag.channelCount)
        playbackObj.put("positionMs", diag.positionMs)
        playbackObj.put("audioSessionId", diag.audioSessionId)
        playbackObj.put("hasAudioFocus", diag.hasAudioFocus)
        root.put("playback", playbackObj)

        val brain = LibraryBrain.getInstance(context)
        val brainSummary = brain.brainSummary.value
        val brainObj = JSONObject()
        brainObj.put("isRunning", brainSummary.isRunning)
        brainObj.put("isPaused", brainSummary.isPaused)
        brainObj.put("totalTracks", brainSummary.totalTracks)
        brainObj.put("completeCount", brainSummary.completeCount)
        brainObj.put("failedCount", brainSummary.failedCount)
        brainObj.put("pendingCount", brainSummary.pendingCount)
        brainObj.put("needsReviewCount", brainSummary.needsReviewCount)
        brainObj.put("currentJobDescription", brainSummary.currentJobDescription)
        root.put("libraryBrain", brainObj)

        val logsArr = JSONArray()
        val entries = DiagnosticLogger.getInstance().getEntries()
        for (e in entries) {
            val o = JSONObject()
            o.put("timestamp", e.timestamp)
            o.put("subsystem", e.subsystem.name)
            o.put("severity", e.severity.name)
            o.put("code", e.code)
            o.put("message", e.message)
            if (e.trackId != null) o.put("trackId", e.trackId)
            logsArr.put(o)
        }
        root.put("recentLogs", logsArr)

        return DiagnosticLogger.sanitize(root.toString(2))
    }

    /**
     * Copies the plain-text diagnostic report to the system clipboard.
     */
    fun copyToClipboard(context: Context, text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("SoundSync Diagnostic Report", text)
            clipboard?.setPrimaryClip(clip)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Shares the diagnostic report text via Android's native share sheet.
     */
    fun shareReport(context: Context, text: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "SoundSync Diagnostics - v${BuildConfig.VERSION_NAME}")
            type = "text/plain"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val chooser = Intent.createChooser(sendIntent, "Share SoundSync Diagnostics").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(chooser)
    }

    /**
     * Saves the diagnostic report to a text file in the app cache directory.
     */
    fun saveReportToFile(context: Context, text: String, formatJson: Boolean = false): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = if (formatJson) "json" else "txt"
        val fileName = "soundsync_diagnostic_$timestamp.$ext"
        val file = File(context.cacheDir, fileName)
        file.writeText(text)
        return file
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun redactPath(path: String): String {
        val name = File(path).name
        val parent = File(path).parentFile?.name ?: "folder"
        return ".../$parent/$name"
    }
}
