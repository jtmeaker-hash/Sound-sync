# SoundSync Upgrade Pack 25–29 Progress

## Status Overview
- **Branch**: `Debug`
- **Active Stage**: Stage 27 — Full Persistent Session State
- **Completed Stages**: Stage 25 — Command / Search Palette, Stage 26 — Local-First Metadata Merging

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
- **Status**: PENDING

### Stage 28 — DJ Prep Environment
- **Status**: PENDING

### Stage 29 — Queue / Shuffle / Playback History Architecture
- **Status**: PENDING

---

## Verification & Regressions
- Baseline test run completed successfully.
- Stage 25 compilation and unit tests passed without regressions.
