package com.aima.bargein.vad

import com.aima.bargein.audio.AudioFilter
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt

class EnergyVoiceActivityDetector : IVoiceActivityDetector {

    private val framesProcessed = AtomicLong(0)
    private val voiceFrames = AtomicLong(0)
    private val rejectedFrames = AtomicLong(0)
    private val totalProcessingTimeUs = AtomicLong(0)
    private var totalConfidence = 0.0

    @Volatile
    private var isActive = false

    @Volatile
    private var isPlaybackActive = false

    private var energyThresholdDb = -40f
    private var noiseFloorDb = -60f

    private val energyHistory = FloatArray(HISTORY_SIZE)
    private var historyIndex = 0
    private var historyFilled = false

    companion object {
        private const val HISTORY_SIZE = 100
        private const val VOICE_THRESHOLD_MARGIN_DB = 15f
        private const val MIN_ENERGY_DB = -60f
        private const val PLAYBACK_SUPPRESSION_DB = 12f // Más estricto durante reproducción

        // Nuevo: contador de frames consecutivos
        private const val MIN_CONSECUTIVE_VOICE_FRAMES = 2 // Necesita 2 frames seguidos
    }

    private var consecutiveVoiceFrames = 0
    private var consecutiveRejectedFrames = 0

    // Control de logging para evitar spam
    private var lastLogTime = 0L
    private val LOG_INTERVAL_MS = 500L // Logear cada 500ms (medio segundo)

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

    fun setPlaybackActive(active: Boolean) {
        isPlaybackActive = active

        if (!active) {
            // Reset contadores al terminar reproducción
            consecutiveVoiceFrames = 0
            consecutiveRejectedFrames = 0
        }

        Timber.d("Playback active: $active")
    }

    override fun processFrame(audioData: ShortArray, length: Int): IVoiceActivityDetector.VadResult {
        val timestamp = System.nanoTime()

        if (!isActive) {
            return IVoiceActivityDetector.VadResult(false, 0f, -100f, timestamp)
        }

        val startTime = System.nanoTime()

        // ===== PASO 1: FILTRO PASO-ALTO =====
        val filteredSamples = audioData.copyOf()
        AudioFilter.applyHighPassFilter(
            samples = filteredSamples,
            length = length,
            cutoffFreq = 300f, // Eliminar frecuencias < 300Hz
            sampleRate = 16000
        )

        // ===== PASO 2: ANÁLISIS DE FRECUENCIAS (FFT REAL) =====
        val freqAnalysis = AudioFilter.analyzeFrequencyBands(
            samples = filteredSamples,
            length = length,
            sampleRate = 16000
        )

        // ===== PASO 3: CALCULAR ENERGÍA RMS =====
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

        // Durante reproducción, ser MÁS ESTRICTO
        if (isPlaybackActive) {
            adaptiveThreshold += PLAYBACK_SUPPRESSION_DB
        }

        // ===== PASO 5: DETECCIÓN CON FILTROS MÚLTIPLES =====
        var hasVoice = false
        var rejectionReason = ""

        // 5.1: Verificar energía suficiente
        if (energyDb <= adaptiveThreshold.coerceAtLeast(energyThresholdDb)) {
            rejectionReason = "Low energy"
        }
        // 5.2: CRÍTICO - Rechazar si es eco del altavoz
        else if (freqAnalysis.isLikelySpeakerEcho()) {
            rejectionReason = "Speaker echo detected"
            rejectedFrames.incrementAndGet()
        }
        // 5.3: CRÍTICO - Debe ser voz real con frecuencias altas
        else if (!freqAnalysis.isLikelyRealVoice()) {
            rejectionReason = "No high frequencies"
            rejectedFrames.incrementAndGet()
        }
        // 5.4: Durante reproducción, rechazar energía EXCESIVA (probablemente altavoz)
        else if (isPlaybackActive && energyDb > -15f) {
            rejectionReason = "Too loud (${String.format("%.1f", energyDb)}dB)"
            rejectedFrames.incrementAndGet()
        }
        // 5.5: TODO OK - Es voz real
        else {
            hasVoice = true
            consecutiveVoiceFrames++
            consecutiveRejectedFrames = 0
        }

        // Si fue rechazado, resetear contador de voz
        if (!hasVoice) {
            consecutiveVoiceFrames = 0
            consecutiveRejectedFrames++
        }

        // NUEVO: Requiere frames consecutivos para confirmar voz
        // Esto evita falsos positivos por ruido momentáneo
        val confirmedVoice = hasVoice && consecutiveVoiceFrames >= MIN_CONSECUTIVE_VOICE_FRAMES

        // ===== PASO 6: LOGGING DETALLADO =====
        if (energyDb > -30f || confirmedVoice) {
            val debugInfo = freqAnalysis.getDebugInfo()

            if (confirmedVoice) {
                Timber.d("✅ VOICE CONFIRMED [${consecutiveVoiceFrames}]: " +
                        "energy=${String.format("%.1f", energyDb)}dB, " +
                        "threshold=${String.format("%.1f", adaptiveThreshold)}dB, " +
                        "playback=$isPlaybackActive | $debugInfo")
                voiceFrames.incrementAndGet()
            } else if (rejectionReason.isNotEmpty()) {
                Timber.v("❌ REJECTED ($rejectionReason): " +
                        "energy=${String.format("%.1f", energyDb)}dB, " +
                        "threshold=${String.format("%.1f", adaptiveThreshold)}dB, " +
                        "playback=$isPlaybackActive | $debugInfo")
            }
        }

        // ===== PASO 7: CALCULAR CONFIANZA =====
        val confidence = if (confirmedVoice) {
            val margin = energyDb - adaptiveThreshold
            var baseConfidence = when {
                margin > 20 -> 0.95f
                margin > 15 -> 0.90f
                margin > 10 -> 0.85f
                margin > 5 -> 0.75f
                else -> 0.65f
            }

            // Incrementar confianza por frames consecutivos
            baseConfidence += (consecutiveVoiceFrames * 0.02f).coerceAtMost(0.15f)

            // Penalización leve durante reproducción
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
        totalConfidence += confidence

        return IVoiceActivityDetector.VadResult(
            hasVoice = confirmedVoice,
            confidence = confidence,
            energyDb = energyDb,
            timestamp = timestamp
        )
    }

    override fun release() {
        isActive = false

        val totalFrames = framesProcessed.get()
        val acceptedFrames = voiceFrames.get()
        val rejected = rejectedFrames.get()

        Timber.d("VAD Stats: total=$totalFrames, accepted=$acceptedFrames, rejected=$rejected")
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