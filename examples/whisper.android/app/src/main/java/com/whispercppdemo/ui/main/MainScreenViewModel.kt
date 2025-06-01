package com.whispercppdemo.ui.main

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercppdemo.Mp3WavConvert.AudioConverterHelper
import com.whispercppdemo.media.decodeWaveFile
import com.whispercppdemo.recorder.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import android.webkit.MimeTypeMap
import com.whispercppdemo.Mp3WavConvert.AudioConverter

fun Context.getFileExtensionFromUri(uri: Uri): String? {
    // Try to get file extension from Uri path first
    val pathExtension = uri.path?.substringAfterLast('.', "")
    if (!pathExtension.isNullOrEmpty()) {
        return pathExtension
    }

    // Otherwise, try to get MIME type from ContentResolver
    val mimeType = contentResolver.getType(uri) ?: return null

    // Get extension from MIME type using MimeTypeMap
    return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
}


private const val LOG_TAG = "MainScreenViewModel"

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var canTranscribe by mutableStateOf(false)
        private set
    var dataLog by mutableStateOf("")
        private set
    var isRecording by mutableStateOf(false)
        private set

    private val modelsPath = File(application.filesDir, "models")
   private val documentsDir = File(application.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "models")

    private val samplesPath = File(application.filesDir, "samples")
    private var recorder: Recorder = Recorder()
    private var whisperContext: com.whispercpp.whisper.WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null
    private var recordedFile: File? = null
    sealed class UiEvent {
        object OpenBinFilePicker : UiEvent()
    }

    val uiEvent = mutableStateOf<UiEvent?>(null)

    fun triggerBinFilePicker() {
        uiEvent.value = UiEvent.OpenBinFilePicker
    }
    init {
        viewModelScope.launch {
            printSystemInfo()

           // loadData()
        }
    }

    private suspend fun printSystemInfo() {
        printMessage(String.format("System Info: %s\n", com.whispercpp.whisper.WhisperContext.getSystemInfo()))
    }

     suspend fun loadBinModelFromUri(context: Context, uri: Uri) {
        printMessage("Loading data...\n")
        try {
            copyAssets()

            loadBaseModel(context,uri)
            canTranscribe = true
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }
    }

    private suspend fun printMessage(msg: String) = withContext(Dispatchers.Main) {
        dataLog += msg
    }

    private suspend fun copyAssets() = withContext(Dispatchers.IO) {
       // modelsPath.mkdirs()
        documentsDir.mkdirs() // E
        samplesPath.mkdirs()
        application.copyData("models", documentsDir, ::printMessage)
        application.copyData("samples", samplesPath, ::printMessage)
        printMessage("All data copied to working directory.\n")
    }
    suspend private fun convertSelectedAudio(context:Context,uri: Uri)  =  withContext(Dispatchers.IO) {
      //  val outputPath = "${getExternalFilesDir(Environment.DIRECTORY_MUSIC)}/output.wav"
       // val inputUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic%2Finput.mp3")
        val outputUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic%2Foutputcsk.wav")
        val audioConverter = AudioConverter()
        val success = audioConverter.convertMp3ToWav(context, uri)



        if (success) {
            printMessage ("Conversion successful!")
            return@withContext outputUri.toFile()
            // Handle success
        } else {
            printMessage ("Conversion failed!")
            // Handle error
        }


           /* val result = AudioConverterHelper(context).convertAudioFile(
                inputUri = uri,
                outputFileName = "converted_audio.wav",
                targetSampleRate = 44100
            ) { progress ->

                // runOnUiThread {
                // Update UI with progress
               // withContext(Dispatchers.IO) {

                viewModelScope.launch(Dispatchers.Main) {
                    printMessage(progress)
                }
              //  }


            }

            result?.let {
                // Conversion successful
                return@withContext it.absolutePath
            }
*/
        }

    private suspend fun loadBaseModel(context:Context ,uri: Uri) = withContext(Dispatchers.IO) {
        try {
            printMessage("Loading model from selected URI...\n")

            // Copy the .bin file from content URI to internal storage
            val fileName = "imported_model.bin" // or extract from URI if needed
            val destFile = File(application.filesDir, fileName)

            application.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                printMessage("Failed to open input stream for URI.\n")

            }
            val extension = context.getFileExtensionFromUri(uri)

           if (extension!="bin") {
              transcribeSample(context, uri)
               return@withContext
            }
            whisperContext = com.whispercpp.whisper.WhisperContext.createContext(destFile.absolutePath)
            printMessage("Loaded model from URI: ${destFile.name}\n")
            canTranscribe = true

        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("Failed to load model from URI: ${e.localizedMessage}\n")
        }
    }



    fun benchmark() = viewModelScope.launch {
        runBenchmark(6)
    }

    fun transcribeSample(context: Context, uri: Uri)  = viewModelScope.launch {
        val extension = context.getFileExtensionFromUri(uri)

        if (extension!="bin") {
            convertSelectedAudio(context,uri).let { uri }.let { transcribeAudio(uri) }
            return@launch
        }

        else
        transcribeAudio(uri)
    }

    private suspend fun runBenchmark(nthreads: Int) {
        if (!canTranscribe) {
            return
        }

        canTranscribe = false

        printMessage("Running benchmark. This will take minutes...\n")
        whisperContext?.benchMemory(nthreads)?.let{ printMessage(it) }
        printMessage("\n")
        whisperContext?.benchGgmlMulMat(nthreads)?.let{ printMessage(it) }

        canTranscribe = true
    }

    private suspend fun getFirstSample(): File = withContext(Dispatchers.IO) {
        samplesPath.listFiles()!!.first()
    }

    private suspend fun readAudioSamples(file: File): FloatArray = withContext(Dispatchers.IO) {
        stopPlayback()
        startPlayback(file)
        return@withContext decodeWaveFile(file)
    }

    private suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private suspend fun startPlayback(file: File) = withContext(Dispatchers.Main) {
        mediaPlayer = MediaPlayer.create(application, file.absolutePath.toUri())
        mediaPlayer?.start()
    }

    private suspend fun transcribeAudio(uri: Uri) {
        if (!canTranscribe) {
            return
        }

        canTranscribe = false




            printMessage("Loading model from selected URI...\n")

            // Copy the .bin file from content URI to internal storage
            val fileName = "imported.wav" // or extract from URI if needed
            val destFile = File(application.filesDir, fileName)

            application.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                printMessage("Failed to open input stream for URI.\n")

            }









        try {
            printMessage("Reading wave samples... ")
            val data = readAudioSamples(destFile)
            printMessage("${data.size / (16000 / 1000)} ms\n")
            printMessage("Transcribing data...\n")
            val start = System.currentTimeMillis()
            val text = whisperContext?.transcribeData(data)
            val elapsed = System.currentTimeMillis() - start
            printMessage("Done ($elapsed ms): \n$text\n")
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }

        canTranscribe = true
    }

    fun toggleRecord() = viewModelScope.launch {
        try {
            if (isRecording) {
                recorder.stopRecording()
                isRecording = false
                recordedFile?.let { transcribeAudio(it.toUri()) }
            } else {
                stopPlayback()
                val file = getTempFileForRecording()
                recorder.startRecording(file) { e ->
                    viewModelScope.launch {
                        withContext(Dispatchers.Main) {
                            printMessage("${e.localizedMessage}\n")
                            isRecording = false
                        }
                    }
                }
                isRecording = true
                recordedFile = file
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
            isRecording = false
        }
    }

    private suspend fun getTempFileForRecording() = withContext(Dispatchers.IO) {
        File.createTempFile("recording", "wav")
    }

    override fun onCleared() {
        runBlocking {
            whisperContext?.release()
            whisperContext = null
            stopPlayback()
        }
    }

    fun triggerBinFilePickerTranscribeSample() {
        uiEvent.value = UiEvent.OpenBinFilePicker
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(application)
            }
        }
    }
}

private suspend fun Context.copyData(
    assetDirName: String,
    destDir: File,
    printMessage: suspend (String) -> Unit
) = withContext(Dispatchers.IO) {
    assets.list(assetDirName)?.forEach { name ->
        val assetPath = "$assetDirName/$name"
        Log.v(LOG_TAG, "Processing $assetPath...")
        val destination = File(destDir, name)
        Log.v(LOG_TAG, "Copying $assetPath to $destination...")
        printMessage("Copying $name...\n")
        assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Log.v(LOG_TAG, "Copied $assetPath to $destination")
    }
}