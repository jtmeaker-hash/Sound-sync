package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
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
import kotlin.math.PI
import kotlin.math.sin

/**
 * Single authoritative DJ Audio Engine managing playback state, MediaPlayer lifecycle,
 * fallback procedural synthesis, and DJ deck parameters (Pitch, EQ, 4-bar Looping, Cues).
 */
class DjAudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "DjAudioEngine"

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
    private var playbackJob: Job? = null

    @Volatile
    private var audioTrack: AudioTrack? = null
    private val audioTrackLock = Any()

    @Volatile
    private var mediaPlayer: MediaPlayer? = null
    private val mediaPlayerLock = Any()

    @Volatile
    private var isUsingMediaPlayer = false

    @Volatile
    private var isEngineReleased = false

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

    init {
        Log.d(TAG, "DjAudioEngine initialized in PAUSED/IDLE state. Auto-play is strictly disabled.")
    }

    private var prepareJob: Job? = null

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

            // 3. Prepare MediaPlayer on background IO thread
            prepareMediaPlayerForTrack(track, initialSec)
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
        if (uriOrPath.isBlank()) return false
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

    private fun prepareMediaPlayerForTrack(track: Track, initialPositionSec: Int) {
        val uriOrPath = track.filePath
        if (!isUriAccessible(uriOrPath)) {
            Log.d(TAG, "URI or path '$uriOrPath' is not directly accessible. Synthesis fallback will be used when played.")
            isUsingMediaPlayer = false
            return
        }

        var mp: MediaPlayer? = null
        try {
            mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
            }

            Log.d(TAG, "Opening URI in MediaPlayer: $uriOrPath")
            if (uriOrPath.startsWith("content://") || uriOrPath.startsWith("file://")) {
                mp.setDataSource(context, Uri.parse(uriOrPath))
            } else {
                mp.setDataSource(uriOrPath)
            }

            mp.prepare()
            applyPlaybackParams(mp)

            if (initialPositionSec > 0) {
                mp.seekTo(initialPositionSec * 1000)
            }

            mp.setOnCompletionListener {
                Log.d(TAG, "MediaPlayer completed playback")
                if (_activeLoopBars.value > 0) {
                    seekToSecond(activeCueSeconds)
                    if (_isPlaying.value) {
                        try {
                            it.start()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error restarting loop in MediaPlayer: ${e.message}")
                        }
                    }
                } else {
                    if (onNextTrackCallback != null) {
                        Log.d(TAG, "Auto-advancing to next track in queue...")
                        onNextTrackCallback?.invoke()
                    } else {
                        seekToSecond(0)
                        pause()
                    }
                }
            }

            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer asynchronous error: what=$what, extra=$extra. Reverting to paused synthesis.")
                synchronized(mediaPlayerLock) {
                    isUsingMediaPlayer = false
                    try {
                        mediaPlayer?.release()
                    } catch (ignored: Exception) {}
                    mediaPlayer = null
                }
                true
            }

            synchronized(mediaPlayerLock) {
                mediaPlayer = mp
                isUsingMediaPlayer = true
            }
            Log.d(TAG, "MediaPlayer successfully prepared for '${track.title}'. Ready in paused state.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException preparing MediaPlayer for '${track.filePath}': ${e.message}")
            safeReleaseMediaPlayer(mp)
            isUsingMediaPlayer = false
        } catch (e: Exception) {
            Log.e(TAG, "Exception preparing MediaPlayer for '${track.filePath}': ${e.message}")
            safeReleaseMediaPlayer(mp)
            isUsingMediaPlayer = false
        }
    }

    private fun safeReleaseMediaPlayer(mp: MediaPlayer?) {
        if (mp == null) return
        try {
            mp.reset()
            mp.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing temporary MediaPlayer: ${e.message}")
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

        Log.d(TAG, "Explicit playback started by user for track: '${track.title}' (isUsingMediaPlayer=$isUsingMediaPlayer)")
        com.example.util.DjLogger.log("PLAYER_PLAY", "'${track.title}' by '${track.artist}'")
        _isPlaying.value = true

        try {
            com.example.service.MediaPlaybackService.startService(context)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start MediaPlaybackService: ${e.message}")
        }

        if (isUsingMediaPlayer) {
            val mp = synchronized(mediaPlayerLock) { mediaPlayer }
            if (mp != null) {
                try {
                    applyPlaybackParams(mp)
                    mp.start()
                    startMediaPlayerTracking()
                    return
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "MediaPlayer was in an invalid state when start() called: ${e.message}. Falling back to synthesis.")
                    synchronized(mediaPlayerLock) {
                        isUsingMediaPlayer = false
                        try {
                            mediaPlayer?.release()
                        } catch (ignored: Exception) {}
                        mediaPlayer = null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting MediaPlayer: ${e.message}. Falling back to synthesis.")
                    isUsingMediaPlayer = false
                }
            }
        }

        // Fallback to audio synthesis preview
        startAudioSynthesis()
    }

    /**
     * Pauses playback and stops audio output immediately.
     */
    fun pause() {
        Log.d(TAG, "Playback paused.")
        com.example.util.DjLogger.log("PLAYER_STOP", "Playback paused")
        _isPlaying.value = false
        playbackJob?.cancel()

        synchronized(mediaPlayerLock) {
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.pause()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ignored exception during MediaPlayer pause: ${e.message}")
                }
            }
        }

        stopAudioSynthesis()
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

        synchronized(mediaPlayerLock) {
            if (isUsingMediaPlayer && mediaPlayer != null) {
                applyPlaybackParams(mediaPlayer)
            }
        }
    }

    fun setEq(low: Float, mid: Float, high: Float) {
        _eqLow.value = low.coerceIn(0f, 2f)
        _eqMid.value = mid.coerceIn(0f, 2f)
        _eqHigh.value = high.coerceIn(0f, 2f)
    }

    fun setFilter(value: Float) {
        _filterKnob.value = value.coerceIn(0f, 1f)
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

        synchronized(mediaPlayerLock) {
            if (isUsingMediaPlayer && mediaPlayer != null) {
                try {
                    mediaPlayer?.seekTo(clampedMs.toInt())
                } catch (e: Exception) {
                    Log.w(TAG, "Seek error in MediaPlayer: ${e.message}")
                }
            }
        }
    }

    private fun applyPlaybackParams(mp: MediaPlayer?) {
        if (mp == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val speed = (1.0f + _pitchPercent.value / 100.0f).coerceIn(0.5f, 2.0f)
                val params = PlaybackParams().apply {
                    this.speed = speed
                    this.pitch = speed
                }
                mp.playbackParams = params
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot apply playback params: ${e.message}")
        }
    }

    private fun startMediaPlayerTracking() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive && _isPlaying.value && !isEngineReleased) {
                val mp = synchronized(mediaPlayerLock) { mediaPlayer }
                val track = _currentTrack.value
                if (mp != null && track != null) {
                    try {
                        val currentMs = mp.currentPosition.toLong()
                        val currentSec = (currentMs / 1000).toInt()
                        _currentPositionMs.value = currentMs
                        _currentPositionSec.value = currentSec
                        val durationMs = if (track.durationSeconds > 0) track.durationSeconds * 1000L else mp.duration.toLong()
                        if (durationMs > 0) {
                            _playbackProgress.value = (currentMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        }

                        // Check active loop
                        if (_activeLoopBars.value > 0) {
                            val currentBpm = _effectiveBpm.value.coerceIn(20.0, 300.0)
                            val loopLengthSec = (_activeLoopBars.value * 4 * 60 / currentBpm).toInt().coerceAtLeast(2)
                            val startLoopSec = activeCueSeconds
                            if (currentSec >= startLoopSec + loopLengthSec) {
                                seekToSecond(startLoopSec)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception in tracking loop: ${e.message}")
                    }
                }
                delay(30)
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
        playbackJob?.cancel()
        playbackJob = scope.launch {
            val sampleRate = 22050
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
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
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
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

                val buffer = ShortArray(bufferSize / 2)
                var phase = 0.0
                var step = 0

                while (isActive && _isPlaying.value && !isEngineReleased) {
                    val track = _currentTrack.value ?: break
                    val currentBpm = _effectiveBpm.value.coerceIn(20.0, 300.0)
                    val beatPeriodSamples = (sampleRate * 60.0 / currentBpm).toInt().coerceAtLeast(100)

                    for (i in buffer.indices) {
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
                        buffer[i] = shortSample
                    }

                    synchronized(audioTrackLock) {
                        if (audioTrack == localTrack && localTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            localTrack.write(buffer, 0, buffer.size)
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
        synchronized(mediaPlayerLock) {
            try {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.stop()
                }
                mediaPlayer?.reset()
                mediaPlayer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ignored error releasing MediaPlayer: ${e.message}")
            }
            mediaPlayer = null
            isUsingMediaPlayer = false
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
        playbackJob?.cancel()
    }
}
