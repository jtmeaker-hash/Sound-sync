package com.example.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

enum class EqFilterType(val displayName: String, val shortCode: String) {
    PEAKING("Peaking / Bell", "PK"),
    LOW_SHELF("Low Shelf", "LS"),
    HIGH_SHELF("High Shelf", "HS"),
    HIGH_PASS("High Pass", "HP"),
    LOW_PASS("Low Pass", "LP"),
    BAND_PASS("Band Pass", "BP"),
    NOTCH("Notch / Cut", "NT")
}

data class EqBand(
    val id: Int,
    val name: String,
    var type: EqFilterType = EqFilterType.PEAKING,
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
 * Professional, high-precision multi-band parametric equalizer utilizing RBJ Audio EQ Cookbook
 * Transposed Direct Form II (TDF-II) biquad filter sections with smooth coefficient ramping,
 * transparent unity bypass, user-configurable auto-headroom, and safe true-peak soft limiting.
 */
class ParametricEq(val sampleRate: Int) {

    companion object {
        const val MIN_FREQ_HZ = 20.0
        const val MAX_FREQ_HZ = 20000.0
        const val MIN_GAIN_DB = -24.0
        const val MAX_GAIN_DB = 24.0
        const val MIN_Q = 0.1
        const val MAX_Q = 20.0

        /**
         * Professional 10-band parametric layout covering the full audible spectrum.
         */
        val DEFAULT_10_BANDS: List<EqBand>
            get() = listOf(
                EqBand(0, "Sub-Bass", EqFilterType.LOW_SHELF, 32.0, 0.0, 0.71, true),
                EqBand(1, "Low Bass", EqFilterType.PEAKING, 64.0, 0.0, 1.0, true),
                EqBand(2, "Upper Bass", EqFilterType.PEAKING, 125.0, 0.0, 1.2, true),
                EqBand(3, "Low-Mid", EqFilterType.PEAKING, 250.0, 0.0, 1.4, true),
                EqBand(4, "Mid", EqFilterType.PEAKING, 500.0, 0.0, 1.4, true),
                EqBand(5, "High-Mid", EqFilterType.PEAKING, 1000.0, 0.0, 1.4, true),
                EqBand(6, "Presence", EqFilterType.PEAKING, 2000.0, 0.0, 1.4, true),
                EqBand(7, "High Presence", EqFilterType.PEAKING, 4000.0, 0.0, 1.2, true),
                EqBand(8, "Brilliance", EqFilterType.PEAKING, 8000.0, 0.0, 1.0, true),
                EqBand(9, "Air", EqFilterType.HIGH_SHELF, 16000.0, 0.0, 0.71, true)
            )

        /**
         * Professional 8-band parametric layout covering the entire audible spectrum.
         */
        val DEFAULT_8_BANDS: List<EqBand>
            get() = listOf(
                EqBand(0, "Sub-Bass", EqFilterType.LOW_SHELF, 32.0, 0.0, 0.71, true),
                EqBand(1, "Bass", EqFilterType.PEAKING, 64.0, 0.0, 1.0, true),
                EqBand(2, "Low-Mid", EqFilterType.PEAKING, 160.0, 0.0, 1.2, true),
                EqBand(3, "Mid", EqFilterType.PEAKING, 500.0, 0.0, 1.4, true),
                EqBand(4, "High-Mid", EqFilterType.PEAKING, 1200.0, 0.0, 1.4, true),
                EqBand(5, "Presence", EqFilterType.PEAKING, 3000.0, 0.0, 1.2, true),
                EqBand(6, "Brilliance", EqFilterType.PEAKING, 8000.0, 0.0, 1.0, true),
                EqBand(7, "Air", EqFilterType.HIGH_SHELF, 16000.0, 0.0, 0.71, true)
            )

        /**
         * Backward compatibility with 7-band tests and legacy configurations.
         */
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

        /**
         * Computes the mathematical magnitude response in dB across a set of frequencies.
         * Used by the UI Canvas to render the true filter response curve.
         */
        fun computeFrequencyResponseDb(
            sampleRate: Int,
            bands: List<EqBand>,
            preampDb: Double,
            autoHeadroom: Boolean,
            frequencies: FloatArray,
            outDb: FloatArray,
            soloBandIndex: Int? = null
        ) {
            val maxBoost = if (autoHeadroom) {
                bands.filterIndexed { idx, it ->
                    it.isEnabled && (soloBandIndex == null || soloBandIndex == idx) &&
                        (it.type == EqFilterType.PEAKING || it.type == EqFilterType.LOW_SHELF || it.type == EqFilterType.HIGH_SHELF)
                }.maxOfOrNull { it.gainDb }?.coerceAtLeast(0.0) ?: 0.0
            } else 0.0

            val baseDb = preampDb - maxBoost

            val coefsList = ArrayList<DoubleArray>(bands.size)
            for (idx in bands.indices) {
                val band = bands[idx]
                if (!band.isEnabled || (soloBandIndex != null && soloBandIndex != idx)) continue
                val coefs = DoubleArray(5)
                calculateBiquadCoefficients(sampleRate, band.type, band.frequencyHz, band.gainDb, band.q, coefs)
                coefsList.add(coefs)
            }

            for (i in frequencies.indices) {
                val f = frequencies[i].toDouble().coerceIn(10.0, (sampleRate * 0.495))
                val w = 2.0 * PI * f / sampleRate
                val cos1 = cos(w)
                val cos2 = 2.0 * cos1 * cos1 - 1.0
                val sin1 = sin(w)
                val sin2 = 2.0 * sin1 * cos1

                var totalDb = baseDb
                for (c in coefsList) {
                    val numRe = c[0] + c[1] * cos1 + c[2] * cos2
                    val numIm = -c[1] * sin1 - c[2] * sin2
                    val denRe = 1.0 + c[3] * cos1 + c[4] * cos2
                    val denIm = -c[3] * sin1 - c[4] * sin2

                    val numMagSq = numRe * numRe + numIm * numIm
                    val denMagSq = denRe * denRe + denIm * denIm

                    if (denMagSq > 1e-18 && numMagSq > 1e-18) {
                        totalDb += 10.0 * log10(numMagSq / denMagSq)
                    } else if (numMagSq <= 1e-18) {
                        totalDb -= 80.0
                    }
                }
                outDb[i] = totalDb.coerceIn(-48.0, 48.0).toFloat()
            }
        }

        /**
         * RBJ Audio EQ Cookbook normalized coefficient calculation.
         * Fills outCoefs with [b0, b1, b2, a1, a2] normalized by a0.
         */
        fun calculateBiquadCoefficients(
            sampleRate: Int,
            type: EqFilterType,
            freqHz: Double,
            gainDb: Double,
            q: Double,
            outCoefs: DoubleArray
        ) {
            val nyquist = sampleRate * 0.495
            val f = freqHz.coerceIn(MIN_FREQ_HZ, nyquist.coerceAtMost(MAX_FREQ_HZ))
            val g = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            val qVal = q.coerceIn(MIN_Q, MAX_Q)

            // Unity short-circuit for 0dB peaking and shelving
            if (abs(g) < 0.001 && (type == EqFilterType.PEAKING || type == EqFilterType.LOW_SHELF || type == EqFilterType.HIGH_SHELF)) {
                outCoefs[0] = 1.0; outCoefs[1] = 0.0; outCoefs[2] = 0.0; outCoefs[3] = 0.0; outCoefs[4] = 0.0
                return
            }

            val w0 = 2.0 * PI * f / sampleRate
            val cosW = cos(w0)
            val sinW = sin(w0)
            val a = 10.0.pow(g / 40.0)

            var b0 = 1.0
            var b1 = 0.0
            var b2 = 0.0
            var a0 = 1.0
            var a1 = 0.0
            var a2 = 0.0

            when (type) {
                EqFilterType.PEAKING -> {
                    val alpha = sinW / (2.0 * qVal)
                    b0 = 1.0 + alpha * a
                    b1 = -2.0 * cosW
                    b2 = 1.0 - alpha * a
                    a0 = 1.0 + alpha / a
                    a1 = -2.0 * cosW
                    a2 = 1.0 - alpha / a
                }
                EqFilterType.LOW_SHELF -> {
                    val inside = maxOf(0.0, (a + 1.0 / a) * (1.0 / qVal - 1.0) + 2.0)
                    val alpha = (sinW / 2.0) * sqrt(inside)
                    val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                    b0 = a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha)
                    b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)
                    b2 = a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha)
                    a0 = (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha
                    a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW)
                    a2 = (a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha
                }
                EqFilterType.HIGH_SHELF -> {
                    val inside = maxOf(0.0, (a + 1.0 / a) * (1.0 / qVal - 1.0) + 2.0)
                    val alpha = (sinW / 2.0) * sqrt(inside)
                    val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                    b0 = a * ((a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha)
                    b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW)
                    b2 = a * ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha)
                    a0 = (a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha
                    a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW)
                    a2 = (a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha
                }
                EqFilterType.LOW_PASS -> {
                    val alpha = sinW / (2.0 * qVal)
                    b1 = 1.0 - cosW
                    b0 = b1 * 0.5
                    b2 = b0
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosW
                    a2 = 1.0 - alpha
                }
                EqFilterType.HIGH_PASS -> {
                    val alpha = sinW / (2.0 * qVal)
                    b0 = (1.0 + cosW) * 0.5
                    b1 = -(1.0 + cosW)
                    b2 = (1.0 + cosW) * 0.5
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosW
                    a2 = 1.0 - alpha
                }
                EqFilterType.BAND_PASS -> {
                    val alpha = sinW / (2.0 * qVal)
                    b0 = alpha
                    b1 = 0.0
                    b2 = -alpha
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosW
                    a2 = 1.0 - alpha
                }
                EqFilterType.NOTCH -> {
                    val alpha = sinW / (2.0 * qVal)
                    b0 = 1.0
                    b1 = -2.0 * cosW
                    b2 = 1.0
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosW
                    a2 = 1.0 - alpha
                }
            }

            if (abs(a0) < 1e-12 || a0.isNaN()) {
                outCoefs[0] = 1.0; outCoefs[1] = 0.0; outCoefs[2] = 0.0; outCoefs[3] = 0.0; outCoefs[4] = 0.0
                return
            }

            val invA0 = 1.0 / a0
            val normB0 = b0 * invA0
            val normB1 = b1 * invA0
            val normB2 = b2 * invA0
            val normA1 = a1 * invA0
            val normA2 = a2 * invA0

            if (normB0.isNaN() || normB1.isNaN() || normB2.isNaN() || normA1.isNaN() || normA2.isNaN() ||
                normB0.isInfinite() || normB1.isInfinite() || normB2.isInfinite() || normA1.isInfinite() || normA2.isInfinite()) {
                outCoefs[0] = 1.0; outCoefs[1] = 0.0; outCoefs[2] = 0.0; outCoefs[3] = 0.0; outCoefs[4] = 0.0
                return
            }

            outCoefs[0] = normB0
            outCoefs[1] = normB1
            outCoefs[2] = normB2
            outCoefs[3] = normA1
            outCoefs[4] = normA2
        }
    }

    // Active parametric bands (default: 10 professional bands)
    private val bands: MutableList<EqBand> = DEFAULT_10_BANDS.map { it.copy() }.toMutableList()

    // Biquad filter pairs per band (Left and Right stereo channels)
    private val biquadsL = mutableListOf<Biquad>()
    private val biquadsR = mutableListOf<Biquad>()

    // Optional solo band index: when non-null, only this band affects audio
    @Volatile
    var soloBandIndex: Int? = null

    // Master preamp in dB (-24 dB to +24 dB)
    @Volatile
    var preampDb: Double = 0.0
        set(value) { field = value.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) }

    // User toggleable auto headroom compensation
    @Volatile
    var autoHeadroomEnabled: Boolean = false

    // Master EQ bypass toggle
    @Volatile
    var isEnabled: Boolean = true

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
    private var appliedSolo: Int? = null
    private val appliedBands = mutableListOf<EqBand>()

    private var outputGain = 1.0f

    // Temporary array for coefficient calculation (avoids heap allocation in hot path)
    private val tempCoefs = DoubleArray(5)

    init {
        rebuildBiquads(immediate = true)
    }

    private fun rebuildBiquads(immediate: Boolean) {
        synchronized(bands) {
            while (biquadsL.size < bands.size) {
                biquadsL.add(Biquad())
                biquadsR.add(Biquad())
            }
            appliedBands.clear()
            for (b in bands) {
                appliedBands.add(b.copy())
            }
            updateCoefficients(immediate)
        }
    }

    fun getBands(): List<EqBand> = synchronized(bands) {
        bands.map { it.copy() }
    }

    fun setBands(newBands: List<EqBand>) {
        synchronized(bands) {
            bands.clear()
            for (b in newBands) {
                bands.add(b.copy())
            }
            rebuildBiquads(immediate = false)
        }
    }

    fun addBand(band: EqBand) {
        synchronized(bands) {
            bands.add(band.copy(id = bands.size))
            rebuildBiquads(immediate = false)
        }
    }

    fun removeBand(index: Int): Boolean {
        synchronized(bands) {
            if (bands.size <= 1 || index !in bands.indices) return false
            bands.removeAt(index)
            for (i in bands.indices) {
                bands[i] = bands[i].copy(id = i)
            }
            if (soloBandIndex == index) {
                soloBandIndex = null
            } else if (soloBandIndex != null && soloBandIndex!! > index) {
                soloBandIndex = soloBandIndex!! - 1
            }
            rebuildBiquads(immediate = false)
            return true
        }
    }

    fun updateBand(index: Int, freqHz: Double, gainDb: Double, q: Double, isEnabled: Boolean, immediate: Boolean = false) {
        synchronized(bands) {
            if (index in bands.indices) {
                val b = bands[index]
                b.frequencyHz = freqHz.coerceIn(MIN_FREQ_HZ, (sampleRate * 0.495).coerceAtMost(MAX_FREQ_HZ))
                b.gainDb = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
                b.q = q.coerceIn(MIN_Q, MAX_Q)
                b.isEnabled = isEnabled
                updateCoefficients(immediate = immediate)
            }
        }
    }

    fun updateBandType(index: Int, type: EqFilterType, immediate: Boolean = false) {
        synchronized(bands) {
            if (index in bands.indices) {
                bands[index].type = type
                updateCoefficients(immediate = immediate)
            }
        }
    }

    fun resetToFlat() {
        synchronized(bands) {
            preampDb = 0.0
            autoHeadroomEnabled = false
            soloBandIndex = null
            lowGain = 1f
            midGain = 1f
            highGain = 1f
            for (b in bands) {
                b.gainDb = 0.0
                b.isEnabled = true
            }
            updateCoefficients(immediate = false)
        }
    }

    fun resetBand(index: Int) {
        synchronized(bands) {
            if (index in bands.indices) {
                bands[index].gainDb = 0.0
                bands[index].isEnabled = true
                updateCoefficients(immediate = false)
            }
        }
    }

    /**
     * Resets the internal delay states of all active biquad filters to zero.
     * Prevents filter ringing, pops, or transient artifacts on seeks/switches.
     */
    fun resetFilters() {
        synchronized(bands) {
            for (bq in biquadsL) bq.reset()
            for (bq in biquadsR) bq.reset()
        }
    }

    /**
     * Processes interleaved stereo 16-bit PCM in place with high precision floating-point DSP,
     * denormal protection, smooth parameter ramping, and safe true-peak limiting.
     */
    fun processStereo(buffer: ShortArray, offset: Int, frameCount: Int) {
        if (!isEnabled) {
            // Smoothly ramp back to unity if previous gain was non-unity
            if (outputGain < 0.9995f || outputGain > 1.0005f) {
                for (i in 0 until frameCount) {
                    val idx = offset + i * 2
                    if (idx + 1 >= buffer.size) break
                    outputGain += (1.0f - outputGain) * 0.01f
                    buffer[idx] = (buffer[idx] * outputGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    buffer[idx + 1] = (buffer[idx + 1] * outputGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            } else {
                outputGain = 1.0f
            }
            return
        }

        val isUnityQuick = (lowGain == 1f && midGain == 1f && highGain == 1f)
        val isUnityParametric = (preampDb == 0.0) && !autoHeadroomEnabled && bands.all {
            !it.isEnabled || (it.gainDb == 0.0 && it.type != EqFilterType.HIGH_PASS && it.type != EqFilterType.LOW_PASS && it.type != EqFilterType.NOTCH && it.type != EqFilterType.BAND_PASS)
        }

        // Bit-exact unity fast path
        if (isUnityQuick && isUnityParametric) {
            if (abs(outputGain - 1.0f) < 0.0005f) {
                outputGain = 1.0f
                var hasActiveState = false
                val count = minOf(bands.size, biquadsL.size)
                for (b in 0 until count) {
                    if (biquadsL[b].hasEnergy() || biquadsR[b].hasEnergy()) {
                        hasActiveState = true
                        break
                    }
                }
                if (!hasActiveState) {
                    return // 100% untouched bit-exact bypass
                }
            }
        }

        ensureCoefficients()

        // Target output gain calculation (clean preamp + deterministic auto-headroom)
        val preampLinear = 10.0.pow(preampDb / 20.0).toFloat()
        val targetOutputGain = if (autoHeadroomEnabled) {
            var maxBoostDb = 0.0
            synchronized(bands) {
                for (b in bands) {
                    if (b.isEnabled && (b.type == EqFilterType.PEAKING || b.type == EqFilterType.LOW_SHELF || b.type == EqFilterType.HIGH_SHELF)) {
                        if (b.gainDb > maxBoostDb) maxBoostDb = b.gainDb
                    }
                }
            }
            val lowDb = linearToDb(lowGain)
            val midDb = linearToDb(midGain)
            val highDb = linearToDb(highGain)
            val totalMaxBoost = maxOf(maxBoostDb, lowDb, midDb, highDb)
            val headroomScale = 10.0.pow(-totalMaxBoost / 20.0).toFloat()
            (preampLinear * headroomScale).coerceIn(0.01f, 4.0f)
        } else {
            preampLinear.coerceIn(0.01f, 16.0f)
        }

        val activeBandCount = minOf(bands.size, biquadsL.size)
        val rampSteps = minOf(frameCount, 64)

        for (i in 0 until frameCount) {
            val idx = offset + i * 2
            if (idx + 1 >= buffer.size) break

            // Ramp coefficients across first sub-block to eliminate zipper noise
            if (i < rampSteps) {
                val remaining = rampSteps - i
                for (b in 0 until activeBandCount) {
                    biquadsL[b].stepTowardsTarget(remaining)
                    biquadsR[b].stepTowardsTarget(remaining)
                }
            }

            outputGain += (targetOutputGain - outputGain) * 0.005f

            var left = buffer[idx].toDouble()
            var right = buffer[idx + 1].toDouble()

            // Transposed Direct Form II filter series cascade
            val currentSolo = soloBandIndex
            for (b in 0 until activeBandCount) {
                if (currentSolo != null) {
                    if (b == currentSolo && bands[b].isEnabled) {
                        left = biquadsL[b].process(left)
                        right = biquadsR[b].process(right)
                    }
                } else if (bands[b].isEnabled) {
                    left = biquadsL[b].process(left)
                    right = biquadsR[b].process(right)
                }
            }

            left *= outputGain
            right *= outputGain

            // Safe true-peak limiter: engages only when exceeding 32000 to prevent digital clipping
            left = softLimit(left)
            right = softLimit(right)

            buffer[idx] = left.toInt().toShort()
            buffer[idx + 1] = right.toInt().toShort()
        }
    }

    /**
     * C1-continuous soft limiter. Leaves samples below 32000.0 untouched (100% bit-exact & linear).
     * Smoothly saturates peaks exceeding 32000.0 to strictly prevent overflow wrap-around.
     */
    private fun softLimit(sample: Double): Double {
        val threshold = 32000.0
        val maxVal = 32767.0
        val absVal = if (sample < 0) -sample else sample
        if (absVal <= threshold) return sample

        val headroom = maxVal - threshold // 767.0
        val excess = absVal - threshold
        val compressed = threshold + headroom * tanh(excess / headroom)
        return if (sample < 0) -compressed else compressed
    }

    private fun ensureCoefficients() {
        var needsUpdate = false

        if (lowGain != appliedLow || midGain != appliedMid || highGain != appliedHigh || preampDb != appliedPreamp || soloBandIndex != appliedSolo) {
            appliedLow = lowGain
            appliedMid = midGain
            appliedHigh = highGain
            appliedPreamp = preampDb
            appliedSolo = soloBandIndex
            needsUpdate = true
        }

        synchronized(bands) {
            if (bands.size != appliedBands.size) {
                rebuildBiquads(immediate = false)
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
            updateCoefficients(immediate = false)
        }
    }

    private fun updateCoefficients(immediate: Boolean) {
        synchronized(bands) {
            val currentSolo = soloBandIndex
            for (i in bands.indices) {
                if (i >= biquadsL.size) break
                val band = bands[i]
                if (!band.isEnabled || (currentSolo != null && currentSolo != i)) {
                    if (immediate) {
                        biquadsL[i].setImmediate(1.0, 0.0, 0.0, 0.0, 0.0)
                        biquadsR[i].setImmediate(1.0, 0.0, 0.0, 0.0, 0.0)
                    } else {
                        biquadsL[i].setTarget(1.0, 0.0, 0.0, 0.0, 0.0)
                        biquadsR[i].setTarget(1.0, 0.0, 0.0, 0.0, 0.0)
                    }
                    continue
                }

                // Combine parametric band gain with quick DJ mixer knob offsets for standard bands
                val djKnobOffsetDb = when (i) {
                    1 -> linearToDb(lowGain)      // Bass band
                    3 -> linearToDb(midGain)      // Mid band
                    5 -> linearToDb(highGain)     // Presence band
                    else -> 0.0
                }
                val effectiveGainDb = (band.gainDb + djKnobOffsetDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

                calculateBiquadCoefficients(sampleRate, band.type, band.frequencyHz, effectiveGainDb, band.q, tempCoefs)

                if (immediate) {
                    biquadsL[i].setImmediate(tempCoefs[0], tempCoefs[1], tempCoefs[2], tempCoefs[3], tempCoefs[4])
                    biquadsR[i].setImmediate(tempCoefs[0], tempCoefs[1], tempCoefs[2], tempCoefs[3], tempCoefs[4])
                } else {
                    biquadsL[i].setTarget(tempCoefs[0], tempCoefs[1], tempCoefs[2], tempCoefs[3], tempCoefs[4])
                    biquadsR[i].setTarget(tempCoefs[0], tempCoefs[1], tempCoefs[2], tempCoefs[3], tempCoefs[4])
                }
            }
        }
    }

    private fun linearToDb(linear: Float): Double {
        val l = linear.coerceIn(0f, 2f)
        if (l <= 0.001f) return -36.0
        if (abs(l - 1f) < 0.001f) return 0.0
        return (20.0 * ln(l.toDouble()) / ln(10.0)).coerceIn(-36.0, 12.0)
    }

    /**
     * Transposed Direct Form II (TDF-II) second-order IIR biquad filter section.
     * Features smooth target coefficient stepping and denormal flushing.
     */
    private class Biquad {
        @Volatile var b0 = 1.0
        @Volatile var b1 = 0.0
        @Volatile var b2 = 0.0
        @Volatile var a1 = 0.0
        @Volatile var a2 = 0.0

        var targetB0 = 1.0
        var targetB1 = 0.0
        var targetB2 = 0.0
        var targetA1 = 0.0
        var targetA2 = 0.0

        // Transposed Direct Form II state delays
        private var s1 = 0.0
        private var s2 = 0.0

        fun reset() {
            s1 = 0.0
            s2 = 0.0
        }

        fun hasEnergy(): Boolean {
            return abs(s1) > 1e-12 || abs(s2) > 1e-12
        }

        fun setImmediate(c0: Double, c1: Double, c2: Double, d1: Double, d2: Double) {
            b0 = c0; b1 = c1; b2 = c2; a1 = d1; a2 = d2
            targetB0 = c0; targetB1 = c1; targetB2 = c2; targetA1 = d1; targetA2 = d2
        }

        fun setTarget(c0: Double, c1: Double, c2: Double, d1: Double, d2: Double) {
            targetB0 = c0; targetB1 = c1; targetB2 = c2; targetA1 = d1; targetA2 = d2
        }

        fun stepTowardsTarget(remainingSteps: Int) {
            if (remainingSteps <= 1) {
                b0 = targetB0; b1 = targetB1; b2 = targetB2; a1 = targetA1; a2 = targetA2
            } else {
                val factor = 1.0 / remainingSteps
                b0 += (targetB0 - b0) * factor
                b1 += (targetB1 - b1) * factor
                b2 += (targetB2 - b2) * factor
                a1 += (targetA1 - a1) * factor
                a2 += (targetA2 - a2) * factor
            }
        }

        inline fun process(x: Double): Double {
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            // Denormal flushing to prevent CPU stalls
            if (s1.isNaN() || s1.isInfinite() || (s1 > -1e-15 && s1 < 1e-15)) s1 = 0.0
            if (s2.isNaN() || s2.isInfinite() || (s2 > -1e-15 && s2 < 1e-15)) s2 = 0.0
            return y
        }
    }
}