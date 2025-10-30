package com.aima.bargein.demo

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class AudioManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioManager"
    }

    fun prepareAudioFile(): File {
        val wavFile = File(context.cacheDir, "test_audio.wav")

        if (wavFile.exists()) {
            Log.i(TAG, "🗑️ Deleting existing WAV file...")
            wavFile.delete()
        }

        var copiedFromAssets = false
        try {
            Log.i(TAG, "📂 Attempting to copy test_audio.wav from assets...")

            val inputStream = context.assets.open("test_audio.wav")
            val outputStream = FileOutputStream(wavFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytes = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytes += bytesRead
            }

            inputStream.close()
            outputStream.flush()
            outputStream.close()

            copiedFromAssets = true
            Log.i(TAG, "✅ WAV file copied successfully from assets!")
            Log.i(TAG, "   Size: ${totalBytes / 1024}KB")

        } catch (e: java.io.FileNotFoundException) {
            Log.w(TAG, "⚠️ test_audio.wav NOT FOUND in assets")
            Log.w(TAG, "   Will generate synthetic audio at 44.1kHz")
        }

        if (!copiedFromAssets) {
            Log.i(TAG, "🔧 Generating synthetic WAV @ 44.1kHz...")
            WavGenerator.generateTestWav(wavFile, durationSeconds = 15)
        }

        return wavFile
    }
}