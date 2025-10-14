package com.aima.bargein.vad

import com.aima.bargein.audio.AudioFilter
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt

class EnergyVoiceActivityDetector : IVoiceActivityDetector {

    private val framesProcessed = AtomicLong(0)
    private val voiceFrames = AtomicLong(0)
    private val totalProcessingTimeUs = AtomicLong(0)
    private var totalConfidence = 0.0

    @Volatile
    private var isActive = false

    @Volatile
    private var isPlaybackActive = false // NUEVO: tracking de reproducción

    private var energyThresholdDb = -40f
    private var noiseFloorDb = -60f

    private val energyHistory = FloatArray(HISTORY_SIZE)
    private var historyIndex = 0
    private var historyFilled = false

    companion object {
        private const val HISTORY_SIZE = 100
        private const val VOICE_THRESHOLD_MARGIN_DB = 15f // Volver a 15
        private const val MIN_ENERGY_DB = -60f
        private const val PLAYBACK_SUPPRESSION_DB = 10f // Volver a 10
    }

    override fun initialize(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): Boolean {
        try {
            energyThresholdDb = when (mode) {
                IVoiceActivityDetector.AggressivenessMode.QUALITY -> -40f
                IVoiceActivityDetector.AggressivenessMode.LOW_BITRATE -> -35f
                IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE -> -30f
                IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE -> -25f
            }

            isActive = true
            Timber.i("Energy VAD initialized: threshold=${energyThresholdDb}dB, mode=$mode")
            return true

        } catch (e: Exception) {
            Timber.e(e, "Error initializing Energy VAD")
            return false
        }
    }

    /**
     * Notifica al VAD que hay reproducción activa.
     * Esto hace que sea más estricto para evitar falsos positivos.
     */
    fun setPlaybackActive(active: Boolean) {
        isPlaybackActive = active
        Timber.d("Playback active state changed: $active")
    }

