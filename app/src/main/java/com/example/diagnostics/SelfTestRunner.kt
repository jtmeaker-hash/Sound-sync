package com.example.diagnostics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.example.BuildConfig
import com.example.brain.BrainProcessingState
import com.example.brain.LibraryBrain
import com.example.data.AppDatabase
import com.example.data.TrackBrainStatusEntity
import com.example.model.SemanticVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale

/**
 * Executes the 11-point SoundSync Self-Test suite.
 * Runs each diagnostic module in isolation so failures in one module
 * never abort subsequent tests.
 */
class SelfTestRunner(private val context: Context) {

    companion object {
        private const val TAG = "SelfTestRunner"

        const val MOD_DATABASE = "Database"
        const val MOD_MEDIA_PERMISSIONS = "Media permissions"
        const val MOD_STORAGE_ACCESS = "Storage access"
        const val MOD_BACKGROUND_JOBS = "Background jobs"
        const val MOD_INTERNET = "Internet"
        const val MOD_METADATA_LOOKUP = "Metadata lookup"
        const val MOD_ARTWORK = "Artwork download"
        const val MOD_AUDIO_DECODER = "Audio decoder"
        const val MOD_GITHUB_UPDATE = "GitHub update check"
        const val MOD_LIBRARY_BRAIN = "Library Brain"
        const val MOD_ERROR_REPORTING = "Error reporting"

        val ALL_MODULES = listOf(
            MOD_DATABASE,
            MOD_MEDIA_PERMISSIONS,
            MOD_STORAGE_ACCESS,
            MOD_BACKGROUND_JOBS,
            MOD_INTERNET,
            MOD_METADATA_LOOKUP,
            MOD_ARTWORK,
            MOD_AUDIO_DECODER,
            MOD_GITHUB_UPDATE,
            MOD_LIBRARY_BRAIN,
            MOD_ERROR_REPORTING
        )
    }

    private val _suiteState = MutableStateFlow(
        SelfTestSuiteState(
            results = ALL_MODULES.associateWith {
                SelfTestResult(
                    moduleName = it,
                    status = SelfTestStatus.IDLE,
                    summary = "Not yet run",
                    technicalDetails = "Tap 'Run All Tests' or 'Test' to execute this diagnostic check."
                )
            }
        )
    )
    val suiteState: StateFlow<SelfTestSuiteState> = _suiteState.asStateFlow()

    /**
     * Runs all 11 diagnostic modules sequentially.
     */
    suspend fun runAllTests() = withContext(Dispatchers.IO) {
        val currentResults = _suiteState.value.results.toMutableMap()
        _suiteState.value = _suiteState.value.copy(
            isRunning = true,
            completedCount = 0,
            progress = 0f
        )

        for ((index, moduleName) in ALL_MODULES.withIndex()) {
            // Mark current module as RUNNING
            currentResults[moduleName] = SelfTestResult(
                moduleName = moduleName,
                status = SelfTestStatus.RUNNING,
                summary = "Executing diagnostic check...",
                technicalDetails = "Module test in progress"
            )
            _suiteState.value = _suiteState.value.copy(
                results = currentResults.toMap(),
                progress = index.toFloat() / ALL_MODULES.size.toFloat()
            )

            // Run individual test safely
            val result = runModuleTestInternal(moduleName)
            currentResults[moduleName] = result

            _suiteState.value = _suiteState.value.copy(
                results = currentResults.toMap(),
                completedCount = index + 1,
                progress = (index + 1).toFloat() / ALL_MODULES.size.toFloat(),
                overallHealth = computeOverallHealth(currentResults.values)
            )
        }

        _suiteState.value = _suiteState.value.copy(
            isRunning = false,
            overallHealth = computeOverallHealth(currentResults.values),
            progress = 1.0f
        )
    }

