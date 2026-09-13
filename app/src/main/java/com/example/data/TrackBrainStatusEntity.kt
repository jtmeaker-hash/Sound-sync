package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.brain.BrainProcessingState
import com.example.brain.BrainSubStatus

@Entity(
    tableName = "track_brain_status",
    indices = [
        Index(value = ["overallStatus"]),
        Index(value = ["metadataStatus"]),
        Index(value = ["artworkStatus"]),
        Index(value = ["bpmStatus"]),
        Index(value = ["keyStatus"]),
        Index(value = ["waveformStatus"]),
        Index(value = ["qualityStatus"]),
        Index(value = ["lyricsStatus"]),
        Index(value = ["replayGainStatus"]),
        Index(value = ["duplicateStatus"]),
        Index(value = ["fileValidationStatus"]),
        Index(value = ["lastAttemptTime"])
    ]
)
data class TrackBrainStatusEntity(
    @PrimaryKey
    val trackId: String,
    val overallStatus: String = BrainProcessingState.PENDING.name,
    val metadataStatus: String = BrainSubStatus.NOT_STARTED.name,
    val artworkStatus: String = BrainSubStatus.NOT_STARTED.name,
    val bpmStatus: String = BrainSubStatus.NOT_STARTED.name,
    val keyStatus: String = BrainSubStatus.NOT_STARTED.name,
    val waveformStatus: String = BrainSubStatus.NOT_STARTED.name,
    val qualityStatus: String = BrainSubStatus.NOT_STARTED.name,
    val lyricsStatus: String = BrainSubStatus.NOT_STARTED.name,
    val replayGainStatus: String = BrainSubStatus.NOT_STARTED.name,
    val duplicateStatus: String = BrainSubStatus.NOT_STARTED.name,
    val fileValidationStatus: String = BrainSubStatus.NOT_STARTED.name,
    val lastAttemptTime: Long? = null,
    val lastSuccessTime: Long? = null,
    val retryCount: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val analysisVersion: Int = 1,
    val sourceProvider: String? = null,
    val bpmVersion: Int = 2,
    val keyVersion: Int = 2,
    val waveformVersion: Int = 1,
    val qualityVersion: Int = 1,
    val replayGainVersion: Int = 1,
    val loudnessLufs: Double? = null,
    val loudnessPeak: Double? = null,
    val fileModifiedTimestamp: Long = 0L,
    val fileSize: Long = 0L
) {
    val overallEnum: BrainProcessingState
        get() = try { BrainProcessingState.valueOf(overallStatus) } catch (_: Exception) { BrainProcessingState.PENDING }

    val isAllComplete: Boolean
        get() = overallEnum == BrainProcessingState.COMPLETE
}
