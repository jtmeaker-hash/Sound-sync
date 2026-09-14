package com.example.audio

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * High-Quality Haas & Mid/Side Stereo Spatial Width DSP Processor.
 *
 * Upgrades the raw delayed duplicate approach into a professional spatial widener:
 * 1. Mid/Side decomposition ensures center elements (vocals, kick, snare) stay solid.
 * 2. Mono-Bass protection keeps low frequencies (<160 Hz) centered and punchy with zero phase cancellation.
 * 3. Band-limited Haas micro-delay (1.0–6.0 ms sweet spot) applies high-passed and tone-damped
 *    decorrelation to the side channel, creating spacious ambient width without slapback echo.
 * 4. 100% mono compatibility: spatial components cancel out cleanly when summed to mono,
 *    completely eliminating hollow comb filtering.
 * 5. Equal-power gain normalization prevents unwanted volume inflation when widening is enabled.
 * 6. Sub-sample fractional delay interpolation eliminates zipper clicks during parameter adjustments.
 */
class HaasSurroundEffect {

    companion object {
        private const val TAG = "HaasSurroundEffect"

        // Safe musical Haas delay range: 0.5–8.0 ms (sweet spot 2.0–3.5 ms; max 12 ms)
        const val MIN_DELAY_MS = 0.5f
        const val MAX_DELAY_MS = 12.0f
        const val DEFAULT_DELAY_MS = 2.5f

        // Amount (width & spatial mix): 0.0 = bypass, 1.0 = full safe spatial width
        const val MIN_AMOUNT = 0f
        const val MAX_AMOUNT = 1f
        const val DEFAULT_AMOUNT = 0.45f

        // Power-of-two circular buffer size (4096 samples >= 42ms at 96kHz)
        private const val BUFFER_SIZE = 4096
        private const val BUFFER_MASK = BUFFER_SIZE - 1

        private const val PREFS_NAME = "soundsync_haas_prefs"
        private const val KEY_ENABLED = "haas_enabled"
        private const val KEY_AMOUNT = "haas_amount"
        private const val KEY_DELAY_MS = "haas_delay_ms"
        private const val KEY_BASS_PROTECT = "haas_bass_protect"

        fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        fun loadSettings(context: Context): HaasSettings {
            val prefs = getPrefs(context)
            return HaasSettings(
                isEnabled = prefs.getBoolean(KEY_ENABLED, false),
                amount = prefs.getFloat(KEY_AMOUNT, DEFAULT_AMOUNT).coerceIn(MIN_AMOUNT, MAX_AMOUNT),
                delayMs = prefs.getFloat(KEY_DELAY_MS, DEFAULT_DELAY_MS).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS),
                bassProtect = prefs.getBoolean(KEY_BASS_PROTECT, true)
            )
        }

