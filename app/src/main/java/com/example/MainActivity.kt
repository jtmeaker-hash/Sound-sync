package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        DjLogger.startTiming("APP_START", "SoundSync cold launch")
        super.onCreate(savedInstanceState)
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
            val libraryDensity by viewModel.libraryDensity.collectAsState()

            SoundSyncTheme(themeMode = themeMode, libraryDensity = libraryDensity) {
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

                // Handle incoming OAuth callback URIs or shared song links
                LaunchedEffect(Unit) {
                    processIncomingIntent(intent, viewModel)
                }

                // Permission Launcher
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val isGranted = permissions.values.any { it }
                    Log.d(TAG, "Storage permission result: isGranted=$isGranted")
                    viewModel.onPermissionResult(isGranted)
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
                        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            arrayOf(
                                Manifest.permission.READ_MEDIA_AUDIO,
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        } else {
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        }
                        permissionLauncher.launch(perms)
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
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: Activity losing focus.")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Activity in background.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Activity destroyed (isFinishing=$isFinishing).")
        if (isFinishing) {
            if (activeViewModel?.audioEngine?.isPlaying?.value != true) {
                com.example.service.MediaPlaybackService.stopService(applicationContext)
            }
        }
    }
}
