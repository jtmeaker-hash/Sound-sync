# SoundSync Upgrade Pack 25–29 Progress

## Status Overview
- **Branch**: `Debug`
- **Active Stage**: Stage 29 — Queue / Shuffle / Playback History Architecture
- **Completed Stages**: Stage 25 — Command / Search Palette, Stage 26 — Local-First Metadata Merging, Stage 27 — Persistent Application & Playback State, Stage 28 — DJ Prep Environment

---

## Stages Progress

### Stage 25 — Command / Search Palette
- **Status**: COMPLETE
- **Delivered Capabilities**:
  - Global Command / Search Palette accessible from DjTopAppBar via dedicated Search/Terminal button.
  - Unified query parser (`CommandPaletteParser`) understanding plain text queries and structured filters: `artist`, `bpm` exact and range, `key` (Camelot and musical keys with automatic Camelot conversion), `folder`, `missing artwork/bpm/key/metadata`, `recently added`, `recently played`, and `unplayed`.
  - Comprehensive command execution (`rescan selected`, `rescan library`, `analyse selected`, `find metadata selected`, `clear queue`, `shuffle queue`, `open DJ Prep`, `open Car Mode`, `open Downloads folder`, `reanalyse all`, `analyse missing`, `open library doctor`, `open metadata review`, etc.).
  - Grouped result presentation: Commands, Tracks with quick actions (Play, Play Next, Add to Queue, DJ Prep, Locate File), Artists, Albums, and Folders.
  - Selection-aware commands: disables commands requiring selection with clear explanatory text when 0 tracks are selected, enables and counts selected tracks when selections exist.
  - Ranking engine prioritizing exact/prefix matches and artist/title matches over distant fields, preventing unrelated track pollution.
  - Database indexed with Room `MIGRATION_18_19` (database version 19) for `title`, `artist`, `album`, `bpm`, `camelotKey`, `musicalKey`.
- **Files Changed / Added**:
  - `app/src/main/java/com/example/command/CommandPaletteModels.kt` (New)
  - `app/src/main/java/com/example/command/CommandPaletteParser.kt` (New)
  - `app/src/main/java/com/example/command/CommandPaletteEngine.kt` (New)
  - `app/src/main/java/com/example/ui/command/CommandPaletteDialog.kt` (New)
  - `app/src/main/java/com/example/data/TrackEntity.kt` (Added search indices)
  - `app/src/main/java/com/example/data/AppDatabase.kt` (Added MIGRATION_18_19, bumped version to 19)
  - `app/src/main/java/com/example/ui/sidemenu/SideMenuDestination.kt` (Added DjPrep destination)
  - `app/src/main/java/com/example/ui/MainDjViewModel.kt` (Added palette state, command dispatcher, queueManager alias)
  - `app/src/main/java/com/example/ui/MainDjScreen.kt` (Integrated top bar trigger, dialog rendering, and destination handler)
  - `app/src/test/java/com/example/CommandPaletteEngineTest.kt` (New unit test suite)
- **Tests Performed**:
  - `CommandPaletteEngineTest`: plain search, artist quoted/unquoted, exact BPM, BPM range, key Camelot/musical, folder filter, missing filters, recently added/played/unplayed, multi-filter queries, selection-aware command states, track ranking. (ALL PASSED)
  - Full project test suite: PASSED.

### Stage 26 — Local-First Metadata Merging
- **Status**: COMPLETE
- **Delivered Capabilities**:
  - Deterministic source provenance hierarchy (`USER_EDIT` [100] > `LOCAL_DSP` [80] > `LOCAL_TAG` [70] > `RESTORED_BACKUP` [60] > `ONLINE_PROVIDER` [50] > `FILENAME_INFERENCE` [30] > `PLACEHOLDER` [10] > `EMPTY` [0]).
  - `LocalFirstMetadataMerger`: Central conflict-resolution engine that protects valid local tags against online overwrite, fills missing fields and generic placeholders (e.g., "Unknown Artist", "Track 01", "Single", empty artwork), preserves local artwork and local DSP BPM/Key analysis, and logs proposed online alternatives as conflicts for user review.
  - Refactored `MetadataResolver`: Removed aggressive overwrite branches (`candidateScore >= 85.0`) in favor of local-first merging; conflicts between good local tags and online suggestions are safely preserved and dispatched to `metadata_review_inbox`.
  - Field-level provenance tracking: Stored and persisted via `fieldProvenanceJson` on `Track`, `TrackEntity`, `MetadataBackupEntity`, and backup exports.
  - Database schema bumped to version 20 via `MIGRATION_19_20` adding `fieldProvenanceJson` column to `tracks` and `metadata_backups`.
  - Rescan preservation: Updated `TrackDao.upsertPhysicalTrack` and library scanning to preserve user overrides, manual BPM/key flags, and field provenance on re-scanning.
  - Enhanced Review UX in `MetadataReviewInboxScreen` and `MetadataReviewManager`: Supports granular field cherry-picking (`acceptSelectedFields`), single-field acceptance (`acceptSpecificField`), and explicit rejection keeping local data (`keepLocal`).
