package com.aima.bargein.audio

import android.Manifest
import android.media.*
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import timber.log.Timber

class AudioCapture(
    private val sampleRate: Int = 24000,
    private val onAudioData: (ShortArray, Long) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val frameSize = (sampleRate * 10) / 1000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(frameSize * 4)

    @Volatile private var isCapturing = false

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture() {
        if (isCapturing) return
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            check(audioRecord?.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }

            audioRecord?.startRecording()
            isCapturing = true
            Timber.i("🎤 Audio capture started (sr=$sampleRate, buf=$bufferSize)")

            captureJob = scope.launch { captureLoop() }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start audio capture")
            cleanup()
            throw e
        }
    }

    private fun captureLoop() {
        val buffer = ShortArray(frameSize)
        while (isCapturing && scope.isActive) {
            val timestamp = System.nanoTime()
            val read = audioRecord?.read(buffer, 0, frameSize, AudioRecord.READ_BLOCKING) ?: 0
            if (read > 0) onAudioData(buffer.copyOf(read), timestamp)
            else if (read < 0) Timber.w("AudioRecord read error: $read")
        }
    }

    fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        captureJob?.cancel()
        cleanup()
        Timber.i("🎧 Audio capture stopped")
    }

    private fun cleanup() {
        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            }
        } catch (e: Exception) {
            Timber.e(e, "cleanup error")
        } finally { audioRecord = null }
    }

    fun release() { stopCapture(); scope.cancel() }
}
