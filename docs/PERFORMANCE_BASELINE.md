# SoundSync Performance Baseline Report

## Objective
Provide a factual baseline to identify performance bottlenecks across CPU, memory, and database I/O for SoundSync.

## Known Bottlenecks and Observations
1. **Startup Latency**: 
   - `MainActivity.onCreate()` launches a large Jetpack Compose graph immediately.
   - Initial database reads or metadata initialization in `MainDjViewModel` block or slow down the initial frame rendering.
2. **Background Work vs. Playback**:
   - Background tasks like `MetadataScanManager` and `LibraryAnalysisWorker` compete with `DjAudioEngine` (the player), leading to audio stutters or skipped UI frames.
   - A single WorkManager thread pool or default IO dispatcher is likely being overwhelmed by `BitmapFactory` allocations and disk I/O.
3. **Database & Queries**:
   - `TrackDao` returns very large lists using `suspend` lists or `Flow<List<TrackEntity>>` directly to the main thread instead of pagination or incremental loading, causing major UI freezes for large libraries.
4. **Artwork Memory & CPU Loading**:
   - Lists loading artworks trigger simultaneous file reads on the UI thread or cause GC pressure by rapidly throwing away Bitmaps while scrolling.
   - `AlbumArtHelper` decodes `Bitmap` objects eagerly.
5. **Recomposition Hotspots**:
   - Complex `StateFlow` changes (like `PlaybackState` or `NowPlaying` track) being updated at 60Hz might be triggering recomposition across the entire app hierarchy instead of isolated components.

## Stage 1 Diagnostics Added
- Added `PerformanceDiagnostics.kt` for explicit timing logs for `AppInitialization` and `MainActivity_onCreate`.

## Conclusion
Refactoring should focus on delegating heavy database reads and image decoding to background threads, chunking/paginating large queries, and throttling/debouncing UI recompositions to avoid overwhelming the Main Thread.