- **Files Changed / Added**:
  - `app/src/main/java/com/example/metadata/merge/LocalFirstMetadataModels.kt` (New)
  - `app/src/main/java/com/example/metadata/merge/LocalFirstMetadataMerger.kt` (New)
  - `app/src/main/java/com/example/model/Models.kt` (Added `fieldProvenanceJson` and provenance helpers to `Track`)
  - `app/src/main/java/com/example/data/TrackEntity.kt` (Added `fieldProvenanceJson`)
  - `app/src/main/java/com/example/data/MetadataBackupEntity.kt` (Added `fieldProvenanceJson`)
  - `app/src/main/java/com/example/data/AppDatabase.kt` (Added `MIGRATION_19_20`, bumped to version 20)
  - `app/src/main/java/com/example/data/TrackDao.kt` (Preserve `fieldProvenanceJson` and user overrides on rescan)
  - `app/src/main/java/com/example/metadata/MetadataResolver.kt` (Integrated `LocalFirstMetadataMerger`)
  - `app/src/main/java/com/example/metadata/backup/MetadataBackupManager.kt` (Persist/restore `fieldProvenanceJson`)
  - `app/src/main/java/com/example/metadata/review/MetadataReviewManager.kt` (Added `acceptSelectedFields`, `keepLocal`)
  - `app/src/main/java/com/example/ui/library/MetadataReviewInboxScreen.kt` (Added selectable comparison rows, Keep Local, Apply Selected)
  - `app/src/main/java/com/example/backup/SoundSyncBackupModels.kt` (Serialize/deserialize `fieldProvenanceJson`)
  - `app/src/test/java/com/example/LocalFirstMetadataMergeTest.kt` (New test suite with 10 comprehensive tests)
- **Tests Performed**:
  - `LocalFirstMetadataMergeTest`:
    1. Empty artist + valid internet artist -> fills artist.
    2. Valid local artist + different online artist -> keeps local artist and records conflict.
    3. User-edited title + conflicting online title -> keeps user edit strictly.
    4. Local artwork present + online artwork present -> keeps local artwork unless explicitly enabled.
    5. Missing artwork -> online artwork fills it after confident match.
    6. Placeholder "Unknown Artist" -> replaced by confident match.
    7. Null online field -> never erases local value.
    8. Re-scan -> does not revert user edits or manual BPM/key.
    9. Metadata conflict review -> applies only selected fields.
    10. Metadata conflict review `keepLocal` -> dismisses proposal and protects local tags.
    (ALL 10 PASSED)
  - `MetadataResolverTest`: ALL PASSED.
  - `MetadataSafetyPipelineTest`: ALL PASSED.
  - `CommandPaletteEngineTest`: ALL PASSED.

### Stage 27 — Persistent Application & Playback State
- **Status**: COMPLETE
- **Delivered Capabilities**:
  - `PersistentSessionManager` & `PersistentSessionModels`: Versioned (v1) atomic JSON state persistence (`persistent_app_session.json`) decoupled from high-frequency SQLite writes to prevent DB churn and corruption.
  - Playback State Persistence: Current track (stable ID + file path), exact playback position with 3-second debouncing, and `wasPlaying` state. On relaunch, position is restored with strict `autoPlay = false` and paused audio to prevent blasting.
  - Completed Track Guard: Resets restored position to 0L if saved within 2 seconds of track duration, preventing looping/stuck fractional ends.
  - Upgraded `PersistentQueueManager`: Preserves full manual queue order, current track, upcoming items, exact generated shuffle sequence (`shuffleSequenceTrackIds`) and index (`shuffleIndex`) preventing random re-shuffling on relaunch, separate ordered LIFO playback history, and repeat modes (OFF, ALL, ONE).
  - Library UI State Persistence: Sort option (`ExplorerSortOption`), sort direction, active search query, selected crate (`selectedCrateId`), genre filter, platform filter, hidden unavailable tracks toggle, last browsed folder path (`currentDirectoryPath`), storage source (`currentStorageSourceId`), selected main tab (`DjTab`), selected local library category (`LocalCategory`), and scroll anchors.
  - Appearance & Mode Persistence: Active theme (`ThemeMode`), Pro dark variant (`ProDarkVariant`), library density (`ProLibraryDensity`), waveform style (`WaveformStyle`), track grid view toggle, and Car Mode state + settings (active, keep awake, night mode, display mode, smart driving shuffle).
  - Scanner / Background Jobs Checkpoints: `ScanStateManager` and `TrackAnalysisManager` checkpoint processed count, total count, last track ID, and last file path. Recovers interrupted/crashed scans to `PAUSED` without restarting from zero or duplicating analysis.
  - Resilient Fallback & Safety: `validateAndRepair` prunes missing/deleted tracks, promotes the next upcoming track if the current track was removed, falls back to root if the browsed folder is deleted, and gracefully recovers from corrupt session JSON via backup files without wiping the user's library or Room database.
  - Lifecycle Integration: Immediate checkpoint flush on pause, track start, queue change, and app backgrounding/termination (`flushImmediate()` in `MainActivity.onPause`/`onStop` and `MainDjViewModel.onCleared`).
