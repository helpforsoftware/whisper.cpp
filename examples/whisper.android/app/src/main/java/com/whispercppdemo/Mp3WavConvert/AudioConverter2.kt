package com.whispercppdemo.Mp3WavConvert

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.media.MediaCodec.BufferInfo
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import io.github.nailik.androidresampler.Resampler
import io.github.nailik.androidresampler.ResamplerConfiguration
import io.github.nailik.androidresampler.data.ResamplerChannel
import io.github.nailik.androidresampler.data.ResamplerQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*


class AudioConverter {

    companion object {
        private const val TAG = "AudioConverter"
        private const val TIMEOUT_USEC = 10000L
    }

    suspend fun convertMp3ToWav(
        context: Context,
        inputMp3Uri: Uri,

    ): File? = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        // outputStream is managed by .use{} block, no need for top-level var for closing
        // var outputStream: OutputStream? = null // Removed as it's managed by .use{}

        try {
            // Setup MediaExtractor with URI
            extractor = MediaExtractor()
            extractor.setDataSource(context, inputMp3Uri, null)

            // Find audio track
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                Log.e(TAG, "No audio track found in MP3 file")
                return@withContext null
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)

            // Setup MediaCodec decoder
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val outputFormat = decoder.outputFormat
            Log.d(TAG, "Decoder output format: $outputFormat")


            val outputFile = File(context.getExternalFilesDir(null), "outputcsk.wav")

            // Get audio format info
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val bitDepth = 16 // PCM 16-bit

            Log.d(TAG, "Audio format - Sample Rate: $sampleRate, Channels: $channelCount")

            val pcmData: ByteArray // Declare pcmData here to be accessible later



            FileOutputStream(outputFile).use { outputStream ->
                val wavHeader = createWavHeader(sampleRate, channelCount, bitDepth)
                outputStream.write(wavHeader)

                pcmData = decodeToPcm(extractor, decoder)
                outputStream.write(pcmData)





            }
            updateWavHeaderForFile(outputFile, sampleRate, channelCount, bitDepth, pcmData.size)
            val inputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)


            updateWaveToResample(outputFile,inputSampleRate,channelCount)

            // IMPORTANT: Update the WAV header with the actual sizes AFTER all data is written and stream is closed
            return@withContext outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error converting MP3 to WAV", e)
            null
        } finally {
            extractor?.release()
            decoder?.stop()
            decoder?.release()
            // outputStream is closed by .use{} or handled by updateWavHeaderForUri
        }
    }
    private fun updateWaveToResample(file: File, inputSampleRate: Int, inputChannels: Int)
    {
        val configuration = ResamplerConfiguration(
            quality = ResamplerQuality.BEST,
            inputChannel  = if (inputChannels == 1) ResamplerChannel.MONO else ResamplerChannel.STEREO,
            inputSampleRate = inputSampleRate,
            outputChannel = ResamplerChannel.MONO,
            outputSampleRate = 16000
        )
        val resampler = Resampler(configuration)
        val inputData = file.readBytes()
        val outputData = resampler.resample(inputData)
        //file is not playable here
        file.writeBytes(outputData)
        resampler.dispose()
        val header = createWavHeaderWithSizes(
            sampleRate = 16000,
            channels = 1,
            bitsPerSample = 16,
            dataSize = outputData.size
        )
        FileOutputStream(file, false).use { out ->
            out.write(header)
            out.write(outputData)
        }
    }