        fun saveSettings(context: Context, settings: HaasSettings) {
            getPrefs(context).edit().apply {
                putBoolean(KEY_ENABLED, settings.isEnabled)
                putFloat(KEY_AMOUNT, settings.amount.coerceIn(MIN_AMOUNT, MAX_AMOUNT))
                putFloat(KEY_DELAY_MS, settings.delayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS))
                putBoolean(KEY_BASS_PROTECT, settings.bassProtect)
                apply()
            }
        }
    }

    data class HaasSettings(
        val isEnabled: Boolean = false,
        val amount: Float = DEFAULT_AMOUNT,
        val delayMs: Float = DEFAULT_DELAY_MS,
        val bassProtect: Boolean = true
    )

    // Circular delay buffer for spatial decorrelation
    private val spatialDelayBuffer = FloatArray(BUFFER_SIZE)
    private var writePos = 0

    // Biquad filters for spatial band-limiting and mono-bass protection
    private val spatialHpFilter = BiquadFilter()
    private val spatialLpFilter = BiquadFilter()
    private val spatialHpFilterR = BiquadFilter()
    private val spatialLpFilterR = BiquadFilter()
    private val sideHpFilter = BiquadFilter()
    private var lastSampleRate = -1

    // Smoothed parameters for click-free transitions
    @Volatile private var configuredAmount = DEFAULT_AMOUNT
    @Volatile private var targetAmount = 0f
    @Volatile private var targetDelayMs = DEFAULT_DELAY_MS
    @Volatile private var currentAmount = 0f
    @Volatile private var currentDelayMs = DEFAULT_DELAY_MS

    // Bass protection toggle
    @Volatile var bassProtect: Boolean = true
        private set

    // Enabled state
    @Volatile var isEnabled = false
        private set

    val amount: Float
        get() = configuredAmount

    val delayMs: Float
        get() = targetDelayMs

    // Smoothing coefficient per frame
    private val smoothingRate = 0.002f

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        targetAmount = if (enabled) configuredAmount else 0f
        if (!enabled) {
            currentAmount = 0f
        }
    }

    fun setAmount(amount: Float) {
        configuredAmount = amount.coerceIn(MIN_AMOUNT, MAX_AMOUNT)
        if (isEnabled) {
            targetAmount = configuredAmount
        }
    }

    fun setDelayMs(delayMs: Float) {
        targetDelayMs = delayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }

    fun setBassProtect(protect: Boolean) {
        bassProtect = protect
    }

    /**
     * Applies high-quality stereo spatial width enhancement to 16-bit interleaved stereo PCM.
     * Preserves center image, kick punch, and provides 100% mono downmix compatibility.
     */
    fun process(buffer: ShortArray, offset: Int, frameCount: Int, sampleRate: Int = 48000) {
        if (!isEnabled && currentAmount < 0.0005f) return

        val safeSampleRate = sampleRate.coerceIn(8000, 192000)
        if (safeSampleRate != lastSampleRate) {
            val srFloat = safeSampleRate.toFloat()
            // 2nd-order Butterworth HP filter for spatial delay lines (cutoff ~220 Hz)
            spatialHpFilter.configureHighPass(220f, srFloat)
            spatialHpFilterR.configureHighPass(220f, srFloat)
            // 2nd-order Butterworth LP filter for tone damping (cutoff ~7500 Hz)
            spatialLpFilter.configureLowPass(7500f, srFloat)
            spatialLpFilterR.configureLowPass(7500f, srFloat)
            // 2nd-order Butterworth HP crossover for side channel mono-bass protection (cutoff ~160 Hz)
            sideHpFilter.configureHighPass(160f, srFloat)
            lastSampleRate = safeSampleRate
        }

        val srFloat = safeSampleRate.toFloat()

        for (i in 0 until frameCount) {
            val idx = offset + i * 2
            if (idx + 1 >= buffer.size) break

            // Smooth live parameters to ensure zero clicks/pops
            currentAmount += (targetAmount - currentAmount) * smoothingRate
            currentDelayMs += (targetDelayMs - currentDelayMs) * smoothingRate

            if (currentAmount < 0.0005f) {
                // Bypass fast path
                continue
            }

            val leftIn = buffer[idx].toFloat()
            val rightIn = buffer[idx + 1].toFloat()

            // 1. Mid/Side decomposition
            val mid = (leftIn + rightIn) * 0.5f
            val side = (leftIn - rightIn) * 0.5f

            // 2. Write mid signal into circular spatial delay buffer
            spatialDelayBuffer[writePos] = mid

            // 3. Dual-tap decorrelation: primary tap + complementary offset tap for balanced soundstage
            val delaySamplesL = (currentDelayMs / 1000f * srFloat).coerceIn(1f, (BUFFER_SIZE - 4).toFloat())
            val intDelayL = delaySamplesL.toInt()
            val fracL = delaySamplesL - intDelayL
            val readIdxL0 = (writePos - intDelayL + BUFFER_SIZE) and BUFFER_MASK
            val readIdxL1 = (readIdxL0 - 1 + BUFFER_SIZE) and BUFFER_MASK
            val rawDelayedL = spatialDelayBuffer[readIdxL0] * (1f - fracL) + spatialDelayBuffer[readIdxL1] * fracL

            val delaySamplesR = (currentDelayMs * 1.25f / 1000f * srFloat).coerceIn(1f, (BUFFER_SIZE - 4).toFloat())
            val intDelayR = delaySamplesR.toInt()
            val fracR = delaySamplesR - intDelayR
            val readIdxR0 = (writePos - intDelayR + BUFFER_SIZE) and BUFFER_MASK
            val readIdxR1 = (readIdxR0 - 1 + BUFFER_SIZE) and BUFFER_MASK
            val rawDelayedR = spatialDelayBuffer[readIdxR0] * (1f - fracR) + spatialDelayBuffer[readIdxR1] * fracR

            writePos = (writePos + 1) and BUFFER_MASK

            // 4. Band-limit the spatial delay lines:
            // High-Pass removes sub-bass comb filtering, Low-Pass tone-damps brittle highs
            val hpOutL = spatialHpFilter.process(rawDelayedL)
            val filteredSpatialL = spatialLpFilter.process(hpOutL)

            val hpOutR = spatialHpFilterR.process(rawDelayedR)
            val filteredSpatialR = spatialLpFilterR.process(hpOutR)

            val spatialDifference = (filteredSpatialL - filteredSpatialR) * 0.5f

            // 5. Progressive widening curve:
            // Gentle and subtle in lower range (0.0..0.3), dramatically expansive and immersive in upper range (0.5..1.0)
            val progressiveAmount = currentAmount * (0.35f + 0.65f * currentAmount)
            val widthScale = 1.0f + progressiveAmount * 1.35f // 1.0x (neutral) up to 2.35x at max
            val spatialMix = progressiveAmount * 0.70f // 0.0 to 0.70 spatial decorrelation

            val sideProcessed = if (bassProtect) {
                // High-pass the side channel so bass (<160 Hz) remains 100% centered mono
                val sideHp = sideHpFilter.process(side)
                sideHp * widthScale + spatialDifference * spatialMix
            } else {
                side * widthScale + spatialDifference * spatialMix
            }

            // 6. Stereo reconstruction: L = Mid + Side, R = Mid - Side
            // Note: In mono collapse (L + R) / 2 = Mid. sideProcessed cancels completely!
            val leftOut = mid + sideProcessed
            val rightOut = mid - sideProcessed

            // 7. Equal-energy gain normalization: prevents loudness jump and maintains clean headroom
            val gainComp = 1.0f / sqrt(1.0f + 0.55f * currentAmount * currentAmount)
            val leftNormalized = leftOut * gainComp
            val rightNormalized = rightOut * gainComp

            buffer[idx] = softLimit(leftNormalized)
            buffer[idx + 1] = softLimit(rightNormalized)
        }
    }

    private fun softLimit(sample: Float): Short {
        val threshold = 32000f
        val maxVal = 32767f
        val absVal = if (sample < 0f) -sample else sample
        if (absVal <= threshold) {
            return sample.toInt().toShort()
        }
        val headroom = maxVal - threshold
        val excess = absVal - threshold
        val compressed = threshold + headroom * tanh(excess.toDouble() / headroom).toFloat()
        return (if (sample < 0f) -compressed else compressed).toInt().toShort()
    }

    /**
     * Resets delay buffers and filter histories (e.g. when seeking or changing tracks).
     */
    fun reset() {
        spatialDelayBuffer.fill(0f)
        writePos = 0
        spatialHpFilter.reset()
        spatialLpFilter.reset()
        spatialHpFilterR.reset()
        spatialLpFilterR.reset()
        sideHpFilter.reset()
        currentAmount = 0f
        targetAmount = if (isEnabled) configuredAmount else 0f
        currentDelayMs = targetDelayMs
    }

    val isActive: Boolean
        get() = isEnabled && (targetAmount > 0.001f || currentAmount > 0.001f)

    /**
     * Transposed Direct Form II Biquad Filter for precision audio filtering.
     */
    private class BiquadFilter {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var z1 = 0f
        private var z2 = 0f

        fun configureHighPass(cutoffHz: Float, sampleRate: Float, q: Float = 0.70710678f) {
            val safeSr = sampleRate.coerceIn(8000f, 192000f)
            val safeFc = cutoffHz.coerceIn(10f, safeSr * 0.49f)
            val w0 = (2.0 * PI * safeFc / safeSr).toFloat()
            val cosw = kotlin.math.cos(w0.toDouble()).toFloat()
            val sinw = kotlin.math.sin(w0.toDouble()).toFloat()
            val alpha = sinw / (2f * q)
            val a0 = 1f + alpha

            b0 = ((1f + cosw) * 0.5f) / a0
            b1 = (-(1f + cosw)) / a0
            b2 = ((1f + cosw) * 0.5f) / a0
            a1 = (-2f * cosw) / a0
            a2 = (1f - alpha) / a0
        }

        fun configureLowPass(cutoffHz: Float, sampleRate: Float, q: Float = 0.70710678f) {
            val safeSr = sampleRate.coerceIn(8000f, 192000f)
            val safeFc = cutoffHz.coerceIn(10f, safeSr * 0.49f)
            val w0 = (2.0 * PI * safeFc / safeSr).toFloat()
            val cosw = kotlin.math.cos(w0.toDouble()).toFloat()
            val sinw = kotlin.math.sin(w0.toDouble()).toFloat()
            val alpha = sinw / (2f * q)
            val a0 = 1f + alpha

            b0 = ((1f - cosw) * 0.5f) / a0
            b1 = (1f - cosw) / a0
            b2 = ((1f - cosw) * 0.5f) / a0
            a1 = (-2f * cosw) / a0
            a2 = (1f - alpha) / a0
        }

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return if (abs(y) < 1e-15f) 0f else y
        }

        fun reset() {
            z1 = 0f
            z2 = 0f
        }
    }
}
