package com.example.ui.diagnostics

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.diagnostics.DiagnosticReportExporter
import com.example.diagnostics.OverallHealth
import com.example.diagnostics.SelfTestResult
import com.example.diagnostics.SelfTestRunner
import com.example.diagnostics.SelfTestStatus
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBOrange
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun SelfTestScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { SelfTestRunner(context) }
    val suiteState by runner.suiteState.collectAsState()

    val expandedModules = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DjObsidian)
    ) {
        // ── TOP BAR ──────────────────────────────────────────────────────────
        Surface(
            color = DjSurfaceDark,
            border = BorderStroke(0.5.dp, DjSurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "SOUNDSYNC SELF-TEST",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = DeckACyan.copy(alpha = 0.2f),
                                border = BorderStroke(0.5.dp, DeckACyan)
                            ) {
                                Text(
                                    "11 CHECKS",
                                    color = DeckACyan,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            "Subsystem integrity & validation runner",
                            color = TextMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { scope.launch { runner.runAllTests() } },
                        enabled = !suiteState.isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeckACyan,
                            contentColor = DjObsidian,
                            disabledContainerColor = DjSurfaceElevated,
                            disabledContentColor = TextMuted
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(28.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        if (suiteState.isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = DjObsidian)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Running...", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Run All Tests", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    IconButton(
                        onClick = {
                            val text = runner.exportSelfTestSummary()
                            DiagnosticReportExporter.shareReport(context, text)
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share results", tint = TextPrimary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (suiteState.isRunning) {
            LinearProgressIndicator(
                progress = { suiteState.progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = DeckACyan,
                trackColor = DjSurfaceBorder
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── OVERALL HEALTH BANNER ─────────────────────────────────────────
            item {
                OverallHealthCard(
                    overallHealth = suiteState.overallHealth,
                    completedCount = suiteState.completedCount,
                    totalCount = suiteState.totalCount,
                    isRunning = suiteState.isRunning
                )
            }

            // ── 11 TEST MODULE ITEMS ──────────────────────────────────────────
            items(SelfTestRunner.ALL_MODULES) { moduleName ->
                val result = suiteState.results[moduleName] ?: SelfTestResult(
                    moduleName = moduleName,
                    status = SelfTestStatus.IDLE,
                    summary = "Not yet executed",
                    technicalDetails = "Tap to run check"
                )
                val isExpanded = expandedModules[moduleName] ?: (result.status == SelfTestStatus.WARNING || result.status == SelfTestStatus.FAIL)

                ModuleTestCard(
                    result = result,
                    isExpanded = isExpanded,
                    onToggleExpand = { expandedModules[moduleName] = !isExpanded },
                    onRetry = { scope.launch { runner.runSingleTest(moduleName) } }
                )
            }

            // ── EXPORT BUTTONS ────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val text = runner.exportSelfTestSummary()
                            DiagnosticReportExporter.copyToClipboard(context, text)
                            Toast.makeText(context, "Self-Test results copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Results", fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            val text = runner.exportSelfTestSummary()
                            DiagnosticReportExporter.shareReport(context, text)
                        },
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DeckACyan, contentColor = DjObsidian)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export Self-Test", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverallHealthCard(
    overallHealth: OverallHealth,
    completedCount: Int,
    totalCount: Int,
    isRunning: Boolean
) {
    val (label, color, description) = when (overallHealth) {
        OverallHealth.GOOD -> Triple("GOOD", Color(0xFF10B981), "All critical and standard audio subsystems are verified and fully operational.")
        OverallHealth.WARNING -> Triple("WARNING (MINOR)", DeckBOrange, "Minor non-critical warnings detected (e.g. offline metadata or update checks).")
        OverallHealth.DEGRADED -> Triple("DEGRADED", DeckBOrange, "One or more non-critical subsystems encountered failures.")
        OverallHealth.CRITICAL -> Triple("CRITICAL", Color(0xFFEF4444), "Critical failure detected in database or audio decoder.")
    }

    Surface(
        color = DjSurfaceDark,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Overall Health:", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = color.copy(alpha = 0.15f),
                        border = BorderStroke(0.5.dp, color)
                    ) {
                        Text(
                            label,
                            color = color,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(description, color = TextSecondary, fontSize = 10.sp, lineHeight = 13.sp)
            }

            Column(horizontalAlignment = Alignment.End) {
                Text("$completedCount / $totalCount", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(if (isRunning) "Running" else "Completed", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ModuleTestCard(
    result: SelfTestResult,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onRetry: () -> Unit
) {
    val (statusColor, icon) = when (result.status) {
        SelfTestStatus.PASS -> Pair(Color(0xFF10B981), Icons.Default.CheckCircle)
        SelfTestStatus.WARNING -> Pair(DeckBOrange, Icons.Default.Warning)
        SelfTestStatus.FAIL -> Pair(Color(0xFFEF4444), Icons.Default.Close)
        SelfTestStatus.RUNNING -> Pair(DeckACyan, Icons.Default.Refresh)
        SelfTestStatus.IDLE, SelfTestStatus.SKIPPED -> Pair(TextMuted, Icons.Default.PlayArrow)
    }

    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        border = BorderStroke(0.5.dp, if (result.status == SelfTestStatus.FAIL) Color(0xFFEF4444).copy(alpha = 0.4f) else DjSurfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (result.status == SelfTestStatus.RUNNING) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = DeckACyan)
                    } else {
                        Icon(icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(16.dp))
                    }

                    Column {
                        Text(
                            result.moduleName,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            result.summary,
                            color = if (result.status == SelfTestStatus.FAIL) Color(0xFFEF4444) else TextMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 1
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = statusColor.copy(alpha = 0.15f),
                        border = BorderStroke(0.5.dp, statusColor)
                    ) {
                        Text(
                            result.status.name,
                            color = statusColor,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(if (isExpanded) 90f else 0f)
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    HorizontalDivider(color = DjSurfaceBorder, thickness = 0.5.dp, modifier = Modifier.padding(bottom = 6.dp))

                    if (!result.technicalDetails.isNullOrBlank()) {
                        Text("Technical Details:", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text(result.technicalDetails, color = TextSecondary, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (!result.likelyCause.isNullOrBlank()) {
                        Text("Likely Cause:", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text(result.likelyCause, color = DeckBOrange, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (!result.suggestedFix.isNullOrBlank()) {
                        Text("Suggested Fix:", color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        Text(result.suggestedFix, color = DeckACyan, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (result.durationMs > 0) "Duration: ${result.durationMs}ms" else "",
                            color = TextMuted,
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.height(26.dp),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(10.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry Test", fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}