- **Files Changed / Added**:
  - `app/src/main/java/com/example/state/PersistentSessionModels.kt` (New)
  - `app/src/main/java/com/example/state/PersistentSessionManager.kt` (New)
  - `app/src/main/java/com/example/player/PersistentQueueManager.kt` (Upgraded to v3 JSON schema with shuffle order & index retention)
  - `app/src/main/java/com/example/storage/ScanStateManager.kt` (Added checkpoint storage & recovery)
  - `app/src/main/java/com/example/analysis/TrackAnalysisManager.kt` (Added analysis loop checkpoints)
  - `app/src/main/java/com/example/MainActivity.kt` (Added lifecycle flush hooks)
  - `app/src/main/java/com/example/ui/MainDjViewModel.kt` (Wired state restoration, 3s position loop, queue sync, and UI state triggers)
  - `app/src/test/java/com/example/PersistentAppStateTest.kt` (New comprehensive test suite with 14 tests)
- **Tests Performed**:
  - `PersistentAppStateTest`:
    1. Track paused halfway through (restores exact position, paused).
    2. Active playback session (wasPlaying remembered, audio paused on relaunch).
    3. Non-empty manual queue (exact items and order preserved).
    4. Shuffle enabled halfway through generated order (sequence and index preserved, not re-shuffled).
    5. Repeat ONE and repeat ALL modes.
    6. Previous-track history (LIFO return in shuffle and normal).
    7. Non-default sort (BPM_DESC, etc.).
    8. Last browsed folder and storage source.
    9. Non-default theme and library density.
    10. Car Mode state and settings.
    11. Scanner/analysis checkpoint halfway through (resumes remaining without duplicating).
    12. One queued track deleted before relaunch (repaired gracefully without crash).
    13. Completed track near end (within 2s) guard resets to 0L.
    14. Corrupted session JSON fallback does not wipe database.
    (ALL 14 PASSED)
  - `CommandPaletteEngineTest`: ALL PASSED.
  - `LocalFirstMetadataMergeTest`: ALL PASSED.

### Stage 28 — DJ Prep Environment
- **Status**: COMPLETE
- **Delivered Capabilities**:
  - **Dedicated DJ Prep Screen** (`DjPrepScreen.kt`): Professional, utilitarian mini-track preparation workspace accessible via side menu (`SideMenuDestination.DjPrep`), track context menus, and global command palette.
  - **Zoomable/Scrollable Waveform with Superimposed Beat Grid**: Interactive canvas supporting horizontal pinch/slider zoom, showing playback playhead, downbeat markers with bar numbering, intermediate beat ticks, and color-coded cue flags.
  - **8 Hot Cue Markers (A–H)**: Distinct color badges (Red, Orange, Yellow, Green, Teal, Cyan, Indigo, Purple). Tap pad to jump or set at playhead; dialog for renaming or deleting cues.
  - **Ordered Memory Cues**: Chronologically sorted reference markers with dedicated Previous / Next jump buttons and comment annotations.
  - **Beat Grid Manipulation & Auditory Metronome**:
    - Shift first downbeat to current playhead position.
    - Fine nudge grid offset (±1ms, ±10ms).
    - BPM double (×2) and halve (÷2) actions with immediate grid recalculation and manual override flag setting.
    - Reset grid to analyzed detected BPM and Key.
    - Real-time auditory metronome synthesizing 16-bit mono PCM clicks (2200 Hz accented downbeat, 1200 Hz beats 2–4) for audible verification against audio.
  - **Native Key Lock (Master Tempo)**: Powered by native Android `AudioTrack.playbackParams` (API 23+) maintaining 1.0f pitch while adjusting tempo, or tracking pitch with tempo when disabled.
  - **Phrase Markers**: Structural region markers (Intro, Verse, Build, Drop, Breakdown, Chorus, Outro, Custom) rendered as an interactive colored ribbon above the waveform with full CRUD editing.
  - **Analysis & Manual Override Protection**: `isManualBpm` and `isManualKey` are strictly preserved in `TrackDao.upsertPhysicalTrack` and `TrackAnalysisManager`, guaranteeing that background rescans never overwrite DJ-corrected BPM, keys, grids, or cue points.
  - **Prep Status Workflow**: Tracks advance through `NOT_ANALYSED` -> `ANALYSED` -> `NEEDS_REVIEW` -> `PREPPED`. Supports single-track status toggling and batch updates from library selection or command palette (`mark_prepped_selected`).
  - **Durable Persistence**: Stored in Room DB `dj_prep_data` table via `DjPrepEntity`, `DjPrepDao`, and `MIGRATION_20_21` (AppDatabase bumped to v21).
