# SoundSync Build & Diagnostics History

> Permanent readable record of SoundSync commits, build/test results, discovered issues and fixes.

## Historical backfill status

This file is intentionally shipped as a safe bootstrap template. **Stage 1 must run
`scripts/ci/backfill_history.py` inside the real SoundSync repository before feature work is committed.**

The backfill will add:

- every commit available from the repository's full Git history;
- commit date, author, SHA and subject;
- every GitHub Actions workflow run still retained and accessible;
- retained failed-run details where GitHub still provides logs.

### Important historical limitation

Git itself records commits, not a permanent list of every time those commits were pushed. GitHub Actions logs
may also expire under repository retention settings. Therefore, older push events or expired CI failure logs
cannot be reconstructed reliably and **must not be invented**. Any unavailable history will be explicitly marked
as unavailable by the backfill.

---

## Automated CI run log

### Stage 4 — Integration, Polish, Backup Compatibility & Regression Gate
- **Date**: 2026-09-13T21:24:00Z
- **Branch**: `Debug`
- **Target**: Stage 4 Completion

#### Issues Discovered:
- Exported diagnostic report lacked consolidated executive summaries and library doctor audit telemetry in plain text and JSON outputs.
- Bluetooth disconnection and wired audio becoming noisy events did not trigger automatic audio engine pause callbacks.
- SoundSync persistent backup schema (version 1) did not retain Library Doctor user ignore and review preferences across app reinstallations.
- Diagnostic report exporter had conflicting local variable declarations and referenced non-existent `OverallHealth.FAILED` enum constant instead of `CRITICAL`/`DEGRADED`.
- JSON diagnostic exporter nested device and memory blocks exclusively inside the `system` object, breaking root-level consumers expecting top-level keys.

#### Changes Made:
- Enhanced `DiagnosticReportExporter` with comprehensive executive summary and library doctor audit sections in both formatted plain text and structured JSON reports.
- Hardened failure recovery by wiring automatic audio pause in `AudioOutputTracker` (`recordDisconnect`, `recordNoisyEvent`) and `BluetoothCarReceiver` (`ACTION_ACL_DISCONNECTED`).
- Upgraded `SoundSyncBackup` schema to version 2, incorporating `doctorIgnoredIssues` and `doctorReviewedIssues` while maintaining full backward compatibility for version 1 backups.
- Integrated `LibraryDoctorPreferences` persistence methods (`getAllIgnored`, `getAllReviewed`, `restoreIgnored`, `restoreReviewed`) into `SoundSyncBackupManager` create and restore flows.
- Extended `SelfTestRunner` with `lastKnownState` companion tracking to allow immediate diagnostics export access to the latest test suite metrics.
- Added unit tests in `SoundSyncBackupAndRestoreTest` covering v1/v2 schema validation and Doctor preference restoration.

#### Fixes Applied:
- Resolved brace nesting issue in `SoundSyncBackupManager.restoreBackup` storage reconciliation block.
- Aligned `OverallHealth` status mapping in `DiagnosticReportExporter` with valid enum values (`CRITICAL`, `DEGRADED`, `WARNING`, `GOOD`).
- Preserved top-level `device` and `memory` JSON properties in `DiagnosticReportExporter.generateJsonReport` alongside the unified `system` object.
- Reunified section numbering in plain text report to preserve `10. RECENT DIAGNOSTIC LOGS` test contract.

#### Test Results:
- Unit tests: 400+ tests passed (0 failures) via `./gradlew testDebugUnitTest`
- Debug APK: SUCCESS (`app-debug.apk`, 27MB) via `./gradlew assembleDebug`
- Release APK: SKIPPED (non-release branch push per repository rule)

### Stage 3 — Library Doctor
- **Date**: 2026-09-13T20:44:00Z
- **Branch**: `Debug`
- **Target**: Stage 3 Completion

#### Issues Discovered:
- Absence of user-facing diagnostic and safe maintenance tools for auditing library inconsistencies and corrupt assets.
- Inconsistent album naming (e.g. whitespace, capitalization, year-suffixes) causing fragmented library organization.
- Duplicate files and alternate versions/remixes were prone to accidental mass-deletion if not conservatively distinguished.
- `AppDatabase` is an abstract class causing `IllegalArgumentException` when attempting to proxy it directly in unit tests; resolved by supporting DAO overrides in auditor and repair manager.
- Album canonical grouping initially trimmed strings before comparison, concealing trailing whitespace anomalies.

#### Changes Made:
- Implemented `LibraryDoctorModels` defining 12 audit categories (`MISSING_ARTWORK`, `MISSING_ARTIST`, `DUPLICATE_TRACKS`, `BROKEN_FILE_PATHS`, `CORRUPTED_AUDIO`, `SUSPICIOUS_BPM`, `SUSPICIOUS_KEY`, `LOW_QUALITY_AUDIO`, `INCONSISTENT_ALBUMS`, `INCOMPLETE_ANALYSIS`, `MISSING_FILES`, `FAILED_BACKGROUND_JOBS`), issue models, severity levels, review statuses, and health scoring.
- Implemented `LibraryDoctorPreferences` storing user ignore, review, and fix states in persistent SharedPreferences (`soundsync_library_doctor_prefs`).
- Implemented `LibraryDoctorAuditor` performing asynchronous non-blocking scans across all 12 categories, calculating a transparent 0-100% health score, isolating remixes/live edits from exact duplicates, and detecting embedded `Artist - Title` patterns.
- Implemented `LibraryDoctorRepairManager` dispatching all safe repair actions exclusively through `LibraryBrain` (`requestCategoryRepairForTrack`, `reanalyseTrack`, etc.) to prevent duplicate/competing background workers.
- Added destructive confirmation safeguards preventing automated deletion of physical duplicate files, stale DB records, or album merges without explicit user modal approval.
- Implemented `LibraryDoctorScreen` dashboard with health score gauge, category filter chips, expandable issue cards, "Fix All Safe Issues" action, BPM half/double-time adjustment dialog, and issue detail modal.
- Added `LibraryDoctor` destination to `SideMenuDestination`, added "Library Doctor" entry under MUSIC in `SideNavigationDrawer`, and connected direct access from `LibraryHealthScreen`.
- Created comprehensive `LibraryDoctorTest` unit test suite covering all 12 categories, preferences lifecycle, embedded artist splitting, duplicate distinction, and safe repair dispatch.

#### Fixes Applied:
- Added constructor DAO overrides (`trackDaoOverride`, `brainDaoOverride`) to `LibraryDoctorAuditor` and `LibraryDoctorRepairManager` to allow clean JVM unit testing without abstract RoomDatabase proxies.
- Refined album consistency matcher to strip bracketed/parenthesized release years and preserve raw whitespace for comparison.
- Added missing `Healing` icon and `verticalScroll` imports.

#### Test Results:
- Unit tests: 404 tests passed (0 failures) via `./gradlew testDebugUnitTest`
- Debug APK: SUCCESS (`app-debug.apk`, 27MB) via `./gradlew assembleDebug`
- Release APK: SKIPPED (non-release branch push per repository rule)

### Stage 2 — Developer Diagnostics & SoundSync Self-Test
- **Date**: 2026-09-13T20:02:00Z
- **Branch**: `Debug`
- **Target**: Stage 2 Completion

#### Issues Discovered:
- Lack of runtime visibility into audio engine decoding, audio buffer metrics, latency, and dual-deck waveform drift.
- Missing in-app self-test verification for database integrity, media permissions, storage access, background workers, decoders, and network.
- No centralized diagnostic report exporter or sanitized log capture for crash/bug investigation.
- `IndexOutOfBoundsException: No group 1` in `DiagnosticLogger` token redaction regex when pattern didn't declare group 1.
- `android.content.Context` is an abstract class causing `IllegalArgumentException` in unit test dynamic proxies.

#### Changes Made:
- Implemented `DeveloperDiagnosticsScreen` with 8 expandable real-time diagnostic sections (Playback & Audio Engine, Waveform & Sync, Output Device, Library Brain, Metadata, Storage & Database, Network & Remote, System & Device).
- Implemented `SelfTestRunner` with 11 isolated subsystem test modules (`Database`, `Media permissions`, `Storage access`, `Background jobs`, `Internet`, `Metadata lookup`, `Artwork download`, `Audio decoder`, `GitHub update check`, `Library Brain`, `Error reporting`) and overall health calculation (`GOOD`, `WARNING`, `DEGRADED`, `CRITICAL`).
- Implemented `SelfTestScreen` with live testing progress, subsystem test cards, retry buttons, and markdown summary exporter.
- Implemented `DiagnosticLogger` circular buffer (100 entries) with strict token/credential redaction and `DiagnosticReportExporter` with clipboard/share intents.
- Implemented `DeveloperModeManager` with 7-tap activation mechanism within 3.5s window and SharedPreferences persistence.
- Connected `AudioOutputTracker` to `DjAudioEngine` audio focus and `BluetoothCarReceiver` ACL connect/disconnect events.
- Added `AboutSettingsScreen` with version unlock badge and updated `SideNavigationDrawer` with `DEV` and `TEST` destination items under SYSTEM.
- Created `DeveloperDiagnosticsAndSelfTestTest` suite covering all diagnostic models, logger redaction, self-test health scoring, and developer mode activation.

#### Fixes Applied:
- Paired each regex pattern in `DiagnosticLogger` with an explicit replacement string to avoid non-existent capturing group lookups.
- Configured Robolectric `ApplicationProvider.getApplicationContext()` in unit tests instead of Java dynamic proxy for Context.
- Aligned `BrainSummary` property access with `LibraryBrain` implementation.

#### Test Results:
- Unit tests: 394 tests passed (0 failures) via `./gradlew testDebugUnitTest`
- Debug APK: SUCCESS (`app-debug.apk`, 27MB) via `./gradlew assembleDebug`
- Release APK: SKIPPED (non-release branch push per repository rule)

### Stage 1 — Library Brain Foundation & CI History Diagnostics
- **Date**: 2026-09-13T18:50:00Z
- **Branch**: `Debug`
- **Target**: Stage 1 Completion

#### Issues Discovered:
- Lack of centralized orchestration layer for background analysis, causing disjointed analysis progress.
- Absence of granular, persistent per-track modular sub-status tracking across all 10 analysis categories (File Validation, Metadata, Artwork, Waveforms, BPM/Key, Quality, ReplayGain, Lyrics, Duplicates).
- No unified concurrency bounding mechanism for audio decoders, running the risk of thread/CPU exhaustion during playback.
- Anonymous `AppDatabase` test mocks in existing test suites lacked implementations for new DAO methods.
- Native SQLite library loader in Robolectric causes `UnsupportedOperationException` on ARM64 Linux when using in-memory SQLite builders.

