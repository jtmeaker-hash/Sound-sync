package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Single authoritative DJ Audio Engine managing playback state, real-time DSP decoding
 * via MediaCodec + AudioTrack (EQ + Haas applied to actual audio), fallback procedural
 * synthesis for demo tracks, and DJ deck parameters (Pitch, EQ, 4-bar Looping, Cues).
 */
class DjAudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "DjAudioEngine"
        private const val TIMEOUT_US: Long = 10_000L

        @Volatile
        private var instance: DjAudioEngine? = null

        fun getInstance(context: Context): DjAudioEngine {
            return instance ?: synchronized(this) {
                instance ?: DjAudioEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    var onNextTrackCallback: (() -> Unit)? = null
    var onPreviousTrackCallback: (() -> Unit)? = null
    var onStopCallback: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.Default)
    private var streamingJob: Job? = null
    private var synthesisJob: Job? = null
    private var prepareJob: Job? = null

    @Volatile
    private var audioTrack: AudioTrack? = null
    private val audioTrackLock = Any()

    @Volatile
    private var streamingTrack: AudioTrack? = null

    // Pending seek target (ms) consumed by the streaming decode loop
    @Volatile
    private var pendingSeekMs: Long? = null

    @Volatile
    private var isEngineReleased = false

    // EQ applied to real audio in the DSP decode path
    private val parametricEq = ParametricEq(44100)

    // Authoritative Playback State: initialized strictly in a paused/stopped state
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack = _currentTrack.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f) // 0.0 to 1.0
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
    private val _pitchPercent = MutableStateFlow(0.0f) // -16% to +16%
    val pitchPercent = _pitchPercent.asStateFlow()

    private val _effectiveBpm = MutableStateFlow(126.0)
    val effectiveBpm = _effectiveBpm.asStateFlow()

    private val _eqLow = MutableStateFlow(1.0f) // 0.0 to 2.0
    val eqLow = _eqLow.asStateFlow()

    private val _eqMid = MutableStateFlow(1.0f)
    val eqMid = _eqMid.asStateFlow()

    private val _eqHigh = MutableStateFlow(1.0f)
    val eqHigh = _eqHigh.asStateFlow()

    private val _filterKnob = MutableStateFlow(0.5f) // 0.0 = Low Pass, 0.5 = Bypass, 1.0 = High Pass
    val filterKnob = _filterKnob.asStateFlow()

    private val _activeLoopBars = MutableStateFlow(0) // 0 = off, 1, 2, 4, 8
    val activeLoopBars = _activeLoopBars.asStateFlow()

    private val _waveformHeights = MutableStateFlow(FloatArray(60) { 0.3f })
    val waveformHeights = _waveformHeights.asStateFlow()

    private var activeCueSeconds: Int = 0

    // Haas Surround Effect
    private val haasEffect = HaasSurroundEffect()
    private val _haasEnabled = MutableStateFlow(false)
    val haasEnabled = _haasEnabled.asStateFlow()

    private val _haasAmount = MutableStateFlow(HaasSurroundEffect.DEFAULT_AMOUNT)
    val haasAmount = _haasAmount.asStateFlow()

    private val _haasDelayMs = MutableStateFlow(HaasSurroundEffect.DEFAULT_DELAY_MS)
    val haasDelayMs = _haasDelayMs.asStateFlow()

    init {
        Log.d(TAG, "DjAudioEngine initialized in PAUSED/IDLE state. Auto-play is strictly disabled.")
        // Restore persisted Haas settings
        val savedHaas = HaasSurroundEffect.loadSettings(context)
        _haasEnabled.value = savedHaas.isEnabled
        _haasAmount.value = savedHaas.amount
        _haasDelayMs.value = savedHaas.delayMs
        haasEffect.setEnabled(savedHaas.isEnabled)
        haasEffect.setAmount(savedHaas.amount)
        haasEffect.setDelayMs(savedHaas.delayMs)
    }

    /**
     * Loads a track for playback or preview.
     * Guaranteed to remain paused unless explicitly instructed via [autoPlay] = true.
     * Operates completely asynchronously without blocking the main/UI thread.
     */
    fun loadTrack(track: Track?, autoPlay: Boolean = false, initialPositionSec: Int = 0) {
        if (isEngineReleased) {
            Log.w(TAG, "Cannot load track: AudioEngine has been released")
            return
        }

        // Always pause before loading a new track
        pause()
        prepareJob?.cancel()
        releasePlayers()

        if (track == null) {
            _currentTrack.value = null
            _playbackProgress.value = 0f
            _currentPositionSec.value = 0
            activeCueSeconds = 0
            Log.d(TAG, "Cleared current track in player")
            return
        }

        Log.d(TAG, "Loading media item: '${track.title}' by '${track.artist}' (URI/Path: ${track.filePath}, autoPlay=$autoPlay)")
        com.example.util.DjLogger.startTiming("TRACK_LOAD_START", "'${track.title}' by '${track.artist}'")
        _currentTrack.value = track
        val baseBpm = if (track.bpm > 0) track.bpm else 126.0
        _effectiveBpm.value = baseBpm * (1.0 + _pitchPercent.value / 100.0)

        val duration = track.durationSeconds.coerceAtLeast(0)
        val initialSec = initialPositionSec.coerceIn(0, duration)
        _currentPositionSec.value = initialSec
        _currentPositionMs.value = initialSec * 1000L
        _playbackProgress.value = if (duration > 0) (initialSec.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        activeCueSeconds = 0

        // Reset Haas delay buffer to avoid stale cross-track artifacts
        haasEffect.reset()

        // Immediately check if waveform is already cached for this exact file identity
        val cacheKey = WaveformCache.getCacheKey(track, context)
        WaveformCache.get(cacheKey, context)?.let { cached ->
            _waveformData.value = cached
            _isWaveformLoading.value = false
        } ?: run {
            _waveformData.value = null
            _isWaveformLoading.value = true
        }

        // Asynchronously prepare MediaPlayer and extract real waveform without blocking UI
        prepareJob = scope.launch(Dispatchers.IO) {
            val prepStartTime = System.currentTimeMillis()
            com.example.util.DjLogger.startTiming("PLAYER_PREPARE", "'${track.title}'")
            Log.d(TAG, "Playback preparation started for '${track.title}'")

            // 1. Asynchronously extract full Rekordbox peak waveform from real audio PCM
            launch {
                try {
                    val fullWaveform = WaveformAnalyzer.analyze(context, track)
                    if (_currentTrack.value?.id == track.id) {
                        _waveformData.value = fullWaveform
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Waveform analysis failed: ${e.message}")
                } finally {
                    if (_currentTrack.value?.id == track.id) {
                        _isWaveformLoading.value = false
                    }
                }
            }

            // 2. Spectrogram overview bars
            val waveform = SpectrogramEngine.extractWaveform(context, track)
            _waveformHeights.value = waveform

            // 3. Store the current position for seamless resume after lazy playback start
            pendingSeekMs = null
            val prepTime = System.currentTimeMillis() - prepStartTime
            com.example.util.DjLogger.endTiming("PLAYER_PREPARE", "Prepared in ${prepTime}ms for '${track.title}'")
            com.example.util.DjLogger.endTiming("TRACK_LOAD_END", "'${track.title}' ready in paused state")
            Log.d(TAG, "Playback preparation finished in ${prepTime}ms for '${track.title}'")

            if (autoPlay && !isEngineReleased) {
                Log.d(TAG, "Explicit autoPlay=true requested for '${track.title}'")
                play()
            } else {
                Log.d(TAG, "Track '${track.title}' loaded and PREPARED IN PAUSED STATE (no autoplay).")
            }
        }
    }

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
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException: Lost permission for URI '$uriOrPath': ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Cannot access URI or file '$uriOrPath': ${e.message}")
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
            } catch (e: Exception) {
                extractor.setDataSource(context, uri, null)
            }
        } else {
            extractor.setDataSource(uriOrPath)
        }
    }

    /**
     * Explicit playback start triggered ONLY by user interaction.
     */
    fun play() {
        if (isEngineReleased) {
            Log.w(TAG, "Cannot start playback: AudioEngine has been released.")
            return
        }

        val track = _currentTrack.value
        if (track == null) {
            Log.w(TAG, "Play called but no track is currently loaded.")
            return
        }

        Log.d(TAG, "Explicit playback started by user for track: '${track.title}'")
        com.example.util.DjLogger.log("PLAYER_PLAY", "'${track.title}' by '${track.artist}'")
        _isPlaying.value = true

        try {
            com.example.service.MediaPlaybackService.startService(context)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start MediaPlaybackService: ${e.message}")
        }

        // Real audio files: route through MediaCodec decoder + AudioTrack so DSP (EQ + Haas) is applied.
        // Demo / inaccessible tracks fall back to procedural synthesis.
        startStreamingPlayback()
    }

    /**
     * Pauses playback and stops audio output immediately.
     */
    fun pause() {
        Log.d(TAG, "Playback paused.")
        com.example.util.DjLogger.log("PLAYER_STOP", "Playback paused")
        _isPlaying.value = false
        streamingJob?.cancel()
        streamingJob = null
        synthesisJob?.cancel()
        stopAudioSynthesis()
        streamingTrack?.let { t -> runCatching { t.pause() } }
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

    fun setPitch(percent: Float) {
        _pitchPercent.value = percent.coerceIn(-16f, 16f)
        val base = _currentTrack.value?.bpm ?: 126.0
        _effectiveBpm.value = base * (1.0 + _pitchPercent.value / 100.0)
        // The streaming decode loop reads _pitchPercent each buffer and applies it
        // via AudioTrack.playbackRate, so pitch changes are picked up immediately.
    }

    fun setEq(low: Float, mid: Float, high: Float) {
        _eqLow.value = low.coerceIn(0f, 2f)
        _eqMid.value = mid.coerceIn(0f, 2f)
        _eqHigh.value = high.coerceIn(0f, 2f)
    }

    fun setFilter(value: Float) {
        _filterKnob.value = value.coerceIn(0f, 1f)
    }

    // ── Haas Surround Effect Controls ──────────────────────────────────────

    fun setHaasEnabled(enabled: Boolean) {
        _haasEnabled.value = enabled
        haasEffect.setEnabled(enabled)
        HaasSurroundEffect.saveSettings(
            context,
            HaasSurroundEffect.HaasSettings(enabled, _haasAmount.value, _haasDelayMs.value)
        )
        Log.d(TAG, "Haas Surround ${if (enabled) "enabled" else "disabled"}")
    }

    fun setHaasAmount(amount: Float) {
        val clamped = amount.coerceIn(HaasSurroundEffect.MIN_AMOUNT, HaasSurroundEffect.MAX_AMOUNT)
        _haasAmount.value = clamped
        haasEffect.setAmount(clamped)
        HaasSurroundEffect.saveSettings(
            context,
            HaasSurroundEffect.HaasSettings(_haasEnabled.value, clamped, _haasDelayMs.value)
        )
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
        if (_activeLoopBars.value == bars) {
            _activeLoopBars.value = 0
        } else {
            _activeLoopBars.value = bars
        }
    }

    fun seekToFraction(fraction: Float) {
        val track = _currentTrack.value ?: return
        val totalMs = if (track.durationSeconds > 0) track.durationSeconds * 1000L else 0L
        val targetMs = (totalMs * fraction.coerceIn(0f, 1f)).toLong()
        seekToMs(targetMs)
    }

    fun seekToRatio(ratio: Float) {
        seekToFraction(ratio)
    }

    fun setLoop(bars: Int) {
        _activeLoopBars.value = bars
    }

    fun seekToSecond(sec: Int) {
        seekToMs(sec * 1000L)
    }

    fun seekToMs(ms: Long) {
        val track = _currentTrack.value ?: return
        val durationMs = if (track.durationSeconds > 0) track.durationSeconds * 1000L else 0L
        val clampedMs = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        val clampedSec = (clampedMs / 1000).toInt()
        
        _currentPositionMs.value = clampedMs
        _currentPositionSec.value = clampedSec
        _playbackProgress.value = if (durationMs > 0) clampedMs.toFloat() / durationMs.toFloat() else 0f

        // If streaming playback is active, queue the seek for the decode loop to apply.
        if (streamingTrack != null) {
            pendingSeekMs = clampedMs
        }
    }

    /**
     * Start real-audio playback through a MediaCodec decoder + AudioTrack.
     * Decoded PCM is passed through the parametric EQ and Haas effect before
     * being written to the output, so both effects genuinely alter the audio.
     */
    private fun startStreamingPlayback() {
        streamingJob?.cancel()
        streamingJob = scope.launch(Dispatchers.IO) {
            val track = _currentTrack.value
            if (track == null) {
                Log.w(TAG, "Streaming playback cancelled: no track loaded.")
                return@launch
            }
            val uriOrPath = track.filePath

            if (!isUriAccessible(uriOrPath)) {
                Log.d(TAG, "Track '$uriOrPath' is not directly accessible. Using synthesis fallback.")
                startAudioSynthesis()
                return@launch
            }

            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            var audioTrack: AudioTrack? = null
            try {
                val ex = MediaExtractor()
                extractor = ex
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
                    Log.w(TAG, "No audio track found for '$uriOrPath'. Using synthesis fallback.")
                    startAudioSynthesis()
                    return@launch
                }
                ex.selectTrack(audioIndex)

                val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                val chCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4"

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = if (minBuf > 0) minBuf.coerceAtLeast(8192) else 8192
                audioTrack = AudioTrack.Builder()
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

                if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioTrack failed to initialize for '$uriOrPath'.")
                    startAudioSynthesis()
                    return@launch
                }
                audioTrack.play()
                streamingTrack = audioTrack

                // Apply pitch via AudioTrack playback rate (matches prior fast-forward behavior)
                runCatching {
                    audioTrack.playbackRate = (sampleRate * (1f + _pitchPercent.value / 100f)).toInt().coerceIn(4000, 192000)
                }

                // Seek to the requested start position
                var startMs = _currentPositionMs.value.coerceAtLeast(0L)
                pendingSeekMs?.let { startMs = it; pendingSeekMs = null }
                if (startMs > 0) {
                    runCatching { ex.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
                }

                val durationMs = (track.durationSeconds.coerceAtLeast(1) * 1000L)
                _currentPositionMs.value = startMs
                _currentPositionSec.value = (startMs / 1000).toInt()
                _playbackProgress.value = if (durationMs > 0) (startMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

                // DSP engine configured for the decoded sample rate
                val eq = ParametricEq(sampleRate)
                val bufferInfo = MediaCodec.BufferInfo()
                val pcmStereo = ShortArray(bufferSize / 2)
                var inputEos = false
                var outputEos = false
                var iterations = 0L

                while (isActive && _isPlaying.value && !isEngineReleased) {
                    iterations++
                    if (iterations > 2_000_000L) {
                        Log.w(TAG, "Streaming loop safety limit hit for '${track.title}'")
                        break
                    }

                    // Apply a queued seek if any
                    pendingSeekMs?.let { seekMs ->
                        pendingSeekMs = null
                        val target = seekMs.coerceIn(0L, durationMs)
                        runCatching {
                            ex.seekTo(target * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            codec.flush()
                        }
                        inputEos = false
                        outputEos = false
                        _currentPositionMs.value = target
                        _currentPositionSec.value = (target / 1000).toInt()
                        _playbackProgress.value = if (durationMs > 0) (target.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                    }

                    if (!inputEos) {
                        val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val size = ex.readSampleData(inBuf, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEos = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                                    ex.advance()
                                }
                            } else {
                                // Could not obtain input buffer; wait and retry.
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // Output format may change (rare); ignore for now.
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
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
                                        val l = ss.get()
                                        val r = ss.get()
                                        pcmStereo[fi * 2] = l
                                        pcmStereo[fi * 2 + 1] = r
                                        fi++
                                    }
                                    filled = fi
                                }

                                if (filled > 0) {
                                    // Apply parametric EQ
                                    eq.lowGain = _eqLow.value
                                    eq.midGain = _eqMid.value
                                    eq.highGain = _eqHigh.value
                                    eq.processStereo(pcmStereo, 0, filled)

                                    // Apply Haas Surround effect
                                    if (haasEffect.isActive) {
                                        haasEffect.process(pcmStereo, 0, filled)
                                    }

                                    // Write decoded+processed PCM (blocks -> backpressure)
                                    audioTrack.write(pcmStereo, 0, filled * 2)

                                    // Track position from the media PTS (source of truth)
                                    val pts = bufferInfo.presentationTimeUs
                                    if (pts >= 0) {
                                        val posMs = (pts / 1000L).coerceIn(0L, durationMs)
                                        _currentPositionMs.value = posMs
                                        _currentPositionSec.value = (posMs / 1000).toInt()
                                        _playbackProgress.value = if (durationMs > 0) (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                                    }

                                    // Refresh pitch (applied via playback rate)
                                    runCatching {
                                        audioTrack.playbackRate = (sampleRate * (1f + _pitchPercent.value / 100f)).toInt().coerceIn(4000, 192000)
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputEos = true
                            }
                        }
                    }

                    // Handle loop boundary / completion
                    if (_activeLoopBars.value > 0) {
                        val currentBpm = _effectiveBpm.value.coerceIn(20.0, 300.0)
                        val loopLenSec = (_activeLoopBars.value * 4 * 60 / currentBpm).toInt().coerceAtLeast(2)
                        val loopEndMs = activeCueSeconds * 1000L + loopLenSec * 1000L
                        if (_currentPositionMs.value >= loopEndMs) {
                            runCatching {
                                ex.seekTo(activeCueSeconds * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                codec.flush()
                            }
                            inputEos = false
                            outputEos = false
                            continue
                        }
                    }

                    if (inputEos && outputEos) {
                        if (onNextTrackCallback != null) {
                            Log.d(TAG, "Streaming playback completed, auto-advancing.")
                            pause()
                            onNextTrackCallback?.invoke()
                        } else {
                            pause()
                            seekToSecond(0)
                        }
                        break
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.e(TAG, "Streaming playback error for '${_currentTrack.value?.title}': ${e.message}", e)
            } finally {
                try { codec?.stop() } catch (ignored: Exception) {}
                try { codec?.release() } catch (ignored: Exception) {}
                try { extractor?.release() } catch (ignored: Exception) {}
                if (streamingTrack === audioTrack) {
                    streamingTrack = null
                }
                try {
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack?.pause()
                    audioTrack?.flush()
                    audioTrack?.release()
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun generateStaticWaveform(track: Track) {
        val seed = track.id.hashCode().toLong()
        val random = kotlin.random.Random(seed)
        val array = FloatArray(60) {
            (0.2f + random.nextFloat() * 0.75f).coerceIn(0.1f, 1.0f)
        }
        _waveformHeights.value = array
    }

    private fun startAudioSynthesis() {
        synthesisJob?.cancel()
        synthesisJob = scope.launch {
            val sampleRate = 22050
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = if (minBuf > 0) minBuf.coerceAtLeast(4096) else 4096

            var localTrack: AudioTrack? = null
            try {
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
                    Log.w(TAG, "AudioTrack failed to initialize, skipping synthesis")
                    localTrack.release()
                    return@launch
                }

                synchronized(audioTrackLock) {
                    audioTrack = localTrack
                }

                localTrack.play()

                // Mono synth buffer
                val monoBuffer = ShortArray(bufferSize / 2)
                // Stereo interleaved output buffer (L, R, L, R, ...)
                val stereoBuffer = ShortArray(bufferSize)
                var phase = 0.0
                var step = 0

                while (isActive && _isPlaying.value && !isEngineReleased) {
                    val track = _currentTrack.value ?: break
                    val currentBpm = _effectiveBpm.value.coerceIn(20.0, 300.0)
                    val beatPeriodSamples = (sampleRate * 60.0 / currentBpm).toInt().coerceAtLeast(100)

                    // Generate mono synth samples
                    for (i in monoBuffer.indices) {
                        step++
                        val beatPos = step % beatPeriodSamples
                        val isKickTime = beatPos < (sampleRate * 0.15)
                        val isHatTime = (beatPos > beatPeriodSamples * 0.5) && (beatPos < beatPeriodSamples * 0.6)

                        // Base Synth Note + Rhythmic Kick & Hat modulated by EQ
                        var sample = 0.0
                        if (isKickTime) {
                            val kickPitch = 120.0 * (1.0 - (beatPos.toDouble() / (sampleRate * 0.15)))
                            sample += sin(2.0 * PI * kickPitch * (beatPos.toDouble() / sampleRate)) * _eqLow.value * 0.8
                        }
                        if (isHatTime) {
                            val noise = (kotlin.random.Random.nextFloat() - 0.5) * 2.0
                            sample += noise * _eqHigh.value * 0.3
                        }

                        // Melodic bassline
                        val rootFreq = when (track.musicalKey) {
                            "8A" -> 110.0 // A2
                            "11B", "11A" -> 146.8 // D3
                            "5A", "5B" -> 130.8 // C3
                            else -> 123.47 // B2
                        }
                        phase += 2.0 * PI * rootFreq / sampleRate
                        if (phase > 2.0 * PI) phase -= 2.0 * PI
                        sample += sin(phase) * 0.25 * _eqMid.value

                        // Filter effect
                        val filter = _filterKnob.value
                        if (filter < 0.45f) {
                            sample *= (filter / 0.45f)
                        } else if (filter > 0.55f) {
                            val hpRatio = (filter - 0.55f) / 0.45f
                            sample = (sample - (sin(phase) * 0.2 * (1.0 - hpRatio)))
                        }

                        val shortSample = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.4).toInt().toShort()
                        monoBuffer[i] = shortSample
                    }

                    // Interleave mono → stereo (duplicate L/R for clean center image)
                    for (i in monoBuffer.indices) {
                        stereoBuffer[i * 2] = monoBuffer[i]
                        stereoBuffer[i * 2 + 1] = monoBuffer[i]
                    }

                    // Apply Haas Surround Effect on stereo buffer (cross-channel delay)
                    if (haasEffect.isActive) {
                        haasEffect.process(stereoBuffer, 0, monoBuffer.size)
                    }

                    synchronized(audioTrackLock) {
                        if (audioTrack == localTrack && localTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            localTrack.write(stereoBuffer, 0, stereoBuffer.size)
                        }
                    }

                    // Advance track position safely
                    val duration = track.durationSeconds.coerceAtLeast(1)
                    val newSec = _currentPositionSec.value + 1
                    if (_activeLoopBars.value > 0) {
                        val loopLengthSec = (_activeLoopBars.value * 4 * 60 / currentBpm).toInt().coerceAtLeast(2)
                        val startLoopSec = activeCueSeconds
                        if (newSec >= startLoopSec + loopLengthSec) {
                            seekToSecond(startLoopSec)
                        } else {
                            seekToSecond(newSec)
                        }
                    } else {
                        if (newSec >= duration) {
                            seekToSecond(0)
                            pause()
                        } else {
                            seekToSecond(newSec)
                        }
                    }

                    delay(300)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio synthesis error: ${e.message}", e)
            } finally {
                stopAudioSynthesis()
            }
        }
    }

    private fun stopAudioSynthesis() {
        synchronized(audioTrackLock) {
            try {
                if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.stop()
                }
                audioTrack?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ignored error stopping AudioTrack: ${e.message}")
            }
            audioTrack = null
        }
    }

    private fun releasePlayers() {
        streamingTrack?.let { t ->
            try {
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
                t.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ignored error releasing streaming AudioTrack: ${e.message}")
            }
            streamingTrack = null
        }
        stopAudioSynthesis()
    }

    /**
     * Releases all player instances and cancels active coroutines when the ViewModel is destroyed.
     */
    fun release() {
        Log.d(TAG, "Releasing DjAudioEngine...")
        isEngineReleased = true
        pause()
        releasePlayers()
        prepareJob?.cancel()
        streamingJob?.cancel()
        synthesisJob?.cancel()
    }
}
