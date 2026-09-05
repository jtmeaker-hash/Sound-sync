package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Single authoritative DJ Audio Engine managing playback state, real-time DSP decoding
 * via MediaCodec + AudioTrack (EQ + Haas applied to actual audio), fallback procedural
 * synthesis for demo tracks, and DJ deck parameters (Pitch, EQ, 4-bar Looping, Cues).
 *
 * Playback resources (MediaExtractor, MediaCodec, AudioTrack) are persistent across
 * pause/resume to eliminate decoder recreation overhead.
 */
class DjAudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "DjAudioEngine"
        private const val TIMEOUT_US: Long = 10_000L
        private const val POSITION_PUBLISH_INTERVAL_MS = 50L

        @Volatile
        private var instance: DjAudioEngine? = null

        fun getInstance(context: Context): DjAudioEngine {
            val current = instance
            if (current != null && !current.isEngineReleased) {
                return current
            }
            return synchronized(this) {
                val existing = instance
                if (existing != null && !existing.isEngineReleased) {
                    existing
                } else {
                    DjAudioEngine(context.applicationContext).also { instance = it }
                }
            }
        }
    }

    // ── Callbacks ──────────────────────────────────────────────────────────
    var onNextTrackCallback: (() -> Unit)? = null
    var onPreviousTrackCallback: (() -> Unit)? = null
    var onStopCallback: (() -> Unit)? = null
    var onNextTrackProvider: (() -> Track?)? = null
    var onTrackStartedCallback: ((Track) -> Unit)? = null
    var onTrackUnavailableCallback: ((Track) -> Unit)? = null

    @Volatile
    private var completionInFlight = false

    // ── Audio Focus Management ─────────────────────────────────────────────
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var hasAudioFocus = false
    @Volatile private var wasPlayingBeforeFocusLoss = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus lost permanently -> pausing")
                wasPlayingBeforeFocusLoss = false
                hasAudioFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost transiently -> pausing")
                if (_isPlaying.value) {
                    wasPlayingBeforeFocusLoss = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost transiently (can duck) -> pausing")
                if (_isPlaying.value) {
                    wasPlayingBeforeFocusLoss = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                hasAudioFocus = true
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    play()
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        if (hasAudioFocus) return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setAcceptsDelayedFocusGain(false)
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    // ── Playback Generation Gate for Atomic Track Switching ────────────────
    private val generationGate = PlaybackGenerationGate()
    @Volatile private var activeSessionId: Long = 0L
    @Volatile private var activeLoopSessionId: Long = 0L

    // ── Scopes ─────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var prepareJob: kotlinx.coroutines.Job? = null
    private var waveformJob: kotlinx.coroutines.Job? = null
    private var spectrogramJob: kotlinx.coroutines.Job? = null

    // ── Dedicated audio thread ─────────────────────────────────────────────
    private val audioThreadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SoundSync-Audio").apply {
            priority = Thread.MAX_PRIORITY
        }
    }

    // ── Low-priority analysis worker ───────────────────────────────────────
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SoundSync-Analysis").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private val analysisScope = CoroutineScope(analysisExecutor.asCoroutineDispatcher() + SupervisorJob())

    // ── Persistent playback resources ──────────────────────────────────────
    @Volatile private var activeExtractor: MediaExtractor? = null
    @Volatile private var activeCodec: MediaCodec? = null
    @Volatile private var activeAudioTrack: AudioTrack? = null
    @Volatile private var activeSampleRate: Int = 44100
    @Volatile private var activeChannelCount: Int = 2

    // ── Pause synchronization ──────────────────────────────────────────────
    private val pauseLock = ReentrantLock()
    private val pauseCondition = pauseLock.newCondition()
    @Volatile private var decoderShouldPause = true
    @Volatile private var decoderRunning = false

    // ── Reusable buffers (allocated once per track load) ───────────────────
    @Volatile private var pcmWorkBuffer: ShortArray = ShortArray(0)
    private val eq by lazy { ParametricEq(activeSampleRate) }

    // ── Position throttling ────────────────────────────────────────────────
    @Volatile private var internalPositionMs: Long = 0L
    @Volatile private var lastPublishedPositionMs: Long = 0L
    @Volatile private var lastPublishedSecond: Int = -1
    @Volatile private var lastPositionPublishTimeMs: Long = 0L

    // ── Playback rate tracking ─────────────────────────────────────────────
    @Volatile private var lastAppliedPlaybackRate: Int = 0

    // ── Diagnostics ────────────────────────────────────────────────────────
    @Volatile private var underrunLogCounter: Int = 0
    @Volatile private var underrunCheckInterval: Int = 200

    // ── Pending seek ───────────────────────────────────────────────────────
    @Volatile private var pendingSeekMs: Long? = null

    @Volatile private var isEngineReleased = false

    // ── Authoritative Playback State ───────────────────────────────────────
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack = _currentTrack.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress = _playbackProgress.asStateFlow()

    private val _currentPositionSec = MutableStateFlow(0)
    val currentPositionSec = _currentPositionSec.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs = _currentPositionMs.asStateFlow()

    private val _waveformData = MutableStateFlow<WaveformData?>(null)
    val waveformData = _waveformData.asStateFlow()

    private val _isWaveformLoading = MutableStateFlow(false)
    val isWaveformLoading = _isWaveformLoading.asStateFlow()

    // DJ Deck controls state
    private val _pitchPercent = MutableStateFlow(0.0f)
    val pitchPercent = _pitchPercent.asStateFlow()

    private val _effectiveBpm = MutableStateFlow(126.0)
    val effectiveBpm = _effectiveBpm.asStateFlow()

    private val eqPrefs = context.getSharedPreferences("soundsync_eq_prefs", Context.MODE_PRIVATE)

    private val _eqEnabled = MutableStateFlow(eqPrefs.getBoolean("eq_enabled", true))
    val eqEnabled = _eqEnabled.asStateFlow()

    private val _eqLow = MutableStateFlow(eqPrefs.getFloat("eq_low", 1.0f).coerceIn(0f, 2f))
    val eqLow = _eqLow.asStateFlow()

    private val _eqMid = MutableStateFlow(eqPrefs.getFloat("eq_mid", 1.0f).coerceIn(0f, 2f))
    val eqMid = _eqMid.asStateFlow()

    private val _eqHigh = MutableStateFlow(eqPrefs.getFloat("eq_high", 1.0f).coerceIn(0f, 2f))
    val eqHigh = _eqHigh.asStateFlow()

    private val _filterKnob = MutableStateFlow(0.5f)
    val filterKnob = _filterKnob.asStateFlow()

    private val _activeLoopBars = MutableStateFlow(0)
    val activeLoopBars = _activeLoopBars.asStateFlow()

    private val _waveformHeights = MutableStateFlow(FloatArray(60) { 0.3f })
    val waveformHeights = _waveformHeights.asStateFlow()

    // Real PCM Audio Live Metrics (for RMS Meter and Clipping Detector)
    private val _liveRmsDb = MutableStateFlow(-60f)
    val liveRmsDb = _liveRmsDb.asStateFlow()

    private val _livePeakDb = MutableStateFlow(-60f)
    val livePeakDb = _livePeakDb.asStateFlow()

    private val _liveClippingDetected = MutableStateFlow(false)
    val liveClippingDetected = _liveClippingDetected.asStateFlow()

    private val _liveClippedSampleCount = MutableStateFlow(0L)
    val liveClippedSampleCount = _liveClippedSampleCount.asStateFlow()

    val persistentQueueManager = com.example.player.PersistentQueueManager.getInstance(context)
    val parametricEqManager = ParametricEqManager.getInstance(context)

    private val playerPrefs = context.getSharedPreferences("soundsync_player_prefs", Context.MODE_PRIVATE)
    private val _isGaplessPlaybackEnabled = MutableStateFlow(playerPrefs.getBoolean("gapless_playback_enabled", true))
    val isGaplessPlaybackEnabled = _isGaplessPlaybackEnabled.asStateFlow()

    fun setGaplessPlaybackEnabled(enabled: Boolean) {
        _isGaplessPlaybackEnabled.value = enabled
        playerPrefs.edit().putBoolean("gapless_playback_enabled", enabled).apply()
    }

    @Volatile private var lastMetricsPublishTimeMs: Long = 0L

    fun resetClippingDetector() {
        _liveClippingDetected.value = false
        _liveClippedSampleCount.value = 0L
    }

    private var activeCueSeconds: Int = 0

    // Haas Surround Effect
    private val haasEffect = HaasSurroundEffect()
    private val _haasEnabled = MutableStateFlow(false)
    val haasEnabled = _haasEnabled.asStateFlow()

    private val _haasAmount = MutableStateFlow(HaasSurroundEffect.DEFAULT_AMOUNT)
    val haasAmount = _haasAmount.asStateFlow()

    private val _haasDelayMs = MutableStateFlow(HaasSurroundEffect.DEFAULT_DELAY_MS)
    val haasDelayMs = _haasDelayMs.asStateFlow()

    private val _crossfadeSeconds = MutableStateFlow(0)
    val crossfadeSeconds = _crossfadeSeconds.asStateFlow()

    init {
        // Restore persisted Haas settings
        val savedHaas = HaasSurroundEffect.loadSettings(context)
        _haasEnabled.value = savedHaas.isEnabled
        _haasAmount.value = savedHaas.amount
        _haasDelayMs.value = savedHaas.delayMs
        haasEffect.setEnabled(savedHaas.isEnabled)
        haasEffect.setAmount(savedHaas.amount)
        haasEffect.setDelayMs(savedHaas.delayMs)
    }

    fun setCrossfadeSeconds(seconds: Int) {
        _crossfadeSeconds.value = seconds.coerceIn(0, 12)
    }

    // ── Track Loading ──────────────────────────────────────────────────────

    fun loadTrack(track: Track?, autoPlay: Boolean = false, initialPositionSec: Int = 0) {
        if (isEngineReleased) return

        // 1. Atomically advance to a new playback session
        val session = generationGate.next()
        activeSessionId = session
        Log.d(TAG, "loadTrack(track='${track?.title}', autoPlay=$autoPlay, initialSec=$initialPositionSec, session=$session)")

        // 2. Immediately cut off previous audio and cancel any background jobs
        prepareJob?.cancel()
        waveformJob?.cancel()
        spectrogramJob?.cancel()
        stopPlaybackImmediately()

        if (track == null) {
            _currentTrack.value = null
            _isPlaying.value = false
            _playbackProgress.value = 0f
            _currentPositionSec.value = 0
            _currentPositionMs.value = 0L
            _waveformData.value = null
            _isWaveformLoading.value = false
            activeCueSeconds = 0
            return
        }

        // 3. Atomically set track metadata and initial playback position
        _currentTrack.value = track
        _isPlaying.value = autoPlay
        val baseBpm = if (track.bpm > 0) track.bpm else 126.0
        _effectiveBpm.value = baseBpm * (1.0 + _pitchPercent.value / 100.0)

        val duration = track.durationSeconds.coerceAtLeast(0)
        val initialSec = initialPositionSec.coerceIn(0, duration)
        val initialMs = initialSec * 1000L
        _currentPositionSec.value = initialSec
        _currentPositionMs.value = initialMs
        _playbackProgress.value = if (duration > 0) (initialSec.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        internalPositionMs = initialMs
        lastPublishedPositionMs = initialMs
        lastPublishedSecond = initialSec
        activeCueSeconds = 0
        pendingSeekMs = if (initialMs > 0) initialMs else null

        // If track is explicitly marked unplayable (e.g. disconnected USB drive), trigger skip callback immediately
        if (!track.isAvailable) {
            Log.w(TAG, "loadTrack: Track '${track.title}' is unplayable (USB storage disconnected). Skipping.")
            _isPlaying.value = false
            onTrackUnavailableCallback?.invoke(track)
            return
        }

        // 4. Reset Haas delay buffer to avoid cross-track audio bleed
        haasEffect.reset()

        // 5. Check waveform cache immediately
        val cacheKey = WaveformCache.getCacheKey(track, context)
        val cached = WaveformCache.get(cacheKey, context)
        if (cached != null) {
            _waveformData.value = cached
            _isWaveformLoading.value = false
        } else {
            _waveformData.value = null
            _isWaveformLoading.value = true
        }

        // 6. Schedule background waveform analysis strictly bound to this session
        waveformJob = analysisScope.launch {
            try {
                val fullWaveform = WaveformAnalyzer.analyze(context, track)
                if (generationGate.isCurrent(session) && _currentTrack.value?.id == track.id) {
                    _waveformData.value = fullWaveform
                }
            } catch (e: Exception) {
                Log.w(TAG, "Waveform analysis failed for session $session: ${e.message}")
            } finally {
                if (generationGate.isCurrent(session) && _currentTrack.value?.id == track.id) {
                    _isWaveformLoading.value = false
                }
            }
        }

        spectrogramJob = analysisScope.launch {
            try {
                val waveform = SpectrogramEngine.extractWaveform(context, track)
                if (generationGate.isCurrent(session) && _currentTrack.value?.id == track.id) {
                    _waveformHeights.value = waveform
                }
            } catch (e: Exception) {
                Log.w(TAG, "Spectrogram extraction failed for session $session: ${e.message}")
            }
        }

        if (autoPlay && !isEngineReleased) {
            playSession(session)
        }
    }

    /**
     * Updates metadata for the currently active track (e.g. after Apple/TheAudioDB enrichment or tag editing)
     * WITHOUT resetting the decoder, position, or audio stream.
     */
    fun updateCurrentTrackMetadata(updatedTrack: Track) {
        if (isEngineReleased) return
        val current = _currentTrack.value
        if (current != null && current.id == updatedTrack.id) {
            _currentTrack.value = updatedTrack
            val baseBpm = if (updatedTrack.bpm > 0) updatedTrack.bpm else 126.0
            _effectiveBpm.value = baseBpm * (1.0 + _pitchPercent.value / 100.0)
            Log.d(TAG, "Updated metadata in-place for active track '${updatedTrack.title}' (bpm=${updatedTrack.bpm}, key=${updatedTrack.musicalKey})")
        }
    }

    // ── Playback Control ───────────────────────────────────────────────────

    fun play() {
        if (isEngineReleased) return
        playSession(activeSessionId)
    }

    private fun playSession(session: Long) {
        if (isEngineReleased || !generationGate.isCurrent(session)) return

        val track = _currentTrack.value ?: return

        // Request audio focus before starting audio output
        requestAudioFocus()

        completionInFlight = false

        // If the decoder loop for THIS EXACT SESSION is running and paused, resume it smoothly
        if (activeLoopSessionId == session && decoderRunning && decoderShouldPause && activeAudioTrack != null) {
            _isPlaying.value = true
            decoderShouldPause = false
            runCatching { activeAudioTrack?.play() }
            pauseLock.withLock { pauseCondition.signalAll() }
            onTrackStartedCallback?.invoke(track)
            try { com.example.service.MediaPlaybackService.startService(context) } catch (_: Exception) {}
            return
        }

        // If already playing for this session, idempotent
        if (activeLoopSessionId == session && decoderRunning && !decoderShouldPause) {
            _isPlaying.value = true
            return
        }

        _isPlaying.value = true
        decoderShouldPause = false
        onTrackStartedCallback?.invoke(track)

        try { com.example.service.MediaPlaybackService.startService(context) } catch (_: Exception) {}

        if (!track.isAvailable) {
            Log.w(TAG, "Cannot play track '${track.title}': storage device is disconnected (${track.filePath})")
            _isPlaying.value = false
            decoderShouldPause = true
            stopPlaybackImmediately()
            onTrackUnavailableCallback?.invoke(track)
        } else if (track.filePath.startsWith("demo://") || !isUriAccessible(track.filePath)) {
            startAudioSynthesis(session)
        } else {
            startStreamingPlayback(session)
        }
    }

    fun pause() {
        if (!_isPlaying.value && decoderShouldPause) return // already paused, idempotent
        _isPlaying.value = false
        completionInFlight = false
        decoderShouldPause = true
        // Wake the decoder so it can enter the paused state
        pauseLock.withLock { pauseCondition.signalAll() }
        activeAudioTrack?.let { t ->
            runCatching { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause() }
        }
        _liveRmsDb.value = -60f
        _livePeakDb.value = -60f
    }

    fun togglePlayPause() {
        if (_isPlaying.value) pause() else play()
    }

    fun triggerCue() {
        if (_isPlaying.value) {
            pause()
            seekToSecond(activeCueSeconds)
        } else {
            seekToSecond(activeCueSeconds)
            play()
        }
    }

    fun setHotCue(cueIndex: Int) {
        val track = _currentTrack.value ?: return
        if (cueIndex < track.hotCues.size) {
            val cueSec = track.hotCues[cueIndex]
            activeCueSeconds = cueSec
            seekToSecond(cueSec)
        }
    }

    // ── Controls ───────────────────────────────────────────────────────────

    fun setPitch(percent: Float) {
        _pitchPercent.value = percent.coerceIn(-16f, 16f)
        val base = _currentTrack.value?.bpm ?: 126.0
        _effectiveBpm.value = base * (1.0 + _pitchPercent.value / 100.0)
    }

    fun setEqEnabled(enabled: Boolean) {
        _eqEnabled.value = enabled
        eqPrefs.edit().putBoolean("eq_enabled", enabled).apply()
    }

    fun setEq(low: Float, mid: Float, high: Float) {
        _eqLow.value = low.coerceIn(0f, 2f)
        _eqMid.value = mid.coerceIn(0f, 2f)
        _eqHigh.value = high.coerceIn(0f, 2f)
        eqPrefs.edit()
            .putFloat("eq_low", _eqLow.value)
            .putFloat("eq_mid", _eqMid.value)
            .putFloat("eq_high", _eqHigh.value)
            .apply()
    }

    fun setFilter(value: Float) {
        _filterKnob.value = value.coerceIn(0f, 1f)
    }

    fun setHaasEnabled(enabled: Boolean) {
        _haasEnabled.value = enabled
        haasEffect.setEnabled(enabled)
        HaasSurroundEffect.saveSettings(
            context,
            HaasSurroundEffect.HaasSettings(enabled, _haasAmount.value, _haasDelayMs.value)
        )
    }

    fun setHaasAmount(amount: Float) {
        val clamped = amount.coerceIn(HaasSurroundEffect.MIN_AMOUNT, HaasSurroundEffect.MAX_AMOUNT)
        _haasAmount.value = clamped
        haasEffect.setAmount(clamped)
        HaasSurroundEffect.saveSettings(
            context,
            HaasSurroundEffect.HaasSettings(_haasEnabled.value, clamped, _haasDelayMs.value)
        )
        if (!_haasEnabled.value) {
            haasEffect.setEnabled(false)
        }
    }

    fun setHaasDelayMs(delayMs: Float) {
        val clamped = delayMs.coerceIn(HaasSurroundEffect.MIN_DELAY_MS, HaasSurroundEffect.MAX_DELAY_MS)
        _haasDelayMs.value = clamped
        haasEffect.setDelayMs(clamped)
        HaasSurroundEffect.saveSettings(
            context,
            HaasSurroundEffect.HaasSettings(_haasEnabled.value, _haasAmount.value, clamped)
        )
    }

    fun toggleLoop(bars: Int) {
        _activeLoopBars.value = if (_activeLoopBars.value == bars) 0 else bars
    }

    fun setLoop(bars: Int) {
        _activeLoopBars.value = bars
    }

    // ── Seeking ────────────────────────────────────────────────────────────

    fun seekToFraction(fraction: Float) {
        val track = _currentTrack.value ?: return
        val totalMs = if (track.durationSeconds > 0) track.durationSeconds * 1000L else 0L
        seekToMs((totalMs * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun seekToRatio(ratio: Float) = seekToFraction(ratio)

    fun seekToSecond(sec: Int) = seekToMs(sec * 1000L)

    fun seekToMs(ms: Long) {
        val track = _currentTrack.value ?: return
        val durationMs = if (track.durationSeconds > 0) track.durationSeconds * 1000L else 0L
        val clampedMs = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        val clampedSec = (clampedMs / 1000).toInt()

        _currentPositionMs.value = clampedMs
        _currentPositionSec.value = clampedSec
        _playbackProgress.value = if (durationMs > 0) clampedMs.toFloat() / durationMs.toFloat() else 0f
        internalPositionMs = clampedMs

        // Queue the seek for the active session's decoder loop
        if (decoderRunning) {
            pendingSeekMs = clampedMs
            if (decoderShouldPause) {
                pauseLock.withLock { pauseCondition.signalAll() }
            }
        }
    }

    // ── Streaming Playback (Real Audio Files) ──────────────────────────────

    private fun startStreamingPlayback(session: Long) {
        decoderShouldPause = false
        decoderRunning = true
        activeLoopSessionId = session

        audioThreadExecutor.execute {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            decoderLoop(session)
        }
    }

    private fun decoderLoop(session: Long) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var audioTrack: AudioTrack? = null
        var crossfadeNextTrack: Track? = null
        var crossfadeNextDecoder: StereoPcmDecoder? = null

        try {
            if (!generationGate.isCurrent(session) || isEngineReleased) return

            val track = _currentTrack.value ?: return
            val uriOrPath = track.filePath

            val ex = MediaExtractor()
            extractor = ex
            activeExtractor = ex
            setExtractorDataSource(ex, uriOrPath)

            var audioIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioIndex = i
                    format = f
                    break
                }
            }
            if (audioIndex < 0 || format == null) {
                // Fallback to synthesis for this session
                if (generationGate.isCurrent(session)) {
                    startAudioSynthesis(session)
                }
                return
            }
            ex.selectTrack(audioIndex)

            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val chCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4"

            if (!generationGate.isCurrent(session) || isEngineReleased) return

            val dec = MediaCodec.createDecoderByType(mime)
            dec.configure(format, null, null, 0)
            dec.start()
            codec = dec
            activeCodec = dec

            // ── Calculate safe buffer size ──────────────────────────────
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val twentyMsBytes = (sampleRate * 2 * 2 * 200 / 1000) // 200ms * 2ch * 2bytes
            val bufferSize = if (minBuf > 0) {
                maxOf(minBuf * 4, twentyMsBytes)
            } else {
                twentyMsBytes
            }

            val at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (at.state != AudioTrack.STATE_INITIALIZED) {
                at.release()
                if (generationGate.isCurrent(session)) {
                    startAudioSynthesis(session)
                }
                return
            }

            if (!generationGate.isCurrent(session) || isEngineReleased) {
                at.release()
                return
            }

            audioTrack = at
            activeAudioTrack = at
            activeSampleRate = sampleRate
            activeChannelCount = chCount

            at.play()

            // Apply initial pitch via AudioTrack playback rate
            val initialRate = (sampleRate * (1f + _pitchPercent.value / 100f)).toInt().coerceIn(4000, 192000)
            at.playbackRate = initialRate
            lastAppliedPlaybackRate = initialRate

            // Seek to requested start position
            var startMs = _currentPositionMs.value.coerceAtLeast(0L)
            pendingSeekMs?.let { startMs = it; pendingSeekMs = null }
            if (startMs > 0) {
                ex.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val durationMs = (track.durationSeconds.coerceAtLeast(1) * 1000L)
            var renderedPositionUs = startMs * 1000L
            internalPositionMs = startMs
            if (generationGate.isCurrent(session)) {
                publishThrottledPosition(startMs, durationMs, force = true)
            }

            // Prepare reusable PCM work buffer
            val pcmStereo = ShortArray(maxOf(bufferSize / 2, sampleRate / 5))
            pcmWorkBuffer = pcmStereo

            // ── Crossfade setup ─────────────────────────────────────────
            val crossfadeDurationMs = _crossfadeSeconds.value.coerceIn(0, 12) * 1000L
            var crossfadeStarted = false
            var nextOnlyPositionFrames = 0L
            var crossfadeDecoderPrepared = false

            // DSP engine
            val dspEq = ParametricEq(sampleRate)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var iterations = 0L

            while (!isEngineReleased && generationGate.isCurrent(session)) {
                // ── Pause synchronization ───────────────────────────────
                if (decoderShouldPause) {
                    try {
                        if (at.playState == AudioTrack.PLAYSTATE_PLAYING) at.pause()
                    } catch (_: Exception) {}
                    pauseLock.withLock {
                        while (decoderShouldPause && !isEngineReleased && generationGate.isCurrent(session)) {
                            pauseCondition.awaitUninterruptibly()
                        }
                    }
                    if (isEngineReleased || !generationGate.isCurrent(session)) break
                    // Resume
                    try { at.play() } catch (_: Exception) {}
                    continue
                }

                if (!_isPlaying.value) {
                    Thread.sleep(5)
                    continue
                }

                iterations++
                if (iterations > 5_000_000L) {
                    Log.w(TAG, "Decoder loop safety limit reached for session $session")
                    break
                }

                // ── Handle pending seek ─────────────────────────────────
                pendingSeekMs?.let { seekMs ->
                    pendingSeekMs = null
                    val target = seekMs.coerceIn(0L, durationMs)
                    ex.seekTo(target * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    dec.flush()
                    inputEos = false
                    outputEos = false
                    renderedPositionUs = target * 1000L
                    internalPositionMs = target
                    if (generationGate.isCurrent(session)) {
                        publishThrottledPosition(target, durationMs, force = true)
                    }
                    haasEffect.reset()
                }

                // ── Feed input buffers ──────────────────────────────────
                if (!inputEos) {
                    val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = dec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val size = ex.readSampleData(inBuf, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                }

                // ── Process output buffers ──────────────────────────────
                val outIdx = dec.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* format changed */ }
                    outIdx >= 0 -> {
                        val outBuf = dec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            val ss = outBuf.asShortBuffer()

                            val frames = min(ss.remaining() / chCount.coerceAtLeast(1), pcmStereo.size / 2)
                            var filled = 0
                            if (chCount <= 1) {
                                while (ss.hasRemaining() && filled < frames) {
                                    val s = ss.get()
                                    pcmStereo[filled * 2] = s
                                    pcmStereo[filled * 2 + 1] = s
                                    filled++
                                }
                            } else {
                                var fi = 0
                                while (ss.remaining() >= 2 && fi < frames) {
                                    pcmStereo[fi * 2] = ss.get()
                                    pcmStereo[fi * 2 + 1] = ss.get()
                                    fi++
                                }
                                filled = fi
                            }

                            if (filled > 0 && generationGate.isCurrent(session)) {
                                val currentPtsMs = (renderedPositionUs / 1000L).coerceIn(0L, durationMs)

                                // ── Crossfade / Gapless late prep ─────────────────
                                val needPreload = (crossfadeDurationMs > 0L && currentPtsMs >= (durationMs - crossfadeDurationMs * 3).coerceAtLeast(0L)) ||
                                        (_isGaplessPlaybackEnabled.value && currentPtsMs >= (durationMs - 3000L).coerceAtLeast(0L))
                                if (_activeLoopBars.value == 0 && !crossfadeDecoderPrepared && !crossfadeStarted && needPreload) {
                                    val candidate = onNextTrackProvider?.invoke()
                                    if (candidate != null && candidate.id != track.id && isUriAccessible(candidate.filePath)) {
                                        runCatching {
                                            val decoder = StereoPcmDecoder(context, candidate.filePath)
                                            if (decoder.sampleRate == sampleRate) {
                                                crossfadeNextTrack = candidate
                                                crossfadeNextDecoder = decoder
                                            } else {
                                                decoder.close()
                                            }
                                        }
                                    }
                                    crossfadeDecoderPrepared = true
                                }

                                // ── Crossfade mixing ────────────────────
                                val transitionStartMs = (durationMs - crossfadeDurationMs).coerceAtLeast(0L)
                                var crossfadePcm: ShortArray? = null

                                if (!crossfadeStarted && crossfadeNextDecoder != null &&
                                    currentPtsMs >= transitionStartMs
                                ) {
                                    crossfadePcm = crossfadeNextDecoder?.readFrames(filled)
                                    if (crossfadePcm?.isNotEmpty() == true) {
                                        crossfadeStarted = true
                                        crossfadeNextTrack?.let { nextTrack ->
                                            if (generationGate.isCurrent(session)) {
                                                _currentTrack.value = nextTrack
                                                internalPositionMs = 0L
                                                publishThrottledPosition(0L, nextTrack.durationSeconds.coerceAtLeast(1) * 1000L, force = true)
                                                onTrackStartedCallback?.invoke(nextTrack)
                                            }
                                        }
                                    }
                                }

                                if (crossfadeStarted && crossfadeNextDecoder != null) {
                                    val nextPcm = crossfadePcm ?: crossfadeNextDecoder?.readFrames(filled)
                                    val nextFrames = (nextPcm?.size ?: 0) / 2
                                    if (nextFrames > 0 && nextPcm != null) {
                                        val progress = if (crossfadeDurationMs > 0L) {
                                            ((currentPtsMs - transitionStartMs).toFloat() / crossfadeDurationMs.toFloat()).coerceIn(0f, 1f)
                                        } else 1f
                                        val currentGain = 1f - progress
                                        val nextGain = progress
                                        for (i in 0 until min(filled, nextFrames)) {
                                            val ni = i * 2
                                            pcmStereo[ni] = (pcmStereo[ni].toInt() * currentGain + nextPcm[ni].toInt() * nextGain)
                                                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                            pcmStereo[ni + 1] = (pcmStereo[ni + 1].toInt() * currentGain + nextPcm[ni + 1].toInt() * nextGain)
                                                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                        }
                                        nextOnlyPositionFrames += nextFrames
                                        crossfadeNextTrack?.let { nextTrack ->
                                            val nextDurationMs = nextTrack.durationSeconds.coerceAtLeast(1) * 1000L
                                            val nextPositionMs = (nextOnlyPositionFrames * 1000L / sampleRate).coerceAtMost(nextDurationMs)
                                            internalPositionMs = nextPositionMs
                                            if (generationGate.isCurrent(session)) {
                                                publishThrottledPosition(nextPositionMs, nextDurationMs)
                                            }
                                        }
                                    }
                                }

                                // ── Apply DSP chain ────────────────────
                                if (_eqEnabled.value && parametricEqManager.isEqEnabled.value) {
                                    dspEq.lowGain = _eqLow.value
                                    dspEq.midGain = _eqMid.value
                                    dspEq.highGain = _eqHigh.value
                                    dspEq.setBands(parametricEqManager.currentBands.value)
                                    dspEq.preampDb = parametricEqManager.preampDb.value
                                    dspEq.processStereo(pcmStereo, 0, filled)
                                }
                                if (haasEffect.isActive) {
                                    haasEffect.process(pcmStereo, 0, filled, sampleRate)
                                }

                                // ── Live RMS & Clipping Metrics ────────
                                val nowMs = System.currentTimeMillis()
                                if (nowMs - lastMetricsPublishTimeMs >= 40L && filled > 0) {
                                    lastMetricsPublishTimeMs = nowMs
                                    var sumSq = 0.0
                                    var maxVal = 0
                                    var clips = 0L
                                    val count = filled * 2
                                    for (si in 0 until count) {
                                        val s = pcmStereo[si].toInt()
                                        val absS = if (s < 0) -s else s
                                        if (absS > maxVal) maxVal = absS
                                        if (absS >= 32760) clips++
                                        sumSq += s * s
                                    }
                                    val r = sqrt(sumSq / count)
                                    val rDb = if (r > 0.0) (20.0 * log10(r / 32767.0)).toFloat().coerceIn(-60f, 0f) else -60f
                                    val pDb = if (maxVal > 0) (20.0 * log10(maxVal.toDouble() / 32767.0)).toFloat().coerceIn(-60f, 0f) else -60f
                                    _liveRmsDb.value = rDb
                                    _livePeakDb.value = pDb
                                    if (clips > 0L) {
                                        _liveClippingDetected.value = true
                                        _liveClippedSampleCount.value += clips
                                    }
                                }

                                // ── Write PCM if still active session ──
                                if (generationGate.isCurrent(session)) {
                                    writePcmBlocking(at, pcmStereo, filled * 2, session)
                                    renderedPositionUs += (filled.toLong()) * 1_000_000L / sampleRate

                                    // ── Pitch / playback rate ──────────
                                    val desiredRate = (sampleRate * (1f + _pitchPercent.value / 100f)).toInt().coerceIn(4000, 192000)
                                    if (desiredRate != lastAppliedPlaybackRate) {
                                        runCatching { at.playbackRate = desiredRate }
                                        lastAppliedPlaybackRate = desiredRate
                                    }

                                    // ── Position update ────────────────
                                    if (!crossfadeStarted) {
                                        internalPositionMs = currentPtsMs
                                        publishThrottledPosition(currentPtsMs, durationMs)
                                    }
                                }
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true
                        }
                    }
                }

                // ── Loop boundary ───────────────────────────────────────
                if (_activeLoopBars.value > 0 && generationGate.isCurrent(session)) {
                    val currentBpm = _effectiveBpm.value.coerceIn(20.0, 300.0)
                    val loopLenSec = (_activeLoopBars.value * 4 * 60 / currentBpm).toInt().coerceAtLeast(2)
                    val loopEndMs = activeCueSeconds * 1000L + loopLenSec * 1000L
                    if (internalPositionMs >= loopEndMs) {
                        ex.seekTo(activeCueSeconds * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        dec.flush()
                        inputEos = false
                        outputEos = false
                        haasEffect.reset()
                        continue
                    }
                }

                // ── Crossfade tail drain ────────────────────────────────
                if (inputEos && outputEos && crossfadeStarted && crossfadeNextDecoder != null && generationGate.isCurrent(session)) {
                    val nextPcm = crossfadeNextDecoder?.readFrames(pcmStereo.size / 2)
                    val nextFrames = (nextPcm?.size ?: 0) / 2
                    if (nextFrames > 0) {
                        System.arraycopy(nextPcm!!, 0, pcmStereo, 0, nextFrames * 2)
                        if (_eqEnabled.value && parametricEqManager.isEqEnabled.value) {
                            dspEq.lowGain = _eqLow.value
                            dspEq.midGain = _eqMid.value
                            dspEq.highGain = _eqHigh.value
                            dspEq.setBands(parametricEqManager.currentBands.value)
                            dspEq.preampDb = parametricEqManager.preampDb.value
                            dspEq.processStereo(pcmStereo, 0, nextFrames)
                        }
                        if (haasEffect.isActive) haasEffect.process(pcmStereo, 0, nextFrames, sampleRate)
                        if (generationGate.isCurrent(session)) {
                            writePcmBlocking(at, pcmStereo, nextFrames * 2)
                            nextOnlyPositionFrames += nextFrames
                            crossfadeNextTrack?.let { nextTrack ->
                                val nextDurationMs = nextTrack.durationSeconds.coerceAtLeast(1) * 1000L
                                val nextPositionMs = (nextOnlyPositionFrames * 1000L / sampleRate).coerceAtMost(nextDurationMs)
                                internalPositionMs = nextPositionMs
                                publishThrottledPosition(nextPositionMs, nextDurationMs)
                            }
                        }
                        continue
                    }
                    crossfadeNextDecoder?.close()
                    crossfadeNextDecoder = null
                }

                // ── Seamless Gapless Transition or Track completion ─────
                if (inputEos && outputEos && generationGate.isCurrent(session)) {
                    if (_isGaplessPlaybackEnabled.value && crossfadeNextDecoder != null && crossfadeNextTrack != null && !crossfadeStarted) {
                        crossfadeStarted = true
                        val nextTrack = crossfadeNextTrack
                        if (nextTrack != null) {
                            _currentTrack.value = nextTrack
                            internalPositionMs = 0L
                            publishThrottledPosition(0L, nextTrack.durationSeconds.coerceAtLeast(1) * 1000L, force = true)
                            onTrackStartedCallback?.invoke(nextTrack)
                            continue
                        }
                    }

                    if (!completionInFlight) {
                        completionInFlight = true
                        _isPlaying.value = false
                        decoderShouldPause = true
                        runCatching { if (at.playState == AudioTrack.PLAYSTATE_PLAYING) at.pause() }
                        publishThrottledPosition(durationMs, durationMs, force = true)
                        runCatching { com.example.service.PlaybackStatsTracker.getInstance(context).onTrackCompletedNormally() }
                        onNextTrackCallback?.invoke()
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decoder error in session $session: ${e.message}")
            if (generationGate.isCurrent(session) && _isPlaying.value && !completionInFlight) {
                completionInFlight = true
                _isPlaying.value = false
                decoderShouldPause = true
                onNextTrackCallback?.invoke()
            }
        } finally {
            if (activeLoopSessionId == session) {
                decoderRunning = false
                decoderShouldPause = true
            }
            runCatching { crossfadeNextDecoder?.close() }
            if (activeExtractor === extractor) {
                runCatching { extractor?.release() }
                activeExtractor = null
            }
            if (activeCodec === codec) {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                activeCodec = null
            }
            if (activeAudioTrack === audioTrack) {
                runCatching {
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack?.pause()
                    audioTrack?.flush()
                    audioTrack?.release()
                }
                activeAudioTrack = null
            }
        }
    }

    /**
     * Blocking PCM write that handles partial writes and error codes correctly.
     * All unwritten data is retried until fully consumed, an unrecoverable error occurs,
     * or the playback session is invalidated / paused.
     */
    private fun writePcmBlocking(audioTrack: AudioTrack, buffer: ShortArray, shortCount: Int, session: Long = -1L) {
        var offset = 0
        var remaining = shortCount
        while (remaining > 0) {
            if (session >= 0L && (!generationGate.isCurrent(session) || isEngineReleased || decoderShouldPause)) {
                break
            }
            val written = audioTrack.write(buffer, offset, remaining)
            if (written > 0) {
                offset += written
                remaining -= written
            } else if (written == 0) {
                Thread.sleep(1)
            } else {
                Log.w(TAG, "AudioTrack write error: $written")
                break
            }
        }
    }

    // ── Position Throttling ────────────────────────────────────────────────

    private fun publishThrottledPosition(positionMs: Long, durationMs: Long, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastPositionPublishTimeMs) < POSITION_PUBLISH_INTERVAL_MS) return
        lastPositionPublishTimeMs = now

        if (positionMs == lastPublishedPositionMs && !force) return
        lastPublishedPositionMs = positionMs

        _currentPositionMs.value = positionMs

        val newSec = (positionMs / 1000).toInt()
        if (newSec != lastPublishedSecond) {
            lastPublishedSecond = newSec
            _currentPositionSec.value = newSec
        }

        _playbackProgress.value = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    }

    // ── Teardown Helpers ───────────────────────────────────────────────────

    private fun stopPlaybackImmediately() {
        decoderShouldPause = true
        _isPlaying.value = false
        completionInFlight = false
        // Wake decoder so any waiting loop immediately detects session change and exits
        pauseLock.withLock { pauseCondition.signalAll() }
        // Cut off audio immediately from speakers
        activeAudioTrack?.let { at ->
            runCatching {
                if (at.playState == AudioTrack.PLAYSTATE_PLAYING) at.pause()
                at.flush()
            }
        }
        releaseSynthesisTrack()
    }

    private fun stopPlayback() = stopPlaybackImmediately()

    private fun releasePlaybackResources() {
        decoderShouldPause = true
        _isPlaying.value = false
        pauseLock.withLock { pauseCondition.signalAll() }

        activeExtractor?.let { ex -> runCatching { ex.release() } }
        activeExtractor = null
        activeCodec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        activeCodec = null
        activeAudioTrack?.let { at ->
            runCatching {
                if (at.playState == AudioTrack.PLAYSTATE_PLAYING) at.stop()
                at.flush()
                at.release()
            }
        }
        activeAudioTrack = null
        releaseSynthesisTrack()
    }

    // ── Synthesis Fallback ─────────────────────────────────────────────────

    @Volatile private var synthesisAudioTrack: AudioTrack? = null

    private fun releaseSynthesisTrack() {
        synthesisAudioTrack?.let { t ->
            runCatching {
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
                t.release()
            }
        }
        synthesisAudioTrack = null
    }

    private fun startAudioSynthesis(session: Long) {
        decoderShouldPause = false
        decoderRunning = true
        activeLoopSessionId = session
        audioThreadExecutor.execute {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            synthesisLoop(session)
        }
    }

    private fun synthesisLoop(session: Long) {
        val sampleRate = 22050
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBuf > 0) maxOf(minBuf * 4, sampleRate / 5 * 2) else sampleRate / 5 * 2

        var localTrack: AudioTrack? = null
        try {
            if (!generationGate.isCurrent(session) || isEngineReleased) return

            localTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (localTrack.state != AudioTrack.STATE_INITIALIZED) {
                localTrack.release()
                if (activeLoopSessionId == session) decoderRunning = false
                return
            }

            if (!generationGate.isCurrent(session) || isEngineReleased) {
                localTrack.release()
                return
            }

            synthesisAudioTrack = localTrack
            activeAudioTrack = localTrack
            localTrack.play()

            val monoBuffer = ShortArray(bufferSize / 2)
            val stereoBuffer = ShortArray(bufferSize)
            var phase = 0.0
            var step = 0
            var syntheticPositionMs = _currentPositionMs.value
            val synthEq = ParametricEq(sampleRate)
            val synthStartNs = System.nanoTime() - syntheticPositionMs * 1_000_000L

            while (!isEngineReleased && generationGate.isCurrent(session)) {
                // Pause synchronization
                if (decoderShouldPause) {
                    try { if (localTrack.playState == AudioTrack.PLAYSTATE_PLAYING) localTrack.pause() } catch (_: Exception) {}
                    pauseLock.withLock {
                        while (decoderShouldPause && !isEngineReleased && generationGate.isCurrent(session)) {
                            pauseCondition.awaitUninterruptibly()
                        }
                    }
                    if (isEngineReleased || !generationGate.isCurrent(session)) break
                    try { localTrack.play() } catch (_: Exception) {}
                    continue
                }

                if (!_isPlaying.value) {
                    Thread.sleep(10)
                    continue
                }

                val track = _currentTrack.value ?: break
                val currentBpm = _effectiveBpm.value.coerceIn(20.0, 300.0)
                val beatPeriodSamples = (sampleRate * 60.0 / currentBpm).toInt().coerceAtLeast(100)

                // Generate mono synth samples
                for (i in monoBuffer.indices) {
                    step++
                    val beatPos = step % beatPeriodSamples
                    val isKickTime = beatPos < (sampleRate * 0.15)
                    val isHatTime = (beatPos > beatPeriodSamples * 0.5) && (beatPos < beatPeriodSamples * 0.6)

                    var sample = 0.0
                    if (isKickTime) {
                        val kickPitch = 120.0 * (1.0 - (beatPos.toDouble() / (sampleRate * 0.15)))
                        sample += sin(2.0 * PI * kickPitch * (beatPos.toDouble() / sampleRate)) * 0.8
                    }
                    if (isHatTime) {
                        sample += (kotlin.random.Random.nextFloat() - 0.5) * 0.6
                    }

                    val rootFreq = when (track.musicalKey) {
                        "8A" -> 110.0
                        "11B", "11A" -> 146.8
                        "5A", "5B" -> 130.8
                        else -> 123.47
                    }
                    phase += 2.0 * PI * rootFreq / sampleRate
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                    sample += sin(phase) * 0.25

                    val filter = _filterKnob.value
                    if (filter < 0.45f) {
                        sample *= (filter / 0.45f)
                    } else if (filter > 0.55f) {
                        val hpRatio = (filter - 0.55f) / 0.45f
                        sample -= sin(phase) * 0.2 * (1.0 - hpRatio)
                    }

                    monoBuffer[i] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.4).toInt().toShort()
                }

                // Interleave mono -> stereo
                for (i in monoBuffer.indices) {
                    stereoBuffer[i * 2] = monoBuffer[i]
                    stereoBuffer[i * 2 + 1] = monoBuffer[i]
                }

                if (_eqEnabled.value) {
                    synthEq.lowGain = _eqLow.value
                    synthEq.midGain = _eqMid.value
                    synthEq.highGain = _eqHigh.value
                    synthEq.processStereo(stereoBuffer, 0, monoBuffer.size)
                }
                if (haasEffect.isActive) {
                    haasEffect.process(stereoBuffer, 0, monoBuffer.size, sampleRate)
                }

                if (generationGate.isCurrent(session)) {
                    writePcmBlocking(localTrack, stereoBuffer, stereoBuffer.size)

                    val duration = track.durationSeconds.coerceAtLeast(1)
                    syntheticPositionMs = ((System.nanoTime() - synthStartNs) / 1_000_000L).coerceAtLeast(syntheticPositionMs)
                    internalPositionMs = syntheticPositionMs.coerceAtMost(duration * 1000L)
                    publishThrottledPosition(internalPositionMs, duration * 1000L)

                    if (_activeLoopBars.value > 0) {
                        val loopLengthSec = (_activeLoopBars.value * 4 * 60 / currentBpm).toInt().coerceAtLeast(2)
                        if ((syntheticPositionMs / 1000).toInt() >= activeCueSeconds + loopLengthSec) {
                            syntheticPositionMs = activeCueSeconds * 1000L
                        }
                    } else if (syntheticPositionMs >= duration * 1000L) {
                        if (!completionInFlight) {
                            completionInFlight = true
                            _isPlaying.value = false
                            decoderShouldPause = true
                            runCatching { localTrack.pause() }
                            onNextTrackCallback?.invoke()
                        }
                        break
                    }
                }

                Thread.sleep(10)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis error in session $session: ${e.message}")
        } finally {
            if (activeLoopSessionId == session) {
                decoderRunning = false
                decoderShouldPause = true
            }
            releaseSynthesisTrack()
            if (activeAudioTrack === localTrack) {
                activeAudioTrack = null
            }
        }
    }

    // ── Utility ────────────────────────────────────────────────────────────

    private fun isUriAccessible(uriOrPath: String): Boolean {
        if (uriOrPath.isBlank() || uriOrPath.startsWith("demo://")) return false
        return try {
            if (uriOrPath.startsWith("content://")) {
                val uri = Uri.parse(uriOrPath)
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            } else if (uriOrPath.startsWith("file://")) {
                val file = File(Uri.parse(uriOrPath).path ?: "")
                file.exists() && file.canRead()
            } else {
                val file = File(uriOrPath)
                file.exists() && file.canRead()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun setExtractorDataSource(extractor: MediaExtractor, uriOrPath: String) {
        if (uriOrPath.startsWith("content://") || uriOrPath.startsWith("file://")) {
            val uri = Uri.parse(uriOrPath)
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    return
                }
                extractor.setDataSource(context, uri, null)
            } catch (_: Exception) {
                extractor.setDataSource(context, uri, null)
            }
        } else {
            extractor.setDataSource(uriOrPath)
        }
    }

    // ── Release ────────────────────────────────────────────────────────────

    fun release() {
        synchronized(Companion) {
            if (instance === this) {
                instance = null
            }
        }
        isEngineReleased = true
        decoderShouldPause = true
        _isPlaying.value = false
        pauseLock.withLock { pauseCondition.signalAll() }
        abandonAudioFocus()
        releasePlaybackResources()
        prepareJob?.cancel()
        analysisScope.cancel()
        scope.cancel()
        try {
            audioThreadExecutor.shutdownNow()
        } catch (_: Exception) {}
        try {
            analysisExecutor.shutdownNow()
        } catch (_: Exception) {}
    }

    /**
     * Small pull decoder used only for the overlap source of a crossfade.
     * Reuses an internal pending buffer to minimize allocations.
     */
    private class StereoPcmDecoder(
        private val context: Context,
        private val uriOrPath: String
    ) {
        private val extractor = MediaExtractor()
        private val codec: MediaCodec
        val sampleRate: Int
        private val channelCount: Int
        private var inputEos = false
        private var outputEos = false
        private var pending = ShortArray(0)
        private var pendingOffset = 0
        // Reusable buffer for decoded frames
        private var frameBuffer = ShortArray(0)

        init {
            if (uriOrPath.startsWith("content://") || uriOrPath.startsWith("file://")) {
                val uri = Uri.parse(uriOrPath)
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    } ?: run {
                        extractor.setDataSource(context, uri, null)
                    }
                } catch (_: Exception) {
                    extractor.setDataSource(context, uri, null)
                }
            } else {
                extractor.setDataSource(uriOrPath)
            }
            var audioIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if ((candidate.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    audioIndex = i
                    format = candidate
                    break
                }
            }
            require(audioIndex >= 0 && format != null) { "No audio track found" }
            extractor.selectTrack(audioIndex)
            sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4")
            codec.configure(format, null, null, 0)
            codec.start()
        }

        fun readFrames(maxFrames: Int): ShortArray? {
            // Return from pending buffer first
            if (pendingOffset < pending.size) {
                val frames = min(maxFrames, (pending.size - pendingOffset) / 2)
                val start = pendingOffset
                pendingOffset += frames * 2
                // Return a slice - for crossfade this is called infrequently enough
                return pending.copyOfRange(start, start + frames * 2)
            }

            repeat(8) {
                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val info = MediaCodec.BufferInfo()
                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = outputBuffer.asShortBuffer()
                        val frames = shorts.remaining() / channelCount.coerceAtLeast(1)
                        val stereo = ShortArray(frames * 2)
                        for (frame in 0 until frames) {
                            val left = shorts.get()
                            val right = if (channelCount > 1 && shorts.hasRemaining()) shorts.get() else left
                            repeat((channelCount - 2).coerceAtLeast(0)) {
                                if (shorts.hasRemaining()) shorts.get()
                            }
                            stereo[frame * 2] = left
                            stereo[frame * 2 + 1] = right
                        }
                        if (stereo.size > maxFrames * 2) {
                            pending = stereo
                            val taken = pending.copyOfRange(0, maxFrames * 2)
                            pendingOffset = maxFrames * 2
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEos = true
                            return taken
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEos = true
                        return stereo
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEos = true
                }
                if (outputEos) return null
            }
            return null
        }

        fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
    }
}