#### Changes Made:
- Integrated CI diagnostics workflow `.github/workflows/soundsync-ci-diagnostics.yml` and backfilled repository commit history.
- Created `TrackBrainStatusEntity` and `TrackBrainDao` with Room Migration 17 -> 18, persisting granular sub-statuses and version identifiers.
- Implemented `LibraryBrain` central orchestration service coordinating all 10 analysis categories with bounded concurrency (Semaphore(1) for DSP, Semaphore(2) for network) and active playback throttling.
- Designed and embedded `LibraryBrainCard` in `LibrarySettingsScreen` with real-time statistics, progress indicators, pause/resume, retry failed, and category reanalysis controls.
- Connected `LibraryBrain` to `MainDjViewModel` and exposed live status in `MainDjScreen`.
- Created pure JVM `LibraryBrainTest` test suite validating all Brain models, enums, summaries, and DAO interactions.

#### Fixes Applied:
- Implemented `trackBrainDao()` mock dynamic proxies in `SoundSyncStep1FoundationTest` and `MetadataSafetyPipelineTest`.
- Fixed `AlbumArtHelper` and `LyricsManager` method calls in `LibraryBrain`.
- Implemented pure JVM dynamic proxy for `LibraryBrainTest` to avoid Robolectric native SQLite loader issues on ARM64 PRoot.

#### Test Results:
- Unit tests: 384 tests passed (0 failures) via `./gradlew testDebugUnitTest`
- Debug APK: SUCCESS (`app-debug.apk`, 27MB) via `./gradlew assembleDebug`
- Release APK: SKIPPED (non-release branch push per repository rule)

## Historical Git commit backfill

_Generated: 2026-09-13T17:57:24.425890+00:00_

_Repository: `jtmeaker-hash/Sound-sync`_


| Date | Commit | Author | Summary |
|---|---|---|---|

| 2026-09-13T15:29:21Z | `fd8e9e7cd0` | jtmeaker-hash | fix(stability): resolve 15-20s startup background crash and Room DB thrash loop |

| 2026-09-13T13:31:19Z | `e0a57369fa` | jtmeaker-hash | fix(stability): eliminate process crash and silent exit root causes |

| 2026-09-13T11:59:28Z | `638260c0ed` | jtmeaker-hash | fix(audio): rebuild haas spatializer quality |

| 2026-09-13T11:05:14Z | `5619c2498a` | jtmeaker-hash | feat(audio): add high quality parametric eq |

| 2026-09-13T09:54:21Z | `ea05ec2df1` | jtmeaker-hash | fix(stability): harden library and playback concurrency |

| 2026-09-13T09:23:28Z | `7270311dca` | jtmeaker-hash | feat(library): add persistent track grid view |

| 2026-09-13T07:55:07Z | `731b128eda` | jtmeaker-hash | fix(artwork): unify artwork resolution and refresh |

| 2026-09-13T07:18:51Z | `177d94669d` | jtmeaker-hash | fix(scanner): correct storage state and scan lifecycle |

| 2026-09-13T06:16:24Z | `aefe374b19` | jtmeaker-hash | feat(library): add hierarchical folder browser |

| 2026-09-13T05:40:31Z | `d5855201b5` | jtmeaker-hash | fix(library): enforce canonical local track identity |

| 2026-09-12T10:01:23Z | `b14c668c1e` | jtmeaker-hash | fix(audio): direct RIFF WAV fallback for AOSP WAVExtractor odd-chunk bug |

| 2026-09-12T07:45:24Z | `2359cd43e2` | jtmeaker-hash | fix(playback): fix SAF manual locate, unknown AFD length extractor error, and numeric URI resolution |

| 2026-09-12T05:52:08Z | `cd499a31ee` | jtmeaker-hash | Fix MediaStore-to-SAF conversion and extractor instantiation failure |

| 2026-09-12T04:14:59Z | `338d7cd446` | jtmeaker-hash | feat(storage): implement multi-tier storage resolution, playback self-healing, and library grid layout |

| 2026-09-11T19:26:59Z | `77b676800e` | jtmeaker-hash | fix(ci): fix playback synthesis fallback and staging audio validation in test suite |

| 2026-09-11T12:36:51Z | `5b3d8111f1` | jtmeaker-hash | fix(metadata): prevent playback breakage across audio formats after metadata rewrite |

| 2026-09-10T09:51:15Z | `d635823d8c` | jtmeaker-hash | fix: add errorMessage property to TagWriteResult for proper diagnostic logging |

| 2026-09-10T09:01:10Z | `d29ce774b6` | jtmeaker-hash | feat(storage): add metadata rewrite diagnostic logging |

| 2026-09-10T12:24:58+08:00 | `a7e5653210` | jtmeaker-hash | refactor: optimize analysis queue and improve playback |

| 2026-09-10T11:31:12+08:00 | `83a6522c08` | jtmeaker-hash | fix: improve track duration accuracy and integrity |

| 2026-09-10T10:51:02+08:00 | `c9f6e1ef19` | jtmeaker-hash | fix: improve metadata duration and bitrate parsing |

| 2026-09-10T08:11:45+08:00 | `0465dd7c61` | jtmeaker-hash | feat(metadata): implement automated artwork management |

| 2026-09-09T23:53:26+08:00 | `a02a4880a9` | jtmeaker-hash | feat(storage): implement robust track self-healing |

| 2026-09-09T11:41:52+08:00 | `c4a9744b8a` | jtmeaker-hash | build: fix local resource task and stabilize live tests |

| 2026-09-08T13:24:13Z | `e4f74880cc` | jtmeaker-hash | fix(test): add filePath and album to COMPLETE track in MetadataResolverTest |

| 2026-09-08T20:31:11+08:00 | `6aedb6e3fc` | jtmeaker-hash | build: upgrade to Java 21 and refine CI pipeline |

| 2026-09-08T20:15:12+08:00 | `7b42de762b` | jtmeaker-hash | fix: improve metadata restoration and preservation |

| 2026-09-08T19:44:54+08:00 | `900d564d9a` | jtmeaker-hash | refactor: improve metadata persistence and file safety |

| 2026-09-08T17:34:47+08:00 | `d9aa3a8b10` | jtmeaker-hash | feat(storage): implement robust track self-healing and path reconciliation |

| 2026-09-07T12:53:25Z | `7013fa8204` | jtmeaker-hash | feat(ui,metadata): overhaul professional workstation theme and resolve metadata intelligence pipeline |

| 2026-09-07T08:13:14Z | `1fd66a6d40` | jtmeaker-hash | fix(metadata): fix storage permissions, Scoped Storage URI resolution, and FLAC tag writing engine |

| 2026-09-07T06:17:07Z | `2465694cf3` | jtmeaker-hash | fix(metadata): implement robust WAV tag writing engine, database persistence fallback, and retry failed writes |

| 2026-09-07T04:38:23Z | `3b67e3ab2b` | jtmeaker-hash | fix(metadata): fix physical file tag embedding failure and handle scoped storage permissions |

| 2026-09-06T17:52:10Z | `a0d0fcefe9` | jtmeaker-hash | test(metadata): add network resilience guards to live pipeline integration tests |

| 2026-09-06T17:17:47Z | `290f168bf6` | jtmeaker-hash | fix(build,metadata): fix CI AAPT2 failure and expand metadata embedding to all audio formats |

| 2026-09-06T16:33:16Z | `3404b3d830` | jtmeaker-hash | feat(metadata): implement physical audio tag embedding and bulk 'Push Metadata to Files' workflow |

| 2026-09-06T15:29:00Z | `b804d593d4` | jtmeaker-hash | feat(metadata): embed metadata into audio files with verbatim preservation, verification, and queue |

| 2026-09-06T05:43:23Z | `e05094e1cf` | jtmeaker-hash | fix(metadata): protect existing artwork in acceptAllProposed unless replaceExistingArtwork enabled |

| 2026-09-06T05:14:55Z | `acea1f15c8` | jtmeaker-hash | fix(metadata): fix test regressions in identity parsing, review approval, and live resolution |

| 2026-09-06T04:19:47Z | `e17caeedd8` | jtmeaker-hash | feat(metadata): implement safe metadata pipeline, transactional backup, review inbox, and restoration engine |

| 2026-09-06T00:57:34Z | `8d23469ebb` | jtmeaker-hash | feat(metadata): implement iTunes textual authority, Cover Art Archive artwork, lossless audio tag writing, and disk read-back verification |

| 2026-09-05T11:42:46Z | `99ab342e4a` | jtmeaker-hash | fix(queue): thread-safe atomic disk persistence and robust test runner compatibility |

| 2026-09-05T11:13:09Z | `bdcad48976` | jtmeaker-hash | feat(step3): complete lyrics engine, timestamp editor, intelligence layer, and Apple metadata verification |

| 2026-09-05T10:17:16Z | `b6d871fc52` | jtmeaker-hash | feat(step2): complete playback, audio analysis, parametric eq, smart crates, and mix compatibility |

| 2026-09-05T09:33:15Z | `7be323a174` | jtmeaker-hash | feat(stage-1): Complete Stage 1 Foundation with Apple metadata subagent diagnostics, DB v12, integrity checker, and full test suite pass |

| 2026-09-05T06:54:22Z | `7e5ac985a5` | jtmeaker-hash | feat: replace MusicBrainz with Apple Search API & TheAudioDB metadata repair system |

| 2026-09-04T12:06:02Z | `9e79de49d8` | jtmeaker-hash | feat: migrate official logo, reorganize car mode & now playing UI, and implement WorkManager background analysis |

| 2026-09-04T09:30:51Z | `38d5e22319` | jtmeaker-hash | feat: implement whole-library background analysis, car mode system, and interactive artist navigation |

| 2026-09-04T08:14:21Z | `97cfaa53cb` | jtmeaker-hash | Implement rekordbox-inspired Pro theme with workstation UI and 3-band waveform |

| 2026-09-04T06:46:54Z | `1d6f307469` | jtmeaker-hash | fix: resolve large-library startup and metadata-save ANR freezes |

| 2026-09-03T12:37:45Z | `8c79af3add` | root | feat: implement Track Inspector, Listening Statistics, and Bulk Track Editor |

| 2026-09-03T09:33:23Z | `49ade7bd5c` | root | Merge remote-tracking branch 'origin/main' |

| 2026-09-03T08:51:18Z | `f010f569a1` | root | fix(djtools): share metronome engine with tap bpm, unblock AudioTrack on stop, and add full unit tests for Metronome, EQ, and Haas |

| 2026-09-03T08:24:34Z | `c94c863774` | root | Implement fully functional DJ Tools, side menu navigation, mini-player layout fix, GitHub releases update redesign, and updated README |

