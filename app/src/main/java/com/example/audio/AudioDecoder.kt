package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Decodes audio tracks into raw 16-bit mono PCM float samples [-1.0f .. 1.0f] using MediaExtractor and MediaCodec.
 * Fully supports Android content:// URIs, file:// URIs, and direct filesystem paths.
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TIMEOUT_US = 5000L
    private const val MAX_PCM_SAMPLES = 44100 * 300 // Max 5 minutes of 44.1kHz mono PCM to prevent OOM

    data class DecodedAudioData(
        val samples: FloatArray,
        val sampleRate: Int,
        val channelCount: Int,
        val durationMs: Long
    )

    /**
     * Decodes an audio file or content URI into normalized mono float samples.
     * Safely runs on Dispatchers.IO.
     */
    suspend fun decodeToMonoPcm(
        context: Context,
        filePathOrUri: String,
        maxDurationSeconds: Int = 180
    ): DecodedAudioData? = withContext(Dispatchers.IO) {
        if (filePathOrUri.isBlank()) return@withContext null

        com.example.util.DjLogger.startTiming("DECODE_START", "Decoding PCM for $filePathOrUri")
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
                extractor.release()
                return@withContext null
            }

            extractor.selectTrack(audioTrackIndex)

            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            // 3. Initialize MediaCodec Decoder
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val maxSamplesToDecode = (sampleRate * maxDurationSeconds).coerceAtMost(MAX_PCM_SAMPLES)
            val pcmList = ArrayList<Float>(maxSamplesToDecode)

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            var decodedSampleCount = 0

            val maxIterations = 2000 // Loop safety limit to prevent hangs

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
                        val shortsAvailable = shortBuffer.remaining()

                        if (channelCount == 1) {
                            while (shortBuffer.hasRemaining() && decodedSampleCount < maxSamplesToDecode) {
                                val s = shortBuffer.get()
                                pcmList.add(s / 32768.0f)
                                decodedSampleCount++
                            }
                        } else {
                            // Convert Stereo/Multi-channel to Mono by averaging channels
                            val channels = channelCount.coerceAtLeast(1)
                            while (shortBuffer.remaining() >= channels && decodedSampleCount < maxSamplesToDecode) {
                                var sum = 0
                                for (ch in 0 until channels) {
                                    sum += shortBuffer.get().toInt()
                                }
                                val mono = (sum / channels) / 32768.0f
                                pcmList.add(mono)
                                decodedSampleCount++
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputBufIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }

            val samplesArray = pcmList.toFloatArray()
            com.example.util.DjLogger.endTiming("DECODE_END", "${samplesArray.size} samples decoded for $filePathOrUri")
            Log.d(TAG, "Decoded ${samplesArray.size} PCM mono samples for $filePathOrUri ($sampleRate Hz, channels=$channelCount)")

            DecodedAudioData(
                samples = samplesArray,
                sampleRate = sampleRate,
                channelCount = channelCount,
                durationMs = durationUs / 1000
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error decoding PCM for $filePathOrUri: ${e.message}")
            null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (ignored: Exception) {}
            try {
                extractor.release()
            } catch (ignored: Exception) {}
        }
    }
}