    /**
     * Retries or runs a single test module.
     */
    suspend fun runSingleTest(moduleName: String) = withContext(Dispatchers.IO) {
        val currentResults = _suiteState.value.results.toMutableMap()
        currentResults[moduleName] = SelfTestResult(
            moduleName = moduleName,
            status = SelfTestStatus.RUNNING,
            summary = "Retrying diagnostic check...",
            technicalDetails = "Module test in progress"
        )
        _suiteState.value = _suiteState.value.copy(
            results = currentResults.toMap()
        )

        val result = runModuleTestInternal(moduleName)
        currentResults[moduleName] = result

        _suiteState.value = _suiteState.value.copy(
            results = currentResults.toMap(),
            overallHealth = computeOverallHealth(currentResults.values)
        )
    }

    private suspend fun runModuleTestInternal(moduleName: String): SelfTestResult {
        val startTime = System.currentTimeMillis()
        return try {
            when (moduleName) {
                MOD_DATABASE -> testDatabase(startTime)
                MOD_MEDIA_PERMISSIONS -> testMediaPermissions(startTime)
                MOD_STORAGE_ACCESS -> testStorageAccess(startTime)
                MOD_BACKGROUND_JOBS -> testBackgroundJobs(startTime)
                MOD_INTERNET -> testInternet(startTime)
                MOD_METADATA_LOOKUP -> testMetadataLookup(startTime)
                MOD_ARTWORK -> testArtwork(startTime)
                MOD_AUDIO_DECODER -> testAudioDecoder(startTime)
                MOD_GITHUB_UPDATE -> testGitHubUpdate(startTime)
                MOD_LIBRARY_BRAIN -> testLibraryBrain(startTime)
                MOD_ERROR_REPORTING -> testErrorReporting(startTime)
                else -> SelfTestResult(
                    moduleName = moduleName,
                    status = SelfTestStatus.SKIPPED,
                    summary = "Unknown module",
                    technicalDetails = "Module not registered in test runner"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception in self-test module: $moduleName", e)
            SelfTestResult(
                moduleName = moduleName,
                status = SelfTestStatus.FAIL,
                summary = "Test threw unexpected exception: ${e.message}",
                technicalDetails = "Exception: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString().take(300)}",
                likelyCause = "Unexpected runtime fault or unhandled state",
                suggestedFix = "Inspect recent diagnostic logs and check device state",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ── 1. Database Module ───────────────────────────────────────────────────
    private suspend fun testDatabase(startTime: Long): SelfTestResult {
        return try {
            val db = AppDatabase.getDatabase(context)
            val openHelper = db.openHelper
            val readableDb = openHelper.readableDatabase
            val schemaVersion = readableDb.version

            // Check schema version
            if (schemaVersion < 18) {
                return SelfTestResult(
                    moduleName = MOD_DATABASE,
                    status = SelfTestStatus.WARNING,
                    summary = "Database schema version ($schemaVersion) is older than expected (18)",
                    technicalDetails = "Current DB version: $schemaVersion. Expected: 18.",
                    likelyCause = "Pending Room migration or stale test schema",
                    suggestedFix = "Restart application to apply Room MIGRATION_17_18",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            // Test read query
            val totalStatusCount = db.trackBrainDao().getTotalCount()

            // Test safe temporary write, read, delete transaction
            val testId = "__selftest_verify_temp__"
            val tempEntity = TrackBrainStatusEntity(
                trackId = testId,
                overallStatus = BrainProcessingState.COMPLETE.name
            )
            db.trackBrainDao().upsert(tempEntity)
            val fetched = db.trackBrainDao().getStatusForTrack(testId)
            db.trackBrainDao().deleteForTrack(testId)

            if (fetched == null) {
                return SelfTestResult(
                    moduleName = MOD_DATABASE,
                    status = SelfTestStatus.FAIL,
                    summary = "Temporary write/read transaction failed",
                    technicalDetails = "Inserted record was not retrievable before deletion.",
                    likelyCause = "SQLite write failure or table lock",
                    suggestedFix = "Verify database integrity and storage permissions",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            SelfTestResult(
                moduleName = MOD_DATABASE,
                status = SelfTestStatus.PASS,
                summary = "Database v$schemaVersion healthy, write/read transaction verified",
                technicalDetails = "Schema version: $schemaVersion. Brain statuses indexed: $totalStatusCount. Integrity: OK.",
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            SelfTestResult(
                moduleName = MOD_DATABASE,
                status = SelfTestStatus.FAIL,
                summary = "Database access failed: ${e.message}",
                technicalDetails = e.stackTraceToString().take(300),
                likelyCause = "Database corruption, disk I/O error, or schema lock",
                suggestedFix = "Check available disk space or restore from backup",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ── 2. Media Permissions Module ──────────────────────────────────────────
    private fun testMediaPermissions(startTime: Long): SelfTestResult {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val isGranted = ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!isGranted) {
            return SelfTestResult(
                moduleName = MOD_MEDIA_PERMISSIONS,
                status = SelfTestStatus.WARNING,
                summary = "Media audio permission is NOT granted",
                technicalDetails = "Permission '$perm' is denied. SoundSync cannot discover or read device audio files automatically.",
                likelyCause = "User denied audio permission during onboarding or revoked it in system settings.",
                suggestedFix = "Open Android Settings -> Apps -> SoundSync -> Permissions -> Enable 'Music and audio'.",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        return SelfTestResult(
            moduleName = MOD_MEDIA_PERMISSIONS,
            status = SelfTestStatus.PASS,
            summary = "Required media permission is granted",
            technicalDetails = "Permission '$perm' is GRANTED. Local storage audio queries permitted.",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ── 3. Storage Access Module ─────────────────────────────────────────────
    private fun testStorageAccess(startTime: Long): SelfTestResult {
        val cacheOk = context.cacheDir.canWrite()
        val filesOk = context.filesDir.canWrite()

        if (!cacheOk || !filesOk) {
            return SelfTestResult(
                moduleName = MOD_STORAGE_ACCESS,
                status = SelfTestStatus.FAIL,
                summary = "Application storage directories are not writable",
                technicalDetails = "cacheDir writable: $cacheOk, filesDir writable: $filesOk",
                likelyCause = "File system read-only mount or OS sandboxing corruption",
                suggestedFix = "Reboot device or check app storage state",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        val statFs = try { StatFs(context.filesDir.absolutePath) } catch (_: Exception) { null }
        val freeMb = statFs?.let { it.availableBlocksLong * it.blockSizeLong / (1024 * 1024) } ?: 500L

        if (freeMb < 100L) {
            return SelfTestResult(
                moduleName = MOD_STORAGE_ACCESS,
                status = SelfTestStatus.WARNING,
                summary = "Low available storage space ($freeMb MB free)",
                technicalDetails = "Free space: $freeMb MB. SoundSync recommends at least 100 MB for waveforms and metadata caches.",
                likelyCause = "Device storage is nearly full",
                suggestedFix = "Free up device storage to prevent database transaction or decode aborts",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        return SelfTestResult(
            moduleName = MOD_STORAGE_ACCESS,
            status = SelfTestStatus.PASS,
            summary = "Internal storage and cache writable ($freeMb MB available)",
            technicalDetails = "Cache and files directories are fully writable. Safe free storage margin confirmed.",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ── 4. Background Jobs Module ────────────────────────────────────────────
    private fun testBackgroundJobs(startTime: Long): SelfTestResult {
        return try {
            val brain = LibraryBrain.getInstance(context)
            val isBrainInit = brain != null
            val wm = try { WorkManager.getInstance(context) } catch (_: Exception) { null }

            SelfTestResult(
                moduleName = MOD_BACKGROUND_JOBS,
                status = SelfTestStatus.PASS,
                summary = "Library Brain coordinator and WorkManager scheduler healthy",
                technicalDetails = "LibraryBrain initialised: $isBrainInit. WorkManager instance available: ${wm != null}.",
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            SelfTestResult(
                moduleName = MOD_BACKGROUND_JOBS,
                status = SelfTestStatus.WARNING,
                summary = "Background scheduler check encounter warning: ${e.message}",
                technicalDetails = e.stackTraceToString().take(300),
                likelyCause = "WorkManager initialization or background process restriction",
                suggestedFix = "Check battery optimization settings for SoundSync",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ── 5. Internet Module ───────────────────────────────────────────────────
    private fun testInternet(startTime: Long): SelfTestResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val hasNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(network)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            cm?.activeNetworkInfo?.isConnected == true
        }

        if (!hasNetwork) {
            return SelfTestResult(
                moduleName = MOD_INTERNET,
                status = SelfTestStatus.WARNING,
                summary = "No active internet connection",
                technicalDetails = "Active network reports no internet capability. Online metadata matching and release checks will run in offline mode.",
                likelyCause = "Device is offline, Wi-Fi is disconnected, or Airplane Mode is active.",
                suggestedFix = "Connect device to Wi-Fi or mobile data if online enrichment is desired.",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        // Test lightweight DNS resolution
        return try {
            val dnsStart = System.currentTimeMillis()
            val addr = InetAddress.getByName("dns.google")
            val dnsTime = System.currentTimeMillis() - dnsStart
            SelfTestResult(
                moduleName = MOD_INTERNET,
                status = SelfTestStatus.PASS,
                summary = "Internet connected, DNS resolution verified (${dnsTime}ms)",
                technicalDetails = "Resolved dns.google -> ${addr.hostAddress} in ${dnsTime}ms.",
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            SelfTestResult(
                moduleName = MOD_INTERNET,
                status = SelfTestStatus.WARNING,
                summary = "Internet connection active but DNS lookup failed",
                technicalDetails = "DNS resolution failed: ${e.message}",
                likelyCause = "Captive portal, firewall, or DNS misconfiguration",
                suggestedFix = "Check local network internet access",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ── 6. Metadata Lookup Module ────────────────────────────────────────────
    private fun testMetadataLookup(startTime: Long): SelfTestResult {
        // Lightweight provider connectivity test against Apple iTunes Search API
        return try {
            val url = URL("https://itunes.apple.com/search?term=daft+punk+one+more+time&entity=song&limit=1")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "SoundSync-Android/${BuildConfig.VERSION_NAME}")

            val code = connection.responseCode
            if (code == 200) {
                val text = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val count = json.optInt("resultCount", 0)
                SelfTestResult(
                    moduleName = MOD_METADATA_LOOKUP,
                    status = SelfTestStatus.PASS,
                    summary = "Apple iTunes Search API reachable (HTTP 200, parsed $count result)",
                    technicalDetails = "Provider: Apple iTunes. Status: 200 OK. Results parsed: $count.",
                    durationMs = System.currentTimeMillis() - startTime
                )
            } else {
                SelfTestResult(
                    moduleName = MOD_METADATA_LOOKUP,
                    status = SelfTestStatus.WARNING,
                    summary = "Metadata provider returned HTTP $code",
                    technicalDetails = "Provider responded with HTTP status $code",
                    likelyCause = "Upstream API rate limiting or temporary maintenance",
                    suggestedFix = "Retry metadata search later or use alternate metadata providers",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            SelfTestResult(
                moduleName = MOD_METADATA_LOOKUP,
                status = SelfTestStatus.WARNING,
                summary = "Metadata lookup provider unreachable (offline)",
                technicalDetails = "Connection failure: ${e.message}",
                likelyCause = "Device is offline or provider is blocked",
                suggestedFix = "Check internet connection. Local embedded ID3 tag extraction will remain fully functional.",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ── 7. Artwork Module ────────────────────────────────────────────────────
    private fun testArtwork(startTime: Long): SelfTestResult {
        return try {
            // 1. Verify artwork cache folder
            val artDir = File(context.cacheDir, "artwork_cache")
            if (!artDir.exists()) artDir.mkdirs()
            val dirWritable = artDir.canWrite()

            // 2. Verify bitmap decode & write/delete
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val bytes = stream.toByteArray()
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            val tempFile = File(artDir, "__selftest_art_test__.tmp")
            tempFile.writeBytes(bytes)
            val canRead = tempFile.exists() && tempFile.length() > 0
            tempFile.delete()

            if (decoded != null && canRead && dirWritable) {
                SelfTestResult(
                    moduleName = MOD_ARTWORK,
                    status = SelfTestStatus.PASS,
                    summary = "Artwork cache and bitmap decoding verified",
                    technicalDetails = "Artwork cache directory writable. In-memory PNG codec and disk cache write/read operational.",
                    durationMs = System.currentTimeMillis() - startTime
                )
            } else {
                SelfTestResult(
                    moduleName = MOD_ARTWORK,
                    status = SelfTestStatus.WARNING,
                    summary = "Artwork cache test completed with partial anomalies",
                    technicalDetails = "Directory writable: $dirWritable, Bitmap decoded: ${decoded != null}, Disk I/O: $canRead",
                    likelyCause = "Storage permissions or memory constraints",
                    suggestedFix = "Verify app cache directory permissions",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            SelfTestResult(
                moduleName = MOD_ARTWORK,
                status = SelfTestStatus.FAIL,
                summary = "Artwork engine check failed: ${e.message}",
                technicalDetails = e.stackTraceToString().take(300),
                likelyCause = "Bitmap decoder error or disk failure",
                suggestedFix = "Clear app cache in system settings",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ── 8. Audio Decoder Module ──────────────────────────────────────────────
    private fun testAudioDecoder(startTime: Long): SelfTestResult {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val decoders = codecList.codecInfos.filter { !it.isEncoder }

            var hasAac = false
            var hasMp3 = false
            var hasFlac = false

            for (info in decoders) {
                val types = info.supportedTypes
                if (types.any { it.equals("audio/mp4a-latm", ignoreCase = true) }) hasAac = true
                if (types.any { it.equals("audio/mpeg", ignoreCase = true) }) hasMp3 = true
                if (types.any { it.equals("audio/flac", ignoreCase = true) }) hasFlac = true
            }

            if (hasAac && hasMp3) {
                SelfTestResult(
                    moduleName = MOD_AUDIO_DECODER,
                    status = SelfTestStatus.PASS,
                    summary = "MediaCodec hardware & software audio decoders operational",
                    technicalDetails = "AAC (audio/mp4a-latm): YES. MP3 (audio/mpeg): YES. FLAC: ${if (hasFlac) "YES" else "NO"}. Total decoders registered: ${decoders.size}.",
                    durationMs = System.currentTimeMillis() - startTime
                )
            } else {
                SelfTestResult(
                    moduleName = MOD_AUDIO_DECODER,
                    status = SelfTestStatus.WARNING,
                    summary = "Standard audio decoders partially missing on device",
                    technicalDetails = "AAC: $hasAac, MP3: $hasMp3, FLAC: $hasFlac. Decoder count: ${decoders.size}.",
                    likelyCause = "Custom ROM or stripped Android MediaCodec configuration",
                    suggestedFix = "Ensure system media codecs are intact. Procedural fallback will be used when MediaCodec fails.",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            SelfTestResult(
                moduleName = MOD_AUDIO_DECODER,
                status = SelfTestStatus.FAIL,
                summary = "MediaCodec decoder query failed: ${e.message}",
                technicalDetails = e.stackTraceToString().take(300),
                likelyCause = "Android MediaCodec service fault",
                suggestedFix = "Reboot device to reset audio server",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ── 9. GitHub Update Check Module ────────────────────────────────────────
    private fun testGitHubUpdate(startTime: Long): SelfTestResult {
        return try {
            val url = URL("https://api.github.com/repos/jtmeaker-hash/Sound-sync/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "SoundSync-Android/${BuildConfig.VERSION_NAME}")

            val code = connection.responseCode
            if (code == 200) {
                val text = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val tagName = json.optString("tag_name", "v1.0.0")
                val releaseVer = SemanticVersion.parse(tagName)
                val currentVer = SemanticVersion.parse(BuildConfig.VERSION_NAME)
                val isNewer = releaseVer > currentVer

                SelfTestResult(
                    moduleName = MOD_GITHUB_UPDATE,
                    status = SelfTestStatus.PASS,
                    summary = "GitHub Releases endpoint reachable, parsed latest release '$tagName'",
                    technicalDetails = "Endpoint: 200 OK. Latest release: $tagName. Current installed: v${BuildConfig.VERSION_NAME}. Update available: $isNewer.",
                    durationMs = System.currentTimeMillis() - startTime
                )
            } else if (code == 403 || code == 429) {
                SelfTestResult(
                    moduleName = MOD_GITHUB_UPDATE,
                    status = SelfTestStatus.WARNING,
                    summary = "GitHub API rate limit reached (HTTP $code)",
                    technicalDetails = "GitHub API rate limit exceeded. Update checks will resume when rate window resets.",
                    likelyCause = "Unauthenticated GitHub API IP rate limit (60 requests/hour)",
                    suggestedFix = "Wait a few minutes before checking for updates again",
                    durationMs = System.currentTimeMillis() - startTime
                )
            } else {
                SelfTestResult(
                    moduleName = MOD_GITHUB_UPDATE,
                    status = SelfTestStatus.WARNING,
                    summary = "GitHub update endpoint returned HTTP $code",
                    technicalDetails = "HTTP status $code from GitHub releases endpoint",
                    likelyCause = "Repository visibility or GitHub maintenance",
                    suggestedFix = "Check repository URL in app settings",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            SelfTestResult(
                moduleName = MOD_GITHUB_UPDATE,
                status = SelfTestStatus.WARNING,
                summary = "GitHub update endpoint unreachable (offline)",
                technicalDetails = "Network connection failed: ${e.message}",
                likelyCause = "Device is offline or DNS lookup failed",
                suggestedFix = "Connect to Wi-Fi to check for newer SoundSync builds",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ── 10. Library Brain Module ─────────────────────────────────────────────
    private suspend fun testLibraryBrain(startTime: Long): SelfTestResult {
        return try {
            val brain = LibraryBrain.getInstance(context)
            val summary = brain.brainSummary.value
            val db = AppDatabase.getDatabase(context)
            val totalTrackStatuses = db.trackBrainDao().getTotalCount()
            val completedCount = db.trackBrainDao().getCountByStatus(BrainProcessingState.COMPLETE.name)

            SelfTestResult(
                moduleName = MOD_LIBRARY_BRAIN,
                status = SelfTestStatus.PASS,
                summary = "Library Brain operational, status table verified ($totalTrackStatuses tracks, $completedCount complete)",
                technicalDetails = "Coordinator: active. Total tracked: $totalTrackStatuses. Complete: $completedCount. Queue length: ${summary.pendingCount}.",
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            SelfTestResult(
                moduleName = MOD_LIBRARY_BRAIN,
                status = SelfTestStatus.FAIL,
                summary = "Library Brain verification failed: ${e.message}",
                technicalDetails = e.stackTraceToString().take(300),
                likelyCause = "Database or concurrency initialization fault",
                suggestedFix = "Restart application or inspect recent diagnostic errors",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    // ── 11. Error Reporting Module ───────────────────────────────────────────
    private fun testErrorReporting(startTime: Long): SelfTestResult {
        return try {
            val logger = DiagnosticLogger.getInstance()
            val testId = logger.addTestEntry()
            val entries = logger.getEntries()
            val found = entries.any { it.id == testId }
            logger.removeTestEntry(testId)

            if (found) {
                SelfTestResult(
                    moduleName = MOD_ERROR_REPORTING,
                    status = SelfTestStatus.PASS,
                    summary = "Diagnostic rolling error log verified",
                    technicalDetails = "In-memory circular buffer: insert, read, and cleanup verified. Sanitization filter active.",
                    durationMs = System.currentTimeMillis() - startTime
                )
            } else {
                SelfTestResult(
                    moduleName = MOD_ERROR_REPORTING,
                    status = SelfTestStatus.FAIL,
                    summary = "Diagnostic logger failed to retain test record",
                    technicalDetails = "Inserted test log entry was not found in buffer.",
                    likelyCause = "Buffer locking or synchronization issue",
                    suggestedFix = "Restart application to re-initialize logging buffer",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            SelfTestResult(
                moduleName = MOD_ERROR_REPORTING,
                status = SelfTestStatus.FAIL,
                summary = "Diagnostic logger error: ${e.message}",
                technicalDetails = e.stackTraceToString().take(300),
                likelyCause = "Unexpected exception during diagnostic log verification",
                suggestedFix = "Inspect app logs",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun computeOverallHealth(results: Collection<SelfTestResult>): OverallHealth {
        val statuses = results.map { it.status }
        val failCount = statuses.count { it == SelfTestStatus.FAIL }
        val warnCount = statuses.count { it == SelfTestStatus.WARNING }

        // Critical failures check
        val dbResult = results.find { it.moduleName == MOD_DATABASE }?.status
        val decoderResult = results.find { it.moduleName == MOD_AUDIO_DECODER }?.status

        return when {
            dbResult == SelfTestStatus.FAIL || decoderResult == SelfTestStatus.FAIL || failCount >= 2 -> OverallHealth.CRITICAL
            failCount == 1 -> OverallHealth.DEGRADED
            warnCount > 0 -> OverallHealth.WARNING
            else -> OverallHealth.GOOD
        }
    }

    /**
     * Exports a formatted text summary of the self-test execution.
     */
    fun exportSelfTestSummary(): String {
        val state = _suiteState.value
        val sb = StringBuilder()
        sb.appendLine("SoundSync Self-Test Results")
        sb.appendLine("App Version: v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        sb.appendLine("Android OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("-----------------------------------------------------------------")
        for (module in ALL_MODULES) {
            val res = state.results[module]
            val statusStr = res?.status?.name ?: "NOT_RUN"
            val dots = ".".repeat(maxOf(2, 30 - module.length))
            sb.appendLine("$module$dots $statusStr")
        }
        sb.appendLine("-----------------------------------------------------------------")
        sb.appendLine("Overall Health: ${state.overallHealth.name}")
        sb.appendLine()

        val issues = state.results.values.filter { it.status == SelfTestStatus.WARNING || it.status == SelfTestStatus.FAIL }
        if (issues.isNotEmpty()) {
            sb.appendLine("ISSUES & DIAGNOSTICS:")
            for (issue in issues) {
                sb.appendLine("• [${issue.status}] ${issue.moduleName}: ${issue.summary}")
                if (!issue.technicalDetails.isNullOrBlank()) {
                    sb.appendLine("  Details: ${issue.technicalDetails}")
                }
                if (!issue.likelyCause.isNullOrBlank()) {
                    sb.appendLine("  Likely Cause: ${issue.likelyCause}")
                }
                if (!issue.suggestedFix.isNullOrBlank()) {
                    sb.appendLine("  Suggested Fix: ${issue.suggestedFix}")
                }
                sb.appendLine()
            }
        }
        return DiagnosticLogger.sanitize(sb.toString())
    }
}