| 2026-09-03T15:00:33+08:00 | `53b96e0ff7` | jtmeaker-hash | Document Termux and Antigravity CLI usage |

| 2026-09-03T14:55:22+08:00 | `3d5bb84075` | jtmeaker-hash | Create Notes.md |

| 2026-09-03T04:40:50Z | `0d97d46718` | root | fix(audio): refine unplayable track detection by isAvailable and fix Haas test range |

| 2026-09-03T04:32:03Z | `bc0116dbbd` | root | fix(build): resolve Icons import, remember brace in AlbumDetailScreen, and valid AudioQualityRating enum in LocalFileSystemScanner |

| 2026-09-03T12:28:54+08:00 | `5a0bef851b` | jtmeaker-hash | Enhance README.md with project details and instructions |

| 2026-09-03T04:22:36Z | `a8414d6a97` | root | feat(storage): support USB external storage scanning, offline library retention, disconnected track indicators, filter toggle, and automatic skip on now playing |

| 2026-09-03T03:52:24Z | `f0c92ca039` | root | feat(audio): move audio effects to now playing settings |

| 2026-09-03T03:36:27Z | `cea503b48d` | root | fix(viewmodel): import kotlinx.coroutines.isActive for background enrichment loop |

| 2026-09-03T03:32:41Z | `c2c9c03183` | root | feat(library): add folder track browser, fix background task termination and clean reboot |

| 2026-09-03T02:56:51Z | `916321ddaa` | root | test: add assertTrue and assertFalse imports in MusicBrainzClientTest |

| 2026-09-03T02:54:02Z | `362c475085` | root | fix(metadata): import kotlin.math.min in MusicBrainzClient |

| 2026-09-03T02:51:05Z | `0973d8e3e5` | root | fix(metadata): preserve user song titles and enforce strict title matching in MusicBrainz |

| 2026-09-03T02:20:14Z | `449ee18248` | root | fix(test): configure Robolectric runner for AudioEmbeddedMetadataReaderTest and allow nullable context |

| 2026-09-03T02:04:09Z | `eee1099791` | root | feat: integrate MusicBrainz Web Service v2 API and embedded metadata reader |

| 2026-09-02T17:15:49Z | `e7139312fc` | root | Add visible UI settings and badges for MusicBrainz vs Local DSP audio analysis metadata provenance |

| 2026-09-02T16:48:39Z | `dd39741a88` | root | Fix DriveFileItem toAppTrack signature and remove duplicate playDriveTrack method |

| 2026-09-02T16:45:11Z | `858d23967d` | root | Fix continuous playback queue sync across all track selection modes |

| 2026-09-02T14:52:51Z | `61f2106b16` | root | Enhance DuplicateDetector fuzzy matching with bracket stripping, prefix removal and token containment |

| 2026-09-02T14:34:25Z | `d870347384` | root | Fix calculateSimilarity missing return in DuplicateDetector and safely ignore screenshot tests |

| 2026-09-02T14:27:17Z | `342cd22239` | root | Ignore Roborazzi screenshot comparison in unit test suite to avoid pixel mismatch in headless CI |

| 2026-09-02T14:14:04Z | `fb1a10c21a` | root | Unify MusicBrainz canonical metadata and BitrateProbe with audio engine stability and playback controls |

| 2026-09-02T13:36:40Z | `4907655a1c` | jtmeaker-hash | Merge canonical metadata and playback stability fixes |

| 2026-09-02T11:57:22Z | `b5a19c4047` | jtmeaker-hash | Skip unsupported Robolectric screenshot test |

| 2026-09-02T11:51:38Z | `cff2525af3` | root | Fix Robolectric SDK 36 test runner configuration and add DuplicateDetector unit tests |

| 2026-09-02T09:30:57Z | `ba3b83c36d` | root | Fix critical audio crossfade bug, foreground service lifecycles, and storage compatibility |

| 2026-09-02T09:13:57Z | `63fb289623` | jtmeaker-hash | Fix waveform timing and encoded bitrate analysis |

| 2026-09-02T14:00:25+08:00 | `05ff7d364d` | jtmeaker-hash | feat: add audio focus and playback controls |

| 2026-09-02T11:24:41+08:00 | `a19812cc46` | jtmeaker-hash | feat: add Song Find intent handling and persistence |

| 2026-09-02T02:17:14Z | `b1089c323b` | jtmeaker-hash | Fix track switching to fully replace previous audio session |

| 2026-09-01T14:38:40Z | `1d79483e89` | jtmeaker-hash | Fix metadata playback and waveform stability |

| 2026-09-01T13:08:27Z | `9d5eebe5fc` | jtmeaker-hash | Add metadata pipeline settings controls |

| 2026-09-01T09:40:52Z | `469539a188` | jtmeaker-hash | Fix debug APK workflow wrapper execution |

| 2026-09-01T09:33:37Z | `fc88f6cfcc` | jtmeaker-hash | Add GitHub Actions debug APK build |

| 2026-09-01T09:29:11Z | `55631532ab` | jtmeaker-hash | Make MusicBrainz canonical for local track enrichment |

| 2026-09-01T08:34:26Z | `ce926178a0` | jtmeaker-hash | Refactor audio engine for playback stability and performance |

| 2026-09-01T05:35:27Z | `b56cc408f8` | jtmeaker-hash | Align waveform progress with rendered audio clock |

| 2026-09-01T05:08:02Z | `5cef45b551` | jtmeaker-hash | Improve playback service stability and update verification |

| 2026-08-31T16:21:25Z | `3c8505676f` | jtmeaker-hash | Fix isNullOrEmpty() on ShortArray? in crossfade PCM check |

| 2026-08-31T15:46:02Z | `5bc9ae9b50` | jtmeaker-hash | Add queue continuation, crossfade, dark theme, and Settings tab polish |

| 2026-08-31T13:24:20Z | `d219afa38e` | jtmeaker-hash | Process real audio through DSP: EQ and Haas now affect actual playback |

| 2026-08-31T13:00:24Z | `8f60fdb996` | jtmeaker-hash | Fix waveform-audio sync and remove DJ Crate export section |

| 2026-08-31T12:52:45Z | `af6b3fc63a` | jtmeaker-hash | Add EQ and Haas Surround UI controls to Now Playing screen |

| 2026-08-31T12:34:25Z | `eb54d71fbb` | jtmeaker-hash | Fix artwork display in Now Playing and add Haas Surround effect |

| 2026-08-31T18:09:42+08:00 | `850b29b802` | jtmeaker-hash | feat: implement MediaPlaybackService and data models |

| 2026-08-31T17:28:45+08:00 | `5a6c056374` | jtmeaker-hash | build: standardize release keystore handling |

| 2026-08-31T17:16:58+08:00 | `0d0e2c7c31` | jtmeaker-hash | build: improve build configuration and CI workflow |

| 2026-08-31T16:59:42+08:00 | `52e658ea16` | jtmeaker-hash | ci: refactor build workflow and improve update logic |

| 2026-08-31T16:27:48+08:00 | `1336dffbaa` | jtmeaker-hash | feat: add Google Drive integration |

| 2026-08-31T16:11:54+08:00 | `ba3fa60865` | jtmeaker-hash | 2.1.1 Spectrogram tweaks |

| 2026-08-31T15:58:12+08:00 | `32d41a118f` | jtmeaker-hash | 2.1 |

| 2026-08-31T15:37:50+08:00 | `c28ccec626` | jtmeaker-hash | 2.0 |

| 2026-08-31T13:04:01+08:00 | `aa909186d9` | jtmeaker-hash | Now Playing Tab 2 |

| 2026-08-31T12:16:34+08:00 | `944966356d` | jtmeaker-hash | Now playing tab |

| 2026-08-31T00:17:18+08:00 | `b59868114b` | jtmeaker-hash | App crash fix |

| 2026-08-30T23:23:50+08:00 | `d434bccb40` | jtmeaker-hash | Freeze fix 3, Spotify and SoundCloud integration, spec style spectrogram |

| 2026-08-30T16:47:37+08:00 | `aea2b454e2` | jtmeaker-hash | Slow and spectrogram fix |

| 2026-08-30T15:58:37+08:00 | `4b8d8c6211` | jtmeaker-hash | Freeze fix |

| 2026-08-30T13:39:36+08:00 | `9e5c70c132` | jtmeaker-hash | Autoplay and Crash fix |

| 2026-08-30T13:01:41+08:00 | `44d2513aae` | jtmeaker-hash | Newest version |

| 2026-08-30T04:25:02+08:00 | `21c34a5a2d` | jtmeaker-hash | build: disable Google Services and harden debug signing |

| 2026-08-30T04:11:31+08:00 | `943cb34621` | jtmeaker-hash | build: upgrade KSP to 2.3.9 |

| 2026-08-29T19:11:47+08:00 | `384f421d00` | jtmeaker-hash | build: update Gradle wrapper and add APK workflow |

| 2026-08-29T16:37:31+08:00 | `e8f4ca4c35` | jtmeaker-hash | build: initialize Android project structure |

| 2026-08-29T16:37:12+08:00 | `f1f9224878` | jtmeaker-hash | Initial commit |


## Historical GitHub Actions backfill


**Actions history not imported:** GitHub CLI (`gh`) is not installed.

Run this script again after `gh auth login` to import retained workflow history.


### Historical coverage note

- Git history above represents commits available in the repository.
- A Git repository does **not** preserve a complete historical ledger of every push event.
- GitHub may expire old Actions logs.
- Missing push/failure records are marked unavailable rather than guessed.

---


<!-- SOUNDSYNC_CI_ENTRIES -->


## CI Run 34779508669 — PASS

