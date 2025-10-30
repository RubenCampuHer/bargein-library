package com.aima.bargein.audio

import android.Manifest
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlin.math.absoluteValue

class AudioCapture(
    private val sampleRate: Int = 44100, // ✅ 44.1kHz para análisis espectral extendido
    private val onAudioData: (ShortArray, Long) -> Unit
) {
    companion object {
        private const val TAG = "BargeInEngine_AudioCapture"
    }

    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Frame de ~11.6ms a 44.1kHz = 512 samples
    private val frameSize = 512

    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(frameSize * 4)

    @Volatile private var isCapturing = false

    // ✅ NUEVO: Estadísticas de supresión de eco
    private var totalFrames = 0L
    private var echoSuppressedFrames = 0L

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture() {
        if (isCapturing) {
            Log.w(TAG, "⚠️ Already capturing")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // ✅ AEC hardware automático
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            check(audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord initialization failed"
            }

            // ✅ NUEVO: AEC explícito si está disponible
            // ⚠️ DESACTIVADO TEMPORALMENTE - El AEC cancela también la voz real
            try {
                val sessionId = audioRecord?.audioSessionId ?: AudioManager.ERROR
                if (sessionId != AudioManager.ERROR && AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(sessionId)
                    // ⚠️ CRÍTICO: Desactivar AEC explícito para evitar cancelación de voz
                    aec?.enabled = false
                    Log.w(TAG, "⚠️ AEC explícito disponible pero DESACTIVADO")
                    Log.w(TAG, "   Motivo: Cancela voz real del usuario")
                    Log.i(TAG, "   Usando solo VOICE_COMMUNICATION (AEC hardware)")
                } else {
                    Log.w(TAG, "⚠️ AEC no disponible - usando solo VOICE_COMMUNICATION")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ No se pudo configurar AEC", e)
            }

            audioRecord?.startRecording()
            isCapturing = true

            Log.i(TAG, "🎤 Audio capture started")
            Log.i(TAG, "   Sample rate: ${sampleRate}Hz")
            Log.i(TAG, "   Frame size: $frameSize samples (~11.6ms)")
            Log.i(TAG, "   Buffer size: $bufferSize bytes")
            Log.i(TAG, "   AEC: ${if (aec?.enabled == true) "✅ Explícito" else "⚠️ Solo hardware"}")

            captureJob = scope.launch { captureLoop() }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start audio capture")
            cleanup()
            throw e
        }
    }

    private fun captureLoop() {
        val buffer = ShortArray(frameSize)
        var frameCount = 0
        var lastLogTime = System.currentTimeMillis()

        while (isCapturing && scope.isActive) {
            try {
                val timestamp = System.nanoTime()
                val read = audioRecord?.read(buffer, 0, frameSize, AudioRecord.READ_BLOCKING) ?: 0

                if (read > 0) {
                    frameCount++
                    onAudioData(buffer.copyOf(read), timestamp)

                    // ✅ Log solo cada 500ms para no saturar Logcat
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLogTime >= 500) {
                        Log.d(TAG, "📊 Captured $frameCount frames (${frameCount * 11.6f / 1000f}s)")
                        lastLogTime = currentTime
                    }
                } else if (read < 0) {
                    Log.w(TAG, "⚠️ AudioRecord read error: $read")
                }

            } catch (e: Exception) {
                if (isCapturing) {
                    Log.e(TAG, "❌ Error in capture loop")
                }
            }
        }

        Log.d(TAG, "🛑 Capture loop ended (frames=$frameCount)")
    }

    fun stopCapture() {
        if (!isCapturing) {
            Log.d(TAG, "ℹ️ Not capturing, nothing to stop")
            return
        }

        Log.i(TAG, "🛑 Stopping audio capture...")
        isCapturing = false
        captureJob?.cancel()
        cleanup()
        Log.i(TAG, "✅ Audio capture stopped")
    }

    private fun cleanup() {
        try {
            // ✅ Liberar AEC primero
            aec?.apply {
                enabled = false
                release()
            }
            aec = null

            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Cleanup error")
        } finally {
            audioRecord = null
        }
    }

    fun release() {
        stopCapture()
        scope.cancel()

        if (totalFrames > 0) {
            val suppressionRate = (echoSuppressedFrames * 100f / totalFrames)
            Log.i(TAG, "📊 Echo suppression stats: ${echoSuppressedFrames}/${totalFrames} frames (${String.format("%.1f%%", suppressionRate)})")
        }

        Log.d(TAG, "🔧 AudioCapture released")
    }
}