    override fun processFrame(audioData: ShortArray, length: Int): IVoiceActivityDetector.VadResult {
        val timestamp = System.nanoTime()

        if (!isActive) {
            return IVoiceActivityDetector.VadResult(
                hasVoice = false,
                confidence = 0f,
                energyDb = -100f,
                timestamp = timestamp
            )
        }

        val startTime = System.nanoTime()

        // ===== PASO 1: APLICAR FILTRO PASO-ALTO =====
        val filteredSamples = audioData.copyOf()
        AudioFilter.applyHighPassFilter(
            samples = filteredSamples,
            length = length,
            cutoffFreq = 250f, // REDUCIDO de 300 a 250Hz
            sampleRate = 16000
        )

        // ===== PASO 2: ANÁLISIS DE FRECUENCIAS =====
        val freqAnalysis = AudioFilter.analyzeFrequencyBands(
            samples = filteredSamples,
            length = length,
            sampleRate = 16000
        )

        // ===== PASO 3: CALCULAR ENERGÍA =====
        val rmsEnergy = calculateRMS(filteredSamples, length)
        val energyDb = if (rmsEnergy > 0) {
            20 * log10(rmsEnergy).coerceAtLeast(MIN_ENERGY_DB)
        } else {
            MIN_ENERGY_DB
        }

        updateEnergyHistory(energyDb)
        noiseFloorDb = estimateNoiseFloor()

// ===== PASO 4: THRESHOLD ADAPTATIVO =====
        var adaptiveThreshold = noiseFloorDb + VOICE_THRESHOLD_MARGIN_DB

// Durante reproducción, subir threshold
        if (isPlaybackActive) {
            adaptiveThreshold += PLAYBACK_SUPPRESSION_DB
        }

// ===== PASO 5: DETECCIÓN CON TRIPLE FILTRO =====
        var hasVoice = energyDb > adaptiveThreshold.coerceAtLeast(energyThresholdDb)

        var rejectionReason = ""

        if (hasVoice) {
            // FILTRO 1: Rechazar si es CLARAMENTE eco del altavoz (por patrón de frecuencias)
            if (freqAnalysis.isLikelySpeakerEcho()) {
                rejectionReason = "Speaker echo pattern detected"
                hasVoice = false
            }
            // FILTRO 2: Debe tener frecuencias altas (voz real)
            else if (!freqAnalysis.isLikelyRealVoice()) {
                rejectionReason = "No high frequencies (not real voice)"
                hasVoice = false
            }
            // FILTRO 3: Durante reproducción, rechazar si energía es DEMASIADO alta
            else if (isPlaybackActive && energyDb > -20f) {
                rejectionReason = "Too loud during playback (${String.format("%.1f", energyDb)}dB)"
                hasVoice = false
            }
        }

// Log detallado
        if (energyDb > -30f) {
            val totalEnergy = freqAnalysis.lowBandEnergy + freqAnalysis.midBandEnergy +
                    freqAnalysis.highBandEnergy + freqAnalysis.veryHighBandEnergy
            val highRatio = if (totalEnergy > 0) freqAnalysis.highBandEnergy / totalEnergy else 0f
            val lowRatio = if (totalEnergy > 0) freqAnalysis.lowBandEnergy / totalEnergy else 0f
            val lowHighRatio = if (highRatio > 0.01f) lowRatio / highRatio else 0f

            if (hasVoice) {
                Timber.d("✅ ACCEPTED: energy=${String.format("%.1f", energyDb)}dB, " +
                        "threshold=${String.format("%.1f", adaptiveThreshold)}dB, " +
                        "playback=$isPlaybackActive, " +
                        "high%=${String.format("%.1f%%", highRatio * 100)}, " +
                        "low/high=${String.format("%.2f", lowHighRatio)}")
            } else if (rejectionReason.isNotEmpty()) {
                Timber.v("❌ REJECTED ($rejectionReason): " +
                        "energy=${String.format("%.1f", energyDb)}dB, " +
                        "threshold=${String.format("%.1f", adaptiveThreshold)}dB, " +
                        "playback=$isPlaybackActive, " +
                        "high%=${String.format("%.1f%%", highRatio * 100)}, " +
                        "low/high=${String.format("%.2f", lowHighRatio)}")
            }
        }

// ===== PASO 6: CALCULAR CONFIANZA =====
        val confidence = if (hasVoice) {
            val margin = energyDb - adaptiveThreshold
            var baseConfidence = when {
                margin > 20 -> 1.0f
                margin > 15 -> 0.9f
                margin > 10 -> 0.8f
                margin > 5 -> 0.7f
                else -> 0.6f
            }

            // Pequeña penalización durante reproducción
            if (isPlaybackActive) {
                baseConfidence *= 0.95f
            }

            baseConfidence.coerceIn(0f, 1f)
        } else {
            0.0f
        }
        val processingTime = (System.nanoTime() - startTime) / 1000
        totalProcessingTimeUs.addAndGet(processingTime)
        framesProcessed.incrementAndGet()

        if (hasVoice) {
            voiceFrames.incrementAndGet()
            Timber.d("✅ Voice: energy=${String.format("%.1f", energyDb)}dB, " +
                    "conf=${String.format("%.2f", confidence)}, " +
                    "threshold=${String.format("%.1f", adaptiveThreshold)}dB, " +
                    "mid=${String.format("%.3f", freqAnalysis.midBandEnergy)}, " +
                    "low=${String.format("%.3f", freqAnalysis.lowBandEnergy)}")
        }

        totalConfidence += confidence

        return IVoiceActivityDetector.VadResult(
            hasVoice = hasVoice,
            confidence = confidence,
            energyDb = energyDb,
            timestamp = timestamp
        )
    }

    override fun release() {
        isActive = false
        Timber.d("Energy VAD released")
    }

    override fun getType(): IVoiceActivityDetector.Type =
        IVoiceActivityDetector.Type.ENERGY

    override fun getMetrics(): IVoiceActivityDetector.VadMetrics {
        val frames = framesProcessed.get()
        val avgTime = if (frames > 0) {
            totalProcessingTimeUs.get() / frames
        } else {
            0L
        }

        val avgConfidence = if (frames > 0) {
            (totalConfidence / frames).toFloat()
        } else {
            0f
        }

        return IVoiceActivityDetector.VadMetrics(
            framesProcessed = frames,
            voiceFrames = voiceFrames.get(),
            averageProcessingTimeUs = avgTime,
            averageConfidence = avgConfidence
        )
    }

    private fun calculateRMS(audioData: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val normalized = audioData[i] / 32768.0
            sum += normalized * normalized
        }
        return sqrt(sum / length).toFloat()
    }

    private fun updateEnergyHistory(energyDb: Float) {
        energyHistory[historyIndex] = energyDb
        historyIndex = (historyIndex + 1) % HISTORY_SIZE

        if (historyIndex == 0 && !historyFilled) {
            historyFilled = true
        }
    }

    private fun estimateNoiseFloor(): Float {
        val size = if (historyFilled) HISTORY_SIZE else historyIndex
        if (size == 0) return MIN_ENERGY_DB

        val sorted = energyHistory.copyOf(size).sortedArray()
        val percentile25Index = (size * 0.25).toInt()

        return sorted[percentile25Index]
    }
}