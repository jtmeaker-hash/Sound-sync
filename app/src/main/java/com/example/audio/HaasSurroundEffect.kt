package com.example.audio

import android.content.Context
import android.content.SharedPreferences

/**
 * Haas (Precedence) Surround Effect DSP processor.
 *
 * Applies a very short controlled inter-channel delay to create additional
 * perceived stereo width without altering the center image.
 *
 * The effect works by delaying one channel by a few milliseconds relative to
 * the other, exploiting the Haas precedence effect where the brain perceives
 * the delayed signal as spatial width rather than a distinct echo.
 *
 * Key safety features:
 * - Delay range limited to 0–12ms (beyond this becomes an audible echo)
 * - Wet/dry mix prevents excessive gain buildup
 * - Smooth parameter interpolation avoids clicks/pops
 * - Gain compensation prevents clipping when summing
 * - At 0% amount, original stereo image is perfectly preserved
 * - At 100%, still within safe DSP range
 */
class HaasSurroundEffect {

    companion object {
        private const val TAG = "HaasSurroundEffect"

        // Delay range: 0–12ms (Haas effect sweet spot is 1–8ms)
        const val MIN_DELAY_MS = 0f
        const val MAX_DELAY_MS = 12f
        const val DEFAULT_DELAY_MS = 5f

        // Amount (wet/dry mix): 0.0 = bypass, 1.0 = full effect
        const val MIN_AMOUNT = 0f
        const val MAX_AMOUNT = 1f
        const val DEFAULT_AMOUNT = 0.5f

        // Max sample rate assumed for buffer sizing
        private const val MAX_SAMPLE_RATE = 48000
        // Max delay in samples = 12ms at 48kHz = 576 samples
        private const val MAX_DELAY_SAMPLES = (MAX_DELAY_MS / 1000f * MAX_SAMPLE_RATE).toInt() + 1

        private const val PREFS_NAME = "soundsync_haas_prefs"
        private const val KEY_ENABLED = "haas_enabled"
        private const val KEY_AMOUNT = "haas_amount"
        private const val KEY_DELAY_MS = "haas_delay_ms"

        fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        fun loadSettings(context: Context): HaasSettings {
            val prefs = getPrefs(context)
            return HaasSettings(
                isEnabled = prefs.getBoolean(KEY_ENABLED, false),
                amount = prefs.getFloat(KEY_AMOUNT, DEFAULT_AMOUNT).coerceIn(MIN_AMOUNT, MAX_AMOUNT),
                delayMs = prefs.getFloat(KEY_DELAY_MS, DEFAULT_DELAY_MS).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
            )
        }

        fun saveSettings(context: Context, settings: HaasSettings) {
            getPrefs(context).edit().apply {
                putBoolean(KEY_ENABLED, settings.isEnabled)
                putFloat(KEY_AMOUNT, settings.amount.coerceIn(MIN_AMOUNT, MAX_AMOUNT))
                putFloat(KEY_DELAY_MS, settings.delayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS))
                apply()
            }
        }
    }

    data class HaasSettings(
        val isEnabled: Boolean = false,
        val amount: Float = DEFAULT_AMOUNT,
        val delayMs: Float = DEFAULT_DELAY_MS
    )

    // Internal delay buffer (ring buffer for the delayed channel)
    // Sized for stereo: separate left and right delay lines
    private val leftDelayBuffer = ShortArray(MAX_DELAY_SAMPLES + 1)
    private val rightDelayBuffer = ShortArray(MAX_DELAY_SAMPLES + 1)
    private var writePos = 0

    // Smoothed parameters (for click-free transitions)
    @Volatile private var targetAmount = 0f
    @Volatile private var targetDelayMs = 0f
    @Volatile private var currentAmount = 0f
    @Volatile private var currentDelayMs = 0f

    // Enabled state
    @Volatile var isEnabled = false
        private set

    // Smoothing rate: how fast parameters approach target (per sample)
    private val amountSmoothingRate = 0.001f  // ~1ms smoothing
    private val delaySmoothingRate = 0.002f   // ~0.5ms smoothing

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            // Smoothly fade amount to 0 to avoid clicks
            targetAmount = 0f
        }
    }

    fun setAmount(amount: Float) {
        targetAmount = amount.coerceIn(MIN_AMOUNT, MAX_AMOUNT)
    }

    fun setDelayMs(delayMs: Float) {
        targetDelayMs = delayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }

    /**
     * Apply Haas surround effect to stereo PCM buffer (16-bit interleaved).
     * When bypassed (amount near 0), audio passes through unmodified.
     *
     * @param buffer Interleaved stereo samples [L0, R0, L1, R1, ...]
     * @param offset Start offset in the buffer
     * @param frameCount Number of stereo frames to process
     */
    fun process(buffer: ShortArray, offset: Int, frameCount: Int) {
        if (!isEnabled && currentAmount < 0.001f) return

        val sampleRate = MAX_SAMPLE_RATE.toFloat()
        val halfAmount = currentAmount * 0.5f  // Prevent gain buildup: max wet contribution is half

        for (i in 0 until frameCount) {
            val idx = offset + i * 2
            if (idx + 1 >= buffer.size) break

            // Smooth parameter transitions
            currentAmount += (targetAmount - currentAmount) * amountSmoothingRate
            currentDelayMs += (targetDelayMs - currentDelayMs) * delaySmoothingRate

            val currentHalfAmount = currentAmount * 0.5f
            val delaySamples = (currentDelayMs / 1000f * sampleRate).toInt().coerceIn(0, MAX_DELAY_SAMPLES)

            val leftIn = buffer[idx].toInt()
            val rightIn = buffer[idx + 1].toInt()

            if (delaySamples == 0 || currentHalfAmount < 0.001f) {
                // No delay or negligible effect — pass through
                // Re-apply smoothing to target for next iteration
                continue
            }

            // Read delayed samples from ring buffer
            val readPos = (writePos - delaySamples + leftDelayBuffer.size) % leftDelayBuffer.size
            val leftDelayed = leftDelayBuffer[readPos].toInt()
            val rightDelayed = rightDelayBuffer[readPos].toInt()

            // Haas effect: mix delayed version into the OPPOSITE channel for width
            // Left output = Left dry + (Right delayed * wet)
            // Right output = Right dry + (Left delayed * wet)
            // This creates stereo width by adding delayed cross-channel signal
            val leftOut = (leftIn * (1f - currentHalfAmount) + rightDelayed * currentHalfAmount).toInt()
            val rightOut = (rightIn * (1f - currentHalfAmount) + leftDelayed * currentHalfAmount).toInt()

            // Soft-clip to prevent overflow
            buffer[idx] = leftOut.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            buffer[idx + 1] = rightOut.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            // Write current samples to delay buffer
            leftDelayBuffer[writePos] = buffer[idx]
            rightDelayBuffer[writePos] = buffer[idx + 1]
            writePos = (writePos + 1) % leftDelayBuffer.size
        }

        // Check if we've fully faded out (for auto-disable)
        if (!isEnabled && currentAmount < 0.001f) {
            currentAmount = 0f
        }
    }

    /**
     * Reset all internal state (call when loading a new track or seeking).
     */
    fun reset() {
        leftDelayBuffer.fill(0)
        rightDelayBuffer.fill(0)
        writePos = 0
        currentAmount = 0f
        currentDelayMs = targetDelayMs
    }

    /**
     * Returns true if the effect is actively processing audio.
     */
    val isActive: Boolean
        get() = isEnabled || currentAmount > 0.001f
}
