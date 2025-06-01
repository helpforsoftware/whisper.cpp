package com.whispercppdemo.Mp3WavConvert

import android.content.Context
import android.media.*
import android.net.Uri
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Clean Android-native audio converter using MediaExtractor/MediaFormat
 * Converts MP3/WAV to 16-bit mono WAV files
 */
class AndroidAudioConverter(private val context: Context) {

    data class AudioInfo(
        val sampleRate: Int,
        val channels: Int,
        val duration: Long,
        val bitRate: Int
    )

    /**
     * Convert MP3/audio file to 16-bit mono WAV
     */
     fun convertToMono16BitWav(
        inputUri: Uri,
        outputFile: File,
        targetSampleRate: Int = 44100
    ): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null

        try {
            // Setup MediaExtractor
            extractor = MediaExtractor().apply {
                setDataSource(context, inputUri, null)
            }

            // Find audio track
            val audioTrack = findAudioTrack(extractor) ?: throw IllegalArgumentException("No audio track found")
            val format = extractor.getTrackFormat(audioTrack.trackIndex)
            extractor.selectTrack(audioTrack.trackIndex)

            // Setup MediaCodec decoder
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("No MIME type")
            decoder = MediaCodec.createDecoderByType(mime)

            // Configure decoder for PCM output
            val outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, targetSampleRate, 1).apply {
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }

            decoder.configure(format, null, null, 0)
            decoder.start()

            // Decode and convert
            val audioData = decodeAudioData(extractor, decoder, audioTrack.info)
            val monoData = if (audioTrack.info.channels > 1) {
                downmixToMono(audioData, audioTrack.info.channels)
            } else {
                audioData
            }

            val resampledData = if (audioTrack.info.sampleRate != targetSampleRate) {
                resample(monoData, audioTrack.info.sampleRate, targetSampleRate)
            } else {
                monoData
            }

            // Convert to 16-bit PCM and write WAV
            val pcm16Data = convertTo16BitPCM(resampledData)
            writeWavFile(outputFile, pcm16Data, targetSampleRate)

            return true

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            decoder?.apply {
                stop()
                release()
            }
            extractor?.release()
        }
    }

    /**
     * Find the first audio track in the media file
     */
    private fun findAudioTrack(extractor: MediaExtractor): AudioTrackInfo? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

            if (mime.startsWith("audio/")) {
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else 0L
                val bitRate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    format.getInteger(MediaFormat.KEY_BIT_RATE)
                } else 0

                val audioInfo = AudioInfo(sampleRate, channelCount, duration, bitRate)
                return AudioTrackInfo(i, audioInfo)
            }
        }
        return null
    }

    /**
     * Decode audio data using MediaCodec
     */
    private fun decodeAudioData(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        audioInfo: AudioInfo
    ): FloatArray {
        val audioSamples = mutableListOf<Float>()
        val inputBuffers = decoder.inputBuffers
        val outputBuffers = decoder.outputBuffers
        val info = MediaCodec.BufferInfo()

        var isEOS = false
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            // Feed input to decoder
            if (!inputDone) {
                val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = inputBuffers[inputBufferIndex]
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            // Get output from decoder
            val outputBufferIndex = decoder.dequeueOutputBuffer(info, 10000)
            when {
                outputBufferIndex >= 0 -> {
                    val outputBuffer = outputBuffers[outputBufferIndex]
                    if (info.size > 0) {
                        val pcmData = ByteArray(info.size)
                        outputBuffer.get(pcmData)
                        outputBuffer.rewind()

                        // Convert PCM bytes to float samples
                        val samples = convertPCMToFloat(pcmData, audioInfo.channels)
                        audioSamples.addAll(samples)
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Handle format change if needed
                }
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Continue loop
                }
            }
        }

        return audioSamples.toFloatArray()
    }

    /**
     * Convert PCM byte data to float samples
     */
    private fun convertPCMToFloat(pcmData: ByteArray, channels: Int): List<Float> {
        val samples = mutableListOf<Float>()
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)

        while (buffer.remaining() >= 2) {
            val sample = buffer.short.toFloat() / 32768f
            samples.add(sample)
        }

        return samples
    }

    /**
     * Downmix multi-channel audio to mono
     */
    private fun downmixToMono(samples: FloatArray, channels: Int): FloatArray {
        if (channels == 1) return samples

        val monoSamples = FloatArray(samples.size / channels)
        for (i in monoSamples.indices) {
            var sum = 0f
            for (ch in 0 until channels) {
                sum += samples[i * channels + ch]
            }
            monoSamples[i] = sum / channels
        }
        return monoSamples
    }

    /**
     * Simple linear interpolation resampling
     */
    private fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return samples

        val ratio = fromRate.toDouble() / toRate
        val newLength = (samples.size / ratio).toInt()
        val resampled = FloatArray(newLength)

        for (i in resampled.indices) {
            val srcIndex = i * ratio
            val index = srcIndex.toInt()
            val fraction = srcIndex - index

            resampled[i] = if (index + 1 < samples.size) {
                (samples[index] * (1 - fraction) + samples[index + 1] * fraction).toFloat()
            } else {
                samples[index]
            }
        }

        return resampled
    }

    /**
     * Convert float samples to 16-bit PCM
     */
    private fun convertTo16BitPCM(samples: FloatArray): ShortArray {
        return ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
        }
    }

    /**
     * Write 16-bit mono WAV file
     */
    private fun writeWavFile(file: File, samples: ShortArray, sampleRate: Int) {
        FileOutputStream(file).use { output ->
            val dataSize = samples.size * 2
            val fileSize = 36 + dataSize

            // Write WAV header
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF chunk
            header.put("RIFF".toByteArray())
            header.putInt(fileSize)
            header.put("WAVE".toByteArray())

            // fmt chunk
            header.put("fmt ".toByteArray())
            header.putInt(16) // PCM format chunk size
            header.putShort(1) // PCM format
            header.putShort(1) // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2) // byte rate
            header.putShort(2) // block align
            header.putShort(16) // bits per sample

            // data chunk
            header.put("data".toByteArray())
            header.putInt(dataSize)

            output.write(header.array())

            // Write sample data
            val sampleBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { sampleBuffer.putShort(it) }
            output.write(sampleBuffer.array())
        }
    }

    /**
     * Get audio file information without conversion
     */
    fun getAudioInfo(uri: Uri): AudioInfo? {
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor().apply {
                setDataSource(context, uri, null)
            }

            val audioTrack = findAudioTrack(extractor)
            return audioTrack?.info

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            extractor?.release()
        }
    }

    private data class AudioTrackInfo(
        val trackIndex: Int,
        val info: AudioInfo
    )
}

