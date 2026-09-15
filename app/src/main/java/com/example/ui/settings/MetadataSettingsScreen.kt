package com.example.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.brain.BrainCategory
import com.example.brain.BrainSummary
import com.example.metadata.MetadataSettings
import com.example.metadata.PushMetadataProgress
import com.example.metadata.PushMetadataReport
import com.example.ui.components.LibraryBrainCard
import com.example.ui.components.MetadataEnrichmentSettingsCard
import com.example.ui.theme.DeckACyan

/**
 * Dedicated screen for Metadata & Artwork settings, online enrichment,
 * library analysis coordinator (Brain), and metadata write push tools.
 */
@Composable
fun MetadataSettingsScreen(
    metadataSettings: MetadataSettings,
    brainSummary: BrainSummary = BrainSummary(),
    onPauseBrain: () -> Unit = {},
    onResumeBrain: () -> Unit = {},
    onRetryBrainFailed: () -> Unit = {},
    onAnalyseIncompleteBrain: () -> Unit = {},
    onReanalyseBrainCategory: (BrainCategory) -> Unit = {},
    onCancelBrainWork: () -> Unit = {},
    onSetEnrichmentEnabled: (Boolean) -> Unit = {},
    onSetAppleSearchEnabled: (Boolean) -> Unit = {},
    onSetTheAudioDbEnabled: (Boolean) -> Unit = {},
    onSetBpmAnalysisEnabled: (Boolean) -> Unit = {},
    onSetKeyAnalysisEnabled: (Boolean) -> Unit = {},
    onSetWriteToFileEnabled: (Boolean) -> Unit = {},
    onSetShowProvenanceBadges: (Boolean) -> Unit = {},
    onSetConcurrency: (Int) -> Unit = {},
    onSetBpmRange: (Int, Int) -> Unit = { _, _ -> },
    isPushingMetadata: Boolean = false,
    pushProgress: PushMetadataProgress? = null,
    pushReport: PushMetadataReport? = null,
    onPushMetadataToFiles: () -> Unit = {},
    onCancelPushMetadata: () -> Unit = {},
    onRetryFailedWrites: () -> Unit = {},
    isMdScanning: Boolean = false,
    mdScanProgress: String = "",
    pendingReviewCount: Int = 0,
    onStartMdScan: () -> Unit = {},
    onCancelMdScan: () -> Unit = {},
    onNavigateToReviewInbox: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "METADATA & ARTWORK ENRICHMENT",
                    color = DeckACyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Configure online providers (Apple Music, TheAudioDB), local DSP tag writers, and automated pipeline review.",
                    color = com.example.ui.theme.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // Library Brain Status & Category Analyzer
        item {
            LibraryBrainCard(
                summary = brainSummary,
                onPause = onPauseBrain,
                onResume = onResumeBrain,
                onRetryFailed = onRetryBrainFailed,
                onAnalyseIncomplete = onAnalyseIncompleteBrain,
                onReanalyseCategory = onReanalyseBrainCategory,
                onCancelWork = onCancelBrainWork
            )
        }

        // Metadata Enrichment & File Writing Configuration
        item {
            MetadataEnrichmentSettingsCard(
                settings = metadataSettings,
                onSetEnrichmentEnabled = onSetEnrichmentEnabled,
                onSetAppleSearchEnabled = onSetAppleSearchEnabled,
                onSetTheAudioDbEnabled = onSetTheAudioDbEnabled,
                onSetBpmAnalysisEnabled = onSetBpmAnalysisEnabled,
                onSetKeyAnalysisEnabled = onSetKeyAnalysisEnabled,
                onSetWriteToFileEnabled = onSetWriteToFileEnabled,
                onSetShowProvenanceBadges = onSetShowProvenanceBadges,
                onSetConcurrency = onSetConcurrency,
                onSetBpmRange = onSetBpmRange,
                isPushingMetadata = isPushingMetadata,
                pushProgress = pushProgress,
                pushReport = pushReport,
                onPushMetadataToFiles = onPushMetadataToFiles,
                onCancelPushMetadata = onCancelPushMetadata,
                onRetryFailedWrites = onRetryFailedWrites,
                isMdScanning = isMdScanning,
                mdScanProgress = mdScanProgress,
                pendingReviewCount = pendingReviewCount,
                onStartMdScan = onStartMdScan,
                onCancelMdScan = onCancelMdScan,
                onNavigateToReviewInbox = onNavigateToReviewInbox
            )
        }
    }
}
