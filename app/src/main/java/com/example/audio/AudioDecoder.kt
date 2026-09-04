package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.example.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Decodes audio tracks into raw 16-bit PCM samples and computes high-resolution
 * waveform amplitude peaks and 3-band (Low, Mid, High) frequency energies.
 *
 * Highly optimized for memory safety & performance:
 * - Streams buffers directly from MediaCodec into fixed bin accumulators without buffering full audio into heap.
 * - Decodes full songs accurately regardless of duration.
 * - Extracts true PCM amplitudes:
 *   - Quiet sections visually smaller.
 *   - Loud sections visually larger.
 *   - Breakdowns clearly visible.
 *   - Silence approaches zero amplitude.
 *   - Drops/choruses visibly correspond with the audio energy.
 */
object AudioDecoder {

    private const val TAG = "SoundSyncDecoder"
    private const val TIMEOUT_US = 5000L
    private const val MAX_SAMPLES_SPECTROGRAM = 3_000_000

    data class DecodedAudioData(
        val samples: FloatArray,
        val sampleRate: Int,
        val channelCount: Int,
        val durationMs: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DecodedAudioData
            return samples.contentEquals(other.samples) &&
                    sampleRate == other.sampleRate &&
                    channelCount == other.channelCount &&
                    durationMs == other.durationMs
        }

