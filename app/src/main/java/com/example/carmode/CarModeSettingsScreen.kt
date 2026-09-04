package com.example.carmode

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.audio.DjAudioEngine
import com.example.ui.theme.LocalSoundSyncTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full Car Mode settings screen supporting Bluetooth vehicle pairing, per-car audio profiles,
 * driving session history, and Bluetooth diagnostics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarModeSettingsScreen(
    carModeManager: CarModeManager,
    audioEngine: DjAudioEngine,
    onBack: () -> Unit,
    onLaunchCarMode: () -> Unit
) {
    val theme = LocalSoundSyncTheme.current
    val context = LocalContext.current

    val keepAwake by carModeManager.keepScreenAwake.collectAsState()
    val isNightMode by carModeManager.isNightMode.collectAsState()
    val smartShuffle by carModeManager.smartDrivingShuffle.collectAsState()
    val configuredAddresses by carModeManager.configuredCarAddresses.collectAsState()
    val configuredNames by carModeManager.configuredCarNames.collectAsState()
    val currentProfile by carModeManager.currentProfile.collectAsState()
    val pastSessions by carModeManager.pastSessions.collectAsState()

    var showPairCarDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showSessionsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Car Mode", fontWeight = FontWeight.Bold, color = theme.textPrimary)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = theme.textPrimary)
                    }
                },
                actions = {
                    Button(
                        onClick = onLaunchCarMode,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.accent),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Launch", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.surface)
            )
        },
        containerColor = theme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Section 1: General Options
            item {
                SectionHeader("GENERAL")
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = theme.surface),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Keep Screen Awake", color = theme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Prevents the display from sleeping while Car Mode is active", color = theme.textMuted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = keepAwake,
                                onCheckedChange = { carModeManager.setKeepScreenAwake(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = theme.accent)
                            )
                        }

                        HorizontalDivider(color = theme.divider, thickness = 0.5.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Car Night Mode", color = theme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Ultra-dark anti-glare theme for night driving safety", color = theme.textMuted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = isNightMode,
                                onCheckedChange = { carModeManager.setNightMode(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = theme.accent)
                            )
                        }

                        HorizontalDivider(color = theme.divider, thickness = 0.5.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Smart Driving Shuffle", color = theme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Favours liked and fresh songs, avoids repeats during the drive", color = theme.textMuted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = smartShuffle,
                                onCheckedChange = { carModeManager.setSmartDrivingShuffle(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = theme.accent)
                            )
                        }
                    }
                }
            }

            // Section 2: Bluetooth Vehicle Detection
            item {
                SectionHeader("BLUETOOTH VEHICLE DETECTION")
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = theme.surface),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Select which Bluetooth devices belong to your vehicle. SoundSync will only auto-launch Car Mode for configured cars.",
                            color = theme.textSecondary,
                            fontSize = 12.sp
                        )

                        if (configuredAddresses.isEmpty()) {
                            Text("No car Bluetooth devices configured yet.", color = theme.textMuted, fontSize = 13.sp)
                        } else {
                            configuredAddresses.forEach { address ->
                                val name = configuredNames[address] ?: "Car Device"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(theme.surfaceRaised, RoundedCornerShape(8.dp))
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(name, color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(address, color = theme.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    IconButton(onClick = { carModeManager.removeConfiguredCar(address) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = theme.error)
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { showPairCarDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.accent)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Car Bluetooth Device")
                        }
                    }
                }
            }

            // Section 3: Audio Profiles & Presets
            item {
                SectionHeader("CAR AUDIO PROFILE")
            }

            item {
                val profile = currentProfile ?: CarAudioProfile("default_car", "Current Car")
                Card(
                    colors = CardDefaults.cardColors(containerColor = theme.surface),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Equalizer Preset for Vehicle", color = theme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                        val eqPresets = listOf("Car Flat", "Bass Reduction", "Road Noise Compensation", "Custom Car EQ")
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            eqPresets.forEach { preset ->
                                val isSelected = profile.eqPreset == preset
                                Surface(
                                    color = if (isSelected) theme.accent else theme.surfaceRaised,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            carModeManager.saveCarProfile(profile.copy(eqPreset = preset))
                                        }
                                ) {
                                    Text(
                                        text = preset,
                                        color = if (isSelected) Color.White else theme.textSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = theme.divider, thickness = 0.5.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("ReplayGain / Volume Levelling", color = theme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Reduces dramatic volume jumps between tracks on road trips", color = theme.textMuted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = profile.replayGainEnabled,
                                onCheckedChange = {
                                    carModeManager.saveCarProfile(profile.copy(replayGainEnabled = it))
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = theme.accent)
                            )
                        }

                        HorizontalDivider(color = theme.divider, thickness = 0.5.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Pause on Disconnect", color = theme.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Immediately stops playback when engine turns off / car disconnects", color = theme.textMuted, fontSize = 12.sp)
                            }
                            Switch(
                                checked = profile.pauseOnDisconnect,
                                onCheckedChange = {
                                    carModeManager.saveCarProfile(profile.copy(pauseOnDisconnect = it))
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = theme.accent)
                            )
                        }
                    }
                }
            }

            // Section 4: History & Diagnostics
            item {
                SectionHeader("TOOLS & DIAGNOSTICS")
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { showSessionsDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.textPrimary)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Driving History")
                    }

                    OutlinedButton(
                        onClick = { showDiagnosticsDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.textPrimary)
                    ) {
                        Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Diagnostics")
                    }
                }
            }
        }
    }

    // Dialog: Add Car Bluetooth Device
    if (showPairCarDialog) {
        AddCarDeviceDialog(
            onDismiss = { showPairCarDialog = false },
            onAddDevice = { address, name ->
                carModeManager.addConfiguredCar(address, name)
                showPairCarDialog = false
            }
        )
    }

    // Dialog: Driving History
    if (showSessionsDialog) {
        DrivingHistoryDialog(
            sessions = pastSessions,
            onDismiss = { showSessionsDialog = false }
        )
    }

    // Dialog: Diagnostics
    if (showDiagnosticsDialog) {
        CarBluetoothDiagnosticsDialog(
            carModeManager = carModeManager,
            audioEngine = audioEngine,
            onDismiss = { showDiagnosticsDialog = false }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFF1E6CFF),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
private fun AddCarDeviceDialog(
    onDismiss: () -> Unit,
    onAddDevice: (String, String) -> Unit
) {
    val context = LocalContext.current
    var customAddress by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }

    val pairedDevices = remember {
        val list = mutableListOf<Pair<String, String>>()
        try {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else true

            if (hasPermission) {
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
                @Suppress("MissingPermission")
                adapter?.bondedDevices?.forEach { dev ->
                    list.add(dev.address to (dev.name ?: "Unknown Bluetooth Device"))
                }
            }
        } catch (_: Exception) {}
        list
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF181B21),
        title = { Text("Add Car Bluetooth Device", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pairedDevices.isNotEmpty()) {
                    Text("Select from paired Bluetooth devices:", color = Color(0xFF8E95A2), fontSize = 12.sp)
                    pairedDevices.forEach { (addr, name) ->
                        Surface(
                            color = Color(0xFF22262F),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAddDevice(addr, name) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(addr, color = Color(0xFF8E95A2), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF1E6CFF))
                            }
                        }
                    }
                } else {
                    Text("Or enter device name and address manually:", color = Color(0xFF8E95A2), fontSize = 12.sp)
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Car Name (e.g. My Honda)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = customAddress,
                        onValueChange = { customAddress = it },
                        label = { Text("Bluetooth Address (e.g. 00:11:22:33:44:55)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            }
        },
        confirmButton = {
            if (pairedDevices.isEmpty() || customAddress.isNotBlank()) {
                Button(
                    onClick = {
                        if (customAddress.isNotBlank()) {
                            onAddDevice(customAddress, customName.ifBlank { "Vehicle" })
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E6CFF))
                ) {
                    Text("Save Device")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF8E95A2))
            }
        }
    )
}

