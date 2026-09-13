package com.example.ui.doctor

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.doctor.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryDoctorScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val auditor = remember { LibraryDoctorAuditor(context) }
    val repairManager = remember { LibraryDoctorRepairManager(context) }

    val auditReport by auditor.auditReportFlow.collectAsState()

    var selectedFilter by remember { mutableStateOf<String>("ALL") }
    var selectedCategoryFilter by remember { mutableStateOf<DoctorCategory?>(null) }
    var selectedIssueForDetail by remember { mutableStateOf<DoctorIssue?>(null) }
    var destructiveConfirmationIssue by remember { mutableStateOf<DoctorIssue?>(null) }
    var bpmAdjustmentIssue by remember { mutableStateOf<DoctorIssue?>(null) }
    var isRepairing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        auditor.runAudit()
    }

    val displayedIssues = remember(auditReport.issues, selectedFilter, selectedCategoryFilter) {
        auditReport.issues.filter { issue ->
            val matchesCategory = selectedCategoryFilter == null || issue.category == selectedCategoryFilter
            val matchesFilter = when (selectedFilter) {
                "ALL" -> issue.reviewStatus == DoctorReviewStatus.OPEN
                "SAFE" -> issue.reviewStatus == DoctorReviewStatus.OPEN && issue.isSafeAutoRepair
                "NEEDS_REVIEW" -> issue.reviewStatus == DoctorReviewStatus.NEEDS_REVIEW || (!issue.isSafeAutoRepair && issue.reviewStatus == DoctorReviewStatus.OPEN)
                "IGNORED" -> issue.reviewStatus == DoctorReviewStatus.IGNORED
                "FIXED" -> issue.reviewStatus == DoctorReviewStatus.FIXED
                else -> true
            }
            matchesCategory && matchesFilter
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Healing, contentDescription = null, tint = DeckACyan, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("SoundSync Library Doctor", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                auditor.runAudit()
                            }
                        },
                        enabled = !auditReport.isAuditing && !isRepairing
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Re-audit Library", tint = DeckACyan)
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
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── HEALTH SCORE & SUMMARY CARD ──────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
                border = BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("LIBRARY HEALTH SCORE", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            val score = auditReport.summary.healthScore
                            val scoreColor = when {
                                score >= 80 -> NeonGreen
                                score >= 50 -> NeonAmber
                                else -> NeonRed
                            }
                            Text(
                                text = "$score%",
                                color = scoreColor,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            if (auditReport.isAuditing) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = DeckACyan, strokeWidth = 2.dp)
                                    Text("Auditing Library...", color = DeckACyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                                Text(
                                    text = auditReport.statusMessage,
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Text("${auditReport.summary.totalTracks} Tracks Indexed", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("${auditReport.summary.totalIssues} Issues Detected", color = if (auditReport.summary.totalIssues == 0) NeonGreen else NeonAmber, fontSize = 11.sp)
                            }
                        }
                    }

                    if (auditReport.isAuditing) {
                        LinearProgressIndicator(
                            progress = { auditReport.auditProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = DeckACyan,
                            trackColor = DjSurfaceDark
                        )
                    }

                    // Key Metrics Counter Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DoctorMetricBadge("Complete", "${auditReport.summary.completeTracks}", NeonGreen)
                        DoctorMetricBadge("Needs Review", "${auditReport.summary.needsReviewCount}", NeonAmber)
                        DoctorMetricBadge("Missing Files", "${auditReport.summary.missingFilesCount}", NeonRed)
                        DoctorMetricBadge("Failed Jobs", "${auditReport.summary.failedAnalysisCount}", NeonRed)
                        DoctorMetricBadge("Missing Art", "${auditReport.summary.missingArtworkCount}", DeckBPink)
                    }

                    // Prominent Fix All Safe Issues Button
                    if (auditReport.summary.safeAutoRepairCount > 0 && !auditReport.isAuditing) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isRepairing = true
                                    val result = repairManager.repairSafeIssues(auditReport.issues)
                                    isRepairing = false
                                    Toast.makeText(
                                        context,
                                        "Repaired & Queued in Brain: ${result.repairedCount} safe issues",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    auditor.runAudit()
                                }
                            },
                            enabled = !isRepairing,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isRepairing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = DjObsidian, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Repairing Through Library Brain...", color = DjObsidian, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.Build, contentDescription = null, tint = DjObsidian, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Fix All Safe Issues (${auditReport.summary.safeAutoRepairCount})", color = DjObsidian, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // ── STATUS FILTER TABS ──────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DoctorFilterChip("All Issues", selectedFilter == "ALL") { selectedFilter = "ALL" }
                DoctorFilterChip("Safe to Fix", selectedFilter == "SAFE") { selectedFilter = "SAFE" }
                DoctorFilterChip("Needs Review", selectedFilter == "NEEDS_REVIEW") { selectedFilter = "NEEDS_REVIEW" }
                DoctorFilterChip("Ignored", selectedFilter == "IGNORED") { selectedFilter = "IGNORED" }
                DoctorFilterChip("Fixed", selectedFilter == "FIXED") { selectedFilter = "FIXED" }
            }

            // ── CATEGORY FILTER CHIPS ───────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DoctorCategoryChip(
                    title = "All Categories",
                    count = auditReport.issues.size,
                    isSelected = selectedCategoryFilter == null,
                    onClick = { selectedCategoryFilter = null }
                )
                for (cat in DoctorCategory.values()) {
                    val count = auditReport.summary.categoryCounts[cat] ?: 0
                    if (count > 0) {
                        DoctorCategoryChip(
                            title = cat.displayName,
                            count = count,
                            isSelected = selectedCategoryFilter == cat,
                            onClick = { selectedCategoryFilter = cat }
                        )
                    }
                }
            }

            // ── ISSUE LIST HEADER ───────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "IDENTIFIED ISSUES (${displayedIssues.size})",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                if (selectedCategoryFilter != null) {
                    Text(
                        text = "Clear filter",
                        color = DeckACyan,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { selectedCategoryFilter = null }
                    )
                }
            }

            // ── ISSUES LIST ─────────────────────────────────────
            if (displayedIssues.isEmpty() && !auditReport.isAuditing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(48.dp))
                        Text("No Issues in This Category", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("All audited library criteria are healthy and consistent.", color = TextMuted, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displayedIssues, key = { it.id }) { issue ->
                        DoctorIssueCard(
                            issue = issue,
                            onSafeFix = {
                                coroutineScope.launch {
                                    val ok = repairManager.repairSingleIssue(issue)
                                    if (ok) {
                                        Toast.makeText(context, "Repaired via Library Brain: ${issue.trackTitle}", Toast.LENGTH_SHORT).show()
                                        auditor.runAudit()
                                    }
                                }
                            },
                            onDestructiveAction = {
                                destructiveConfirmationIssue = issue
                            },
                            onAdjustBpm = {
                                bpmAdjustmentIssue = issue
                            },
                            onIgnore = {
                                repairManager.ignoreIssue(issue.id)
                                coroutineScope.launch { auditor.runAudit() }
                            },
                            onMarkReviewed = {
                                repairManager.markReviewed(issue.id)
                                coroutineScope.launch { auditor.runAudit() }
                            },
                            onShowDetail = {
                                selectedIssueForDetail = issue
                            }
                        )
                    }
                }
            }
        }
    }

    // ── DESTRUCTIVE CONFIRMATION DIALOG ─────────────────────────
    destructiveConfirmationIssue?.let { issue ->
        AlertDialog(
            onDismissRequest = { destructiveConfirmationIssue = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = NeonRed)
                    Spacer(Modifier.width(8.dp))
                    Text("Confirm Permanent Action", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Are you sure you want to perform this operation? This will modify or remove library data:",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Text("Track: ${issue.trackTitle}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Problem: ${issue.problem}", color = TextMuted, fontSize = 12.sp)
                    Text("Action: ${issue.recommendedAction}", color = NeonAmber, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val trackId = issue.trackId
                            if (trackId != null) {
                                when (issue.category) {
                                    DoctorCategory.DUPLICATE_TRACKS -> {
                                        repairManager.deleteDuplicateTrack(trackId, issue.filePath, deletePhysicalFile = false, issueId = issue.id)
                                    }
                                    DoctorCategory.BROKEN_FILE_PATHS, DoctorCategory.MISSING_FILES -> {
                                        repairManager.removeStaleDatabaseEntry(trackId, issue.id)
                                    }
                                    DoctorCategory.INCONSISTENT_ALBUMS -> {
                                        issue.proposedValue?.let { target ->
                                            repairManager.standardizeAlbum(issue.artist, issue.currentValue.orEmpty(), target, issue.id)
                                        }
                                    }
                                    else -> {}
                                }
                            }
                            destructiveConfirmationIssue = null
                            auditor.runAudit()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonRed)
                ) {
                    Text("Confirm & Execute", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { destructiveConfirmationIssue = null }) {
                    Text("Cancel", color = TextPrimary)
                }
            },
            containerColor = DjSurfaceDark
        )
    }

    // ── BPM ADJUSTMENT DIALOG ────────────────────────────────────
    bpmAdjustmentIssue?.let { issue ->
        val currentBpm = issue.currentValue?.replace(" BPM", "")?.toDoubleOrNull() ?: 120.0
        AlertDialog(
            onDismissRequest = { bpmAdjustmentIssue = null },
            title = { Text("Correct Suspicious BPM", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Track: ${issue.trackTitle}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Current BPM: ${String.format("%.1f", currentBpm)}", color = TextSecondary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    issue.trackId?.let { tid ->
                                        repairManager.updateTrackBpm(tid, currentBpm / 2.0, issue.id)
                                    }
                                    bpmAdjustmentIssue = null
                                    auditor.runAudit()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceCard)
                        ) {
                            Text("Half (${String.format("%.1f", currentBpm / 2.0)})", color = DeckACyan)
                        }
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    issue.trackId?.let { tid ->
                                        repairManager.updateTrackBpm(tid, currentBpm * 2.0, issue.id)
                                    }
                                    bpmAdjustmentIssue = null
                                    auditor.runAudit()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceCard)
                        ) {
                            Text("Double (${String.format("%.1f", currentBpm * 2.0)})", color = DeckACyan)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            issue.trackId?.let { tid ->
                                // Re-analyse via Brain
                                com.example.brain.LibraryBrain.getInstance(context).requestCategoryRepairForTrack(tid, com.example.brain.BrainCategory.BPM_KEY)
                            }
                            bpmAdjustmentIssue = null
                            auditor.runAudit()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
                ) {
                    Text("Re-analyse in Brain", color = DjObsidian, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { bpmAdjustmentIssue = null }) {
                    Text("Cancel", color = TextPrimary)
                }
            },
            containerColor = DjSurfaceDark
        )
    }

    // ── ISSUE DETAIL MODAL ───────────────────────────────────────
    selectedIssueForDetail?.let { issue ->
        AlertDialog(
            onDismissRequest = { selectedIssueForDetail = null },
            title = {
                Column {
                    Text(issue.category.displayName, color = DeckACyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(issue.problem, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailRow("Track", issue.trackTitle)
                    DetailRow("Artist", issue.artist)
                    DetailRow("Album", issue.album)
                    DetailRow("File Path", issue.filePath)
                    DetailRow("Severity", issue.severity.name)
                    DetailRow("Confidence", "${(issue.confidence * 100).toInt()}%")
                    DetailRow("Evidence", issue.evidence)
                    DetailRow("Recommended Action", issue.recommendedAction)
                    issue.currentValue?.let { DetailRow("Current Value", it) }
                    issue.proposedValue?.let { DetailRow("Proposed Value", it) }
                }
            },
            confirmButton = {
                Button(onClick = { selectedIssueForDetail = null }) {
                    Text("Close", color = TextPrimary)
                }
            },
            containerColor = DjSurfaceDark
        )
    }
}

@Composable
fun DoctorIssueCard(
    issue: DoctorIssue,
    onSafeFix: () -> Unit,
    onDestructiveAction: () -> Unit,
    onAdjustBpm: () -> Unit,
    onIgnore: () -> Unit,
    onMarkReviewed: () -> Unit,
    onShowDetail: () -> Unit
) {
    val severityColor = when (issue.severity) {
        DoctorIssueSeverity.CRITICAL -> NeonRed
        DoctorIssueSeverity.ERROR -> NeonRed
        DoctorIssueSeverity.WARNING -> NeonAmber
        DoctorIssueSeverity.INFO -> DeckACyan
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onShowDetail() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
        border = BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(severityColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = issue.category.displayName,
                        color = severityColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "${(issue.confidence * 100).toInt()}% conf",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = issue.trackTitle,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = issue.problem,
                color = NeonAmber,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = issue.evidence,
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onIgnore) {
                    Text("Ignore", color = TextMuted, fontSize = 11.sp)
                }

                if (issue.category == DoctorCategory.SUSPICIOUS_BPM) {
                    Button(
                        onClick = onAdjustBpm,
                        colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Fix BPM", color = DjObsidian, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (issue.isSafeAutoRepair) {
                    Button(
                        onClick = onSafeFix,
                        colors = ButtonDefaults.buttonColors(containerColor = DeckACyan),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, tint = DjObsidian, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Fix via Brain", color = DjObsidian, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onDestructiveAction,
                        colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceElevated),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Review Action", color = NeonAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun DoctorMetricBadge(label: String, count: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = count, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(text = label, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
fun DoctorFilterChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) DeckACyan else DjSurfaceCard,
        border = BorderStroke(1.dp, if (isSelected) DeckACyan else DjSurfaceBorder)
    ) {
        Text(
            text = label,
            color = if (isSelected) DjObsidian else TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun DoctorCategoryChip(title: String, count: Int, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) DjSurfaceElevated else DjSurfaceDark,
        border = BorderStroke(1.dp, if (isSelected) DeckACyan else DjSurfaceBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = title, color = if (isSelected) DeckACyan else TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(text = "($count)", color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(text = label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(text = value, color = TextPrimary, fontSize = 12.sp)
    }
}
