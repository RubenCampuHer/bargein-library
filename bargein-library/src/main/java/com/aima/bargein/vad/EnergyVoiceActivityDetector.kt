package com.aima.bargein.vad

import com.aima.bargein.audio.AudioFilter
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * EnergyVoiceActivityDetector
 * VAD simple basado en energía RMS.
 * Adaptado a 44.1 kHz y frames de 512 samples.
 * Se conserva como fallback o modo de compatibilidad.
 */
class EnergyVoiceActivityDetector : IVoiceActivityDetector {

    private val framesProcessed = AtomicLong(0)
    private val voiceFrames = AtomicLong(0)
    private val rejectedFrames = AtomicLong(0)
    private val totalProcessingTimeUs = AtomicLong(0)
    private var totalConfidence = 0.0

    @Volatile private var isActive = false
    @Volatile private var isPlaybackActive = false
    @Volatile private var shouldProcess = false

    // parámetros principales
    private var energyThresholdDb = -42f
    private val minEnergyDb = -60f
    private val minConsecutiveVoiceFrames = 3
    private val playbackSuppressionDb = 8f
    private val maxEnergyDuringPlaybackDb = -27f
    private val energyJumpThresholdDb = 15f

    private var lastEnergyDb = -60f
    private var consecutiveVoiceFrames = 0
    private var lastLogTime = 0L

    private val LOG_INTERVAL_MS = 500L
    private val sampleRate = 44100
    private val frameSize = 512

    override fun initialize(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): Boolean {
        energyThresholdDb = when (mode) {
            IVoiceActivityDetector.AggressivenessMode.QUALITY -> -48f
            IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE -> -42f
            IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE -> -46f
        }
        isActive = true
        shouldProcess = false
        Timber.i("✅ EnergyVAD inicializado a ${sampleRate}Hz (frame=$frameSize)")
        return true
    }

    override fun setPlaybackActive(active: Boolean) {
        isPlaybackActive = active
        shouldProcess = active
        consecutiveVoiceFrames = 0
        Timber.i(if (active) "🔊 Playback activo" else "🔕 Playback inactivo")
    }

    fun setShouldProcess(process: Boolean) {
        shouldProcess = process
    }

    override fun processFrame(audioData: ShortArray, length: Int): IVoiceActivityDetector.VadResult {
        val timestamp = System.nanoTime()
        if (!isActive || !shouldProcess) {
            return IVoiceActivityDetector.VadResult(false, 0f, -100f, timestamp)
        }

        // Paso 1: filtro paso-alto
        val filter = AudioFilter(sampleRate = sampleRate)
        val filtered = filter.process(audioData)
        val energyDb = filter.calculateEnergyDb(filtered)

        // Paso 2: detección simple por energía
        var hasVoice = false
        var reason = ""
        if (isPlaybackActive) {
            if (energyDb > maxEnergyDuringPlaybackDb) {
                reason = "Demasiado alto durante playback"
            } else if (energyDb - lastEnergyDb > energyJumpThresholdDb) {
                reason = "Salto de energía súbito"
            } else if (energyDb > energyThresholdDb) {
                hasVoice = true
            }
        } else if (energyDb > energyThresholdDb) {
            hasVoice = true
        }

        if (hasVoice) {
            consecutiveVoiceFrames++
        } else {
            consecutiveVoiceFrames = 0
        }

        val confirmed = hasVoice && consecutiveVoiceFrames >= minConsecutiveVoiceFrames
        val confidence = if (confirmed) calculateConfidence(energyDb, energyThresholdDb) else 0f

        if (System.currentTimeMillis() - lastLogTime > LOG_INTERVAL_MS) {
            Timber.d(
                if (confirmed) "✅ VOICE conf=${String.format("%.2f", confidence)} E=${String.format("%.1f", energyDb)}dB"
                else "⏸️ No voice E=${String.format("%.1f", energyDb)}dB $reason"
            )
            lastLogTime = System.currentTimeMillis()
        }

        lastEnergyDb = energyDb
        framesProcessed.incrementAndGet()
        if (confirmed) voiceFrames.incrementAndGet()
        totalConfidence += confidence

        return IVoiceActivityDetector.VadResult(confirmed, confidence, energyDb, timestamp)
    }

    private fun calculateConfidence(energy: Float, threshold: Float): Float {
        val delta = energy - threshold
        return (delta / 15f).coerceIn(0f, 1f)
    }

    override fun release() {
        isActive = false
        Timber.d("🔧 EnergyVAD liberado")
    }

    override fun getType(): IVoiceActivityDetector.Type =
        IVoiceActivityDetector.Type.ENERGY

    override fun getMetrics(): IVoiceActivityDetector.VadMetrics {
        val frames = framesProcessed.get().coerceAtLeast(1)
        return IVoiceActivityDetector.VadMetrics(
            framesProcessed = frames,
            voiceFrames = voiceFrames.get(),
            averageProcessingTimeUs = totalProcessingTimeUs.get() / frames,
            averageConfidence = (totalConfidence / frames).toFloat()
        )
    }
}
