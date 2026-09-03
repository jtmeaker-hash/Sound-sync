package com.example.ui.djtools

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DeckACyan
import com.example.ui.theme.DeckBPink
import com.example.ui.theme.DjObsidian
import com.example.ui.theme.DjSurfaceBorder
import com.example.ui.theme.DjSurfaceCard
import com.example.ui.theme.DjSurfaceDark
import com.example.ui.theme.DjSurfaceElevated
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * High-precision standalone metronome engine.
 * Generates exact sample-accurate PCM clicks directly into an independent AudioTrack buffer.
 * Timing is locked to the hardware DAC clock and is completely immune to UI frame drops or Compose recomposition.
 */
class MetronomeEngine {
    companion object {
        const val MIN_BPM = 30
        const val MAX_BPM = 300
        const val DEFAULT_BPM = 120
        private const val SAMPLE_RATE = 44100
    }

    private val isRunning = AtomicBoolean(false)
    private val currentBpmVal = AtomicInteger(DEFAULT_BPM)
    private val currentBeatsPerMeasure = AtomicInteger(4)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _bpm = MutableStateFlow(DEFAULT_BPM)
    val bpm = _bpm.asStateFlow()

    private val _currentBeat = MutableStateFlow(1)
    val currentBeat = _currentBeat.asStateFlow()

    private val _beatsPerMeasure = MutableStateFlow(4)
    val beatsPerMeasure = _beatsPerMeasure.asStateFlow()

    private var audioThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    // Synthesized click waveforms (15ms duration)
    private val accentClickPcm: ShortArray = generateClickPcm(frequencyHz = 1600.0, durationMs = 15, isAccent = true)
    private val regularClickPcm: ShortArray = generateClickPcm(frequencyHz = 900.0, durationMs = 15, isAccent = false)

    fun setBpm(newBpm: Int) {
        val clamped = newBpm.coerceIn(MIN_BPM, MAX_BPM)
        currentBpmVal.set(clamped)
        _bpm.value = clamped
    }

    fun adjustBpm(delta: Int) {
        setBpm(currentBpmVal.get() + delta)
    }

    fun setBeatsPerMeasure(beats: Int) {
        val clamped = beats.coerceIn(1, 12)
        currentBeatsPerMeasure.set(clamped)
        _beatsPerMeasure.value = clamped
    }

    fun resetBpm() {
        setBpm(DEFAULT_BPM)
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        _isPlaying.value = true
        _currentBeat.value = 1

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE / 10)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        audioThread = Thread({
            var beatIndex = 0
            val silenceBuffer = ShortArray(4096)

            while (isRunning.get()) {
                val bpmNow = currentBpmVal.get().coerceIn(MIN_BPM, MAX_BPM)
                val beats = currentBeatsPerMeasure.get().coerceIn(1, 12)
                val beatNum = (beatIndex % beats) + 1
                _currentBeat.value = beatNum

                // Choose accented click for beat 1, regular click for others
                val clickPcm = if (beatNum == 1) accentClickPcm else regularClickPcm
                track.write(clickPcm, 0, clickPcm.size)

                // Total samples per beat = (60.0 / bpm) * SAMPLE_RATE
                val totalSamplesPerBeat = (SAMPLE_RATE * 60.0 / bpmNow).toInt()
                var remainingSilenceSamples = (totalSamplesPerBeat - clickPcm.size).coerceAtLeast(0)

                while (remainingSilenceSamples > 0 && isRunning.get()) {
                    val toWrite = remainingSilenceSamples.coerceAtMost(silenceBuffer.size)
                    track.write(silenceBuffer, 0, toWrite)
                    remainingSilenceSamples -= toWrite
                }

                beatIndex++
            }

            runCatching {
                track.stop()
                track.release()
            }
        }, "SoundSync-Metronome").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        _isPlaying.value = false
        audioThread?.interrupt()
        audioThread = null
        audioTrack = null
        _currentBeat.value = 1
    }

    fun toggle() {
        if (_isPlaying.value) stop() else start()
    }

    fun release() {
        stop()
    }

    private fun generateClickPcm(frequencyHz: Double, durationMs: Int, isAccent: Boolean): ShortArray {
        val numSamples = (SAMPLE_RATE * durationMs / 1000.0).toInt()
        val pcm = ShortArray(numSamples)
        val amplitude = if (isAccent) 30000.0 else 22000.0

        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            // Exponential decay envelope
            val env = Math.exp(-t * (if (isAccent) 120.0 else 180.0))
            val wave = sin(2.0 * PI * frequencyHz * t)
            pcm[i] = (wave * env * amplitude).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm
    }
}

