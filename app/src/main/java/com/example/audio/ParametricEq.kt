package com.example.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time 3-band parametric EQ using RBJ Audio-EQ-Cookbook biquad filters.
 *
 * Three bands genuinely alter the frequency spectrum:
 *  - LOW : low-shelf peaking at ~180 Hz
 *  - MID : peaking (bell) filter at ~1 kHz
 *  - HIGH: high-shelf beginning at ~6.5 kHz
 *
 * Each band takes a linear gain multiplier (0..2, where 1.0 = unity). Filters
 * are updated lazily only when their gain changes, so tweaking a slider while a
 * track is playing is audible immediately without clicks (smooth state carries).
 *
 * Processing runs on interleaved stereo 16-bit PCM.
 */
class ParametricEq(private val sampleRate: Int) {

    // Center frequencies (Hz) for the three bands
    private val LOW_FREQ = 180.0
    private val MID_FREQ = 1000.0
    private val HIGH_FREQ = 6500.0
    private val MID_Q = 1.2

    // Six biquad stages (two channels x three bands), processed in series per channel
    private val lowL = Biquad()
    private val lowR = Biquad()
    private val midL = Biquad()
    private val midR = Biquad()
    private val highL = Biquad()
    private val highR = Biquad()

    // Current target gains (linear multipliers). Set from the UI/engine each frame.
    @Volatile var lowGain: Float = 1f
        set(value) { field = value.coerceIn(0f, 2f) }
    @Volatile var midGain: Float = 1f
        set(value) { field = value.coerceIn(0f, 2f) }
    @Volatile var highGain: Float = 1f
        set(value) { field = value.coerceIn(0f, 2f) }

    // Last applied gains, used to detect parameter changes
    private var appliedLow = Float.NaN
    private var appliedMid = Float.NaN
    private var appliedHigh = Float.NaN

    /**
     * Process an interleaved stereo 16-bit PCM buffer in place.
     * @param buffer     Interleaved [L,R,L,R,...] shorts
     * @param offset     Starting frame index (sample frame = 2 shorts)
     * @param frameCount Number of stereo frames to process
     */
    fun processStereo(buffer: ShortArray, offset: Int, frameCount: Int) {
        // Fast path: unity on all bands = bypass
        if (lowGain == 1f && midGain == 1f && highGain == 1f) return
        ensureCoefficients()

        for (i in 0 until frameCount) {
            val idx = offset + i * 2
            if (idx + 1 >= buffer.size) break

            val leftIn = buffer[idx].toDouble()
            val rightIn = buffer[idx + 1].toDouble()

            var left = highL.process(midL.process(lowL.process(leftIn)))
            var right = highR.process(midR.process(lowR.process(rightIn)))

            left = left.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
            right = right.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())

            buffer[idx] = left.toInt().toShort()
            buffer[idx + 1] = right.toInt().toShort()
        }
    }

    private fun ensureCoefficients() {
        if (lowGain != appliedLow) {
            configureLowShelf(lowL, lowR, lowGain)
            appliedLow = lowGain
        }
        if (midGain != appliedMid) {
            configurePeaking(midL, midR, midGain)
            appliedMid = midGain
        }
        if (highGain != appliedHigh) {
            configureHighShelf(highL, highR, highGain)
            appliedHigh = highGain
        }
    }

    private fun configureLowShelf(l: Biquad, r: Biquad, gain: Float) {
        val db = linearToDb(gain)
        val a = 10.0.pow(db / 40.0)
        val w0 = 2.0 * PI * LOW_FREQ / sampleRate
        val cosW = cos(w0)
        val sinW = kotlin.math.sin(w0)
        // S = 1 shelf slope -> alpha
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
        l.reset()
        r.reset()
    }

    private fun configureHighShelf(l: Biquad, r: Biquad, gain: Float) {
        val db = linearToDb(gain)
        val a = 10.0.pow(db / 40.0)
        val w0 = 2.0 * PI * HIGH_FREQ / sampleRate
        val cosW = cos(w0)
        val sinW = kotlin.math.sin(w0)
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
        l.reset()
        r.reset()
    }

    private fun configurePeaking(l: Biquad, r: Biquad, gain: Float) {
        val db = linearToDb(gain)
        val a = 10.0.pow(db / 40.0)
        val w0 = 2.0 * PI * MID_FREQ / sampleRate
        val cosW = cos(w0)
        val alpha = kotlin.math.sin(w0) / (2.0 * MID_Q)

        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosW
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha / a

        val coefs = doubleArrayOf(b0, b1, b2, a1, a2)
        l.setCoefficients(coefs, a0)
        r.setCoefficients(coefs, a0)
        l.reset()
        r.reset()
    }

    private fun linearToDb(linear: Float): Double {
        val l = linear.coerceIn(0f, 2f)
        if (l <= 0f) return -36.0
        return (20.0 * ln(l.toDouble()) / ln(10.0)).coerceIn(-36.0, 12.0)
    }

    /**
     * Single second-order IIR biquad filter section.
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