- **Files Changed / Added**:
  - `app/src/main/java/com/example/djprep/DjPrepModels.kt` (New)
  - `app/src/main/java/com/example/djprep/DjPrepEntity.kt` (New)
  - `app/src/main/java/com/example/djprep/DjPrepDao.kt` (New)
  - `app/src/main/java/com/example/djprep/DjPrepManager.kt` (New)
  - `app/src/main/java/com/example/ui/djprep/DjPrepScreen.kt` (New)
  - `app/src/main/java/com/example/data/AppDatabase.kt` (Added `DjPrepEntity`, `djPrepDao()`, `MIGRATION_20_21`, DB v21)
  - `app/src/main/java/com/example/data/TrackDao.kt` (Preserve manual BPM/key flags in `upsertPhysicalTrack`)
  - `app/src/main/java/com/example/analysis/TrackAnalysisManager.kt` (Analysis loop respects manual BPM/key overrides)
  - `app/src/main/java/com/example/audio/DjAudioEngine.kt` (Added native `playbackParams` key lock support and `keyLockEnabled` state flow)
  - `app/src/main/java/com/example/ui/MainDjScreen.kt` (Integrated `DjPrep` screen routing)
  - `app/src/main/java/com/example/ui/MainDjViewModel.kt` (Integrated prep actions and batch command palette trigger)
  - `app/src/test/java/com/example/DjPrepEnvironmentTest.kt` (New test suite with 11 comprehensive tests)
  - `app/src/test/java/com/example/LocalFirstMetadataMergeTest.kt` (Implemented `djPrepDao()` in test DB stub)
  - `app/src/test/java/com/example/MetadataSafetyPipelineTest.kt` (Implemented `djPrepDao()` in test DB stub)
  - `app/src/test/java/com/example/SoundSyncStep1FoundationTest.kt` (Implemented `djPrepDao()` in test DB stub)
- **Tests Performed**:
  - `DjPrepEnvironmentTest`:
    1. `testHotCueCrudAndNavigation`: Set, rename, delete, jump Hot Cues A-H.
    2. `testMemoryCuesOrderedNavigation`: Chronological sorting and Previous/Next navigation.
    3. `testSetFirstDownbeatAndNudgeGrid`: Downbeat repositioning, ±1ms, -10ms offset nudges, beat timestamp calculations.
    4. `testDoubleAndHalveBpm`: BPM doubling, halving, manual override flags, and reset to analyzed.
    5. `testManualBpmAndKeyRescanImmunity`: Manual BPM/key survive library rescan.
    6. `testPhraseMarkerCrudAndColorPersistence`: Phrase markers CRUD and ribbon visualization data.
    7. `testKeyLockAudioEngine`: Native Key Lock toggle and audio engine state flow.
    8. `testPrepStatusWorkflowAndBatchUpdate`: Single and batch status transitions to PREPPED.
    9. `testMissingMetadataTrackGracefulHandling`: Safe defaults for tracks missing BPM, key, or duration.
    10. `testMetronomeClickPcmGeneration`: Synthesized 16-bit mono PCM click audio (accented vs standard).
    11. `testDjPrepEntityJsonSerialization`: Lossless Room entity round-trip conversion.
    (ALL 11 PASSED)
  - `PersistentAppStateTest`: ALL PASSED (0 regressions).
  - `LocalFirstMetadataMergeTest`: ALL PASSED (0 regressions).

### Stage 29 — Queue / Shuffle / Playback History Architecture
- **Status**: IN PROGRESS

---

## Verification & Regressions
- Baseline test run completed successfully.
- Stage 25 compilation and unit tests passed without regressions.
- Stage 26 compilation and unit tests passed without regressions.
- Stage 27 compilation and unit tests passed without regressions.
- Stage 28 compilation and unit tests passed without regressions.


