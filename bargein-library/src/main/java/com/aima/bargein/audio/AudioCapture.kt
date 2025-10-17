package com.aima.bargein.audio

import android.Manifest
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import timber.log.Timber
import kotlin.math.absoluteValue

class AudioCapture(
    private val sampleRate: Int = 44100, // ✅ 44.1kHz para análisis espectral extendido
    private val onAudioData: (ShortArray, Long) -> Unit
) {
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
            Timber.w("⚠️ Already capturing")
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
                    Timber.w("⚠️ AEC explícito disponible pero DESACTIVADO")
                    Timber.w("   Motivo: Cancela voz real del usuario")
                    Timber.i("   Usando solo VOICE_COMMUNICATION (AEC hardware)")
                } else {
                    Timber.w("⚠️ AEC no disponible - usando solo VOICE_COMMUNICATION")
                }
            } catch (e: Exception) {
                Timber.w(e, "⚠️ No se pudo configurar AEC")
            }

            audioRecord?.startRecording()
            isCapturing = true

            Timber.i("🎤 Audio capture started")
            Timber.i("   Sample rate: ${sampleRate}Hz")
            Timber.i("   Frame size: $frameSize samples (~11.6ms)")
            Timber.i("   Buffer size: $bufferSize bytes")
            Timber.i("   AEC: ${if (aec?.enabled == true) "✅ Explícito" else "⚠️ Solo hardware"}")

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
                        Timber.v("📊 Captured $frameCount frames (${frameCount * 11.6f / 1000f}s)")
                        lastLogTime = currentTime
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
            Timber.e(e, "❌ Cleanup error")
        } finally {
            audioRecord = null
        }
    }

    fun release() {
        stopCapture()
        scope.cancel()

        if (totalFrames > 0) {
            val suppressionRate = (echoSuppressedFrames * 100f / totalFrames)
            Timber.i("📊 Echo suppression stats: ${echoSuppressedFrames}/${totalFrames} frames (${String.format("%.1f%%", suppressionRate)})")
        }

        Timber.d("🔧 AudioCapture released")
    }
}