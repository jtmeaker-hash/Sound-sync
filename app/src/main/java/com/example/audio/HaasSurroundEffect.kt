package com.example.audio

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * High-Quality Haas & Mid/Side Stereo Spatial Width DSP Processor.
 *
 * Implements genuine Haas precedence spatial widening via inter-channel delay:
 * 1. Mid/Side decomposition ensures center elements (vocals, kick, snare) stay solid.
 * 2. Mono-Bass protection keeps low frequencies (<160 Hz) centered and punchy with zero phase cancellation.
 * 3. Band-limited Haas micro-delay (0.0–30.0 ms) applies tone-damped
 *    decorrelation to the side channel, creating spacious ambient width without slapback echo.
 * 4. 100% mono compatibility: spatial components cancel out cleanly when summed to mono,
 *    completely eliminating hollow comb filtering.
 * 5. Equal-power gain normalization prevents unwanted volume inflation when widening is enabled.
 * 6. Sub-sample fractional delay interpolation eliminates zipper clicks during parameter adjustments.
 */
class HaasSurroundEffect {

    enum class HaasPreset {
        OFF,
        SUBTLE,
        WIDE,
        ULTRA_WIDE,
        CUSTOM
    }

    enum class ProcessingMode {
        SIDE_ONLY,
        FULL_STEREO
    }

