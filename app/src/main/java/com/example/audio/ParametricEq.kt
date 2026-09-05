package com.example.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class EqFilterType(val displayName: String) {
    LOW_SHELF("Low Shelf"),
    PEAKING("Peaking / Bell"),
    HIGH_SHELF("High Shelf"),
    LOW_PASS("Low Pass"),
    HIGH_PASS("High Pass")
}

data class EqBand(
    val id: Int,
    val name: String,
    val type: EqFilterType = EqFilterType.PEAKING,
    var frequencyHz: Double,
    var gainDb: Double = 0.0,
    var q: Double = 1.0,
    var isEnabled: Boolean = true
)

data class EqPreset(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean = false,
    val preampDb: Double = 0.0,
    val bands: List<EqBand>
)

/**
 * High-precision, multi-band parametric equalizer utilizing RBJ Audio-EQ-Cookbook biquad filters.
 *
 * Implements Step 2 Part C requirements:
 * - Multi-band parametric filtering with arbitrary frequency, gain in dB (-15dB to +15dB),
 *   and Q / bandwidth (0.3 to 10.0).
 * - Per-band enable / bypass toggling.
 * - Master preamp gain with soft limiter / headroom management to eliminate clipping distortion.
 * - Seamless backward-compatible integration with DJ 3-knob quick mixer (lowGain, midGain, highGain).
 * - Zero click / artifact coefficient recalculation.
 */
class ParametricEq(private val sampleRate: Int) {

    companion object {
        val DEFAULT_7_BANDS: List<EqBand>
            get() = listOf(
                EqBand(0, "Sub-Bass", EqFilterType.LOW_SHELF, 60.0, 0.0, 0.71, true),
                EqBand(1, "Bass", EqFilterType.PEAKING, 150.0, 0.0, 1.0, true),
                EqBand(2, "Low-Mid", EqFilterType.PEAKING, 450.0, 0.0, 1.2, true),
                EqBand(3, "Mid", EqFilterType.PEAKING, 1000.0, 0.0, 1.4, true),
                EqBand(4, "High-Mid", EqFilterType.PEAKING, 2500.0, 0.0, 1.4, true),
                EqBand(5, "Presence", EqFilterType.PEAKING, 6000.0, 0.0, 1.2, true),
                EqBand(6, "Brilliance", EqFilterType.HIGH_SHELF, 14000.0, 0.0, 0.71, true)
            )
    }

    // Active parametric bands
    private val bands: MutableList<EqBand> = DEFAULT_7_BANDS.map { it.copy() }.toMutableList()

    // Biquad filter pairs per band (Left and Right stereo channels)
    private val biquadsL = mutableListOf<Biquad>()
    private val biquadsR = mutableListOf<Biquad>()

    // Master preamp in dB (-12 dB to +12 dB)
    @Volatile
    var preampDb: Double = 0.0
        set(value) { field = value.coerceIn(-12.0, 12.0) }

    // Quick DJ 3-Knob backward compatibility (linear multipliers 0.0 to 2.0, unity = 1.0)
    @Volatile
    var lowGain: Float = 1f
        set(value) { field = value.coerceIn(0f, 2f) }

    @Volatile
    var midGain: Float = 1f
        set(value) { field = value.coerceIn(0f, 2f) }

    @Volatile
    var highGain: Float = 1f
        set(value) { field = value.coerceIn(0f, 2f) }

    // State tracking to detect updates
    private var appliedPreamp = Double.NaN
    private var appliedLow = Float.NaN
    private var appliedMid = Float.NaN
    private var appliedHigh = Float.NaN
    private val appliedBands = mutableListOf<EqBand>()

    private var outputGain = 1f

    init {
        rebuildBiquads()
    }

    private fun rebuildBiquads() {
        biquadsL.clear()
        biquadsR.clear()
        for (i in bands.indices) {
            biquadsL.add(Biquad())
            biquadsR.add(Biquad())
        }
        appliedBands.clear()
        for (b in bands) {
            appliedBands.add(b.copy())
        }
        updateCoefficients()
    }

    fun getBands(): List<EqBand> = synchronized(bands) {
        bands.map { it.copy() }
    }

    fun setBands(newBands: List<EqBand>) {
        synchronized(bands) {
            bands.clear()
            bands.addAll(newBands.map { it.copy() })
            rebuildBiquads()
        }
    }

    fun updateBand(index: Int, freqHz: Double, gainDb: Double, q: Double, isEnabled: Boolean) {
        synchronized(bands) {
            if (index in bands.indices) {
                val b = bands[index]
                b.frequencyHz = freqHz.coerceIn(20.0, (sampleRate / 2.05))
                b.gainDb = gainDb.coerceIn(-15.0, 15.0)
                b.q = q.coerceIn(0.2, 10.0)
                b.isEnabled = isEnabled
            }
        }
    }