        override fun hashCode(): Int {
            var result = samples.contentHashCode()
            result = 31 * result + sampleRate
            result = 31 * result + channelCount
            result = 31 * result + durationMs.hashCode()
            return result
        }
    }

    /**
     * Decodes the full audio track and computes true PCM waveform peaks and 3-band energy arrays.
     * Uses streaming accumulators for zero-allocation performance and full track coverage.
     */
    suspend fun decodeRealWaveformPcm(
        context: Context,
        track: Track,
        targetBins: Int,
        onProgress: (percent: Int) -> Unit = {}
    ): WaveformData? = withContext(Dispatchers.IO) {
        val filePathOrUri = track.filePath
        if (filePathOrUri.isBlank()) {
            Log.w(TAG, "decodeRealWaveformPcm: blank filePathOrUri for '${track.title}'")
            return@withContext null
        }

        val startTime = System.currentTimeMillis()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            // 1. Configure Extractor
            if (filePathOrUri.startsWith("content://") || filePathOrUri.startsWith("file://")) {
                val uri = Uri.parse(filePathOrUri)
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    } ?: run {
                        extractor.setDataSource(context, uri, null)
                    }
                } catch (e: Exception) {
                    extractor.setDataSource(context, uri, null)
                }
            } else {
                val file = File(filePathOrUri)
                if (!file.exists() || !file.canRead()) {
                    Log.w(TAG, "File not accessible on filesystem: $filePathOrUri")
                    return@withContext null
                }
                extractor.setDataSource(filePathOrUri)
            }

            // 2. Select audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var mime = ""

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (trackMime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    mime = trackMime
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Log.w(TAG, "No audio track found in $filePathOrUri")
                return@withContext null
            }

            extractor.selectTrack(audioTrackIndex)

            val rawSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val sampleRate = if (rawSampleRate > 0) rawSampleRate else 44100

            val rawChannelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2
            val channelCount = rawChannelCount.coerceIn(1, 8)

            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else (track.durationSeconds.coerceAtLeast(10) * 1_000_000L)

            val totalDurationUs = if (durationUs > 0) durationUs else (track.durationSeconds.coerceAtLeast(10) * 1_000_000L)
            val durationMs = totalDurationUs / 1000L

            val binCount = targetBins.coerceIn(600, 7200)

            // Accumulator arrays for all bins (Memory footprint ~40 KB)
            val maxPeakInBin = FloatArray(binCount)
            val sumSqInBin = FloatArray(binCount)
            val lowSumSqInBin = FloatArray(binCount)
            val midSumSqInBin = FloatArray(binCount)
            val highSumSqInBin = FloatArray(binCount)
            val sampleCountInBin = IntArray(binCount)

            // 3. Initialize MediaCodec
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            var decodedFrames = 0L

            // 3-band simple IIR filter states for frequency separation
            var lowFilterState = 0.0f
            var midFilterState = 0.0f

            val timePerSampleUs = 1_000_000.0 / sampleRate
            val estimatedBuffers = ((totalDurationUs / 1000L) / 15L).toInt()
            val maxIterations = max(60000, estimatedBuffers * 3)
            var iteration = 0
            var consecutiveEmptyOutputs = 0

            while (!isOutputEOS && iteration++ < maxIterations) {
                if (!isInputEOS) {
                    val inputBufIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputBufIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isInputEOS = true
                            } else {
                                codec.queueInputBuffer(
                                    inputBufIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufIndex >= 0) {
                    consecutiveEmptyOutputs = 0
                    val outputBuffer = codec.getOutputBuffer(outputBufIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                        val shortBuffer = outputBuffer.asShortBuffer()
                        val presentationTimeUs = bufferInfo.presentationTimeUs

                        var frameInBuf = 0
                        while (shortBuffer.remaining() >= channelCount) {
                            var sum = 0
                            for (ch in 0 until channelCount) {
                                sum += shortBuffer.get().toInt()
                            }
                            val mono = (sum / channelCount.toFloat()) / 32768.0f // [-1.0 .. 1.0]
                            val absS = abs(mono)

                            // 3-Band DSP separation
                            // Low-pass (~300Hz)
                            lowFilterState += 0.08f * (mono - lowFilterState)
                            val lowSample = lowFilterState

                            // Band-pass (~300Hz - 3kHz)
                            midFilterState += 0.25f * (mono - midFilterState)
                            val midSample = midFilterState - lowFilterState

                            // High-pass (> 3kHz)
                            val highSample = mono - midFilterState

                            val currentSampleTimeUs = presentationTimeUs + (frameInBuf * timePerSampleUs).toLong()
                            val bin = ((currentSampleTimeUs.toDouble() / totalDurationUs.toDouble()) * binCount)
                                .toInt().coerceIn(0, binCount - 1)

                            if (absS > maxPeakInBin[bin]) maxPeakInBin[bin] = absS
                            sumSqInBin[bin] += mono * mono
                            lowSumSqInBin[bin] += lowSample * lowSample
                            midSumSqInBin[bin] += midSample * midSample
                            highSumSqInBin[bin] += highSample * highSample
                            sampleCountInBin[bin]++

                            frameInBuf++
                            decodedFrames++
                        }
                    }

                    codec.releaseOutputBuffer(outputBufIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                } else {
                    if (isInputEOS && ++consecutiveEmptyOutputs > 500) {
                        isOutputEOS = true
                    }
                }
            }

            Log.d(TAG, "PCM Decoding complete: $decodedFrames audio frames processed into $binCount bins in ${System.currentTimeMillis() - startTime}ms for '${track.title}'")

            if (decodedFrames < 100) {
                Log.w(TAG, "Insufficient audio frames decoded for '${track.title}'")
                return@withContext null
            }

            // 4. Compute final RMS and peak amplitude per bin
            val peaks = FloatArray(binCount)
            val lowBand = FloatArray(binCount)
            val midBand = FloatArray(binCount)
            val highBand = FloatArray(binCount)
            val rmsBand = FloatArray(binCount)

            var globalMaxPeak = 0.0001f
            var globalMaxLow = 0.0001f
            var globalMaxMid = 0.0001f
            var globalMaxHigh = 0.0001f
            var globalMaxRms = 0.0001f

            for (b in 0 until binCount) {
                val count = sampleCountInBin[b]
                if (count > 0) {
                    val rms = sqrt(sumSqInBin[b] / count)
                    val maxPeak = maxPeakInBin[b]
                    // Preserve small transients (kicks, snares, hi-hats, percussive peaks)
                    // while maintaining musical dynamics and body
                    val transientPeak = max(rms * 0.55f + maxPeak * 0.45f, maxPeak * 0.85f)
                    val low = sqrt(lowSumSqInBin[b] / count)
                    val mid = sqrt(midSumSqInBin[b] / count)
                    val high = sqrt(highSumSqInBin[b] / count)

                    peaks[b] = transientPeak
                    rmsBand[b] = rms
                    lowBand[b] = low
                    midBand[b] = mid
                    highBand[b] = high

                    if (transientPeak > globalMaxPeak) globalMaxPeak = transientPeak
                    if (rms > globalMaxRms) globalMaxRms = rms
                    if (low > globalMaxLow) globalMaxLow = low
                    if (mid > globalMaxMid) globalMaxMid = mid
                    if (high > globalMaxHigh) globalMaxHigh = high
                }
            }

            // Interpolate any unpopulated bins (e.g. from presentation time alignment)
            var lastValidPeak = 0.0f
            var lastValidLow = 0.0f
            var lastValidMid = 0.0f
            var lastValidHigh = 0.0f
            var lastValidRms = 0.0f
            for (b in 0 until binCount) {
                if (sampleCountInBin[b] == 0) {
                    peaks[b] = lastValidPeak * 0.9f
                    lowBand[b] = lastValidLow * 0.9f
                    midBand[b] = lastValidMid * 0.9f
                    highBand[b] = lastValidHigh * 0.9f
                    rmsBand[b] = lastValidRms * 0.9f
                } else {
                    lastValidPeak = peaks[b]
                    lastValidLow = lowBand[b]
                    lastValidMid = midBand[b]
                    lastValidHigh = highBand[b]
                    lastValidRms = rmsBand[b]
                }
            }

            // Normalize: quiet sections remain quiet, loud sections hit near 1.0, silence approaches 0.0
            val peakNorm = if (globalMaxPeak > 0.0001f) globalMaxPeak else 1.0f
            val lowNorm = if (globalMaxLow > 0.0001f) globalMaxLow else 1.0f
            val midNorm = if (globalMaxMid > 0.0001f) globalMaxMid else 1.0f
            val highNorm = if (globalMaxHigh > 0.0001f) globalMaxHigh else 1.0f
            val rmsNorm = if (globalMaxRms > 0.0001f) globalMaxRms else 1.0f

            for (b in 0 until binCount) {
                peaks[b] = (peaks[b] / peakNorm).coerceIn(0.0f, 1.0f)
                lowBand[b] = (lowBand[b] / lowNorm).coerceIn(0.0f, 1.0f)
                midBand[b] = (midBand[b] / midNorm).coerceIn(0.0f, 1.0f)
                highBand[b] = (highBand[b] / highNorm).coerceIn(0.0f, 1.0f)
                rmsBand[b] = (rmsBand[b] / rmsNorm).coerceIn(0.0f, 1.0f)
            }

            WaveformData(
                trackId = track.id,
                durationMs = durationMs,
                samplePoints = binCount,
                peaks = peaks,
                lowBand = lowBand,
                midBand = midBand,
                highBand = highBand,
                bpm = if (track.bpm > 0) track.bpm else 126.0,
                isRealAudioData = true,
                rms = rmsBand
            )
        } catch (e: CancellationException) {
            Log.d(TAG, "decodeRealWaveformPcm cancelled for '${track.title}'")
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "decodeRealWaveformPcm error for '${track.title}': ${e.message}", e)
            null
        } finally {
            try { codec?.stop() } catch (ignored: Exception) {}
            try { codec?.release() } catch (ignored: Exception) {}
            try { extractor.release() } catch (ignored: Exception) {}
        }
    }

    /**
     * Decodes an audio file or content URI into normalized mono float samples for STFT analysis.
     */
    suspend fun decodeToMonoPcm(
        context: Context,
        filePathOrUri: String,
        maxDurationSeconds: Int = 180
    ): DecodedAudioData? = withContext(Dispatchers.IO) {
        if (filePathOrUri.isBlank()) {
            Log.w(TAG, "decodeToMonoPcm called with blank filePathOrUri")
            return@withContext null
        }

        val decodeStartTime = System.currentTimeMillis()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            if (filePathOrUri.startsWith("content://") || filePathOrUri.startsWith("file://")) {
                val uri = Uri.parse(filePathOrUri)
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    } ?: run {
                        extractor.setDataSource(context, uri, null)
                    }
                } catch (e: Exception) {
                    extractor.setDataSource(context, uri, null)
                }
            } else {
                val file = File(filePathOrUri)
                if (!file.exists() || !file.canRead()) {
                    Log.w(TAG, "File not accessible on filesystem: $filePathOrUri")
                    return@withContext null
                }
                extractor.setDataSource(filePathOrUri)
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var mime = ""

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (trackMime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    mime = trackMime
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Log.w(TAG, "No audio track format found in $filePathOrUri")
                return@withContext null
            }

            extractor.selectTrack(audioTrackIndex)

            val rawSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val sampleRate = if (rawSampleRate > 0) rawSampleRate else 44100

            val rawChannelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2
            val channelCount = rawChannelCount.coerceIn(1, 8)

            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val durationMs = if (durationUs > 0) durationUs / 1000 else 0L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val maxSamplesToDecode = (sampleRate * maxDurationSeconds.coerceAtLeast(10)).coerceIn(4096, MAX_SAMPLES_SPECTROGRAM)
            val pcmBuffer = FloatArray(maxSamplesToDecode)
            var decodedSampleCount = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            val estimatedBuffers = ((maxSamplesToDecode / 1024) * 2)
            val maxIterations = max(60000, estimatedBuffers * 3)
            var iteration = 0
            var consecutiveEmptyOutputs = 0

            while (!isOutputEOS && decodedSampleCount < maxSamplesToDecode && iteration++ < maxIterations) {
                if (!isInputEOS) {
                    val inputBufIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputBufIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isInputEOS = true
                            } else {
                                codec.queueInputBuffer(
                                    inputBufIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufIndex >= 0) {
                    consecutiveEmptyOutputs = 0
                    val outputBuffer = codec.getOutputBuffer(outputBufIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                        val shortBuffer = outputBuffer.asShortBuffer()

                        if (channelCount == 1) {
                            while (shortBuffer.hasRemaining() && decodedSampleCount < maxSamplesToDecode) {
                                val s = shortBuffer.get()
                                pcmBuffer[decodedSampleCount++] = s / 32768.0f
                            }
                        } else {
                            while (shortBuffer.remaining() >= channelCount && decodedSampleCount < maxSamplesToDecode) {
                                var sum = 0
                                for (ch in 0 until channelCount) {
                                    sum += shortBuffer.get().toInt()
                                }
                                val mono = (sum / channelCount.toFloat()) / 32768.0f
                                pcmBuffer[decodedSampleCount++] = mono
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputBufIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                } else {
                    if (isInputEOS && ++consecutiveEmptyOutputs > 500) {
                        isOutputEOS = true
                    }
                }
            }

            val finalSamples = if (decodedSampleCount == pcmBuffer.size) {
                pcmBuffer
            } else {
                pcmBuffer.copyOf(decodedSampleCount)
            }

            val totalDecodeTime = System.currentTimeMillis() - decodeStartTime
            Log.d(TAG, "decodeToMonoPcm: Decoded ${finalSamples.size} PCM mono samples in ${totalDecodeTime}ms for '$filePathOrUri'")

            DecodedAudioData(
                samples = finalSamples,
                sampleRate = sampleRate,
                channelCount = channelCount,
                durationMs = durationMs
            )
        } catch (e: CancellationException) {
            Log.d(TAG, "Decoder cancelled for: $filePathOrUri")
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Decoder error for '$filePathOrUri': ${e.message}", e)
            null
        } finally {
            try { codec?.stop() } catch (ignored: Exception) {}
            try { codec?.release() } catch (ignored: Exception) {}
            try { extractor.release() } catch (ignored: Exception) {}
        }
    }
}