private fun updateWavHeaderForFile(
    file: File,
    sampleRate: Int,
    channels: Int,
    bitsPerSample: Int,
    dataSize: Int
) {
    try {
        val fullData = file.readBytes()


        if (fullData.size < 44) {
            Log.e(TAG, "WAV data invalid or too short for header update")
            return
        }

        val correctedHeader = createWavHeaderWithSizes(sampleRate, channels, bitsPerSample, dataSize)

        FileOutputStream(file, false).use { out ->
            out.write(correctedHeader)
            out.write(fullData.copyOfRange(44, fullData.size))
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to update WAV header", e)
    }
}

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }

    private fun decodeToPcm(extractor: MediaExtractor, decoder: MediaCodec): ByteArray {
      //  val inputBuffers = decoder.inputBuffers
      //  val outputBuffers = decoder.outputBuffers // Note: Deprecated, use getOutputBuffer(int)
        val bufferInfo = BufferInfo()
        val pcmData = ByteArrayOutputStream()

        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            // Feed input to decoder
            if (!inputDone) {
                val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)

                    val sampleSize = extractor.readSampleData(inputBuffer!!, 0)

                    if (sampleSize < 0) {
                        // End of stream
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val presentationTime = extractor.sampleTime
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Get output from decoder
            val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No output available yet
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // Output buffers changed, get new ones (deprecated in newer APIs)
                    // outputBuffers = decoder.outputBuffers // Re-fetch if needed for older APIs
                    Log.d(TAG, "MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED")
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = decoder.outputFormat
                    Log.d(TAG, "Output format changed: $newFormat")
                }
                outputBufferIndex >= 0 -> {
                    val outputBuffer = decoder.getOutputBuffer(outputBufferIndex) // Use getOutputBuffer

                    outputBuffer?.let {
                        if (bufferInfo.size > 0) {
                            val pcmChunk = ByteArray(bufferInfo.size)
                            it.position(bufferInfo.offset)
                            it.limit(bufferInfo.offset + bufferInfo.size) // Set limit for reading
                            it.get(pcmChunk, 0, bufferInfo.size)
                            pcmData.write(pcmChunk)
                        }
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
        }

        return pcmData.toByteArray()
    }

    private fun createWavHeader(sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val header = ByteArray(44)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        // RIFF header
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()

        // File size (will be updated later)
        writeInt(header, 4, 0) // Placeholder for ChunkSize

        // WAVE header
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()

        // fmt subchunk
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()

        writeInt(header, 16, 16) // Subchunk1Size (16 for PCM)
        writeShort(header, 20, 1) // AudioFormat (1 for PCM)
        writeShort(header, 22, channels) // NumChannels
        writeInt(header, 24, sampleRate) // SampleRate
        writeInt(header, 28, byteRate) // ByteRate
        writeShort(header, 32, blockAlign) // BlockAlign
        writeShort(header, 34, bitsPerSample) // BitsPerSample

        // data subchunk
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()

        // Data size (will be updated later)
        writeInt(header, 40, 0) // Placeholder for Subchunk2Size

        return header
    }

    private fun updateWavHeaderForUri(
        context: Context,
        uri: Uri,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataSize: Int
    ) {
        try {
            // Read the entire file content
            val fullData = context.contentResolver.openInputStream(uri)?.readBytes()
            if (fullData == null || fullData.size < 44) {
                Log.e(TAG, "WAV data invalid or too short for header update")
                return
            }

            // Create the corrected header with actual sizes
            val correctedHeader = createWavHeaderWithSizes(sampleRate, channels, bitsPerSample, dataSize)

            // Re-open the output stream in "rwt" (read/write/truncate) mode
            // This effectively truncates the file and allows writing from the beginning
            context.contentResolver.openOutputStream(uri, "rwt")?.use { out ->
                out.write(correctedHeader) // Write the corrected header
                out.write(fullData.copyOfRange(44, fullData.size)) // Write the rest of the audio data
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WAV header", e)
        }
    }


    private fun createWavHeaderWithSizes(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataSize: Int
    ): ByteArray {
        val header = ByteArray(44)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        // RIFF header
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()

        // File size (total file size - 8 bytes)
        writeInt(header, 4, dataSize + 36) // RIFF ChunkSize

        // WAVE header
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()

        // fmt subchunk
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()

        writeInt(header, 16, 16) // Subchunk1Size (16 for PCM)
        writeShort(header, 20, 1) // AudioFormat (1 for PCM)
        writeShort(header, 22, channels) // NumChannels
        writeInt(header, 24, sampleRate) // SampleRate
        writeInt(header, 28, byteRate) // ByteRate
        writeShort(header, 32, blockAlign) // BlockAlign
        writeShort(header, 34, bitsPerSample) // BitsPerSample

        // data subchunk
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()

        // Data size
        writeInt(header, 40, dataSize) // Subchunk2Size

        return header
    }

    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    // This function was not used in the original code, but kept for completeness
    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
}