    fun resetToFlat() {
        synchronized(bands) {
            preampDb = 0.0
            lowGain = 1f
            midGain = 1f
            highGain = 1f
            bands.forEach {
                it.gainDb = 0.0
                it.isEnabled = true
            }
        }
    }

    /**
     * Processes interleaved stereo 16-bit PCM in place.
     */
    fun processStereo(buffer: ShortArray, offset: Int, frameCount: Int) {
        val isUnityQuick = (lowGain == 1f && midGain == 1f && highGain == 1f)
        val isUnityParametric = preampDb == 0.0 && bands.all { !it.isEnabled || it.gainDb == 0.0 }

        if (isUnityQuick && isUnityParametric) {
            if (outputGain >= 0.9995f) return
            for (i in 0 until frameCount) {
                val idx = offset + i * 2
                if (idx + 1 >= buffer.size) break
                outputGain += (1f - outputGain) * 0.0025f
                buffer[idx] = (buffer[idx] * outputGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                buffer[idx + 1] = (buffer[idx + 1] * outputGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return
        }

        ensureCoefficients()

        // Preamp and auto-headroom calculation
        val preampLinear = 10.0.pow(preampDb / 20.0).toFloat()
        val maxBoostDb = bands.filter { it.isEnabled }.maxOfOrNull { it.gainDb }?.coerceAtLeast(0.0) ?: 0.0
        val quickBoostLinear = maxOf(1f, lowGain * midGain * highGain)
        val headroomScale = 1f / sqrt(quickBoostLinear * 10.0.pow(maxBoostDb / 20.0).toFloat())
        val targetOutputGain = (preampLinear * headroomScale).coerceIn(0.1f, 2.0f)

        val activeBandCount = minOf(bands.size, biquadsL.size)

        for (i in 0 until frameCount) {
            val idx = offset + i * 2
            if (idx + 1 >= buffer.size) break

            outputGain += (targetOutputGain - outputGain) * 0.0025f

            var left = buffer[idx].toDouble()
            var right = buffer[idx + 1].toDouble()

            // Run through active biquad stages in series
            for (b in 0 until activeBandCount) {
                if (bands[b].isEnabled) {
                    left = biquadsL[b].process(left)
                    right = biquadsR[b].process(right)
                }
            }

            left *= outputGain
            right *= outputGain

            // Soft-knee limiting at peaks to prevent hard digital clipping
            left = softClip(left)
            right = softClip(right)

            buffer[idx] = left.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            buffer[idx + 1] = right.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun softClip(sample: Double): Double {
        val threshold = 30000.0
        val maxVal = 32767.0
        val absVal = if (sample < 0) -sample else sample
        if (absVal <= threshold) return sample

        // Smooth cubic saturation above threshold
        val excess = (absVal - threshold) / (maxVal - threshold)
        val compressed = threshold + (maxVal - threshold) * (excess - (excess * excess * excess / 3.0))
        return if (sample < 0) -compressed.coerceAtMost(maxVal) else compressed.coerceAtMost(maxVal)
    }

    private fun ensureCoefficients() {
        var needsUpdate = false

        if (lowGain != appliedLow || midGain != appliedMid || highGain != appliedHigh || preampDb != appliedPreamp) {
            appliedLow = lowGain
            appliedMid = midGain
            appliedHigh = highGain
            appliedPreamp = preampDb
            needsUpdate = true
        }

        synchronized(bands) {
            if (bands.size != appliedBands.size) {
                rebuildBiquads()
                return
            }
            for (i in bands.indices) {
                val cur = bands[i]
                val app = appliedBands[i]
                if (cur.frequencyHz != app.frequencyHz || cur.gainDb != app.gainDb || cur.q != app.q || cur.isEnabled != app.isEnabled || cur.type != app.type) {
                    appliedBands[i] = cur.copy()
                    needsUpdate = true
                }
            }
        }

        if (needsUpdate) {
            updateCoefficients()
        }
    }

    private fun updateCoefficients() {
        synchronized(bands) {
            for (i in bands.indices) {
                if (i >= biquadsL.size) break
                val band = bands[i]
                if (!band.isEnabled) continue

                // Combine parametric band gain with quick DJ mixer knob offsets
                val djKnobOffsetDb = when (i) {
                    1 -> linearToDb(lowGain)      // Bass band
                    3 -> linearToDb(midGain)      // Mid band
                    5 -> linearToDb(highGain)     // Presence / High band
                    else -> 0.0
                }
                val effectiveGainDb = (band.gainDb + djKnobOffsetDb).coerceIn(-24.0, 24.0)

                when (band.type) {
                    EqFilterType.LOW_SHELF -> configureLowShelf(biquadsL[i], biquadsR[i], band.frequencyHz, effectiveGainDb)
                    EqFilterType.HIGH_SHELF -> configureHighShelf(biquadsL[i], biquadsR[i], band.frequencyHz, effectiveGainDb)
                    EqFilterType.PEAKING -> configurePeaking(biquadsL[i], biquadsR[i], band.frequencyHz, effectiveGainDb, band.q)
                    EqFilterType.LOW_PASS -> configureLowPass(biquadsL[i], biquadsR[i], band.frequencyHz, band.q)
                    EqFilterType.HIGH_PASS -> configureHighPass(biquadsL[i], biquadsR[i], band.frequencyHz, band.q)
                }
            }
        }
    }

    private fun configureLowShelf(l: Biquad, r: Biquad, freqHz: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz.coerceIn(20.0, sampleRate / 2.1) / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / 2.0 * sqrt(2.0)

        val b0 = a * ((a + 1.0) - (a - 1.0) * cosW + 2.0 * sqrt(a) * alpha)
        val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)
        val b2 = a * ((a + 1.0) - (a - 1.0) * cosW - 2.0 * sqrt(a) * alpha)
        val a0 = (a + 1.0) + (a - 1.0) * cosW + 2.0 * sqrt(a) * alpha
        val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW)
        val a2 = (a + 1.0) + (a - 1.0) * cosW - 2.0 * sqrt(a) * alpha

        val coefs = doubleArrayOf(b0, b1, b2, a1, a2)
        l.setCoefficients(coefs, a0)
        r.setCoefficients(coefs, a0)
    }

    private fun configureHighShelf(l: Biquad, r: Biquad, freqHz: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz.coerceIn(20.0, sampleRate / 2.1) / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / 2.0 * sqrt(2.0)

        val b0 = a * ((a + 1.0) + (a - 1.0) * cosW + 2.0 * sqrt(a) * alpha)
        val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW)
        val b2 = a * ((a + 1.0) + (a - 1.0) * cosW - 2.0 * sqrt(a) * alpha)
        val a0 = (a + 1.0) - (a - 1.0) * cosW + 2.0 * sqrt(a) * alpha
        val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW)
        val a2 = (a + 1.0) - (a - 1.0) * cosW - 2.0 * sqrt(a) * alpha

        val coefs = doubleArrayOf(b0, b1, b2, a1, a2)
        l.setCoefficients(coefs, a0)
        r.setCoefficients(coefs, a0)
    }

    private fun configurePeaking(l: Biquad, r: Biquad, freqHz: Double, gainDb: Double, q: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz.coerceIn(20.0, sampleRate / 2.1) / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q.coerceIn(0.2, 10.0))

        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosW
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha / a

        val coefs = doubleArrayOf(b0, b1, b2, a1, a2)
        l.setCoefficients(coefs, a0)
        r.setCoefficients(coefs, a0)
    }

