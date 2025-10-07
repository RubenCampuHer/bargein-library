package com.aima.bargein.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission  // ← AÑADIR ESTE IMPORT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class AudioCapture(
    private val sampleRate: Int = 16000,
    private val onAudioData: (ShortArray, Long) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val captureScope = CoroutineScope(Dispatchers.IO)

    private val frameSize = (sampleRate * FRAME_DURATION_MS / 1000)
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(frameSize * 4)

    @Volatile
    private var isCapturing = false

    companion object {
        private const val FRAME_DURATION_MS = 10
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)  // ← AÑADIR ESTA LÍNEA
    fun startCapture() {
        if (isCapturing) {
            Timber.w("Audio capture already running")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord initialization failed")
            }

            audioRecord?.startRecording()
            isCapturing = true

            Timber.i("Audio capture started: sampleRate=$sampleRate, bufferSize=$bufferSize")

            captureJob = captureScope.launch {
                captureLoop()
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to start audio capture")
            cleanup()
            throw e
        }
    }

    // ... resto del código sin cambios

    fun stopCapture() {
        if (!isCapturing) return

        isCapturing = false
        captureJob?.cancel()
        cleanup()

        Timber.i("Audio capture stopped")
    }

    private fun captureLoop() {
        val buffer = ShortArray(frameSize)

        while (isCapturing && captureScope.isActive) {
            try {
                val timestamp = System.nanoTime()
                val samplesRead = audioRecord?.read(buffer, 0, frameSize) ?: 0

                when {
                    samplesRead > 0 -> {
                        onAudioData(buffer.copyOf(samplesRead), timestamp)
                    }
                    samplesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Timber.e("AudioRecord error: INVALID_OPERATION")
                        break
                    }
                    samplesRead == AudioRecord.ERROR_BAD_VALUE -> {
                        Timber.e("AudioRecord error: BAD_VALUE")
                        break
                    }
                }

            } catch (e: Exception) {
                if (isCapturing) {
                    Timber.e(e, "Error in capture loop")
                }
                break
            }
        }
    }

    private fun cleanup() {
        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
            audioRecord = null
        } catch (e: Exception) {
            Timber.e(e, "Error cleaning up AudioRecord")
        }
    }

    fun isCapturing(): Boolean = isCapturing

    fun release() {
        stopCapture()
        captureScope.cancel()
    }
}