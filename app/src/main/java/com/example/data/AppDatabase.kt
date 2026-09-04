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
        BulkOperationHistoryEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun sourceFolderDao(): SourceFolderDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun songFindDao(): SongFindDao
    abstract fun playbackSessionDao(): PlaybackSessionDao
    abstract fun bulkOperationHistoryDao(): BulkOperationHistoryDao

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "soundsync_dj_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
