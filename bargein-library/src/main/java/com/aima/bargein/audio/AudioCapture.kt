package com.aima.bargein.audio

import android.Manifest
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import timber.log.Timber

class AudioCapture(
    private val sampleRate: Int = 16000, // ✅ CORREGIDO: 16kHz consistente
    private val onAudioData: (ShortArray, Long) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Frame de 10ms a 16kHz = 160 samples
    private val frameSize = (sampleRate * 10) / 1000

    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(frameSize * 4)

    // ✅ NUEVO: AEC y AGC incorporados
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null

    @Volatile private var isCapturing = false

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture() {
        if (isCapturing) {
            Timber.w("⚠️ Already capturing")
            return
        }

        try {
            // ✅ CRÍTICO: Usar VOICE_COMMUNICATION para mejor AEC
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // ✅ Mejor AEC
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            check(audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord initialization failed"
            }

            val audioSessionId = audioRecord!!.audioSessionId

            // ✅ Habilitar AEC nativo si está disponible
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)
                    acousticEchoCanceler?.enabled = true
                    Timber.i("✅ AcousticEchoCanceler enabled (session=$audioSessionId)")
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ Failed to enable AEC")
                }
            } else {
                Timber.w("⚠️ AEC not available on this device")
            }

            // ✅ Habilitar AGC para normalizar volumen
            if (AutomaticGainControl.isAvailable()) {
                try {
                    automaticGainControl = AutomaticGainControl.create(audioSessionId)
                    automaticGainControl?.enabled = true
                    Timber.i("✅ AutomaticGainControl enabled")
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ Failed to enable AGC")
                }
            }

            audioRecord?.startRecording()
            isCapturing = true

            Timber.i("🎤 Audio capture started")
            Timber.i("   Sample rate: ${sampleRate}Hz")
            Timber.i("   Frame size: $frameSize samples (10ms)")
            Timber.i("   Buffer size: $bufferSize bytes")
            Timber.i("   Audio session: $audioSessionId")

            captureJob = scope.launch { captureLoop() }

        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to start audio capture")
            cleanup()
            throw e
        }
    }

    private fun captureLoop() {
        val buffer = ShortArray(frameSize)
        var frameCount = 0

        while (isCapturing && scope.isActive) {
            try {
                val timestamp = System.nanoTime()
                val read = audioRecord?.read(buffer, 0, frameSize, AudioRecord.READ_BLOCKING) ?: 0

                if (read > 0) {
                    frameCount++
                    onAudioData(buffer.copyOf(read), timestamp)

                    // Log cada 500 frames (5 segundos a 10ms/frame)
                    if (frameCount % 500 == 0) {
                        Timber.v("📊 Captured $frameCount frames")
                    }
                } else if (read < 0) {
                    Timber.w("⚠️ AudioRecord read error: $read")
                }

            } catch (e: Exception) {
                if (isCapturing) {
                    Timber.e(e, "❌ Error in capture loop")
                }
            }
        }

        Timber.d("🛑 Capture loop ended (frames=$frameCount)")
    }

    fun stopCapture() {
        if (!isCapturing) {
            Timber.d("ℹ️ Not capturing, nothing to stop")
            return
        }

        Timber.i("🛑 Stopping audio capture...")
        isCapturing = false
        captureJob?.cancel()
        cleanup()
        Timber.i("✅ Audio capture stopped")
    }

    private fun cleanup() {
        try {
            // Liberar AEC y AGC
            acousticEchoCanceler?.release()
            acousticEchoCanceler = null

            automaticGainControl?.release()
            automaticGainControl = null

            // Liberar AudioRecord
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Cleanup error")
        } finally {
            audioRecord = null
        }
    }

    fun release() {
        stopCapture()
        scope.cancel()
        Timber.d("🔧 AudioCapture released")
    }
}