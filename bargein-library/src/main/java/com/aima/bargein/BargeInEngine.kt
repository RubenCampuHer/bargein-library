package com.aima.bargein

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.aima.bargein.audio.AudioCapture
import com.aima.bargein.audio.AudioPlayback
import com.aima.bargein.vad.AdaptiveEnergyVAD
import com.aima.bargein.vad.IVoiceActivityDetector
import kotlinx.coroutines.*
import timber.log.Timber
import kotlin.system.measureNanoTime

/**
 * 🎛️ BargeInEngine - Motor principal del sistema de interrupción por voz (44.1kHz)
 * ✅ Coordina captura + reproducción + detección VAD adaptativa
 */
class BargeInEngine(
    private val context: Context,
    private val listener: BargeInListener
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sampleRate = 44100
    private val frameSize = 512

    private var vad: AdaptiveEnergyVAD? = null
    private var audioCapture: AudioCapture? = null
    private var audioPlayback: AudioPlayback? = null

    private var isListening = false
    private var isPlaying = false
    private var bargeInTriggered = false
    private var firstVoiceFrameTimeNs: Long = 0L

    // ==========================================================
    // == INICIALIZACIÓN ==
    // ==========================================================
    fun initialize() {
        Timber.i("⚙️ Inicializando BargeInEngine a ${sampleRate}Hz...")

        vad = AdaptiveEnergyVAD(sampleRate)
        vad?.initialize(sampleRate, IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE)

        audioPlayback = AudioPlayback(scope)

        audioCapture = AudioCapture(sampleRate, frameSize) { samples, _ ->
            processAudioFrame(samples)
        }

        Timber.i("✅ BargeInEngine inicializado correctamente")
    }

    fun release() {
        Timber.i("🧹 Liberando recursos de BargeInEngine...")
        stop()
        audioPlayback?.release()
        audioCapture?.release()
        vad?.release()
        scope.cancel()
        Timber.i("✅ BargeInEngine liberado")
    }

    // ==========================================================
    // == CONTROL DE ESCUCHA ==
    // ==========================================================
    fun startListening() {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError(
                BargeInError(ErrorCode.PERMISSION_DENIED, "Missing RECORD_AUDIO permission")
            )
            return
        }

        if (isListening) {
            Timber.w("⚠️ Already listening")
            return
        }

        isListening = true
        bargeInTriggered = false
        firstVoiceFrameTimeNs = 0L

        Timber.i("🎤 Starting microphone capture...")
        try {
            audioCapture?.startCapture()
            listener.onStateChanged(BargeInState.LISTENING)
            Timber.i("✅ Listening started")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to start capture")
            isListening = false
            listener.onError(
                BargeInError(ErrorCode.AUDIO_CAPTURE_FAILED, "Failed to start capture: ${e.message}", e)
            )
        }
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        audioCapture?.stopCapture()
        listener.onStateChanged(BargeInState.STOPPED)
        Timber.i("🛑 Listening stopped")
    }

    // ==========================================================
    // == REPRODUCCIÓN ==
    // ==========================================================
    fun playAudio(pcmData: ShortArray) {
        if (isPlaying) {
            Timber.w("⚠️ Already playing - stopping previous instance")
            audioPlayback?.stop()
        }

        isPlaying = true
        bargeInTriggered = false
        firstVoiceFrameTimeNs = 0L

        vad?.setPlaybackActive(true)
        Timber.i("🎵 Starting audio playback... (${pcmData.size / sampleRate.toFloat()}s)")

        scope.launch {
            try {
                audioPlayback?.playPcmData(pcmData)
                val durationMs = (pcmData.size / sampleRate.toFloat()) * 1000
                delay(durationMs.toLong() + 500)
                if (isPlaying) onPlaybackEnded()
            } catch (e: Exception) {
                Timber.e(e, "❌ Error during playback")
                isPlaying = false
                vad?.setPlaybackActive(false)
            }
        }
    }

    private fun onPlaybackEnded() {
        Timber.i("✅ Playback completed normally")
        isPlaying = false
        vad?.setPlaybackActive(false)
        listener.onPlaybackEnded()
    }

    fun stop() {
        Timber.i("⏹️ Stopping all activity")
        isPlaying = false
        isListening = false
        audioPlayback?.stop()
        audioCapture?.stopCapture()
        vad?.setPlaybackActive(false)
        listener.onStateChanged(BargeInState.STOPPED)
    }

    // ==========================================================
    // == PROCESAMIENTO DE AUDIO ==
    // ==========================================================
    private fun processAudioFrame(samples: ShortArray) {
        if (!isListening || !isPlaying) return

        val localVad = vad ?: return
        val result: IVoiceActivityDetector.VadResult

        val processingNs = measureNanoTime {
            result = localVad.processFrame(samples, samples.size)
        }

        if (result.hasVoice && !bargeInTriggered) {
            if (firstVoiceFrameTimeNs == 0L) {
                firstVoiceFrameTimeNs = System.nanoTime()
                Timber.d("🗣️ Voice activity detected! energy=${result.energyDb}dB conf=${result.confidence}")
            }

            if (result.confidence >= 0.6f) {
                triggerBargeIn(result)
            }
        }

        // Log puntual de rendimiento
        if ((System.currentTimeMillis() % 2000L) < 10L) {
            Timber.v("⏱️ VAD time: ${processingNs / 1000}µs")
        }
    }

    private fun triggerBargeIn(result: IVoiceActivityDetector.VadResult) {
        if (bargeInTriggered) return

        bargeInTriggered = true
        val now = System.nanoTime()
        val latencyMs = (now - firstVoiceFrameTimeNs) / 1_000_000f

        Timber.i("🚨 BARGE-IN DETECTED! latency=${String.format("%.1f", latencyMs)}ms energy=${result.energyDb}dB")

        stopPlaybackImmediate()

        val event = BargeInEvent(
            detectionTimestamp = firstVoiceFrameTimeNs,
            stopTimestamp = now,
            latencyMs = latencyMs,
            confidence = result.confidence,
            energyDb = result.energyDb
        )
        listener.onUserInterruption(event)
        listener.onStateChanged(BargeInState.INTERRUPTED)
    }

    private fun stopPlaybackImmediate() {
        Timber.i("⏹️ Stopping playback immediately")
        audioPlayback?.stop()
        isPlaying = false
        vad?.setPlaybackActive(false)
    }

    // ==========================================================
    // == TESTS Y DIAGNÓSTICO ==
    // ==========================================================
    fun playSweep() {
        if (isPlaying) return
        isPlaying = true
        vad?.setPlaybackActive(true)
        Timber.i("🎧 Playing sweep test (20Hz–20kHz)")
        scope.launch {
            audioPlayback?.playSweep()
            delay(8500)
            onPlaybackEnded()
        }
    }

    fun playPilotTone() {
        if (isPlaying) return
        isPlaying = true
        vad?.setPlaybackActive(true)
        Timber.i("📊 Playing 15kHz pilot tone")
        scope.launch {
            audioPlayback?.playPilotTone()
            delay(5500)
            onPlaybackEnded()
        }
    }

    fun getMetrics(): BargeInMetrics {
        return BargeInMetrics(
            sampleRate = sampleRate,
            frameSize = frameSize,
            isListening = isListening,
            isPlaying = isPlaying,
            vadMetrics = vad?.getMetrics()
        )
    }

    fun setSensitivityMode(mode: AdaptiveEnergyVAD.SensitivityMode) {
        (vad as? AdaptiveEnergyVAD)?.setSensitivityMode(mode)
    }
}

// ==========================================================
// == MÉTRICAS ==
// ==========================================================
data class BargeInMetrics(
    val sampleRate: Int,
    val frameSize: Int,
    val isListening: Boolean,
    val isPlaying: Boolean,
    val vadMetrics: IVoiceActivityDetector.VadMetrics?
)