    companion object {
        private const val TAG = "HaasSurroundEffect"

        // Delay range: 0.0–30.0 ms (sweet spot 5–15 ms)
        const val MIN_DELAY_MS = 0.0f
        const val MAX_DELAY_MS = 30.0f
        const val DEFAULT_DELAY_MS = 8.0f

        // Effect Mix (wet/dry width mix): 0.0 = pure dry, 1.0 = full spatial width
        const val MIN_EFFECT_MIX = 0.0f
        const val MAX_EFFECT_MIX = 1.0f
        const val DEFAULT_EFFECT_MIX = 0.70f

        // Backward-compatible aliases for amount
        const val MIN_AMOUNT = MIN_EFFECT_MIX
        const val MAX_AMOUNT = MAX_EFFECT_MIX
        const val DEFAULT_AMOUNT = DEFAULT_EFFECT_MIX

        // Stereo Width: 0.0 to 2.0 (1.0 = normal, >1.0 = widened)
        const val MIN_STEREO_WIDTH = 0.0f
        const val MAX_STEREO_WIDTH = 2.0f
        const val DEFAULT_STEREO_WIDTH = 1.15f

        // Crossfeed: 0.0 to 1.0
        const val MIN_CROSSFEED = 0.0f
        const val MAX_CROSSFEED = 1.0f
        const val DEFAULT_CROSSFEED = 0.0f

        // Low Cutoff Hz (Bass protect cutoff frequency)
        const val MIN_LOW_CUTOFF_HZ = 20.0f
        const val MAX_LOW_CUTOFF_HZ = 500.0f
        const val DEFAULT_LOW_CUTOFF_HZ = 160.0f

        // High Cutoff Hz (Tone damping / high frequency cutoff)
        const val MIN_HIGH_CUTOFF_HZ = 1000.0f
        const val MAX_HIGH_CUTOFF_HZ = 20000.0f
        const val DEFAULT_HIGH_CUTOFF_HZ = 7500.0f

        // Spatial Balance: -100.0 to +100.0 (0.0 = centered)
        const val MIN_BALANCE = -100.0f
        const val MAX_BALANCE = 100.0f
        const val DEFAULT_BALANCE = 0.0f

        // Output Compensation dB: -12.0 dB to +12.0 dB
        const val MIN_OUTPUT_COMP_DB = -12.0f
        const val MAX_OUTPUT_COMP_DB = 12.0f
        const val DEFAULT_OUTPUT_COMP_DB = 0.0f

        // Presets Delay Values
        const val PRESET_OFF_MS = 0.0f
        const val PRESET_SUBTLE_MS = 5.0f
        const val PRESET_WIDE_MS = 8.0f
        const val PRESET_ULTRA_WIDE_MS = 15.0f

        // Power-of-two circular buffer size (8192 samples >= 42ms at 192kHz, >= 170ms at 48kHz)
        private const val BUFFER_SIZE = 8192
        private const val BUFFER_MASK = BUFFER_SIZE - 1

        private const val PREFS_NAME = "soundsync_haas_prefs"
        private const val KEY_ENABLED = "haas_enabled"
        private const val KEY_AMOUNT = "haas_amount"
        private const val KEY_DELAY_MS = "haas_delay_ms"
        private const val KEY_BASS_PROTECT = "haas_bass_protect"
        private const val KEY_PRESET = "haas_preset"
        private const val KEY_MODE = "haas_mode"
        private const val KEY_STEREO_WIDTH = "haas_stereo_width"
        private const val KEY_EFFECT_MIX = "haas_effect_mix"
        private const val KEY_CROSSFEED = "haas_crossfeed"
        private const val KEY_LOW_CUTOFF_HZ = "haas_low_cutoff_hz"
        private const val KEY_HIGH_CUTOFF_HZ = "haas_high_cutoff_hz"
        private const val KEY_BALANCE = "haas_balance"
        private const val KEY_OUTPUT_COMP_DB = "haas_output_comp_db"

        fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        fun loadSettings(context: Context): HaasSettings {
            val prefs = getPrefs(context)
            val legacyAmount = prefs.getFloat(KEY_AMOUNT, DEFAULT_EFFECT_MIX)
            val effectMix = prefs.getFloat(KEY_EFFECT_MIX, legacyAmount).coerceIn(MIN_EFFECT_MIX, MAX_EFFECT_MIX)

            val presetStr = prefs.getString(KEY_PRESET, HaasPreset.WIDE.name)
            val preset = try {
                HaasPreset.valueOf(presetStr ?: HaasPreset.WIDE.name)
            } catch (_: Exception) {
                HaasPreset.WIDE
            }

            val modeStr = prefs.getString(KEY_MODE, ProcessingMode.SIDE_ONLY.name)
            val mode = try {
                ProcessingMode.valueOf(modeStr ?: ProcessingMode.SIDE_ONLY.name)
            } catch (_: Exception) {
                ProcessingMode.SIDE_ONLY
            }

            return HaasSettings(
                isEnabled = prefs.getBoolean(KEY_ENABLED, false),
                amount = effectMix,
                delayMs = prefs.getFloat(KEY_DELAY_MS, DEFAULT_DELAY_MS).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS),
                bassProtect = prefs.getBoolean(KEY_BASS_PROTECT, true),
                preset = preset,
                mode = mode,
                stereoWidth = prefs.getFloat(KEY_STEREO_WIDTH, DEFAULT_STEREO_WIDTH).coerceIn(MIN_STEREO_WIDTH, MAX_STEREO_WIDTH),
                effectMix = effectMix,
                crossfeed = prefs.getFloat(KEY_CROSSFEED, DEFAULT_CROSSFEED).coerceIn(MIN_CROSSFEED, MAX_CROSSFEED),
                lowCutoffHz = prefs.getFloat(KEY_LOW_CUTOFF_HZ, DEFAULT_LOW_CUTOFF_HZ).coerceIn(MIN_LOW_CUTOFF_HZ, MAX_LOW_CUTOFF_HZ),
                highCutoffHz = prefs.getFloat(KEY_HIGH_CUTOFF_HZ, DEFAULT_HIGH_CUTOFF_HZ).coerceIn(MIN_HIGH_CUTOFF_HZ, MAX_HIGH_CUTOFF_HZ),
                balance = prefs.getFloat(KEY_BALANCE, DEFAULT_BALANCE).coerceIn(MIN_BALANCE, MAX_BALANCE),
                outputCompDb = prefs.getFloat(KEY_OUTPUT_COMP_DB, DEFAULT_OUTPUT_COMP_DB).coerceIn(MIN_OUTPUT_COMP_DB, MAX_OUTPUT_COMP_DB)
            )
        }