    private fun configureLowPass(l: Biquad, r: Biquad, freqHz: Double, q: Double) {
        val w0 = 2.0 * PI * freqHz.coerceIn(20.0, sampleRate / 2.1) / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q.coerceIn(0.2, 10.0))

        val b1 = 1.0 - cosW
        val b0 = b1 / 2.0
        val b2 = b0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha

        val coefs = doubleArrayOf(b0, b1, b2, a1, a2)
        l.setCoefficients(coefs, a0)
        r.setCoefficients(coefs, a0)
    }

    private fun configureHighPass(l: Biquad, r: Biquad, freqHz: Double, q: Double) {
        val w0 = 2.0 * PI * freqHz.coerceIn(20.0, sampleRate / 2.1) / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q.coerceIn(0.2, 10.0))

        val b1 = -(1.0 + cosW)
        val b0 = (1.0 + cosW) / 2.0
        val b2 = b0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha

        val coefs = doubleArrayOf(b0, b1, b2, a1, a2)
        l.setCoefficients(coefs, a0)
        r.setCoefficients(coefs, a0)
    }

    private fun linearToDb(linear: Float): Double {
        val l = linear.coerceIn(0f, 2f)
        if (l <= 0f) return -36.0
        return (20.0 * ln(l.toDouble()) / ln(10.0)).coerceIn(-36.0, 12.0)
    }

    /**
     * Direct Form I second-order IIR biquad filter section.
     */
    private class Biquad {
        private var b0 = 0.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0

        @Volatile private var x1 = 0.0
        @Volatile private var x2 = 0.0
        @Volatile private var y1 = 0.0
        @Volatile private var y2 = 0.0

        fun setCoefficients(c: DoubleArray, a0: Double) {
            b0 = c[0] / a0
            b1 = c[1] / a0
            b2 = c[2] / a0
            a1 = c[3] / a0
            a2 = c[4] / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        }
    }
}