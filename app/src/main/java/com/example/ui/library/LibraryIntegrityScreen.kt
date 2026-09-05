package com.example.ui.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppDatabase
import com.example.data.integrity.DatabaseIntegrityChecker
import com.example.data.integrity.IntegrityIssue
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryIntegrityScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }
    val checker = remember { DatabaseIntegrityChecker(context, database) }

    val report by checker.reportFlow.collectAsState()

    LaunchedEffect(Unit) {
        checker.scanIntegrity()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Database Integrity Checker", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { coroutineScope.launch { checker.scanIntegrity() } },
                        enabled = !report.isScanning
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = DeckACyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DjObsidian)
            )
        },
        containerColor = DjObsidian
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("INTEGRITY STATUS", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (report.isScanning) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = DeckACyan, strokeWidth = 2.dp)
                                Text("Scanning...", color = DeckACyan, fontSize = 11.sp)
                            }
                        } else {
                            val allGood = report.issues.isEmpty()
                            Text(if (allGood) "ALL HEALTHY" else "${report.issues.size} ISSUES FOUND", color = if (allGood) NeonGreen else NeonAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IntegrityStatMini("Healthy", "${report.healthyTracks}", NeonGreen)
                        IntegrityStatMini("Missing", "${report.missingFilesCount}", NeonRed)
                        IntegrityStatMini("Duplicates", "${report.duplicateRecordsCount}", DeckBPink)
                        IntegrityStatMini("Corrupt Meta", "${report.brokenMetadataCount}", NeonAmber)
                    }

                    // Repair Action Button
                    if (report.autoRepairableCount > 0 && !report.isScanning) {
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val count = checker.repairSafeIssues()
                                    Toast.makeText(context, "Safely repaired $count issues", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null, tint = DjObsidian, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Auto-Repair Safe Issues (${report.autoRepairableCount})", color = DjObsidian, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Issue List
            Text("DETECTED ANOMALIES", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)

            if (report.issues.isEmpty() && !report.isScanning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Verified, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No Integrity Issues", color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text("All tracks, files, and references are consistent.", color = TextMuted, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(report.issues, key = { it.id }) { issue ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (issue.isAutoRepairable) Icons.Default.BuildCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (issue.isAutoRepairable) DeckACyan else NeonAmber,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(issue.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(issue.description, color = TextSecondary, fontSize = 11.sp)
                                }
                                if (issue.isAutoRepairable) {
                                    Text("Safe Repair", color = DeckACyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntegrityStatMini(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}