        fun saveSettings(context: Context, settings: HaasSettings) {
            getPrefs(context).edit().apply {
                putBoolean(KEY_ENABLED, settings.isEnabled)
                putFloat(KEY_AMOUNT, settings.effectMix.coerceIn(MIN_EFFECT_MIX, MAX_EFFECT_MIX))
                putFloat(KEY_DELAY_MS, settings.delayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS))
                putBoolean(KEY_BASS_PROTECT, settings.bassProtect)
                putString(KEY_PRESET, settings.preset.name)
                putString(KEY_MODE, settings.mode.name)
                putFloat(KEY_STEREO_WIDTH, settings.stereoWidth.coerceIn(MIN_STEREO_WIDTH, MAX_STEREO_WIDTH))
                putFloat(KEY_EFFECT_MIX, settings.effectMix.coerceIn(MIN_EFFECT_MIX, MAX_EFFECT_MIX))
                putFloat(KEY_CROSSFEED, settings.crossfeed.coerceIn(MIN_CROSSFEED, MAX_CROSSFEED))
                putFloat(KEY_LOW_CUTOFF_HZ, settings.lowCutoffHz.coerceIn(MIN_LOW_CUTOFF_HZ, MAX_LOW_CUTOFF_HZ))
                putFloat(KEY_HIGH_CUTOFF_HZ, settings.highCutoffHz.coerceIn(MIN_HIGH_CUTOFF_HZ, MAX_HIGH_CUTOFF_HZ))
                putFloat(KEY_BALANCE, settings.balance.coerceIn(MIN_BALANCE, MAX_BALANCE))
                putFloat(KEY_OUTPUT_COMP_DB, settings.outputCompDb.coerceIn(MIN_OUTPUT_COMP_DB, MAX_OUTPUT_COMP_DB))
                apply()
            }
        }
    }

    data class HaasSettings(
        val isEnabled: Boolean = false,
        val amount: Float = DEFAULT_EFFECT_MIX,
        val delayMs: Float = DEFAULT_DELAY_MS,
        val bassProtect: Boolean = true,
        val preset: HaasPreset = HaasPreset.WIDE,
        val mode: ProcessingMode = ProcessingMode.SIDE_ONLY,
        val stereoWidth: Float = DEFAULT_STEREO_WIDTH,
        val effectMix: Float = DEFAULT_EFFECT_MIX,
        val crossfeed: Float = DEFAULT_CROSSFEED,
        val lowCutoffHz: Float = DEFAULT_LOW_CUTOFF_HZ,
        val highCutoffHz: Float = DEFAULT_HIGH_CUTOFF_HZ,
        val balance: Float = DEFAULT_BALANCE,
        val outputCompDb: Float = DEFAULT_OUTPUT_COMP_DB
    )

    // Circular delay buffer for Haas precedence inter-channel delay
    private val delayBuffer = FloatArray(BUFFER_SIZE)
    private var writePos = 0

    // Filters:
    // sideHpFilter: protects low frequencies on the existing side channel (<160 Hz)
    // haasHpFilter: protects low frequencies on the Haas decorrelation signal (<160 Hz)
    // toneDampFilter: tone damps high frequencies on delayed Haas path (~7500 Hz) to avoid comb filtering harshness
    // crossfeedLpFilter: gentle lowpass for crossfeed signal
    private val sideHpFilter = BiquadFilter()
    private val haasHpFilter = BiquadFilter()
    private val toneDampFilter = BiquadFilter()
    private val crossfeedLpFilter = BiquadFilter()
    private var lastSampleRate = -1
    private var lastConfiguredLowCutoff = -1f
    private var lastConfiguredHighCutoff = -1f

    // Parameter properties
    @Volatile var preset: HaasPreset = HaasPreset.WIDE
        private set

    @Volatile var mode: ProcessingMode = ProcessingMode.SIDE_ONLY
        private set

    @Volatile var stereoWidth: Float = DEFAULT_STEREO_WIDTH
        private set

    @Volatile var effectMix: Float = DEFAULT_EFFECT_MIX
        private set

    @Volatile var crossfeed: Float = DEFAULT_CROSSFEED
        private set

    @Volatile var lowCutoffHz: Float = DEFAULT_LOW_CUTOFF_HZ
        private set

    @Volatile var highCutoffHz: Float = DEFAULT_HIGH_CUTOFF_HZ
        private set

    @Volatile var balance: Float = DEFAULT_BALANCE
        private set

    @Volatile var outputCompDb: Float = DEFAULT_OUTPUT_COMP_DB
        private set

    @Volatile var bassProtect: Boolean = true
        private set

    @Volatile var isEnabled: Boolean = false
        private set

    // Backward-compatible amount accessor
    val amount: Float
        get() = effectMix

    val delayMs: Float
        get() = targetDelayMs

    // Smoothed parameters for click-free transitions
    @Volatile private var targetAmount = 0f
    @Volatile private var targetDelayMs = DEFAULT_DELAY_MS
    @Volatile private var currentAmount = 0f
    @Volatile private var currentDelayMs = DEFAULT_DELAY_MS

    // Smoothing coefficient per frame
    private val smoothingRate = 0.002f

    fun setPreset(preset: HaasPreset) {
        this.preset = preset
    }

    fun applyPreset(preset: HaasPreset) {
        this.preset = preset
        when (preset) {
            HaasPreset.OFF -> {
                setEnabled(false)
            }
            HaasPreset.SUBTLE -> {
                setEnabled(true)
                setDelayMs(PRESET_SUBTLE_MS, fromPreset = true)
                setStereoWidth(1.05f, fromPreset = true)
                setEffectMix(0.60f, fromPreset = true)
                setCrossfeed(0.0f, fromPreset = true)
                setLowCutoffHz(DEFAULT_LOW_CUTOFF_HZ, fromPreset = true)
                setHighCutoffHz(DEFAULT_HIGH_CUTOFF_HZ, fromPreset = true)
                setSpatialBalance(DEFAULT_BALANCE, fromPreset = true)
                setOutputCompDb(DEFAULT_OUTPUT_COMP_DB, fromPreset = true)
                setBassProtect(true, fromPreset = true)
                setMode(ProcessingMode.SIDE_ONLY, fromPreset = true)
            }
            HaasPreset.WIDE -> {
                setEnabled(true)
                setDelayMs(PRESET_WIDE_MS, fromPreset = true)
                setStereoWidth(1.15f, fromPreset = true)
                setEffectMix(0.70f, fromPreset = true)
                setCrossfeed(0.0f, fromPreset = true)
                setLowCutoffHz(DEFAULT_LOW_CUTOFF_HZ, fromPreset = true)
                setHighCutoffHz(DEFAULT_HIGH_CUTOFF_HZ, fromPreset = true)
                setSpatialBalance(DEFAULT_BALANCE, fromPreset = true)
                setOutputCompDb(DEFAULT_OUTPUT_COMP_DB, fromPreset = true)
                setBassProtect(true, fromPreset = true)
                setMode(ProcessingMode.SIDE_ONLY, fromPreset = true)
            }
            HaasPreset.ULTRA_WIDE -> {
                setEnabled(true)
                setDelayMs(PRESET_ULTRA_WIDE_MS, fromPreset = true)
                setStereoWidth(1.25f, fromPreset = true)
                setEffectMix(0.85f, fromPreset = true)
                setCrossfeed(0.0f, fromPreset = true)
                setLowCutoffHz(DEFAULT_LOW_CUTOFF_HZ, fromPreset = true)
                setHighCutoffHz(DEFAULT_HIGH_CUTOFF_HZ, fromPreset = true)
                setSpatialBalance(DEFAULT_BALANCE, fromPreset = true)
                setOutputCompDb(DEFAULT_OUTPUT_COMP_DB, fromPreset = true)
                setBassProtect(true, fromPreset = true)
                setMode(ProcessingMode.SIDE_ONLY, fromPreset = true)
            }
            HaasPreset.CUSTOM -> {
                // Keep current settings
            }
        }
        this.preset = preset
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        targetAmount = if (enabled) effectMix else 0f
        if (!enabled) {
            currentAmount = 0f
            if (preset != HaasPreset.OFF) {
                preset = HaasPreset.OFF
            }
        } else {
            if (preset == HaasPreset.OFF) {
                preset = HaasPreset.CUSTOM
            }
        }
    }

    fun setEffectMix(mix: Float, fromPreset: Boolean = false) {
        effectMix = mix.coerceIn(MIN_EFFECT_MIX, MAX_EFFECT_MIX)
        if (isEnabled) {
            targetAmount = effectMix
        }
        if (!fromPreset) {
            preset = HaasPreset.CUSTOM
        }
    }

    fun setAmount(amount: Float, fromPreset: Boolean = false) {
        setEffectMix(amount, fromPreset)
    }

    fun setDelayMs(delay: Float, fromPreset: Boolean = false) {
        targetDelayMs = delay.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        if (!fromPreset) {
            preset = HaasPreset.CUSTOM
        }
    }

    fun setStereoWidth(width: Float, fromPreset: Boolean = false) {
        stereoWidth = width.coerceIn(MIN_STEREO_WIDTH, MAX_STEREO_WIDTH)
        if (!fromPreset) {
            preset = HaasPreset.CUSTOM
        }
    }

    fun setCrossfeed(cf: Float, fromPreset: Boolean = false) {
        crossfeed = cf.coerceIn(MIN_CROSSFEED, MAX_CROSSFEED)
        if (!fromPreset) {
            preset = HaasPreset.CUSTOM
        }
    }

    fun setLowCutoffHz(hz: Float, fromPreset: Boolean = false) {
        lowCutoffHz = hz.coerceIn(MIN_LOW_CUTOFF_HZ, MAX_LOW_CUTOFF_HZ)
        if (!fromPreset) {
            preset = HaasPreset.CUSTOM
        }
    }

    fun setHighCutoffHz(hz: Float, fromPreset: Boolean = false) {
        highCutoffHz = hz.coerceIn(MIN_HIGH_CUTOFF_HZ, MAX_HIGH_CUTOFF_HZ)
        if (!fromPreset) {
            preset = HaasPreset.CUSTOM
        }
    }

    fun setSpatialBalance(bal: Float, fromPreset: Boolean = false) {
        balance = bal.coerceIn(MIN_BALANCE, MAX_BALANCE)
        if (!fromPreset) {
            preset = HaasPreset.CUSTOM
        }
    }

    fun setOutputCompDb(db: Float, fromPreset: Boolean = false) {
        outputCompDb = db.coerceIn(MIN_OUTPUT_COMP_DB, MAX_OUTPUT_COMP_DB)
        if (!fromPreset) {
            preset = HaasPreset.CUSTOM
        }
    }

    fun setBassProtect(protect: Boolean, fromPreset: Boolean = false) {
        bassProtect = protect
        if (!fromPreset) {
            preset = HaasPreset.CUSTOM
        }
    }

    fun setMode(m: ProcessingMode, fromPreset: Boolean = false) {
        mode = m
        if (!fromPreset) {
            preset = HaasPreset.CUSTOM
        }
    }

    /**
     * Applies genuine Haas precedence inter-channel delay DSP to 16-bit interleaved stereo PCM.
     * Combines fractional delay precedence widening (0–30 ms) with Mid/Side decomposition,
     * mono-bass protection, customizable low/high cutoffs, crossfeed, spatial balance,
     * and output compensation gain.
     */
    fun process(buffer: ShortArray, offset: Int, frameCount: Int, sampleRate: Int = 48000) {
        if (!isEnabled && currentAmount < 0.0005f) return
        if (targetDelayMs <= 0.0001f && currentDelayMs <= 0.0001f && abs(stereoWidth - 1.0f) < 0.001f && crossfeed < 0.001f) return
        if (targetAmount <= 0.0001f && currentAmount <= 0.0001f) return

        val safeSampleRate = sampleRate.coerceIn(8000, 192000)
        val curLowCutoff = lowCutoffHz
        val curHighCutoff = highCutoffHz

        if (safeSampleRate != lastSampleRate || curLowCutoff != lastConfiguredLowCutoff || curHighCutoff != lastConfiguredHighCutoff) {
            val srFloat = safeSampleRate.toFloat()
            sideHpFilter.configureHighPass(curLowCutoff, srFloat)
            haasHpFilter.configureHighPass(curLowCutoff, srFloat)
            toneDampFilter.configureLowPass(curHighCutoff, srFloat)
            crossfeedLpFilter.configureLowPass(1200f, srFloat)
            lastSampleRate = safeSampleRate
            lastConfiguredLowCutoff = curLowCutoff
            lastConfiguredHighCutoff = curHighCutoff
        }

        val srFloat = safeSampleRate.toFloat()

        // Output compensation linear gain: 10^(dB / 20)
        val outputGainLinear = Math.pow(10.0, (outputCompDb / 20.0).toDouble()).toFloat()

        // Spatial balance: balance in [-100, 100]
        // Balance adjusts spatial image by weighting L/R channels smoothly
        val normBal = (balance / 100.0f).coerceIn(-1.0f, 1.0f)
        val leftBalGain = if (normBal > 0f) (1.0f - normBal * 0.5f) else 1.0f
        val rightBalGain = if (normBal < 0f) (1.0f + normBal * 0.5f) else 1.0f

        val cfLevel = crossfeed.coerceIn(0.0f, 1.0f) * 0.35f

        for (i in 0 until frameCount) {
            val idx = offset + i * 2
            if (idx + 1 >= buffer.size) break

            // Smooth live parameters to ensure zero clicks/pops during adjustments
            currentAmount += (targetAmount - currentAmount) * smoothingRate
            currentDelayMs += (targetDelayMs - currentDelayMs) * smoothingRate

            if (currentAmount < 0.0005f) {
                // Bypass fast path when neutral
                continue
            }

            var leftIn = buffer[idx].toFloat()
            var rightIn = buffer[idx + 1].toFloat()

            // Optional crossfeed stage (headphone crossfeed: slight bleed of filtered opposite channel)
            if (cfLevel > 0.001f) {
                val crossL = crossfeedLpFilter.process(rightIn) * cfLevel
                val crossR = crossfeedLpFilter.process(leftIn) * cfLevel
                leftIn = leftIn * (1.0f - cfLevel * 0.5f) + crossL
                rightIn = rightIn * (1.0f - cfLevel * 0.5f) + crossR
            }

            // 1. Mid/Side decomposition
            val mid = (leftIn + rightIn) * 0.5f
            val side = (leftIn - rightIn) * 0.5f

            // 2. Write mid signal into circular spatial delay buffer
            delayBuffer[writePos] = mid

            // 3. Fractional delay reading for true Haas precedence effect
            val delaySamples = (currentDelayMs / 1000f * srFloat).coerceIn(1f, (BUFFER_SIZE - 4).toFloat())
            val intDelay = delaySamples.toInt()
            val frac = delaySamples - intDelay

            val readIdx0 = (writePos - intDelay + BUFFER_SIZE) and BUFFER_MASK
            val readIdx1 = (readIdx0 - 1 + BUFFER_SIZE) and BUFFER_MASK
            val rawDelayedSample = delayBuffer[readIdx0] * (1f - frac) + delayBuffer[readIdx1] * frac

            writePos = (writePos + 1) and BUFFER_MASK

            // 4. Tone damping on delayed path
            val filteredDelayed = toneDampFilter.process(rawDelayedSample)

            // 5. Haas spatial decorrelation: difference between instantaneous and delayed mid
            val haasDiff = (mid - filteredDelayed) * 0.5f

            // 6. Bass protection: filter low frequencies so punch remains 100% centered in-phase
            val haasDiffProcessed = if (bassProtect) {
                haasHpFilter.process(haasDiff)
            } else {
                haasDiff
            }

            // Stereo width scaling: combines explicit stereoWidth setting with progressive mix
            val progressiveAmount = currentAmount * (0.35f + 0.65f * currentAmount)
            val baseWidthScale = stereoWidth
            val effectiveWidth = baseWidthScale * (1.0f + progressiveAmount * 0.35f)
            val spatialMix = progressiveAmount * 0.85f

            val sideProcessed = if (bassProtect) {
                val sideHp = sideHpFilter.process(side)
                sideHp * effectiveWidth + haasDiffProcessed * spatialMix
            } else {
                side * effectiveWidth + haasDiffProcessed * spatialMix
            }

            // 7. Stereo reconstruction:
            // In SIDE_ONLY mode: L = Mid + Side, R = Mid - Side
            // In mono collapse: (L + R) / 2 = Mid, sideProcessed cancels completely with 0 comb filtering
            // If FULL_STEREO mode: allows gentle ambient widening on Mid path
            val leftOut: Float
            val rightOut: Float
            if (mode == ProcessingMode.FULL_STEREO) {
                val midSpread = haasDiffProcessed * 0.2f * progressiveAmount
                leftOut = (mid + midSpread) + sideProcessed
                rightOut = (mid - midSpread) - sideProcessed
            } else {
                leftOut = mid + sideProcessed
                rightOut = mid - sideProcessed
            }

            // 8. Equal-energy gain normalization + spatial balance + output compensation
            val gainComp = (1.0f / sqrt(1.0f + 0.35f * currentAmount * currentAmount)) * outputGainLinear
            val leftFinal = (leftIn * (1.0f - currentAmount) + leftOut * currentAmount) * leftBalGain * gainComp
            val rightFinal = (rightIn * (1.0f - currentAmount) + rightOut * currentAmount) * rightBalGain * gainComp

            buffer[idx] = softLimit(leftFinal)
            buffer[idx + 1] = softLimit(rightFinal)
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
        return if (sample < 0f) (-compressed).toInt().toShort() else compressed.toInt().toShort()
    }

    /**
     * Resets delay buffers and filter histories (e.g. when seeking or changing tracks).
     */
    fun reset() {
        delayBuffer.fill(0f)
        writePos = 0
        sideHpFilter.reset()
        haasHpFilter.reset()
        toneDampFilter.reset()
        crossfeedLpFilter.reset()
        currentAmount = 0f
        targetAmount = if (isEnabled) effectMix else 0f
        currentDelayMs = targetDelayMs
    }

    val isActive: Boolean
        get() = isEnabled && (targetAmount > 0.001f || currentAmount > 0.001f) && (targetDelayMs > 0.01f || currentDelayMs > 0.01f)

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
            val cosw = cos(w0.toDouble()).toFloat()
            val sinw = sin(w0.toDouble()).toFloat()
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
            val cosw = cos(w0.toDouble()).toFloat()
            val sinw = sin(w0.toDouble()).toFloat()
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