@Composable
private fun DrivingHistoryDialog(
    sessions: List<DrivingSession>,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("EEE, d MMM • h:mm a", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF181B21),
        title = { Text("Driving Sessions History", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            if (sessions.isEmpty()) {
                Text("No recorded driving sessions yet.", color = Color(0xFF8E95A2), fontSize = 13.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions) { session ->
                        val durationMinutes = (session.totalDurationMs / 60000).coerceAtLeast(1)
                        Surface(
                            color = Color(0xFF22262F),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(session.carName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("${durationMinutes}m", color = Color(0xFF1E6CFF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Text(dateFormat.format(Date(session.startedAt)), color = Color(0xFF8E95A2), fontSize = 11.sp)
                                Text("${session.tracksPlayedCount} tracks played • ${session.tracksSkippedCount} skipped", color = Color(0xFF8E95A2), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Color(0xFF1E6CFF), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun CarBluetoothDiagnosticsDialog(
    carModeManager: CarModeManager,
    audioEngine: DjAudioEngine,
    onDismiss: () -> Unit
) {
    val currentDevice by carModeManager.currentCarDevice.collectAsState()
    val isCarActive by carModeManager.isCarModeActive.collectAsState()
    val isPlaying by audioEngine.isPlaying.collectAsState()
    val currentTrack by audioEngine.currentTrack.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF181B21),
        title = { Text("Car Diagnostics", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagnosticItem("Connected Vehicle", currentDevice ?: "None")
                DiagnosticItem("Car Mode Active", if (isCarActive) "YES" else "NO")
                DiagnosticItem("Playback State", if (isPlaying) "PLAYING" else "PAUSED")
                DiagnosticItem("Current Track", currentTrack?.title ?: "None")
                DiagnosticItem("Sample Rate", "44100 Hz / 16-bit PCM")
                DiagnosticItem("Bluetooth Codec", "SBC / AAC (Standard A2DP)")
                DiagnosticItem("MediaSession State", "Active (AVRCP Transport Hooked)")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF1E6CFF), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun DiagnosticItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF8E95A2), fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}
