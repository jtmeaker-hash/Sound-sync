package com.example

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.os.StrictMode
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainDjScreen
import com.example.ui.MainDjViewModel
import com.example.ui.theme.SoundSyncTheme
import com.example.ui.theme.ThemeMode
import com.example.util.DjLogger

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var activeViewModel: MainDjViewModel? = null
    
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        com.example.scheduling.AdaptiveWorkScheduler.reportUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.example.diagnostics.PerformanceDiagnostics.startTiming("MainActivity_onCreate")
        DjLogger.startTiming("APP_START", "SoundSync cold launch")
        super.onCreate(savedInstanceState)
        logHistoricalProcessExitReasons()
        Log.d(TAG, "onCreate: Activity starting up. Auto-playback is strictly prohibited.")

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }

        enableEdgeToEdge()

        setContent {
            val viewModel: MainDjViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsState()
            val proDarkVariant by viewModel.proDarkVariant.collectAsState()
            val libraryDensity by viewModel.libraryDensity.collectAsState()

            SoundSyncTheme(
                themeMode = themeMode,
                proDarkVariant = proDarkVariant,
                libraryDensity = libraryDensity
            ) {
                activeViewModel = viewModel

                val isCarModeActive by viewModel.carModeManager.isCarModeActive.collectAsState()
                val keepScreenAwake by viewModel.carModeManager.keepScreenAwake.collectAsState()

                LaunchedEffect(isCarModeActive, keepScreenAwake) {
                    if (isCarModeActive && keepScreenAwake) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                // Permission Launcher
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions[Manifest.permission.READ_MEDIA_AUDIO] == true
                    } else {
                        permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true || permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
                    }
                    Log.d(TAG, "Storage permission result: storageGranted=$storageGranted")
                    viewModel.onPermissionResult(storageGranted)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissions[Manifest.permission.BLUETOOTH_CONNECT]?.let { btGranted ->
                            Log.d(TAG, "Bluetooth permission result: btGranted=$btGranted")
                            viewModel.onBluetoothPermissionResult(btGranted)
                        }
                    }
                    viewModel.markStartupPermissionsRequested()
                }

                // Handle incoming OAuth callback URIs or shared song links, and check startup permissions
                LaunchedEffect(Unit) {
                    processIncomingIntent(intent, viewModel)

                    if (!viewModel.isStartupPermissionsRequested()) {
                        val neededPermissions = mutableListOf<String>()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                neededPermissions.add(Manifest.permission.READ_MEDIA_AUDIO)
                            }
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                neededPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                        }
                        if (neededPermissions.isNotEmpty()) {
                            Log.d(TAG, "Requesting initial startup permissions: $neededPermissions")
                            permissionLauncher.launch(neededPermissions.toTypedArray())
                        } else {
                            viewModel.markStartupPermissionsRequested()
                        }
                    }
                }

                // Scoped Storage MediaStore Write Permission Launcher (Android 10+ / 11+)
                val writePermissionRequest by viewModel.pendingWritePermissionRequest.collectAsState()
                val writePermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    val isGranted = result.resultCode == Activity.RESULT_OK
                    Log.d(TAG, "MediaStore write permission result: isGranted=$isGranted")
                    viewModel.onWritePermissionResult(isGranted)
                }

                LaunchedEffect(writePermissionRequest) {
                    val req = writePermissionRequest
                    if (req != null) {
                        try {
                            writePermissionLauncher.launch(
                                IntentSenderRequest.Builder(req.intentSender).build()
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to launch write permission dialog: ${e.message}", e)
                            viewModel.onWritePermissionResult(false)
                        }
                    }
                }

                // Storage Access Framework (SAF) Folder Picker Launcher
                val safFolderLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri: Uri? ->
                    if (uri != null) {
                        Log.d(TAG, "SAF folder selected: $uri")
                        viewModel.importSafFolder(uri)
                    }
                }

                // Dedicated SAF Folder Permission Grant Launcher (for bulk tag embedding)
                val folderPermissionRequest by viewModel.pendingFolderPermissionRequest.collectAsState()
                val folderGrantLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri: Uri? ->
                    Log.d(TAG, "SAF folder permission granted for embedding: $uri")
                    viewModel.onFolderPermissionResult(uri)
                }

                if (folderPermissionRequest != null) {
                    val req = folderPermissionRequest!!
                    AlertDialog(
                        onDismissRequest = { viewModel.onFolderPermissionResult(null) },
                        title = { Text("Storage Permission Required") },
                        text = {
                            Text(
                                "SoundSync needs permission to modify the music files in '${req.folderDisplayName}' (${req.affectedTrackCount} tracks).\n\n" +
                                "Select this folder once to grant persistent write access so metadata can be embedded directly into your audio files without repeated prompts."
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    try {
                                        folderGrantLauncher.launch(null)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to launch folder picker: ${e.message}", e)
                                        viewModel.onFolderPermissionResult(null)
                                    }
                                }
                            ) {
                                Text("Select Folder")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.onFolderPermissionResult(null) }) {
                                Text("Skip / Library Only")
                            }
                        }
                    )
                }

                // Multiple Audio Files Picker Launcher
                val audioFilesPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
                ) { uris: List<Uri> ->
                    if (uris.isNotEmpty()) {
                        Log.d(TAG, "Importing ${uris.size} audio files selected by user")
                        viewModel.importAudioFiles(uris)
                    }
                }

                MainDjScreen(
                    viewModel = viewModel,
                    onRequestStoragePermission = {
                        val perms = mutableListOf<String>()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                        }
                        permissionLauncher.launch(perms.toTypedArray())
                    },
                    onPickSafFolder = {
                        safFolderLauncher.launch(null)
                    },
                    onPickAudioFiles = {
                        audioFilesPickerLauncher.launch(
                            arrayOf(
                                "audio/*",
                                "audio/mpeg",
                                "audio/mp3",
                                "audio/flac",
                                "audio/wav",
                                "audio/x-wav",
                                "audio/aac",
                                "audio/m4a",
                                "audio/ogg",
                                "application/ogg"
                            )
                        )
                    }
                )
            }
        }
        DjLogger.endTiming("APP_START", "UI content set complete")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        activeViewModel?.let { vm ->
            processIncomingIntent(intent, vm)
        }
    }

    private fun processIncomingIntent(incomingIntent: Intent?, viewModel: MainDjViewModel) {
        if (incomingIntent == null) return
        if (incomingIntent.action == "com.example.carmode.ACTION_LAUNCH_CAR_MODE") {
            Log.d(TAG, "Received ACTION_LAUNCH_CAR_MODE intent from Bluetooth trigger")
            viewModel.carModeManager.enterCarMode(manual = false)
        } else if (incomingIntent.action == Intent.ACTION_SEND && incomingIntent.type?.startsWith("text/") == true) {
            val sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
                ?: incomingIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val subject = incomingIntent.getStringExtra(Intent.EXTRA_SUBJECT)
            if (!sharedText.isNullOrBlank()) {
                Log.d(TAG, "Received shared text intent for Song Find: $sharedText")
                viewModel.handleIncomingSharedText(sharedText, subject)
            }
        } else {
            incomingIntent.data?.let { uri ->
                Log.d(TAG, "Received deep link URI: $uri")
                viewModel.handleDeepLinkUri(uri)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: Activity visible (audio remains in its current user-commanded state).")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Activity in foreground.")
        activeViewModel?.refreshPermissions()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: Activity losing focus.")
        try {
            com.example.state.PersistentSessionManager.getInstance(this).flushImmediate()
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Activity in background.")
        try {
            com.example.state.PersistentSessionManager.getInstance(this).flushImmediate()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Activity destroyed (isFinishing=$isFinishing).")
        if (isFinishing) {
            if (activeViewModel?.audioEngine?.isPlaying?.value != true) {
                com.example.service.MediaPlaybackService.stopService(applicationContext)
            }
        }
        activeViewModel = null
    }

    private fun logHistoricalProcessExitReasons() {
        try {
            val crashFile = java.io.File(filesDir, "crashes/last_crash.txt")
            if (crashFile.exists() && crashFile.canRead()) {
                val dump = crashFile.readText()
                Log.e(TAG, "PREVIOUS FATAL CRASH DUMP FOUND:\n$dump")
            }
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val reasons = am?.getHistoricalProcessExitReasons(packageName, 0, 5)
                if (!reasons.isNullOrEmpty()) {
                    reasons.forEach { exitInfo ->
                        val reasonStr = when (exitInfo.reason) {
                            android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
                            android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH_APP"
                            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                            android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
                            android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
                            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                            android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
                            android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                            android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                            android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                            else -> "UNKNOWN(${exitInfo.reason})"
                        }
                        Log.w(TAG, "Historical Process Exit: reason=$reasonStr, status=${exitInfo.status}, desc=${exitInfo.description}, pss=${exitInfo.pss}KB, rss=${exitInfo.rss}KB, timestamp=${exitInfo.timestamp}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query historical process exit reasons: ${e.message}")
            }
        }
    }
}
