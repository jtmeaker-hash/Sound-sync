package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        CrateEntity::class,
        SourceFolderEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        SongFindEntity::class,
        PlaybackSessionEntity::class,
        BulkOperationHistoryEntity::class,
        MetadataHistoryEntity::class,
        MetadataReviewItemEntity::class,
        WatchedFolderEntity::class,
        LyricsEntity::class
    ],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun sourceFolderDao(): SourceFolderDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun songFindDao(): SongFindDao
    abstract fun playbackSessionDao(): PlaybackSessionDao
    abstract fun bulkOperationHistoryDao(): BulkOperationHistoryDao
    abstract fun metadataHistoryDao(): MetadataHistoryDao
    abstract fun metadataReviewInboxDao(): MetadataReviewInboxDao
    abstract fun watchedFolderDao(): WatchedFolderDao
    abstract fun lyricsDao(): LyricsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `source_folders` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `label` TEXT NOT NULL,
                        `path` TEXT NOT NULL,
                        `uriString` TEXT NOT NULL,
                        `typeName` TEXT NOT NULL,
                        `isOnline` INTEGER NOT NULL,
                        `trackCount` INTEGER NOT NULL,
                        `freeSpaceGb` REAL NOT NULL,
                        `totalSpaceGb` REAL NOT NULL,
                        `lastScanned` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Safe table updates for TrackEntity
                try {
                    db.execSQL("ALTER TABLE tracks ADD COLUMN trackNumber INTEGER NOT NULL DEFAULT 0")
                } catch (ignored: Exception) {}
                try {
                    db.execSQL("ALTER TABLE tracks ADD COLUMN discNumber INTEGER NOT NULL DEFAULT 1")
                } catch (ignored: Exception) {}
                try {
                    db.execSQL("ALTER TABLE tracks ADD COLUMN storageRelativePath TEXT NOT NULL DEFAULT ''")
                } catch (ignored: Exception) {}

                // Playlists table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlists` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `sourceId` TEXT,
                        `backingFileUri` TEXT,
                        `backingRelativePath` TEXT,
                        `isRockboxCompatible` INTEGER NOT NULL,
                        `isImported` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // Playlist tracks cross-reference table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_tracks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `playlistId` TEXT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `dateAdded` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId_position` ON `playlist_tracks` (`playlistId`, `position`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = listOf(
                    "bpmConfidence REAL NOT NULL DEFAULT 0.0",
                    "bpmAnalysisVersion TEXT",
                    "bpmLastAnalyzed INTEGER",
                    "camelotKey TEXT NOT NULL DEFAULT ''",
                    "keyConfidence REAL NOT NULL DEFAULT 0.0",
                    "keyAnalysisVersion TEXT",
                    "keyLastAnalyzed INTEGER",
                    "albumArtist TEXT NOT NULL DEFAULT ''",
                    "releaseDate TEXT",
                    "releaseYear INTEGER",
                    "recordLabel TEXT",
                    "barcode TEXT",
                    "isrc TEXT",
                    "musicBrainzRecordingId TEXT",
                    "musicBrainzArtistId TEXT",
                    "musicBrainzReleaseId TEXT",
                    "musicBrainzReleaseGroupId TEXT",
                    "musicBrainzMatchConfidence REAL NOT NULL DEFAULT 0.0",
                    "musicBrainzLastChecked INTEGER",
                    "artworkUrl TEXT"
                )
                columns.forEach { definition ->
                    val name = definition.substringBefore(' ')
                    try { db.execSQL("ALTER TABLE tracks ADD COLUMN $name $definition") } catch (_: Exception) { }
                }
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `song_finds` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `sourceAppName` TEXT NOT NULL,
                        `notes` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL,
                        `isCompleted` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_finds_url` ON `song_finds` (`url`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_song_finds_createdAt` ON `song_finds` (`createdAt`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE tracks ADD COLUMN contentFingerprint TEXT NOT NULL DEFAULT ''")
                } catch (ignored: Exception) {}
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_contentFingerprint` ON `tracks` (`contentFingerprint`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_filePath` ON `tracks` (`filePath`)")
                } catch (ignored: Exception) {}
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create playback_sessions table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `endedAt` INTEGER NOT NULL,
                        `listenedDurationMs` INTEGER NOT NULL,
                        `trackDurationMs` INTEGER NOT NULL,
                        `completed` INTEGER NOT NULL,
                        `skipped` INTEGER NOT NULL,
                        `playbackContext` TEXT NOT NULL,
                        `playlistId` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_sessions_trackId` ON `playback_sessions` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_sessions_startedAt` ON `playback_sessions` (`startedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_sessions_completed` ON `playback_sessions` (`completed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_sessions_skipped` ON `playback_sessions` (`skipped`)")

                // 2. Create bulk_operation_history table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bulk_operation_history` (
                        `id` TEXT PRIMARY KEY NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `operationType` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `affectedTracksCount` INTEGER NOT NULL,
                        `undoPayloadJson` TEXT NOT NULL
                    )
                    """.trimIndent()
                )

                // 3. Add rating, customTags, notes, composer, isManualBpm, isManualKey columns to tracks table
                val newTrackColumns = listOf(
                    "rating INTEGER NOT NULL DEFAULT 0",
                    "customTags TEXT NOT NULL DEFAULT ''",
                    "notes TEXT NOT NULL DEFAULT ''",
                    "composer TEXT NOT NULL DEFAULT ''",
                    "isManualBpm INTEGER NOT NULL DEFAULT 0",
                    "isManualKey INTEGER NOT NULL DEFAULT 0"
                )
                newTrackColumns.forEach { definition ->
                    val colName = definition.substringBefore(' ')
                    try {
                        db.execSQL("ALTER TABLE tracks ADD COLUMN $colName $definition")
                    } catch (_: Exception) {}
                }
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_crateId` ON `tracks` (`crateId`)")
                } catch (_: Exception) {}
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_dateAdded` ON `tracks` (`dateAdded`)")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val newTrackColumns = listOf(
                    "analysisState TEXT NOT NULL DEFAULT 'NOT_ANALYSED'",
                    "analysisVersion INTEGER NOT NULL DEFAULT 1",
                    "lastAnalysedAt INTEGER",
                    "analysisFailureReason TEXT",
                    "analysisRetryCount INTEGER NOT NULL DEFAULT 0",
                    "fileModifiedTimestamp INTEGER NOT NULL DEFAULT 0"
                )
                newTrackColumns.forEach { definition ->
                    val colName = definition.substringBefore(' ')
                    try {
                        db.execSQL("ALTER TABLE tracks ADD COLUMN $colName $definition")
                    } catch (_: Exception) {}
                }
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_analysisState` ON `tracks` (`analysisState`)")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val newColumns = listOf(
                    "originalArtist TEXT",
                    "resolvedArtist TEXT",
                    "metadataSource TEXT",
                    "metadataConfidence REAL NOT NULL DEFAULT 0.0"
                )
                newColumns.forEach { definition ->
                    val colName = definition.substringBefore(' ')
                    try {
                        db.execSQL("ALTER TABLE tracks ADD COLUMN $colName $definition")
                    } catch (_: Exception) {}
                }
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create tracks_new without MusicBrainz columns and with new Apple/TheAudioDB columns
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tracks_new` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `genre` TEXT NOT NULL,
                        `subGenre` TEXT NOT NULL,
                        `bpm` REAL NOT NULL,
                        `bpmConfidence` REAL NOT NULL,
                        `bpmAnalysisVersion` TEXT,
                        `bpmLastAnalyzed` INTEGER,
                        `musicalKey` TEXT NOT NULL,
                        `camelotKey` TEXT NOT NULL,
                        `keyConfidence` REAL NOT NULL,
                        `keyAnalysisVersion` TEXT,
                        `keyLastAnalyzed` INTEGER,
                        `durationSeconds` INTEGER NOT NULL,
                        `bitrateKbps` INTEGER NOT NULL,
                        `format` TEXT NOT NULL,
                        `fileSizeMb` REAL NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `isOfflineReady` INTEGER NOT NULL,
                        `syncState` TEXT NOT NULL,
                        `platformsString` TEXT NOT NULL,
                        `energyRating` INTEGER NOT NULL,
                        `hotCuesString` TEXT NOT NULL,
                        `isAiTagged` INTEGER NOT NULL,
                        `qualityRating` TEXT NOT NULL,
                        `dateAdded` INTEGER NOT NULL,
                        `crateId` TEXT NOT NULL,
                        `trackNumber` INTEGER NOT NULL,
                        `discNumber` INTEGER NOT NULL,
                        `albumArtist` TEXT NOT NULL,
                        `releaseDate` TEXT,
                        `releaseYear` INTEGER,
                        `recordLabel` TEXT,
                        `barcode` TEXT,
                        `isrc` TEXT,
                        `appleTrackId` INTEGER,
                        `appleCollectionId` INTEGER,
                        `appleArtistId` INTEGER,
                        `theAudioDbAlbumId` TEXT,
                        `theAudioDbArtistId` TEXT,
                        `artworkSource` TEXT,
                        `artworkCachePath` TEXT,
                        `metadataScanState` TEXT NOT NULL DEFAULT 'NOT_SCANNED',
                        `metadataScanTimestamp` INTEGER,
                        `userConfirmedMetadata` INTEGER NOT NULL DEFAULT 0,
                        `artworkUrl` TEXT,
                        `storageRelativePath` TEXT NOT NULL,
                        `contentFingerprint` TEXT NOT NULL,
                        `rating` INTEGER NOT NULL,
                        `customTags` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `composer` TEXT NOT NULL,
                        `isManualBpm` INTEGER NOT NULL,
                        `isManualKey` INTEGER NOT NULL,
                        `analysisState` TEXT NOT NULL,
                        `analysisVersion` INTEGER NOT NULL,
                        `lastAnalysedAt` INTEGER,
                        `analysisFailureReason` TEXT,
                        `analysisRetryCount` INTEGER NOT NULL,
                        `fileModifiedTimestamp` INTEGER NOT NULL,
                        `originalArtist` TEXT,
                        `resolvedArtist` TEXT,
                        `metadataSource` TEXT,
                        `metadataConfidence` REAL NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                // 2. Copy data across
                db.execSQL(
                    """
                    INSERT INTO `tracks_new` (
                        id, title, artist, album, genre, subGenre, bpm, bpmConfidence, bpmAnalysisVersion, bpmLastAnalyzed,
                        musicalKey, camelotKey, keyConfidence, keyAnalysisVersion, keyLastAnalyzed, durationSeconds,
                        bitrateKbps, format, fileSizeMb, filePath, isOfflineReady, syncState, platformsString, energyRating,
                        hotCuesString, isAiTagged, qualityRating, dateAdded, crateId, trackNumber, discNumber, albumArtist,
                        releaseDate, releaseYear, recordLabel, barcode, isrc, artworkUrl, storageRelativePath,
                        contentFingerprint, rating, customTags, notes, composer, isManualBpm, isManualKey, analysisState,
                        analysisVersion, lastAnalysedAt, analysisFailureReason, analysisRetryCount, fileModifiedTimestamp,
                        originalArtist, resolvedArtist, metadataSource, metadataConfidence
                    )
                    SELECT
                        id, title, artist, album, genre, subGenre, bpm, bpmConfidence, bpmAnalysisVersion, bpmLastAnalyzed,
                        musicalKey, camelotKey, keyConfidence, keyAnalysisVersion, keyLastAnalyzed, durationSeconds,
                        bitrateKbps, format, fileSizeMb, filePath, isOfflineReady, syncState, platformsString, energyRating,
                        hotCuesString, isAiTagged, qualityRating, dateAdded, crateId, trackNumber, discNumber, albumArtist,
                        releaseDate, releaseYear, recordLabel, barcode, isrc, artworkUrl, storageRelativePath,
                        contentFingerprint, rating, customTags, notes, composer, isManualBpm, isManualKey, analysisState,
                        analysisVersion, lastAnalysedAt, analysisFailureReason, analysisRetryCount, fileModifiedTimestamp,
                        originalArtist, resolvedArtist, metadataSource, metadataConfidence
                    FROM `tracks`
                    """.trimIndent()
                )

                // 3. Drop old table and rename new
                db.execSQL("DROP TABLE `tracks`")
                db.execSQL("ALTER TABLE `tracks_new` RENAME TO `tracks`")

                // 4. Recreate indices
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_contentFingerprint` ON `tracks` (`contentFingerprint`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_filePath` ON `tracks` (`filePath`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_crateId` ON `tracks` (`crateId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_dateAdded` ON `tracks` (`dateAdded`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_analysisState` ON `tracks` (`analysisState`)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add fingerprint columns to tracks
                try {
                    db.execSQL("ALTER TABLE `tracks` ADD COLUMN `fingerprintAlgorithm` TEXT")
                } catch (ignored: Exception) {}
                try {
                    db.execSQL("ALTER TABLE `tracks` ADD COLUMN `fingerprintTimestamp` INTEGER")
                } catch (ignored: Exception) {}

                // metadata_history table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `metadata_history` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `trackId` TEXT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `fieldChanged` TEXT NOT NULL,
                        `previousValue` TEXT,
                        `newValue` TEXT,
                        `source` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `isAutomatic` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metadata_history_trackId` ON `metadata_history` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metadata_history_timestamp` ON `metadata_history` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metadata_history_filePath` ON `metadata_history` (`filePath`)")

                // metadata_review_inbox table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `metadata_review_inbox` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `trackId` TEXT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `originalArtist` TEXT NOT NULL,
                        `originalTitle` TEXT NOT NULL,
                        `originalAlbum` TEXT NOT NULL,
                        `proposedArtist` TEXT NOT NULL,
                        `proposedTitle` TEXT NOT NULL,
                        `proposedAlbum` TEXT NOT NULL,
                        `proposedGenre` TEXT,
                        `proposedYear` INTEGER,
                        `proposedTrackNumber` INTEGER,
                        `proposedArtworkUrl` TEXT,
                        `provider` TEXT NOT NULL,
                        `confidenceScore` REAL NOT NULL,
                        `evidenceSummary` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metadata_review_inbox_trackId` ON `metadata_review_inbox` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metadata_review_inbox_status` ON `metadata_review_inbox` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metadata_review_inbox_confidenceScore` ON `metadata_review_inbox` (`confidenceScore`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_metadata_review_inbox_timestamp` ON `metadata_review_inbox` (`timestamp`)")

                // watched_folders table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `watched_folders` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `folderPathOrUri` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `includeSubfolders` INTEGER NOT NULL,
                        `autoScanNewFiles` INTEGER NOT NULL,
                        `autoAnalyzeMetadata` INTEGER NOT NULL,
                        `autoFingerprint` INTEGER NOT NULL,
                        `autoAnalyzeBpmKey` INTEGER NOT NULL,
                        `autoFetchArtwork` INTEGER NOT NULL,
                        `ignoredExtensions` TEXT NOT NULL,
                        `lastScannedTimestamp` INTEGER NOT NULL,
                        `isEnabled` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // lyrics table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lyrics` (
                        `trackId` TEXT NOT NULL PRIMARY KEY,
                        `plainLyrics` TEXT NOT NULL DEFAULT '',
                        `syncedLyricsJson` TEXT NOT NULL DEFAULT '',
                        `isSynced` INTEGER NOT NULL DEFAULT 0,
                        `isUserEdited` INTEGER NOT NULL DEFAULT 0,
                        `source` TEXT NOT NULL DEFAULT 'none',
                        `offsetMs` INTEGER NOT NULL DEFAULT 0,
                        `remoteLyricsId` TEXT,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_lyrics_trackId` ON `lyrics` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lyrics_source` ON `lyrics` (`source`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lyrics_isUserEdited` ON `lyrics` (`isUserEdited`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lyrics_updatedAt` ON `lyrics` (`updatedAt`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "soundsync_dj_database"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
