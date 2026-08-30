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

class DjAudioEngine(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var playbackJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isUsingMediaPlayer = false

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack = _currentTrack.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f) // 0.0 to 1.0
    val playbackProgress = _playbackProgress.asStateFlow()

    private val _currentPositionSec = MutableStateFlow(0)
    val currentPositionSec = _currentPositionSec.asStateFlow()

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

    fun loadTrack(track: Track) {
        val wasPlaying = _isPlaying.value
        pause()
        releasePlayers()

        _currentTrack.value = track
        _effectiveBpm.value = track.bpm * (1.0 + _pitchPercent.value / 100.0)
        _playbackProgress.value = 0f
        _currentPositionSec.value = 0
        activeCueSeconds = 0
        generateStaticWaveform(track)

        prepareMediaPlayerForTrack(track)

        if (wasPlaying) {
            play()
        }
    }

    private fun prepareMediaPlayerForTrack(track: Track) {
        try {
            val uriOrPath = track.filePath
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )

            var prepared = false
            if (uriOrPath.startsWith("content://") || uriOrPath.startsWith("file://")) {
                mp.setDataSource(context, Uri.parse(uriOrPath))
                mp.prepare()
                prepared = true
            } else if (File(uriOrPath).exists() && File(uriOrPath).canRead()) {
                mp.setDataSource(uriOrPath)
                mp.prepare()
                prepared = true
            }

            if (prepared) {
                applyPlaybackParams(mp)
                mp.setOnCompletionListener {
                    if (_activeLoopBars.value > 0) {
                        seekToSecond(activeCueSeconds)
                        mp.start()
                    } else {
                        seekToSecond(0)
                        pause()
                    }
                }
                mp.setOnErrorListener { _, what, extra ->
                    Log.w("DjAudioEngine", "MediaPlayer error: $what, $extra")
                    isUsingMediaPlayer = false
                    true
                }
                mediaPlayer = mp
                isUsingMediaPlayer = true
            } else {
                mp.release()
                isUsingMediaPlayer = false
            }
        } catch (e: Exception) {
            Log.d("DjAudioEngine", "Cannot use MediaPlayer for path '${track.filePath}', falling back to synthesis: ${e.message}")
            isUsingMediaPlayer = false
        }
    }

    fun play() {
        if (_currentTrack.value == null) return
        _isPlaying.value = true

        if (isUsingMediaPlayer && mediaPlayer != null) {
            try {
                applyPlaybackParams(mediaPlayer)
                mediaPlayer?.start()
                startMediaPlayerTracking()
            } catch (e: Exception) {
                isUsingMediaPlayer = false
                startAudioSynthesis()
            }
        } else {
            startAudioSynthesis()
        }
    }

    fun pause() {
        _isPlaying.value = false
        playbackJob?.cancel()
        if (isUsingMediaPlayer && mediaPlayer != null) {
            try {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.pause()
                }
            } catch (ignored: Exception) {}
        } else {
            stopAudioSynthesis()
        }
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

        if (isUsingMediaPlayer && mediaPlayer != null) {
            applyPlaybackParams(mediaPlayer)
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
        val totalSec = track.durationSeconds
        val targetSec = (totalSec * fraction).toInt()
        seekToSecond(targetSec)
    }

    fun seekToRatio(ratio: Float) {
        seekToFraction(ratio)
    }

    fun setLoop(bars: Int) {
        _activeLoopBars.value = bars
    }

    fun seekToSecond(sec: Int) {
        val track = _currentTrack.value ?: return
        val clamped = sec.coerceIn(0, track.durationSeconds)
        _currentPositionSec.value = clamped
        _playbackProgress.value = if (track.durationSeconds > 0) clamped.toFloat() / track.durationSeconds else 0f

        if (isUsingMediaPlayer && mediaPlayer != null) {
            try {
                mediaPlayer?.seekTo(clamped * 1000)
            } catch (ignored: Exception) {}
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
            Log.w("DjAudioEngine", "Cannot apply playback params", e)
        }
    }

    private fun startMediaPlayerTracking() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive && _isPlaying.value) {
                val mp = mediaPlayer
                val track = _currentTrack.value
                if (mp != null && track != null) {
                    try {
                        val currentMs = mp.currentPosition
                        val currentSec = currentMs / 1000
                        _currentPositionSec.value = currentSec
                        val durationMs = if (track.durationSeconds > 0) track.durationSeconds * 1000 else mp.duration
                        if (durationMs > 0) {
                            _playbackProgress.value = (currentMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        }

                        // Check active loop
                        if (_activeLoopBars.value > 0) {
                            val currentBpm = _effectiveBpm.value
                            val loopLengthSec = (_activeLoopBars.value * 4 * 60 / currentBpm).toInt().coerceAtLeast(2)
                            val startLoopSec = activeCueSeconds
                            if (currentSec >= startLoopSec + loopLengthSec) {
                                seekToSecond(startLoopSec)
                            }
                        }
                    } catch (ignored: Exception) {}
                }
                delay(100)
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
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            try {
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
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()

                val buffer = ShortArray(bufferSize / 2)
                var phase = 0.0
                var step = 0

                while (isActive && _isPlaying.value) {
                    val track = _currentTrack.value ?: break
                    val currentBpm = _effectiveBpm.value
                    val beatPeriodSamples = (sampleRate * 60.0 / currentBpm).toInt()

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

                    audioTrack?.write(buffer, 0, buffer.size)

                    // Advance track position
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
                        if (newSec >= track.durationSeconds) {
                            seekToSecond(0)
                            pause()
                        } else {
                            seekToSecond(newSec)
                        }
                    }

                    delay(300)
                }
            } catch (e: Exception) {
                // Audio device handling
            } finally {
                stopAudioSynthesis()
            }
        }
    }

    private fun stopAudioSynthesis() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignored
        }
        audioTrack = null
    }

    private fun releasePlayers() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (ignored: Exception) {}
        mediaPlayer = null
        stopAudioSynthesis()
    }

    fun release() {
        pause()
        releasePlayers()
        playbackJob?.cancel()
    }
}
