package com.example.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppDatabase
import com.example.model.Track
import com.example.ui.MainDjViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HealthMetric(
    val title: String,
    val count: Int,
    val icon: ImageVector,
    val color: Color,
    val filterType: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHealthScreen(
    viewModel: MainDjViewModel,
    onBack: () -> Unit,
    onNavigateToIntegrity: () -> Unit,
    onNavigateToReviewInbox: () -> Unit,
    onNavigateToDuplicates: () -> Unit,
    onFilterTracks: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }
    val trackDao = remember { database.trackDao() }
    val inboxDao = remember { database.metadataReviewInboxDao() }

    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var reviewInboxCount by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val entities = trackDao.getAllTracksSync()
            tracks = entities.map { it.toTrack() }
            reviewInboxCount = inboxDao.getPendingCount()
            isLoading = false
        }
    }

    val totalTracks = tracks.size
    val missingTitle = remember(tracks) { tracks.count { it.title.isBlank() || it.title.equals("Untitled", true) } }
    val missingArtist = remember(tracks) { tracks.count { it.artist.isBlank() || it.artist.equals("Unknown Artist", true) } }
    val missingAlbum = remember(tracks) { tracks.count { it.album.isBlank() || it.album.equals("Single", true) || it.album.equals("Unknown Album", true) } }
    val missingArtwork = remember(tracks) { tracks.count { it.artworkCachePath.isNullOrBlank() && it.artworkUrl.isNullOrBlank() } }
    val missingBpm = remember(tracks) { tracks.count { it.bpm <= 0.0 } }
    val missingKey = remember(tracks) { tracks.count { it.musicalKey.isBlank() && it.camelotKey.isBlank() } }
    val missingFingerprint = remember(tracks) { tracks.count { it.contentFingerprint.isBlank() } }
    val missingQuality = remember(tracks) { tracks.count { it.qualityRating == null } }

    val metrics = listOf(
        HealthMetric("Missing Artist", missingArtist, Icons.Default.Person, NeonAmber, "MISSING_ARTIST"),
        HealthMetric("Missing Title", missingTitle, Icons.Default.Title, NeonRed, "MISSING_TITLE"),
        HealthMetric("Missing Album", missingAlbum, Icons.Default.Album, TextSecondary, "MISSING_ALBUM"),
        HealthMetric("Missing Artwork", missingArtwork, Icons.Default.Image, DeckBPink, "MISSING_ARTWORK"),
        HealthMetric("Missing BPM", missingBpm, Icons.Default.Speed, DeckACyan, "MISSING_BPM"),
        HealthMetric("Missing Key", missingKey, Icons.Default.MusicNote, NeonPurple, "MISSING_KEY"),
        HealthMetric("Missing Fingerprint", missingFingerprint, Icons.Default.Fingerprint, DeckACyan, "MISSING_FINGERPRINT"),
        HealthMetric("Review Inbox", reviewInboxCount, Icons.Default.Inbox, NeonGreen, "REVIEW_INBOX")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library Health Dashboard", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Overall Score Card
            val healthyCount = totalTracks - (missingArtist + missingTitle + missingArtwork + missingBpm + missingKey).coerceAtMost(totalTracks)
            val healthPercent = if (totalTracks > 0) ((healthyCount.toDouble() / totalTracks) * 100).toInt().coerceIn(0, 100) else 100

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DjSurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("LIBRARY INTEGRITY SCORE", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("$healthPercent%", color = if (healthPercent > 80) NeonGreen else if (healthPercent > 50) NeonAmber else NeonRed, fontSize = 32.sp, fontWeight = FontWeight.Black)
                        Text("$totalTracks tracks indexed in library", color = TextMuted, fontSize = 12.sp)
                    }
                    Button(
                        onClick = onNavigateToIntegrity,
                        colors = ButtonDefaults.buttonColors(containerColor = DeckACyan)
                    ) {
                        Icon(Icons.Default.Healing, contentDescription = null, tint = DjObsidian, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Integrity Tool", color = DjObsidian, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onNavigateToReviewInbox,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Icon(Icons.Default.Inbox, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Inbox ($reviewInboxCount)", color = TextPrimary, fontSize = 12.sp)
                }

                Button(
                    onClick = onNavigateToDuplicates,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = DjSurfaceCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                ) {
                    Icon(Icons.Default.CompareArrows, contentDescription = null, tint = DeckBPink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Duplicates", color = TextPrimary, fontSize = 12.sp)
                }
            }

            // Health Metrics Grid
            Text("HEALTH BREAKDOWN", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)

            for (row in metrics.chunked(2)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (metric in row) {
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (metric.filterType == "REVIEW_INBOX") {
                                        onNavigateToReviewInbox()
                                    } else {
                                        onFilterTracks(metric.filterType)
                                    }
                                },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Icon(metric.icon, contentDescription = null, tint = metric.color, modifier = Modifier.size(20.dp))
                                    Text(
                                        text = "${metric.count}",
                                        color = if (metric.count == 0) NeonGreen else metric.color,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(metric.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}
