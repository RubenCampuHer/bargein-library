package com.aima.bargein.audio

import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import kotlinx.coroutines.*
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 🎙️ AudioCapture - Captura audio PCM16 a 44.1kHz con soporte para AEC ON/OFF
 * ✅ Permite conmutar dinámicamente entre:
 *    - VOICE_COMMUNICATION (AEC activado)
 *    - MIC (AEC desactivado)
 */
class AudioCapture(
    private val sampleRate: Int = 44100,
    private val frameSize: Int = 512,
    private val onAudioData: (samples: ShortArray, length: Int) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var isCapturing = false
    @Volatile var aecEnabled = true

    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(frameSize * 4)

    fun startCapture() {
        stopCapture() // reinicia si ya estaba activa

        val source = if (aecEnabled)
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else
            MediaRecorder.AudioSource.MIC

        Timber.i("🎤 Audio capture initializing (${if (aecEnabled) "AEC ON" else "AEC OFF"})")
        Timber.i("   Sample rate: ${sampleRate}Hz")
        Timber.i("   Frame size: $frameSize samples (~${frameSize / sampleRate.toFloat() * 1000}ms)")
        Timber.i("   Buffer size: $bufferSize bytes")

        try {
            audioRecord = AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (aecEnabled) {
                val sessionId = audioRecord?.audioSessionId ?: 0
                Timber.i("   Audio session: $sessionId")
                if (AcousticEchoCanceler.isAvailable()) {
                    val aec = AcousticEchoCanceler.create(sessionId)
                    aec?.enabled = true
                    Timber.i("   ✅ AcousticEchoCanceler enabled")
                } else {
                    Timber.w("   ⚠️ AEC not available on this device")
                }
            }

            audioRecord?.startRecording()
            isCapturing = true
            Timber.i("✅ Audio capture started successfully")

            captureJob = scope.launch {
                val buffer = ShortArray(frameSize)
                var totalFrames = 0
                while (isCapturing && isActive) {
                    val read = audioRecord?.read(buffer, 0, frameSize) ?: 0
                    if (read > 0) {
                        totalFrames++
                        onAudioData(buffer.copyOf(read), read)
                        if (totalFrames % 172 == 0) {
                            Timber.v("📊 Captured $totalFrames frames (${totalFrames * frameSize} samples)")
                        }
                    }
                }
                Timber.d("🛑 Capture loop ended (total frames=$totalFrames)")
            }
        } catch (e: SecurityException) {
            Timber.e(e, "❌ Permission denied for microphone")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "❌ Error initializing AudioRecord")
            stopCapture()
        }
    }

    fun stopCapture() {
        Timber.i("🛑 Stopping audio capture...")
        isCapturing = false
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.apply {
                stop()
                release()
            }
            audioRecord = null
            Timber.i("✅ Audio capture stopped")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error stopping AudioRecord")
        }
    }

    fun toggleAEC() {
        aecEnabled = !aecEnabled
        Timber.i("🔁 AEC toggled: ${if (aecEnabled) "ON (VOICE_COMMUNICATION)" else "OFF (MIC)"}")
        if (isCapturing) {
            stopCapture()
            startCapture()
        }
    }

    fun release() {
        stopCapture()
        scope.cancel()
    }
}
