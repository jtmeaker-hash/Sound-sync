package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes audio tracks into raw 16-bit mono PCM float samples [-1.0f .. 1.0f] using MediaExtractor and MediaCodec.
 * Highly optimized for memory safety: uses primitive FloatArray buffers with hard memory bounds to prevent OOM crashes.
 */
object AudioDecoder {

    private const val TAG = "SoundSyncDecoder"
    private const val TIMEOUT_US = 5000L
    // Hard limit: 400,000 mono float samples = ~1.6 MB memory footprint (prevents OOM on all Android devices)
    private const val MAX_PCM_SAMPLES = 400_000

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
     * Decodes an audio file or content URI into normalized mono float samples.
     * Safely runs on Dispatchers.IO with strict try-finally decoder resource cleanup.
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
        Log.d(TAG, "Decoder start: URI/Path='$filePathOrUri', maxDurationSeconds=$maxDurationSeconds")

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            // 1. Configure Extractor Data Source
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

            // 2. Locate Audio Track
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

            Log.d(TAG, "Audio stream detected: MIME=$mime, sampleRate=$sampleRate Hz, channels=$channelCount, durationMs=$durationMs ms")

            // 3. Initialize MediaCodec Decoder
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            // Preallocate primitive FloatArray (capped at MAX_PCM_SAMPLES = 400k floats = 1.6MB heap)
            val maxSamplesToDecode = (sampleRate * maxDurationSeconds.coerceAtLeast(10)).coerceIn(4096, MAX_PCM_SAMPLES)
            val pcmBuffer = FloatArray(maxSamplesToDecode)
            var decodedSampleCount = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            val maxIterations = 2500 // Safety threshold to prevent infinite decode loops
            var iteration = 0

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
                            // Convert Stereo/Multi-channel to Mono by averaging channels
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
                }
            }

            val finalSamples = if (decodedSampleCount == pcmBuffer.size) {
                pcmBuffer
            } else {
                pcmBuffer.copyOf(decodedSampleCount)
            }

            val totalDecodeTime = System.currentTimeMillis() - decodeStartTime
            val estimatedMemKb = (finalSamples.size * 4) / 1024
            Log.d(TAG, "Decoder end: Decoded ${finalSamples.size} PCM mono samples in ${totalDecodeTime}ms (Heap: ${estimatedMemKb} KB) for '$filePathOrUri'")

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
            try {
                codec?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Non-critical: codec.stop error: ${e.message}")
            }
            try {
                codec?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Non-critical: codec.release error: ${e.message}")
            }
            try {
                extractor.release()
            } catch (e: Exception) {
                Log.w(TAG, "Non-critical: extractor.release error: ${e.message}")
            }
        }
    }
}

