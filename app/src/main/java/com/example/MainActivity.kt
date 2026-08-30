package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainDjScreen
import com.example.ui.MainDjViewModel
import com.example.ui.theme.SoundSyncTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity starting up. Auto-playback is strictly prohibited.")
        enableEdgeToEdge()

        setContent {
            SoundSyncTheme {
                val viewModel: MainDjViewModel = viewModel()

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
        Log.d(TAG, "onDestroy: Activity destroyed.")
    }
}
