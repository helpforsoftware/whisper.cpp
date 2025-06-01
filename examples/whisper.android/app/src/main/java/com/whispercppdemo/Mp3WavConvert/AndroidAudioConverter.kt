import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Log
import java.io.*

object AudioConverter {
    private const val TAG = "AudioConverter"

    fun convertToMono16BitPCM(context: Context, inputUri: Uri): File {
        val outputFile = File(context.cacheDir, "output.wav")

        val extractor = MediaExtractor()
        val inputStream = context.contentResolver.openInputStream(inputUri)
            ?: throw IOException("Cannot open input stream")
        val inputFile = File(context.cacheDir, "temp_input")
        inputStream.use { input ->
            FileOutputStream(inputFile).use { output ->
                input.copyTo(output)
            }
        }

        extractor.setDataSource(inputFile.absolutePath)

        // Select the first audio track
        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                break
            }
        }

        if (trackIndex == -1) throw IOException("No audio track found")

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val outputStream = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val monoSampleRate = 16000
        val channelCount = 1 // target mono

        var sawOutputEOS = false
        var sawInputEOS = false

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inputBufferIndex = codec.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputBufferIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                val pcmData = ByteArray(bufferInfo.size)
                outputBuffer.get(pcmData)
                outputBuffer.clear()

                // Convert stereo to mono if needed
                val monoData = toMono16Bit(pcmData, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                outputStream.write(monoData)

                codec.releaseOutputBuffer(outputBufferIndex, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    sawOutputEOS = true
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        // Write WAV header + data
        val finalData = outputStream.toByteArray()
        writeWavFile(outputFile, finalData, monoSampleRate, channelCount)

        return outputFile
    }

    private fun toMono16Bit(stereoData: ByteArray, numChannels: Int): ByteArray {
        if (numChannels == 1) return stereoData

        val mono = ByteArray(stereoData.size / numChannels)
        for (i in mono.indices step 2) {
            val left = ((stereoData[i * 2 + 1].toInt() shl 8) or (stereoData[i * 2].toInt() and 0xff))
            val right = ((stereoData[i * 2 + 3].toInt() shl 8) or (stereoData[i * 2 + 2].toInt() and 0xff))
            val mixed = ((left + right) / 2).toShort()
            mono[i] = (mixed.toInt() and 0xff).toByte()
            mono[i + 1] = ((mixed.toInt() shr 8) and 0xff).toByte()
        }
        return mono
    }

    private fun writeWavFile(outputFile: File, audioData: ByteArray, sampleRate: Int, channels: Int) {
        val totalDataLen = 36 + audioData.size
        val byteRate = sampleRate * channels * 2

        val header = ByteArray(44)
        val audioDataLen = audioData.size

        // RIFF/WAVE header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeInt(header, 4, totalDataLen)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeInt(header, 16, 16)
        writeShort(header, 20, 1) // PCM format
        writeShort(header, 22, channels.toShort())
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, (channels * 2).toShort())
        writeShort(header, 34, 16)
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeInt(header, 40, audioDataLen)

        FileOutputStream(outputFile).use {
            it.write(header)
            it.write(audioData)
        }
    }

    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xff).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xff).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Short) {
        buffer[offset] = (value.toInt() and 0xff).toByte()
        buffer[offset + 1] = ((value.toInt() shr 8) and 0xff).toByte()
    }
}
