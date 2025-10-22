package com.aima.bargein.demo

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

class AudioManager(private val context: Context) {

    fun prepareAudioFile(): File {
        val wavFile = File(context.cacheDir, "test_audio.wav")

        if (wavFile.exists()) {
            Timber.i("🗑️ Deleting existing WAV file...")
            wavFile.delete()
        }

        var copiedFromAssets = false
        try {
            Timber.i("📂 Attempting to copy test_audio.wav from assets...")

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
            Timber.i("✅ WAV file copied successfully from assets!")
            Timber.i("   Size: ${totalBytes / 1024}KB")

        } catch (e: java.io.FileNotFoundException) {
            Timber.w("⚠️ test_audio.wav NOT FOUND in assets")
            Timber.w("   Will generate synthetic audio at 44.1kHz")
        }

        if (!copiedFromAssets) {
            Timber.i("🔧 Generating synthetic WAV @ 44.1kHz...")
            WavGenerator.generateTestWav(wavFile, durationSeconds = 15)
        }

        return wavFile
    }
}