- **Date:** 2026-09-13T20:08:31.838876+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `Debug`
- **Commit:** [`b95a06d150`](https://github.com/jtmeaker-hash/Sound-sync/commit/b95a06d150419d08fa8907144586634c02c819bd)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/34779508669)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

- Lack of runtime visibility into audio engine decoding, audio buffer metrics, latency, and dual-deck waveform drift
- Missing in-app self-test verification for database integrity, media permissions, storage access, background workers, decoders, and network
- No centralized diagnostic report export or sanitized log capture for crash/bug investigation

No CI build/test failures detected in this run.

### Summary of changes

- Implemented DeveloperDiagnosticsScreen with 8 expandable real-time diagnostic sections
- Implemented SelfTestRunner with 11 isolated subsystem test modules and overall health calculation (GOOD/WARNING/DEGRADED/CRITICAL)
- Implemented SelfTestScreen with live testing progress, subsystem test cards, retry buttons, and markdown summary exporter
- Implemented DiagnosticLogger circular buffer (100 entries) with strict token/credential redaction and DiagnosticReportExporter with clipboard/share intents
- Implemented DeveloperModeManager with 7-tap activation mechanism within 3.5s window and SharedPreferences persistence
- Connected AudioOutputTracker to DjAudioEngine audio focus and BluetoothCarReceiver ACL connect/disconnect events
- Added AboutSettingsScreen and updated SideNavigationDrawer with DEV and TEST destination items under SYSTEM
- Created DeveloperDiagnosticsAndSelfTestTest suite covering all diagnostic models, logger redaction, self-test health scoring, and developer mode activation

### Summary of fixes

- Paired regex patterns in DiagnosticLogger with explicit replacement strings to prevent IndexOutOfBoundsException
- Configured Robolectric ApplicationProvider.getApplicationContext() in unit tests instead of Java dynamic proxy
- Aligned BrainSummary property access with LibraryBrain implementation

### Commit/diff summary

```text
b95a06d feat(diagnostics): developer diagnostics dashboard and 11-module self-test runner
 .../main/java/com/example/audio/DjAudioEngine.kt   |  84 +++
 .../com/example/carmode/BluetoothCarReceiver.kt    |   7 +
 .../com/example/diagnostics/AudioOutputTracker.kt  | 245 +++++++
 .../example/diagnostics/DeveloperModeManager.kt    | 103 +++
 .../com/example/diagnostics/DiagnosticLogger.kt    | 191 +++++
 .../com/example/diagnostics/DiagnosticModels.kt    | 204 ++++++
 .../diagnostics/DiagnosticReportExporter.kt        | 355 +++++++++
 .../java/com/example/diagnostics/SelfTestRunner.kt | 735 +++++++++++++++++++
 app/src/main/java/com/example/ui/MainDjScreen.kt   |  20 +
 .../ui/diagnostics/DeveloperDiagnosticsScreen.kt   | 816 +++++++++++++++++++++
 .../com/example/ui/diagnostics/SelfTestScreen.kt   | 475 ++++++++++++
 .../com/example/ui/settings/AboutSettingsScreen.kt | 413 +++++++++++
 .../example/ui/settings/GitHubSettingsScreen.kt    |  13 +-
 .../com/example/ui/sidemenu/SideMenuDestination.kt |   3 +
 .../example/ui/sidemenu/SideNavigationDrawer.kt    |  68 +-
 app/src/main/java/com/example/ui/theme/Color.kt    |   1 +
 .../DeveloperDiagnosticsAndSelfTestTest.kt         | 186 +++++
 docs/AGY_STAGE_STATE.md                            |   4 +-
 docs/BUILD_DIAGNOSTICS_LOG.md                      |  32 +
 19 files changed, 3947 insertions(+), 8 deletions(-)
```

---

## CI Run 34781757716 — PASS

- **Date:** 2026-09-13T20:52:26.422069+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `Debug`
- **Commit:** [`1c39d3ee06`](https://github.com/jtmeaker-hash/Sound-sync/commit/1c39d3ee06f7627513808f7179356168dd30cf67)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/34781757716)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

- Absence of user-facing diagnostic and safe maintenance tools for auditing library inconsistencies and corrupt assets
- Inconsistent album naming across tracks fragmenting music collections
- Duplicate files and alternate versions/remixes were prone to accidental mass-deletion if not conservatively distinguished

No CI build/test failures detected in this run.

### Summary of changes

- Implemented LibraryDoctorModels defining 12 audit categories, issue models, severity levels, review statuses, and health scoring
- Implemented LibraryDoctorPreferences storing user ignore, review, and fix states in persistent SharedPreferences
- Implemented LibraryDoctorAuditor performing asynchronous non-blocking scans across all 12 categories, calculating a transparent 0-100% health score, isolating remixes/live edits from exact duplicates, and detecting embedded Artist - Title patterns
- Implemented LibraryDoctorRepairManager dispatching all safe repair actions exclusively through LibraryBrain to prevent duplicate/competing background workers
- Added destructive confirmation safeguards preventing automated deletion of physical duplicate files, stale DB records, or album merges without explicit user modal approval
- Implemented LibraryDoctorScreen dashboard with health score gauge, category filter chips, expandable issue cards, Fix All Safe Issues action, BPM half/double-time adjustment dialog, and issue detail modal
- Added LibraryDoctor destination to SideMenuDestination, added Library Doctor entry under MUSIC in SideNavigationDrawer, and connected direct access from LibraryHealthScreen
- Created comprehensive LibraryDoctorTest unit test suite covering all 12 categories, preferences lifecycle, embedded artist splitting, duplicate distinction, and safe repair dispatch

### Summary of fixes

- Added constructor DAO overrides (trackDaoOverride, brainDaoOverride) to LibraryDoctorAuditor and LibraryDoctorRepairManager to allow clean JVM unit testing without abstract RoomDatabase proxies
- Refined album consistency matcher to strip bracketed/parenthesized release years and preserve raw whitespace for comparison
- Added missing Healing icon and verticalScroll imports

### Commit/diff summary

```text
1c39d3e feat(doctor): implement SoundSync Library Doctor audit, diagnostics, and safe repairs
 .../main/java/com/example/brain/LibraryBrain.kt    |  61 ++
 .../com/example/doctor/LibraryDoctorAuditor.kt     | 593 ++++++++++++++++++
 .../java/com/example/doctor/LibraryDoctorModels.kt |  84 +++
 .../com/example/doctor/LibraryDoctorPreferences.kt | 124 ++++
 .../example/doctor/LibraryDoctorRepairManager.kt   | 280 +++++++++
 app/src/main/java/com/example/ui/MainDjScreen.kt   |   8 +-
 .../com/example/ui/doctor/LibraryDoctorScreen.kt   | 683 +++++++++++++++++++++
 .../com/example/ui/library/LibraryHealthScreen.kt  |  28 +-
 .../com/example/ui/sidemenu/SideMenuDestination.kt |   1 +
 .../example/ui/sidemenu/SideNavigationDrawer.kt    |  12 +-
 .../java/com/example/doctor/LibraryDoctorTest.kt   | 337 ++++++++++
 docs/AGY_STAGE_STATE.md                            |   6 +-
 docs/BUILD_DIAGNOSTICS_LOG.md                      |  32 +
 13 files changed, 2236 insertions(+), 13 deletions(-)
```

---

## CI Run 34783780370 — PASS

- **Date:** 2026-09-13T21:38:31.650021+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `Debug`
- **Commit:** [`6aaecdb38c`](https://github.com/jtmeaker-hash/Sound-sync/commit/6aaecdb38cea0f431042d54b10c9327eff32c6f7)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/34783780370)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

docs: finalize Stage 4 completion status in AGY_STAGE_STATE.md

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
6aaecdb docs: finalize Stage 4 completion status in AGY_STAGE_STATE.md
 docs/AGY_STAGE_STATE.md | 2 +-
 1 file changed, 1 insertion(+), 1 deletion(-)
```

---

## CI Run 34808655063 — FAIL

- **Date:** 2026-09-14T05:17:49.499650+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `Debug`
- **Commit:** [`53e0fb9234`](https://github.com/jtmeaker-hash/Sound-sync/commit/53e0fb9234ebe374d804bade4e1c5c0dff473bec)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/34808655063)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `ExampleRobolectricTest > ScanStateManager correctly recovers from interrupted scan FAILED`
- `java.lang.AssertionError at ExampleRobolectricTest.kt:215`
- `StorageDiagnosticsAndScanLifecycleTest > testAppKilledReopenedDuringScan FAILED`
- `java.lang.AssertionError at StorageDiagnosticsAndScanLifecycleTest.kt:266`
- `456 tests completed, 2 failed, 2 skipped`
- `> Task :app:testDebugUnitTest FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`
- `Execution failed for task ':app:testDebugUnitTest'.`
- `BUILD FAILED in 5m 3s`

### Summary of changes

feat: Stage 29 - Queue, Shuffle Order, and Playback History Architecture

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
53e0fb9 feat: Stage 29 - Queue, Shuffle Order, and Playback History Architecture
 SOUNDSYNC_UPGRADE_25_29_PROGRESS.md                |  49 ++-
 .../com/example/player/PersistentQueueManager.kt   | 258 +++++++++++--
 .../com/example/state/PersistentSessionManager.kt  |  38 +-
 .../com/example/state/PersistentSessionModels.kt   |   4 +-
 .../main/java/com/example/ui/MainDjViewModel.kt    | 194 +++-------
 .../example/QueueShuffleHistoryArchitectureTest.kt | 425 +++++++++++++++++++++
 6 files changed, 793 insertions(+), 175 deletions(-)
```

---

## CI Run 34816120284 — FAIL

- **Date:** 2026-09-14T07:11:39.817304+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `Debug`
- **Commit:** [`1f7276099c`](https://github.com/jtmeaker-hash/Sound-sync/commit/1f7276099c13f1caa2102b67259de07951ee4a62)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/34816120284)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `DjPrepEnvironmentTest > testMemoryCuesOrderedNavigation FAILED`
- `java.lang.AssertionError at DjPrepEnvironmentTest.kt:264`
- `ExampleRobolectricTest > ScanStateManager correctly recovers from interrupted scan FAILED`
- `java.lang.AssertionError at ExampleRobolectricTest.kt:215`
- `StorageDiagnosticsAndScanLifecycleTest > testAppKilledReopenedDuringScan FAILED`
- `java.lang.AssertionError at StorageDiagnosticsAndScanLifecycleTest.kt:266`
- `463 tests completed, 3 failed, 2 skipped`
- `> Task :app:testDebugUnitTest FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`

### Summary of changes

fix(metadata): integrate manual cover art selection with MD Approval queue and physical tag embedding

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
1f72760 fix(metadata): integrate manual cover art selection with MD Approval queue and physical tag embedding
 .../com/example/metadata/MetadataFileWriteQueue.kt |  72 ++-
 .../metadata/review/MetadataReviewManager.kt       | 134 ++++-
 app/src/main/java/com/example/model/Models.kt      |   9 +-
 .../ui/components/MetadataProvenanceBadge.kt       |  10 +-
 .../example/ui/inspector/TrackInspectorScreen.kt   | 133 ++++-
 .../ui/library/MetadataReviewInboxScreen.kt        | 202 ++++---
 .../ManualCoverArtMdApprovalIntegrationTest.kt     | 580 +++++++++++++++++++++
 7 files changed, 1032 insertions(+), 108 deletions(-)
```

---

## CI Run 34839546832 — FAIL

- **Date:** 2026-09-14T11:47:46.544068+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `Debug`
- **Commit:** [`c590410e30`](https://github.com/jtmeaker-hash/Sound-sync/commit/c590410e304ab4ad53db3ef0eaef685791a1777a)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/34839546832)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `ExampleRobolectricTest > ScanStateManager correctly recovers from interrupted scan FAILED`
- `java.lang.AssertionError at ExampleRobolectricTest.kt:215`
- `StorageDiagnosticsAndScanLifecycleTest > testAppKilledReopenedDuringScan FAILED`
- `java.lang.AssertionError at StorageDiagnosticsAndScanLifecycleTest.kt:266`
- `481 tests completed, 2 failed, 2 skipped`
- `> Task :app:testDebugUnitTest FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`
- `Execution failed for task ':app:testDebugUnitTest'.`
- `BUILD FAILED in 3m 58s`

### Summary of changes

SoundSync Update Pack: Stages 1 & 2 Completed

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
c590410 SoundSync Update Pack: Stages 1 & 2 Completed
 SOUNDSYNC_UPDATE_PACK_STATUS.md                    |   62 ++
 .../main/java/com/example/audio/DjAudioEngine.kt   |    8 +
 .../java/com/example/audio/HaasSurroundEffect.kt   |   64 +-
 .../main/java/com/example/audio/ParametricEq.kt    |  102 +-
 .../java/com/example/audio/ParametricEqManager.kt  |  438 ++++++--
 .../com/example/backup/SoundSyncBackupManager.kt   |   22 +-
 app/src/main/java/com/example/data/AppDatabase.kt  |   55 +-
 app/src/main/java/com/example/data/ArtistDao.kt    |   59 ++
 app/src/main/java/com/example/data/ArtistEntity.kt |   45 +
 .../metadata/artist/ArtistCollaborationParser.kt   |  125 +++
 .../example/metadata/artist/ArtistIndexManager.kt  |  202 ++++
 app/src/main/java/com/example/ui/MainDjScreen.kt   |   22 +-
 .../main/java/com/example/ui/MainDjViewModel.kt    |   40 +-
 .../example/ui/components/ParametricEqDialog.kt    | 1050 +++++++++++++++-----
 .../example/ui/settings/LibrarySettingsScreen.kt   |   58 +-
 .../example/ui/settings/MetadataSettingsScreen.kt  |  126 +++
 .../com/example/LocalFirstMetadataMergeTest.kt     |    1 +
 .../ManualCoverArtMdApprovalIntegrationTest.kt     |    1 +
 .../java/com/example/MetadataSafetyPipelineTest.kt |    1 +
 .../com/example/SoundSyncStep1FoundationTest.kt    |    1 +
 .../example/Stage1LibraryMetadataSettingsTest.kt   |  284 ++++++
 .../java/com/example/audio/ParametricEqTest.kt     |  138 ++-
 22 files changed, 2437 insertions(+), 467 deletions(-)
```

---

## CI Run 34857596919 — FAIL

- **Date:** 2026-09-14T14:55:11.405088+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `Debug`
- **Commit:** [`144df2460c`](https://github.com/jtmeaker-hash/Sound-sync/commit/144df2460c185dabc55d4aaf1ce164b40547f1dd)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/34857596919)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `ExampleRobolectricTest > ScanStateManager correctly recovers from interrupted scan FAILED`
- `java.lang.AssertionError at ExampleRobolectricTest.kt:215`
- `Stage3DjPrepTest > testMemoryCueCrudAndNavigation FAILED`
- `java.lang.AssertionError at Stage3DjPrepTest.kt:185`
- `StorageDiagnosticsAndScanLifecycleTest > testAppKilledReopenedDuringScan FAILED`
- `java.lang.AssertionError at StorageDiagnosticsAndScanLifecycleTest.kt:266`
- `495 tests completed, 3 failed, 2 skipped`
- `> Task :app:testDebugUnitTest FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`

### Summary of changes

SoundSync Update Pack: Completed Stages 3 & 4 (DJ Prep Environment, Full Backup/Restore v3, Integration & Regression QA)

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
144df24 SoundSync Update Pack: Completed Stages 3 & 4 (DJ Prep Environment, Full Backup/Restore v3, Integration & Regression QA)
 SOUNDSYNC_UPDATE_PACK_STATUS.md                    |  136 ++-
 .../main/java/com/example/audio/DjAudioEngine.kt   |   62 +-
 .../com/example/backup/SoundSyncBackupManager.kt   |   69 +-
 .../com/example/backup/SoundSyncBackupModels.kt    |   98 +-
 .../main/java/com/example/djprep/DjPrepManager.kt  |   98 +-
 .../metadata/artist/ArtistCollaborationParser.kt   |   33 +-
 .../java/com/example/ui/djprep/DjPrepScreen.kt     | 1279 +++++++++++++++-----
 .../example/ui/sidemenu/SideNavigationDrawer.kt    |   11 +-
 .../example/Stage4IntegrationAndRegressionTest.kt  |  337 ++++++
 .../java/com/example/djprep/Stage3DjPrepTest.kt    |  414 +++++++
 10 files changed, 2182 insertions(+), 355 deletions(-)
```

---

## CI Run 34928170299 — FAIL

- **Date:** 2026-09-15T04:23:39.676806+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`5045d5d7b8`](https://github.com/jtmeaker-hash/Sound-sync/commit/5045d5d7b89ed9d3e052e951733bb16317c0e783)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/34928170299)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `ExampleRobolectricTest > ScanStateManager correctly recovers from interrupted scan FAILED`
- `java.lang.AssertionError at ExampleRobolectricTest.kt:215`
- `Stage3DjPrepTest > testMemoryCueCrudAndNavigation FAILED`
- `java.lang.AssertionError at Stage3DjPrepTest.kt:185`
- `StorageDiagnosticsAndScanLifecycleTest > testAppKilledReopenedDuringScan FAILED`
- `java.lang.AssertionError at StorageDiagnosticsAndScanLifecycleTest.kt:266`
- `495 tests completed, 3 failed, 2 skipped`
- `> Task :app:testDebugUnitTest FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`

### Summary of changes

Merge pull request #8 from jtmeaker-hash/Debug

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
5045d5d Merge pull request #8 from jtmeaker-hash/Debug

 .github/workflows/soundsync-ci-diagnostics.yml     |  179 ++
 SOUNDSYNC_UPDATE_PACK_STATUS.md                    |  120 ++
 SOUNDSYNC_UPGRADE_25_29_PROGRESS.md                |  225 ++
 app/src/main/AndroidManifest.xml                   |    1 -
 app/src/main/java/com/example/MainActivity.kt      |    6 +
 .../com/example/analysis/TrackAnalysisManager.kt   |   68 +-
 .../main/java/com/example/audio/DjAudioEngine.kt   |  179 +-
 .../java/com/example/audio/HaasSurroundEffect.kt   |   64 +-
 .../main/java/com/example/audio/ParametricEq.kt    |  102 +-
 .../java/com/example/audio/ParametricEqManager.kt  |  438 +++-
 .../com/example/backup/SoundSyncBackupManager.kt   |  137 +-
 .../com/example/backup/SoundSyncBackupModels.kt    |  109 +-
 app/src/main/java/com/example/brain/BrainModels.kt |   73 +
 .../main/java/com/example/brain/LibraryBrain.kt    |  890 ++++++++
 .../com/example/carmode/BluetoothCarReceiver.kt    |   10 +
 .../com/example/command/CommandPaletteEngine.kt    |  431 ++++
 .../com/example/command/CommandPaletteModels.kt    |  112 +
 .../com/example/command/CommandPaletteParser.kt    |  192 ++
 app/src/main/java/com/example/data/AppDatabase.kt  |  242 ++-
 app/src/main/java/com/example/data/ArtistDao.kt    |   59 +
 app/src/main/java/com/example/data/ArtistEntity.kt |   45 +
 .../java/com/example/data/MetadataBackupEntity.kt  |    3 +-
 .../main/java/com/example/data/TrackBrainDao.kt    |   87 +
 .../com/example/data/TrackBrainStatusEntity.kt     |   62 +
 app/src/main/java/com/example/data/TrackDao.kt     |   20 +-
 app/src/main/java/com/example/data/TrackEntity.kt  |   17 +-
 .../com/example/diagnostics/AudioOutputTracker.kt  |  253 +++
 .../example/diagnostics/DeveloperModeManager.kt    |  103 +
 .../com/example/diagnostics/DiagnosticLogger.kt    |  191 ++
 .../com/example/diagnostics/DiagnosticModels.kt    |  204 ++
 .../diagnostics/DiagnosticReportExporter.kt        |  527 +++++
 .../java/com/example/diagnostics/SelfTestRunner.kt |  745 +++++++
 app/src/main/java/com/example/djprep/DjPrepDao.kt  |   42 +
 .../main/java/com/example/djprep/DjPrepEntity.kt   |  171 ++
 .../main/java/com/example/djprep/DjPrepManager.kt  |  745 +++++++
 .../main/java/com/example/djprep/DjPrepModels.kt   |  125 ++
 .../com/example/doctor/LibraryDoctorAuditor.kt     |  593 ++++++
 .../java/com/example/doctor/LibraryDoctorModels.kt |   84 +
 .../com/example/doctor/LibraryDoctorPreferences.kt |  153 ++
 .../example/doctor/LibraryDoctorRepairManager.kt   |  280 +++
 .../metadata/AudioEmbeddedMetadataReader.kt        |   19 +-
 .../com/example/metadata/MetadataFileWriteQueue.kt |   72 +-
 .../java/com/example/metadata/MetadataResolver.kt  |  122 +-
 .../metadata/artist/ArtistCollaborationParser.kt   |  150 ++
 .../example/metadata/artist/ArtistIndexManager.kt  |  202 ++
 .../metadata/backup/MetadataBackupManager.kt       |    6 +-
 .../metadata/merge/LocalFirstMetadataMerger.kt     |  439 ++++
 .../metadata/merge/LocalFirstMetadataModels.kt     |  109 +
 .../metadata/review/MetadataReviewManager.kt       |  249 ++-
 app/src/main/java/com/example/model/Models.kt      |   34 +-
 .../com/example/player/PersistentQueueManager.kt   |  363 +++-
 .../com/example/state/PersistentSessionManager.kt  |  801 +++++++
 .../com/example/state/PersistentSessionModels.kt   |  105 +
 .../java/com/example/storage/ScanStateManager.kt   |   54 +-
 app/src/main/java/com/example/ui/MainDjScreen.kt   |  152 +-
 .../main/java/com/example/ui/MainDjViewModel.kt    |  643 ++++--
 .../com/example/ui/command/CommandPaletteDialog.kt |  975 +++++++++
 .../com/example/ui/components/LibraryBrainCard.kt  |  409 ++++
 .../ui/components/MetadataProvenanceBadge.kt       |   10 +-
 .../example/ui/components/ParametricEqDialog.kt    | 1050 ++++++---
 .../ui/diagnostics/DeveloperDiagnosticsScreen.kt   |  816 +++++++
 .../com/example/ui/diagnostics/SelfTestScreen.kt   |  475 +++++
 .../java/com/example/ui/djprep/DjPrepScreen.kt     | 2230 ++++++++++++++++++++
 .../com/example/ui/doctor/LibraryDoctorScreen.kt   |  683 ++++++
 .../example/ui/inspector/TrackInspectorScreen.kt   |  133 +-
 .../com/example/ui/library/LibraryHealthScreen.kt  |   28 +-
 .../ui/library/MetadataReviewInboxScreen.kt        |  284 ++-
 .../com/example/ui/settings/AboutSettingsScreen.kt |  413 ++++
 .../example/ui/settings/GitHubSettingsScreen.kt    |   13 +-
 .../example/ui/settings/LibrarySettingsScreen.kt   |   55 +-
 .../example/ui/settings/MetadataSettingsScreen.kt  |  126 ++
 .../com/example/ui/sidemenu/SideMenuDestination.kt |    5 +
 .../example/ui/sidemenu/SideNavigationDrawer.kt    |   91 +-
 app/src/main/java/com/example/ui/theme/Color.kt    |    1 +
 .../main/java/com/example/util/AlbumArtHelper.kt   |    2 +-
 .../java/com/example/CommandPaletteEngineTest.kt   |  170 ++
 .../test/java/com/example/DjPrepEnvironmentTest.kt |  644 ++++++
 .../com/example/LocalFirstMetadataMergeTest.kt     |  549 +++++
 .../ManualCoverArtMdApp
```

---

## CI Run 34930333112 — FAIL

- **Date:** 2026-09-15T04:52:30.581243+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`8350b13774`](https://github.com/jtmeaker-hash/Sound-sync/commit/8350b1377442aab2fb3c7ac2f56cfe7ea96d11ef)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/34930333112)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ❌ FAIL |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `e: file:///home/runner/work/Sound-sync/Sound-sync/app/src/main/java/com/example/metadata/MetadataFileWriter.kt:56:57 Unresolved reference 'ARTWORK_WRITE_FAILED'.`
- `e: file:///home/runner/work/Sound-sync/Sound-sync/app/src/main/java/com/example/ui/inspector/TrackInspectorScreen.kt:290:35 'when' expression must be exhaustive. Add the 'is ArtworkEmbedded', 'is ArtworkWriteFailed', 'is TextWritten' branches or an 'else' branch.`
- `> Task :app:compileDebugKotlin FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`
- `Execution failed for task ':app:compileDebugKotlin'.`
- `> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction`
- `BUILD FAILED in 1m 21s`

**Debug APK failed (exit 1)**
- `e: file:///home/runner/work/Sound-sync/Sound-sync/app/src/main/java/com/example/metadata/MetadataFileWriter.kt:56:57 Unresolved reference 'ARTWORK_WRITE_FAILED'.`
- `e: file:///home/runner/work/Sound-sync/Sound-sync/app/src/main/java/com/example/ui/inspector/TrackInspectorScreen.kt:290:35 'when' expression must be exhaustive. Add the 'is ArtworkEmbedded', 'is ArtworkWriteFailed', 'is TextWritten' branches or an 'else' branch.`
- `> Task :app:compileDebugKotlin FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`
- `Execution failed for task ':app:compileDebugKotlin'.`
- `> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction`
- `BUILD FAILED in 58s`

### Summary of changes

feat: improve file access and metadata processing

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
8350b13 feat: improve file access and metadata processing
 .../com/example/analysis/AudioQualityInspector.kt  |  73 ++++++-
 .../com/example/analysis/WavContainerParser.kt     |  13 ++
 .../main/java/com/example/audio/BitrateProbe.kt    |  30 ++-
 .../com/example/metadata/ArtworkEmbeddingHelper.kt |  26 ++-
 .../metadata/AudioEmbeddedMetadataReader.kt        |  27 ++-
 .../com/example/metadata/MetadataFileWriteQueue.kt |  24 ++-
 .../com/example/metadata/MetadataFileWriter.kt     |  80 ++++++--
 .../java/com/example/metadata/MetadataResolver.kt  |  15 +-
 .../metadata/artwork/ArtworkEmbedValidator.kt      | 161 ++++++++++++++++
 .../java/com/example/storage/AudioTagWriter.kt     | 193 ++++++++++++-------
 .../java/com/example/storage/SafStorageManager.kt  |   7 +
 .../storage/StorageWritePermissionHelper.kt        | 126 +++++++-----
 .../example/AudioArtworkAndFieldsIntegrityTest.kt  | 211 +++++++++++++++++++++
 gradlew                                            |   0
 scripts/ci/append_ci_report.py                     |   0
 scripts/ci/backfill_history.py                     |   0
 16 files changed, 836 insertions(+), 150 deletions(-)
```

---

## CI Run 34934275596 — FAIL

- **Date:** 2026-09-15T05:54:35.769162+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`4f4f94d8ee`](https://github.com/jtmeaker-hash/Sound-sync/commit/4f4f94d8eeb49ed0641310a3d9f41586a57a8265)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/34934275596)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `AudioMetadataWriteIntegrationTest > mp3 file metadata writing embeds ID3v23 tags and artwork with audio frames preserved FAILED`
- `java.lang.RuntimeException at ImageUtil.java:131`
- `Caused by: java.lang.RuntimeException at ImageUtil.java:131`
- `Caused by: javax.imageio.IIOException at JPEGImageReader.java:-2`
- `AudioMetadataWriteIntegrationTest > sensible merge preserves existing embedded tags when new track payload contains blanks FAILED`
- `java.lang.AssertionError at AudioMetadataWriteIntegrationTest.kt:711`
- `AudioMetadataWriteIntegrationTest > aiff file metadata writing embeds ID3 chunk with verbatim PCM audio preservation FAILED`
- `AudioMetadataWriteIntegrationTest > wav file metadata writing preserves audio frames and passes pre-commit audio validation FAILED`
- `AudioMetadataWriteIntegrationTest > 24-bit 96kHz High-Res PCM WAV metadata writing preserves audio verbatim FAILED`
- `AudioMetadataWriteIntegrationTest > m4a file metadata writing adjusts nested stco chunk offsets and passes pre-commit audio validation FAILED`

### Summary of changes

refactor: unify metadata write success states

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
4f4f94d refactor: unify metadata write success states
 app/src/main/java/com/example/metadata/MetadataFileWriter.kt   |  4 +---
 app/src/main/java/com/example/model/Models.kt                  |  6 +++++-
 .../java/com/example/ui/components/MetadataProvenanceBadge.kt  | 10 +++++++---
 .../main/java/com/example/ui/inspector/TrackInspectorScreen.kt |  7 +++++--
 4 files changed, 18 insertions(+), 9 deletions(-)
```

---

## CI Run 34936150564 — FAIL

- **Date:** 2026-09-15T06:21:57.875760+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`56ef33f92e`](https://github.com/jtmeaker-hash/Sound-sync/commit/56ef33f92e9c0c42182cb6cc3fcfc727e4a398d7)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/34936150564)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `ExampleRobolectricTest > ScanStateManager correctly recovers from interrupted scan FAILED`
- `java.lang.AssertionError at ExampleRobolectricTest.kt:215`
- `Stage3DjPrepTest > testMemoryCueCrudAndNavigation FAILED`
- `java.lang.AssertionError at Stage3DjPrepTest.kt:185`
- `StorageDiagnosticsAndScanLifecycleTest > testAppKilledReopenedDuringScan FAILED`
- `java.lang.AssertionError at StorageDiagnosticsAndScanLifecycleTest.kt:266`
- `501 tests completed, 3 failed, 2 skipped`
- `> Task :app:testDebugUnitTest FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`

### Summary of changes

refactor(metadata): optimize artwork validation

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
56ef33f refactor(metadata): optimize artwork validation
 .../com/example/metadata/MetadataFileWriter.kt     | 24 ++++--
 .../metadata/artwork/ArtworkEmbedValidator.kt      | 88 ++++++++++++++++++++--
 2 files changed, 101 insertions(+), 11 deletions(-)
```

---

## CI Run 34943214269 — PASS

- **Date:** 2026-09-15T07:49:08.246344+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`929054f97b`](https://github.com/jtmeaker-hash/Sound-sync/commit/929054f97b2bf20f4b69e280e09b4bf08792ff34)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/34943214269)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

fix: improve cue ID uniqueness and scan recovery logic

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
929054f fix: improve cue ID uniqueness and scan recovery logic
 app/src/main/java/com/example/djprep/DjPrepManager.kt     | 2 +-
 app/src/main/java/com/example/storage/ScanStateManager.kt | 5 +++--
 2 files changed, 4 insertions(+), 3 deletions(-)
```

---

## CI Run 35080001811 — PASS

- **Date:** 2026-09-16T09:38:50.877761+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`619fbb7749`](https://github.com/jtmeaker-hash/Sound-sync/commit/619fbb77494550d3c6e0bb8e482d20ddd84ee379)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35080001811)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

feat: add cover art filtering and management

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
619fbb7 feat: add cover art filtering and management
 .../metadata/artwork/CanonicalArtworkResolver.kt   |  14 +
 app/src/main/java/com/example/model/Models.kt      |   6 +
 .../main/java/com/example/ui/MainDjViewModel.kt    |   7 +
 .../example/ui/inspector/TrackInspectorScreen.kt   |  32 +-
 .../com/example/ui/library/LocalLibraryScreen.kt   |   3 +
 .../java/com/example/ui/library/SongsScreen.kt     | 377 ++++++++++++++++++---
 .../com/example/ui/sidemenu/SideMenuDestination.kt |  10 +-
 .../example/ui/sidemenu/SideNavigationDrawer.kt    | 228 ++++++++-----
 .../main/java/com/example/util/AlbumArtHelper.kt   | 126 +++++++
 9 files changed, 667 insertions(+), 136 deletions(-)
```

---

## CI Run 35108816490 — PASS

- **Date:** 2026-09-16T14:35:09.019013+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`450f913fff`](https://github.com/jtmeaker-hash/Sound-sync/commit/450f913fff06371d04bc741233006078303b146f)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35108816490)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

fix: add crash loop protection and fault tolerance

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
450f913 fix: add crash loop protection and fault tolerance
 .../main/java/com/example/SoundSyncApplication.kt  |   5 +
 .../main/java/com/example/ui/MainDjViewModel.kt    | 117 +++++--
 .../com/example/ui/library/AlbumDetailScreen.kt    |  21 +-
 .../java/com/example/ui/library/AlbumsScreen.kt    |  89 +++--
 .../com/example/ui/library/ArtistDetailScreen.kt   |   6 +-
 .../com/example/ui/library/LocalLibraryScreen.kt   |   4 +-
 .../main/java/com/example/util/AlbumArtHelper.kt   | 363 ++++++++++++---------
 .../com/example/util/CrashProtectionManager.kt     | 121 +++++++
 8 files changed, 509 insertions(+), 217 deletions(-)
```

---

## CI Run 35119339701 — FAIL

- **Date:** 2026-09-16T16:08:53.517502+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`ad02cb8048`](https://github.com/jtmeaker-hash/Sound-sync/commit/ad02cb804881e24aeec96b3bd03c91bb988efdee)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35119339701)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `SoundSyncStep3LyricsIntelligenceTest > testGetLibraryHealthInsights FAILED`
- `java.lang.AssertionError at SoundSyncStep3LyricsIntelligenceTest.kt:443`
- `501 tests completed, 1 failed, 2 skipped`
- `> Task :app:testDebugUnitTest FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`
- `Execution failed for task ':app:testDebugUnitTest'.`
- `BUILD FAILED in 4m 58s`

### Summary of changes

feat: centralize artwork detection logic

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
ad02cb8 feat: centralize artwork detection logic
 .../main/java/com/example/SoundSyncApplication.kt  |   5 +
 .../main/java/com/example/brain/LibraryBrain.kt    |  10 +-
 .../com/example/command/CommandPaletteEngine.kt    |   2 +-
 .../com/example/doctor/LibraryDoctorAuditor.kt     |  17 +-
 .../intelligence/SoundSyncIntelligenceEngine.kt    |   6 +-
 .../com/example/metadata/artwork/ArtworkModels.kt  |  13 +
 .../metadata/artwork/CanonicalArtworkDetector.kt   | 352 +++++++++++++++++++++
 .../metadata/artwork/CanonicalArtworkResolver.kt   |  15 +-
 .../com/example/smartcrate/SmartCrateEngine.kt     |   2 +-
 .../main/java/com/example/ui/MainDjViewModel.kt    |  14 +-
 .../example/ui/components/LibraryInsightsDialog.kt |   6 +-
 .../com/example/ui/library/LibraryHealthScreen.kt  |   2 +-
 .../java/com/example/ui/library/SongsScreen.kt     |   6 +-
 .../example/ui/stats/ListeningStatisticsScreen.kt  |   3 +-
 .../main/java/com/example/util/AlbumArtHelper.kt   | 121 +------
 15 files changed, 424 insertions(+), 150 deletions(-)
```

---

## CI Run 35200800085 — PASS

- **Date:** 2026-09-17T08:44:23.921583+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`1b82b32835`](https://github.com/jtmeaker-hash/Sound-sync/commit/1b82b32835d33c60c2b274d5228695a36e17adab)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35200800085)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

fix(artwork): unify canonical artwork detection and cover-art filter consistency

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
1b82b32 fix(artwork): unify canonical artwork detection and cover-art filter consistency
 .../metadata/artwork/ArtworkStatusResolver.kt      |  33 ++
 .../metadata/artwork/CanonicalArtworkDetector.kt   |  40 +-
 app/src/main/java/com/example/model/Models.kt      |  16 +-
 .../java/com/example/CanonicalArtworkStatusTest.kt | 428 +++++++++++++++++++++
 gradlew                                            |   0
 5 files changed, 497 insertions(+), 20 deletions(-)
```

---

## CI Run 35206312404 — PASS

- **Date:** 2026-09-17T09:44:50.092727+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`c6a5bfd028`](https://github.com/jtmeaker-hash/Sound-sync/commit/c6a5bfd02849d8f9c6a1261a95a7724d0005f062)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35206312404)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

Merge remote-tracking branch 'origin/Debug' (reconcile history with main)

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
c6a5bfd Merge remote-tracking branch 'origin/Debug' (reconcile history with main)
```

---

## CI Run 35211174072 — PASS

- **Date:** 2026-09-17T10:35:09.644378+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`38b9185099`](https://github.com/jtmeaker-hash/Sound-sync/commit/38b91850999bbd6d361436352a303154141c2724)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35211174072)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

ci: enable Build Debug APK on main and restrict release publishing to tags

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
38b9185 ci: enable Build Debug APK on main and restrict release publishing to tags
 .github/workflows/build-apk.yml       | 3 +--
 .github/workflows/build-debug-apk.yml | 1 +
 2 files changed, 2 insertions(+), 2 deletions(-)
```

---

## CI Run 35217130927 — PASS

- **Date:** 2026-09-17T11:48:24.560573+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`ee4c3f756d`](https://github.com/jtmeaker-hash/Sound-sync/commit/ee4c3f756df67f0a780dd6a4ba2b5d0692ef56bf)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35217130927)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

fix(backup): resolve restore freeze, ANR, and crash regressions

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
ee4c3f7 fix(backup): resolve restore freeze, ANR, and crash regressions
 .../com/example/analysis/TrackAnalysisManager.kt   |   8 +-
 .../com/example/backup/RestoreDiagnosticLogger.kt  |  76 ++++
 .../com/example/backup/SoundSyncBackupManager.kt   | 391 +++++++++++++++------
 .../com/example/backup/SoundSyncBackupModels.kt    |  50 ++-
 .../main/java/com/example/backup/TrackMatcher.kt   | 166 ++++++++-
 .../main/java/com/example/brain/LibraryBrain.kt    |  22 +-
 .../java/com/example/model/PlayabilityModels.kt    |   3 +-
 .../main/java/com/example/ui/MainDjViewModel.kt    |  16 +-
 .../com/example/ui/components/LocalMusicView.kt    |  13 +-
 .../example/ui/settings/BackupSettingsScreen.kt    | 150 +++++++-
 .../java/com/example/BackupRestorePipelineTest.kt  | 254 +++++++++++++
 11 files changed, 1020 insertions(+), 129 deletions(-)
```

---

## CI Run 35222717790 — PASS

- **Date:** 2026-09-17T12:49:34.648802+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`d171449b41`](https://github.com/jtmeaker-hash/Sound-sync/commit/d171449b41780d07d83ac8bd92e7fb5ae1f16c46)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35222717790)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

Fix metadata re-analysis loop and startup ANR freeze

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
d171449 Fix metadata re-analysis loop and startup ANR freeze
 .../com/example/analysis/TrackAnalysisManager.kt   |  82 ++++++-
 .../metadata/LocalMetadataCompletenessChecker.kt   | 200 +++++++++++++++++
 .../com/example/storage/LocalFileSystemScanner.kt  |  43 +++-
 .../java/com/example/storage/MediaScannerHelper.kt |  51 ++++-
 .../main/java/com/example/ui/MainDjViewModel.kt    |  50 +++--
 .../com/example/ui/library/LocalLibraryScreen.kt   |   6 +-
 .../java/com/example/ui/library/SongsScreen.kt     | 131 +++++++-----
 .../com/example/LocalMetadataCompletenessTest.kt   | 238 +++++++++++++++++++++
 8 files changed, 720 insertions(+), 81 deletions(-)
```

---

## CI Run 35296782123 — PASS

- **Date:** 2026-09-18T01:54:21.262636+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`85ed88c9be`](https://github.com/jtmeaker-hash/Sound-sync/commit/85ed88c9be348abbfae742287183bdce9031c3dd)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35296782123)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

feat(metadata): unify write and approve workflow with all proposed fields checked by default

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
85ed88c feat(metadata): unify write and approve workflow with all proposed fields checked by default
 .../metadata/review/MetadataReviewManager.kt       | 269 ++++++++++++-------
 .../ui/library/MetadataReviewInboxScreen.kt        | 290 +++++++++++++--------
 .../ManualCoverArtMdApprovalIntegrationTest.kt     | 194 ++++++++++++++
 3 files changed, 548 insertions(+), 205 deletions(-)
```

---

## CI Run 35306147876 — PASS

- **Date:** 2026-09-18T04:19:52.181395+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`a204a04208`](https://github.com/jtmeaker-hash/Sound-sync/commit/a204a042083cb0ae9328eb3932f800f12b244e1b)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35306147876)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

feat(audio,storage): batch metadata permissions, startup bluetooth check, true 6-band EQ, and audible Haas spatializer

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
a204a04 feat(audio,storage): batch metadata permissions, startup bluetooth check, true 6-band EQ, and audible Haas spatializer
 app/src/main/java/com/example/MainActivity.kt      |  84 +++-
 .../main/java/com/example/audio/DjAudioEngine.kt   | 153 ++++--
 .../java/com/example/audio/HaasSurroundEffect.kt   | 135 +++--
 .../main/java/com/example/audio/ParametricEq.kt    |  54 +-
 .../java/com/example/carmode/CarModeManager.kt     |   2 +-
 .../com/example/carmode/CarModeSettingsScreen.kt   |  89 +++-
 .../com/example/diagnostics/AudioOutputTracker.kt  |  14 +-
 .../com/example/metadata/MetadataFileWriteQueue.kt |  73 ++-
 .../metadata/review/MetadataReviewManager.kt       |  27 +
 app/src/main/java/com/example/ui/MainDjScreen.kt   |  13 +
 .../main/java/com/example/ui/MainDjViewModel.kt    |  38 ++
 .../com/example/ui/components/AudioEffectsPanel.kt | 541 ++++++++++++++++-----
 .../ui/components/NowPlayingSettingsSheet.kt       |  14 +-
 .../ui/library/MetadataReviewInboxScreen.kt        |  26 +-
 14 files changed, 967 insertions(+), 296 deletions(-)
```

---

## CI Run 35311469763 — PASS

- **Date:** 2026-09-18T05:43:08.260372+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`cb55e65ba6`](https://github.com/jtmeaker-hash/Sound-sync/commit/cb55e65ba623fc54c1ef1297d7837836d3611518)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35311469763)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

perf: complete Android performance audit, reactive cascade throttle, zero-alloc waveform, fast-path media scan

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
cb55e65 perf: complete Android performance audit, reactive cascade throttle, zero-alloc waveform, fast-path media scan
 .../com/example/analysis/TrackAnalysisManager.kt   | 46 ++++++++-------
 app/src/main/java/com/example/data/AppDatabase.kt  | 16 ++++-
 app/src/main/java/com/example/data/TrackEntity.kt  |  4 +-
 .../metadata/artwork/CanonicalArtworkDetector.kt   |  7 ---
 .../com/example/storage/LocalFileSystemScanner.kt  | 59 ++++++++++---------
 .../java/com/example/storage/MediaScannerHelper.kt | 15 +++++
 .../example/storage/TrackSelfHealingResolver.kt    | 33 +++++++++++
 .../main/java/com/example/ui/MainDjViewModel.kt    | 60 ++++++++++++++-----
 .../example/ui/components/DjFileExplorerView.kt    |  4 +-
 .../java/com/example/ui/components/DjMiniPlayer.kt | 68 +++++++++++++---------
 .../example/ui/components/DuplicateFinderSheet.kt  |  2 +-
 .../example/ui/components/RekordboxWaveformView.kt | 24 +++++---
 .../ui/components/SpectrogramAnalyzerView.kt       |  2 +-
 .../com/example/ui/library/SmartCratesScreen.kt    |  4 +-
 14 files changed, 229 insertions(+), 115 deletions(-)
```

---

## CI Run 35327875359 — FAIL

- **Date:** 2026-09-18T09:12:34.170327+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`6c9bbc6018`](https://github.com/jtmeaker-hash/Sound-sync/commit/6c9bbc601832ec7721b18bc879b115d2da6cabf1)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35327875359)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `StorageDiagnosticsAndScanLifecycleTest > testOneFailedTrackDoesNotKeepEntireScanAliveForever FAILED`
- `org.junit.ComparisonFailure at StorageDiagnosticsAndScanLifecycleTest.kt:197`
- `StorageDiagnosticsAndScanLifecycleTest > testRetryableErrorRetriesWithinBoundThenTerminatesCorrectly FAILED`
- `org.junit.ComparisonFailure at StorageDiagnosticsAndScanLifecycleTest.kt:231`
- `558 tests completed, 2 failed, 2 skipped`
- `> Task :app:testDebugUnitTest FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`
- `Execution failed for task ':app:testDebugUnitTest'.`
- `BUILD FAILED in 4m 15s`

### Summary of changes

fix(audio,player): rock-solid BPM/key DSP analysis and continuous playback repair

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
6c9bbc6 fix(audio,player): rock-solid BPM/key DSP analysis and continuous playback repair
 .../com/example/analysis/TrackAnalysisManager.kt   |  35 +-
 .../main/java/com/example/audio/AudioDecoder.kt    |   9 +-
 app/src/main/java/com/example/data/TrackDao.kt     |  11 +-
 .../com/example/metadata/LocalPcmAudioAnalyzer.kt  | 552 +++++++++++++++++----
 app/src/main/java/com/example/model/Models.kt      |   4 +-
 .../com/example/player/PersistentQueueManager.kt   |  50 +-
 .../main/java/com/example/ui/MainDjViewModel.kt    |  25 +-
 .../com/example/BpmKeyAnalysisRegressionTest.kt    | 215 ++++++++
 .../example/ContinuousPlaybackRegressionTest.kt    | 186 +++++++
 9 files changed, 954 insertions(+), 133 deletions(-)
```

---

## CI Run 35340432440 — PASS

- **Date:** 2026-09-18T11:41:29.552787+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`6eb38cdde7`](https://github.com/jtmeaker-hash/Sound-sync/commit/6eb38cdde704ffb30ee8ec8b5823e2f95195d314)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35340432440)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

fix(analysis): align terminal failure state with FAILED for storage scan lifecycle contracts

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
6eb38cd fix(analysis): align terminal failure state with FAILED for storage scan lifecycle contracts
 app/src/main/java/com/example/analysis/TrackAnalysisManager.kt | 4 ++--
 1 file changed, 2 insertions(+), 2 deletions(-)
```

---

## CI Run 35503716372 — FAIL

- **Date:** 2026-09-20T10:00:06.616599+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`7ff3cfb175`](https://github.com/jtmeaker-hash/Sound-sync/commit/7ff3cfb1753c4a9d5806018c542ca5040b6bf97a)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35503716372)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ❌ FAIL |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `> Task :app:compileDebugKotlin FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`
- `Execution failed for task ':app:compileDebugKotlin'.`
- `> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction`
- `BUILD FAILED in 1m 21s`

**Debug APK failed (exit 1)**
- `> Task :app:compileDebugKotlin FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`
- `Execution failed for task ':app:compileDebugKotlin'.`
- `> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction`
- `BUILD FAILED in 56s`

### Summary of changes

Import current Google AI Studio SoundSync ZIP

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
7ff3cfb Import current Google AI Studio SoundSync ZIP
 .../main/java/com/example/audio/DjAudioEngine.kt   | 184 +++++++++++++++---
 .../SoundSyncStep3LyricsIntelligenceTest.kt        |  46 ++++-
 .../java/com/example/audio/HaasSpatializerTest.kt  | 214 +++++++++++++++------
 3 files changed, 360 insertions(+), 84 deletions(-)
```

---

## CI Run 35511629152 — FAIL

- **Date:** 2026-09-20T12:51:00.361882+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`529e841332`](https://github.com/jtmeaker-hash/Sound-sync/commit/529e8413326187f6a45982eb3b610d226cb78a4b)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35511629152)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `SoundSyncStep3LyricsIntelligenceTest > testCanonicalArtworkDetectorPlaceholderDistinction FAILED`
- `java.lang.AssertionError at SoundSyncStep3LyricsIntelligenceTest.kt:474`
- `559 tests completed, 1 failed, 2 skipped`
- `> Task :app:testDebugUnitTest FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`
- `Execution failed for task ':app:testDebugUnitTest'.`
- `BUILD FAILED in 2m 57s`

### Summary of changes

fix: restore compatible Haas audio engine API

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
529e841 fix: restore compatible Haas audio engine API
 .../main/java/com/example/audio/DjAudioEngine.kt   | 184 +++---------------
 .../java/com/example/audio/HaasSpatializerTest.kt  | 214 ++++++---------------
 2 files changed, 81 insertions(+), 317 deletions(-)
```

---

## CI Run 35517566067 — FAIL

- **Date:** 2026-09-20T14:49:45.703014+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`a6e7f7cc08`](https://github.com/jtmeaker-hash/Sound-sync/commit/a6e7f7cc08078951fd8dbc317180ad8952d14a25)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35517566067)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ❌ FAIL |
| Debug APK | ❌ FAIL |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

### CI failures

**Unit tests failed (exit 1)**
- `> Task :app:compileDebugKotlin FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`
- `Execution failed for task ':app:compileDebugKotlin'.`
- `> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction`
- `BUILD FAILED in 1m 8s`

**Debug APK failed (exit 1)**
- `> Task :app:compileDebugKotlin FAILED`
- `FAILURE: Build failed with an exception.`
- `* What went wrong:`
- `Execution failed for task ':app:compileDebugKotlin'.`
- `> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction`
- `BUILD FAILED in 45s`

### Summary of changes

fix(audio): complete advanced Haas surround upgrade

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
a6e7f7c fix(audio): complete advanced Haas surround upgrade
 .../main/java/com/example/audio/DjAudioEngine.kt   | 184 +++++++--
 .../java/com/example/audio/HaasSurroundEffect.kt   | 421 ++++++++++++++++++---
 app/src/main/java/com/example/ui/MainDjScreen.kt   |  42 +-
 .../com/example/ui/components/AudioEffectsPanel.kt | 181 ++++++++-
 .../ui/components/NowPlayingSettingsSheet.kt       |  32 ++
 .../java/com/example/audio/HaasSpatializerTest.kt  | 213 ++++++++---
 6 files changed, 919 insertions(+), 154 deletions(-)
```

---

## CI Run 35522049772 — PASS

- **Date:** 2026-09-20T16:20:15.076161+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`e13502d8ff`](https://github.com/jtmeaker-hash/Sound-sync/commit/e13502d8ff91d0518f1292fac2c56b1ebccad905)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35522049772)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

fix(test): align canonical artwork placeholder test url with placeholder tokens

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
e13502d fix(test): align canonical artwork placeholder test url with placeholder tokens
 app/src/test/java/com/example/SoundSyncStep3LyricsIntelligenceTest.kt | 2 +-
 1 file changed, 1 insertion(+), 1 deletion(-)
```

---

## CI Run 35533122874 — PASS

- **Date:** 2026-09-20T19:47:58.341474+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`8b339d96ac`](https://github.com/jtmeaker-hash/Sound-sync/commit/8b339d96ac5f718b9339d8d2e6d1a96c2af39663)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35533122874)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

perf: complete stage 1 - baseline profiling and diagnostics

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
8b339d9 perf: complete stage 1 - baseline profiling and diagnostics
 app/src/main/java/com/example/MainActivity.kt      |  1 +
 .../main/java/com/example/SoundSyncApplication.kt  |  2 ++
 .../example/diagnostics/PerformanceDiagnostics.kt  | 29 ++++++++++++++++++++++
 docs/PERFORMANCE_BASELINE.md                       | 26 +++++++++++++++++++
 4 files changed, 58 insertions(+)
```

---

## CI Run 35563722876 — PASS

- **Date:** 2026-09-21T05:18:15.205267+00:00
- **Repository:** `jtmeaker-hash/Sound-sync`
- **Branch/ref:** `main`
- **Commit:** [`c0754f4b40`](https://github.com/jtmeaker-hash/Sound-sync/commit/c0754f4b402591642bc0e400ee270daa3c1654bd)
- **Author:** jtmeaker-hash <jtmeaker@gmail.com>
- **Actor:** `jtmeaker-hash`
- **Event:** `push`
- **Full log / report:** [Open GitHub Actions run](https://github.com/jtmeaker-hash/Sound-sync/actions/runs/35563722876)

### Test & build results

| Check | Result |
|---|---|
| Unit tests | ✅ PASS |
| Debug APK | ✅ PASS |
| Release APK | ⏭️ SKIPPED (non-release push) |

### Issues

No explicit Issues: section in commit message.

No CI build/test failures detected in this run.

### Summary of changes

perf: complete stage 2 - anr, crash, main-thread and stability fixes

### Summary of fixes

No explicit Fixes: section in commit message.

### Commit/diff summary

```text
c0754f4 perf: complete stage 2 - anr, crash, main-thread and stability fixes
 .../com/example/storage/LocalFileSystemScanner.kt  |  4 +-
 .../java/com/example/ui/library/AlbumsScreen.kt    | 29 +++++-----
 .../java/com/example/ui/library/ArtistsScreen.kt   | 16 +++---
 .../ui/library/PlaybackIssuesManagerDialog.kt      | 24 +++++----
 .../ui/library/SelectTracksForPlaylistSheet.kt     | 22 ++++----
 .../java/com/example/ui/library/SongsScreen.kt     | 63 +++++++++++-----------
 6 files changed, 88 insertions(+), 70 deletions(-)
```

---