/**
 * Usage example and integration
 */
class AudioConverterHelper(private val context: Context) {

    private val converter = AndroidAudioConverter(context)

    /**
     * Convert audio file with progress callback
     */
    suspend fun convertAudioFile(
        inputUri: Uri,
        outputFileName: String,
        targetSampleRate: Int = 44100,
        onProgress: ((String) -> Unit)? = null
    ): File? {
        try {
            onProgress?.invoke("Starting conversion...")

            // Get audio info
            val audioInfo = converter.getAudioInfo(inputUri)
            onProgress?.invoke("Audio info: ${audioInfo?.sampleRate}Hz, ${audioInfo?.channels}ch")

            // Create output file
            val outputFile = File(context.getExternalFilesDir(null), outputFileName)

            onProgress?.invoke("Converting to 16-bit mono WAV...")
            val success = converter.convertToMono16BitWav(inputUri, outputFile, targetSampleRate)

            return if (success) {
                onProgress?.invoke("Conversion completed: ${outputFile.absolutePath}")
                outputFile
            } else {
                onProgress?.invoke("Conversion failed")
                null
            }

        } catch (e: Exception) {
            onProgress?.invoke("Error: ${e.message}")
            return null
        }
    }

    /**
     * Batch convert multiple files
     */
    suspend fun batchConvert(
        inputUris: List<Uri>,
        outputDir: File,
        onFileProgress: ((Int, Int, String) -> Unit)? = null
    ): List<File> {
        val convertedFiles = mutableListOf<File>()

        inputUris.forEachIndexed { index, uri ->
            try {
                val fileName = "converted_${System.currentTimeMillis()}_$index.wav"
                val outputFile = File(outputDir, fileName)

                onFileProgress?.invoke(index + 1, inputUris.size, "Converting $fileName...")

                val success = converter.convertToMono16BitWav(uri, outputFile)
                if (success) {
                    convertedFiles.add(outputFile)
                }

            } catch (e: Exception) {
                onFileProgress?.invoke(index + 1, inputUris.size, "Failed: ${e.message}")
            }
        }

        return convertedFiles
    }
}

// Example Activity/Fragment usage
/*
class MainActivity : AppCompatActivity() {
    private lateinit var converterHelper: AudioConverterHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        converterHelper = AudioConverterHelper(this)
    }

    private fun convertSelectedAudio(uri: Uri) {
        lifecycleScope.launch {
            val result = converterHelper.convertAudioFile(
                inputUri = uri,
                outputFileName = "converted_audio.wav",
                targetSampleRate = 44100
            ) { progress ->
                runOnUiThread {
                    // Update UI with progress
                    Toast.makeText(this@MainActivity, progress, Toast.LENGTH_SHORT).show()
                }
            }

            result?.let {
                // Conversion successful
                Log.d("AudioConverter", "Converted file: ${it.absolutePath}")
            }
        }
    }
}
*/