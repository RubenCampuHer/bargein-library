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

    private var energyThresholdDb = -38f
    private var noiseFloorDb = -60f

    private val energyHistory = FloatArray(HISTORY_SIZE)
    private var historyIndex = 0
    private var historyFilled = false

    companion object {
        private const val HISTORY_SIZE = 100

        // ✅ THRESHOLDS OPTIMIZADOS - Evita ruido, detecta voz
        private const val VOICE_THRESHOLD_MARGIN_DB = 6f   // Ligeramente más alto
        private const val MIN_ENERGY_DB = -60f
        private const val PLAYBACK_SUPPRESSION_DB = 7f     // Más estricto durante playback

        // ✅ CRÍTICO: Mínimo 2 frames para evitar falsos positivos
        private const val MIN_CONSECUTIVE_VOICE_FRAMES = 2

        // ✅ Durante playback, rechazar energía muy alta
        private const val MAX_ENERGY_DURING_PLAYBACK_DB = -12f

        // ✅ Salto de energía = altavoz
        private const val ENERGY_JUMP_THRESHOLD_DB = 15f
    }

    private var lastEnergyDb = -60f
    private var energyBeforePlayback = -60f

    private var consecutiveVoiceFrames = 0
    private var consecutiveRejectedFrames = 0

    // Control de logging para evitar spam
    private var lastLogTime = 0L
    private val LOG_INTERVAL_MS = 500L

    override fun initialize(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): Boolean {
        try {
            // ✅ Thresholds ULTRA PERMISIVOS - Acepta casi todo
            energyThresholdDb = when (mode) {
                IVoiceActivityDetector.AggressivenessMode.QUALITY -> -50f         // Muy bajo
                IVoiceActivityDetector.AggressivenessMode.LOW_BITRATE -> -45f     // Muy bajo
                IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE -> -45f      // Muy bajo
                IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE -> -48f // Ultra bajo
            }

            isActive = true
            Timber.i("✅ Energy VAD initialized")
            Timber.i("   Threshold: ${energyThresholdDb}dB")
            Timber.i("   Mode: $mode")
            Timber.i("   Consecutive frames required: $MIN_CONSECUTIVE_VOICE_FRAMES")
            return true

        } catch (e: Exception) {
            Timber.e(e, "❌ Error initializing Energy VAD")
            return false
        }
    }

    fun setPlaybackActive(active: Boolean) {
        isPlaybackActive = active

        if (active) {
            // Guardar energía antes del playback para comparación
            energyBeforePlayback = lastEnergyDb
            Timber.d("🔊 Playback started - baseline energy: ${String.format("%.1f", energyBeforePlayback)}dB")
        } else {
            // Reset contadores al terminar reproducción
            consecutiveVoiceFrames = 0
            consecutiveRejectedFrames = 0
            Timber.d("🔕 Playback ended - counters reset")
        }
    }

    override fun processFrame(audioData: ShortArray, length: Int): IVoiceActivityDetector.VadResult {
        val timestamp = System.nanoTime()

        if (!isActive) {
            return IVoiceActivityDetector.VadResult(false, 0f, -100f, timestamp)
        }

        val startTime = System.nanoTime()

        // ===== PASO 1: FILTRO PASO-ALTO (450Hz) =====
        val filteredSamples = audioData.copyOf()
        AudioFilter.applyHighPassFilter(
            samples = filteredSamples,
            length = length,
            cutoffFreq = 450f, // ✅ Más agresivo
            sampleRate = 16000
        )

        // ===== PASO 2: ANÁLISIS DE FRECUENCIAS MEJORADO =====
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

        // Nunca bajar del threshold base
        adaptiveThreshold = adaptiveThreshold.coerceAtLeast(energyThresholdDb)

        // ===== PASO 5: DETECCIÓN CON FILTROS EQUILIBRADOS =====
        var hasVoice = false
        var rejectionReason = ""

        // 5.1: Verificar energía suficiente
        if (energyDb <= adaptiveThreshold) {
            rejectionReason = "Low energy (${String.format("%.1f", energyDb)}dB < ${String.format("%.1f", adaptiveThreshold)}dB)"
        }
        // 5.2: ✅ Rechazar altavoz obvio
        else if (freqAnalysis.isLikelySpeakerEcho()) {
            rejectionReason = "Speaker echo [${freqAnalysis.getDebugInfo()}]"
            rejectedFrames.incrementAndGet()
        }
        // 5.3: ✅ Verificar que sea voz
        else if (!freqAnalysis.isLikelyRealVoice()) {
            rejectionReason = "Not voice pattern [${freqAnalysis.getDebugInfo()}]"
            rejectedFrames.incrementAndGet()
        }
        // 5.4: ✅ CRÍTICO: Durante playback, filtros adicionales
        else if (isPlaybackActive) {
            // 5.4a: Rechazar si energía es MUY alta (probablemente altavoz puro)
            if (energyDb > MAX_ENERGY_DURING_PLAYBACK_DB) {
                rejectionReason = "Too loud during playback (${String.format("%.1f", energyDb)}dB > ${MAX_ENERGY_DURING_PLAYBACK_DB}dB)"
                rejectedFrames.incrementAndGet()
            }
            // 5.4b: NUEVO - Rechazar si energía sube súbitamente (inicio de playback = altavoz)
            else if (energyDb - lastEnergyDb > ENERGY_JUMP_THRESHOLD_DB) {
                rejectionReason = "Sudden energy jump (${String.format("%.1f", energyDb - lastEnergyDb)}dB increase)"
                rejectedFrames.incrementAndGet()
            }
            // 5.4c: Todo OK - Es voz durante playback
            else {
                hasVoice = true
                consecutiveVoiceFrames++
                consecutiveRejectedFrames = 0
            }
        }
        // 5.5: Sin playback - Es voz real
        else {
            hasVoice = true
            consecutiveVoiceFrames++
            consecutiveRejectedFrames = 0
        }

        // Guardar energía actual para siguiente frame
        lastEnergyDb = energyDb

        // Si fue rechazado, resetear contador de voz
        if (!hasVoice) {
            consecutiveVoiceFrames = 0
            consecutiveRejectedFrames++
        }

        // ✅ NUEVO: Requiere frames consecutivos para confirmar voz
        val confirmedVoice = hasVoice && consecutiveVoiceFrames >= MIN_CONSECUTIVE_VOICE_FRAMES

        // ===== PASO 6: LOGGING CONTROLADO =====
        val currentTime = System.currentTimeMillis()
        val shouldLog = (currentTime - lastLogTime) >= LOG_INTERVAL_MS

        if (shouldLog || confirmedVoice || energyDb > -30f) { // ✅ Log cuando hay energía significativa
            val debugInfo = freqAnalysis.getDebugInfo()

            if (confirmedVoice) {
                Timber.d("✅ VOICE CONFIRMED [frame #${consecutiveVoiceFrames}]")
                Timber.d("   Energy: ${String.format("%.1f", energyDb)}dB (threshold: ${String.format("%.1f", adaptiveThreshold)}dB)")
                Timber.d("   Playback: $isPlaybackActive")
                Timber.d("   Analysis: $debugInfo")
                voiceFrames.incrementAndGet()
            } else if (rejectionReason.isNotEmpty() && (energyDb > -35f || shouldLog)) {
                // ✅ Log más detallado para debugging
                Timber.v("❌ REJECTED: $rejectionReason")
                if (energyDb > -30f) {
                    Timber.v("   🔍 Debug: energyDb=${String.format("%.1f", energyDb)}, " +
                            "noise=${String.format("%.1f", noiseFloorDb)}, " +
                            "adaptThresh=${String.format("%.1f", adaptiveThreshold)}")
                }
            }

            lastLogTime = currentTime
        }

        // ===== PASO 7: CALCULAR CONFIANZA =====
        val confidence = if (confirmedVoice) {
            calculateConfidence(
                energyDb = energyDb,
                threshold = adaptiveThreshold,
                consecutiveFrames = consecutiveVoiceFrames,
                freqAnalysis = freqAnalysis,
                isPlaybackActive = isPlaybackActive
            )
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

    /**
     * ✅ Calcula confianza basándose en múltiples factores.
     */
    private fun calculateConfidence(
        energyDb: Float,
        threshold: Float,
        consecutiveFrames: Int,
        freqAnalysis: AudioFilter.FrequencyAnalysis,
        isPlaybackActive: Boolean
    ): Float {
        // Base: margen sobre threshold
        val margin = energyDb - threshold
        var confidence = when {
            margin > 20 -> 0.95f
            margin > 15 -> 0.90f
            margin > 10 -> 0.85f
            margin > 5 -> 0.75f
            else -> 0.65f
        }

        // Bonus por frames consecutivos (hasta +0.15)
        confidence += (consecutiveFrames * 0.02f).coerceAtMost(0.15f)

        // Bonus por periodicidad alta (voz estructurada)
        if (freqAnalysis.periodicity > 0.5f) {
            confidence += 0.05f
        }

        // Bonus por alto contenido de frecuencias altas
        if (freqAnalysis.highBandEnergy > 0.25f) {
            confidence += 0.05f
        }

        // Penalización durante playback
        if (isPlaybackActive) {
            confidence *= 0.90f
        }

        return confidence.coerceIn(0f, 1f)
    }

    override fun release() {
        isActive = false

        val totalFrames = framesProcessed.get()
        val acceptedFrames = voiceFrames.get()
        val rejected = rejectedFrames.get()
        val acceptanceRate = if (totalFrames > 0) {
            (acceptedFrames * 100f / totalFrames)
        } else {
            0f
        }

        Timber.i("📊 VAD Final Stats:")
        Timber.i("   Total frames: $totalFrames")
        Timber.i("   Voice frames: $acceptedFrames (${String.format("%.1f%%", acceptanceRate)})")
        Timber.i("   Rejected frames: $rejected")
        Timber.d("🔧 Energy VAD released")
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

    /**
     * Calcula RMS (Root Mean Square) de la señal de audio.
     * Normaliza los valores de 16-bit signed integer a [-1, 1].
     */
    private fun calculateRMS(audioData: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val normalized = audioData[i] / 32768.0
            sum += normalized * normalized
        }
        return sqrt(sum / length).toFloat()
    }

    /**
     * Mantiene un historial de los últimos N frames de energía
     * para estimar el ruido de fondo adaptativo.
     */
    private fun updateEnergyHistory(energyDb: Float) {
        energyHistory[historyIndex] = energyDb
        historyIndex = (historyIndex + 1) % HISTORY_SIZE

        if (historyIndex == 0 && !historyFilled) {
            historyFilled = true
        }
    }

    /**
     * Estima el ruido de fondo usando el percentil 25 del historial.
     * Esto ignora picos ocasionales y captura el nivel base de ruido.
     */
    private fun estimateNoiseFloor(): Float {
        val size = if (historyFilled) HISTORY_SIZE else historyIndex
        if (size == 0) return MIN_ENERGY_DB

        val sorted = energyHistory.copyOf(size).sortedArray()
        val percentile25Index = (size * 0.25).toInt()

        return sorted[percentile25Index]
    }
}