/**
 * DJ Tools - Metronome Composable UI.
 */
@Composable
fun MetronomeTool(
    engine: MetronomeEngine = remember { MetronomeEngine() },
    modifier: Modifier = Modifier
) {
    val isPlaying by engine.isPlaying.collectAsState()
    val bpm by engine.bpm.collectAsState()
    val currentBeat by engine.currentBeat.collectAsState()
    val beatsPerMeasure by engine.beatsPerMeasure.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            // Keep running or release if dialog is dismissed
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DjSurfaceDark),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DjSurfaceBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "METRONOME",
                        color = DeckACyan,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Hardware audio clock · sample-accurate click",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isPlaying) NeonGreen.copy(alpha = 0.2f) else DjSurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isPlaying) NeonGreen else DjSurfaceBorder)
                ) {
                    Text(
                        text = if (isPlaying) "ACTIVE" else "STOPPED",
                        color = if (isPlaying) NeonGreen else TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Large BPM Display & Beat Visualizer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DjObsidian, RoundedCornerShape(10.dp))
                    .border(1.dp, DjSurfaceBorder, RoundedCornerShape(10.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "$bpm",
                    color = if (isPlaying) DeckACyan else TextPrimary,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "BPM",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                // Beat Dots (Accent on Beat 1)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (b in 1..beatsPerMeasure) {
                        val isCurrent = isPlaying && currentBeat == b
                        val isAccent = b == 1
                        val activeColor = if (isAccent) NeonAmber else DeckACyan

                        Box(
                            modifier = Modifier
                                .size(if (isAccent) 16.dp else 12.dp)
                                .background(
                                    if (isCurrent) activeColor else DjSurfaceElevated,
                                    CircleShape
                                )
                                .border(
                                    1.dp,
                                    if (isCurrent) activeColor else DjSurfaceBorder,
                                    CircleShape
                                )
                        )
                    }
                }
            }

            // BPM Slider (30 .. 300)
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = bpm.toFloat(),
                    onValueChange = { engine.setBpm(it.toInt()) },
                    valueRange = MetronomeEngine.MIN_BPM.toFloat()..MetronomeEngine.MAX_BPM.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = DeckACyan,
                        activeTrackColor = DeckACyan,
                        inactiveTrackColor = DjSurfaceElevated
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${MetronomeEngine.MIN_BPM} BPM", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("${MetronomeEngine.MAX_BPM} BPM", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // BPM Adjustment Buttons (+1, -1, +5, -5, Reset)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { engine.adjustBpm(-5) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Text("-5", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                OutlinedButton(
                    onClick = { engine.adjustBpm(-1) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Text("-1", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                OutlinedButton(
                    onClick = { engine.adjustBpm(1) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Text("+1", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                OutlinedButton(
                    onClick = { engine.adjustBpm(5) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Text("+5", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                OutlinedButton(
                    onClick = { engine.resetBpm() },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = TextMuted, modifier = Modifier.size(14.dp))
                }
            }

            // Time Signature Selector (2/4, 3/4, 4/4, 6/8)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Beats:", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                listOf(2, 3, 4, 6).forEach { beats ->
                    val isSelected = beatsPerMeasure == beats
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { engine.setBeatsPerMeasure(beats) },
                        color = if (isSelected) DeckACyan.copy(alpha = 0.2f) else DjSurfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) DeckACyan else DjSurfaceBorder
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "$beats/4",
                            color = if (isSelected) DeckACyan else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Big Start/Stop Button
            Button(
                onClick = { engine.toggle() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPlaying) NeonRed else DeckACyan,
                    contentColor = DjObsidian
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isPlaying) "STOP METRONOME" else "START METRONOME",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
