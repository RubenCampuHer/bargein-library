package com.aima.bargein.vad

import android.util.Log
import com.aima.bargein.BargeInConfig
import com.aima.bargein.audio.AudioFilter
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.absoluteValue
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

    // ✅ Periodo de gracia post-playback
    @Volatile
    private var playbackEndTime = 0L
    private val GRACE_PERIOD_MS = 500L

    // ✅ Calibración dinámica del baseline
    @Volatile
    private var isCalibrating = false
    private val calibrationFrames = mutableListOf<Float>()
    private var calibratedBaselineDb = -60f
    private val CALIBRATION_DURATION_MS = 200L
    private var calibrationStartTime = 0L

    // ✅ Buffer de referencia far-end (audio del altavoz)
    private val farEndBuffer = mutableListOf<Short>()
    private val MAX_FAR_END_BUFFER = 2048 // ~46ms @ 44.1kHz
    @Volatile
    private var hasFarEndReference = false

    // ✅ Ventana de energía reciente para detección por delta
    private val recentEnergyWindow = FloatArray(10) // Últimos 10 frames (~116ms)
    private var energyWindowIndex = 0
    private var energyWindowFilled = false

    // ✅ NUEVO: Variables configurables (se sobrescriben desde config)
    private var deltaVoiceThresholdDb: Float = 15f
    private var minAbsoluteVoiceEnergyDb: Float = -25f
    private var maxZcrForVoice: Float = 0.18f
    private var deltaBaselineAdjustmentFactor: Float = 0.6f

    // ✅ NUEVO: Parámetros base (sin ajuste por volumen)
    private var baseDeltaVoiceThresholdDb: Float = 15f
    private var baseMinAbsoluteVoiceEnergyDb: Float = -25f
    private var baseMaxZcrForVoice: Float = 0.18f
    private var baseDeltaBaselineAdjustmentFactor: Float = 0.6f

    // ✅ NUEVO: Volumen actual del sistema (0.0 - 1.0)
    @Volatile
    private var currentVolume: Float = 0.5f

    // ✅ NUEVO: Estadísticas de ajuste por volumen
    private var lastVolumeAdjustmentLog = 0L
    private val VOLUME_LOG_INTERVAL_MS = 2000L

    private var energyThresholdDb = -38f
    private var noiseFloorDb = -60f

    private val energyHistory = FloatArray(HISTORY_SIZE)
    private var historyIndex = 0
    private var historyFilled = false

    companion object {
        private const val TAG = "BargeInEngine_EnergyVoiceActivityDetector"
        private const val HISTORY_SIZE = 100

        private const val VOICE_THRESHOLD_MARGIN_DB = 6f
        private const val MIN_ENERGY_DB = -60f
        private const val PLAYBACK_SUPPRESSION_DB = 8f
        private const val MIN_CONSECUTIVE_VOICE_FRAMES = 3
        private const val MAX_ENERGY_ABOVE_BASELINE_DB = 18f
        private const val ENERGY_JUMP_THRESHOLD_DB = 30f
        private const val MAX_CORRELATION_THRESHOLD = 0.75f

        // ✅ Valores por defecto (se sobrescriben desde config)
        private const val DEFAULT_DELTA_VOICE_THRESHOLD_DB = 15f
        private const val DEFAULT_MIN_ABSOLUTE_VOICE_ENERGY_DB = -25f
        private const val DEFAULT_MAX_ZCR_FOR_VOICE = 0.18f
        private const val DEFAULT_DELTA_BASELINE_ADJUSTMENT_FACTOR = 0.6f

        // ✅ NUEVO: Configuración de ajuste por volumen
        private const val VOLUME_THRESHOLD = 0.65f  // 65% - umbral de inicio
        private const val MAX_VOLUME_SCALE = 0.64f  // Escala máxima: +64% a 100%
    }

    private var lastEnergyDb = -60f
    private var energyBeforePlayback = -60f

    private var consecutiveVoiceFrames = 0
    private var consecutiveRejectedFrames = 0

    // Control de logging: solo cada 500ms
    private var lastLogTime = 0L
    private val LOG_INTERVAL_MS = 500L

    /**
     * ✅ Mantiene compatibilidad con interfaz original
     */
    override fun initialize(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): Boolean {
        return initializeWithConfig(sampleRate, mode, null)
    }

    /**
     * ✅ NUEVO: Inicialización con configuración personalizada
     */
    fun initializeWithConfig(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode,
        config: BargeInConfig?
    ): Boolean {
        try {
            // ✅ Cargar configuración personalizada si existe
            if (config != null) {
                // Guardar valores base (sin ajuste)
                baseDeltaVoiceThresholdDb = config.deltaVoiceThresholdDb
                baseMinAbsoluteVoiceEnergyDb = config.minAbsoluteVoiceEnergyDb
                baseMaxZcrForVoice = config.maxZcrForVoice
                baseDeltaBaselineAdjustmentFactor = config.deltaBaselineAdjustmentFactor

                // Inicializar valores actuales con los base
                deltaVoiceThresholdDb = baseDeltaVoiceThresholdDb
                minAbsoluteVoiceEnergyDb = baseMinAbsoluteVoiceEnergyDb
                maxZcrForVoice = baseMaxZcrForVoice
                deltaBaselineAdjustmentFactor = baseDeltaBaselineAdjustmentFactor

                Log.i(TAG, "✅ Custom VAD thresholds loaded:")
                Log.i(TAG, "   Delta threshold: ${deltaVoiceThresholdDb}dB")
                Log.i(TAG, "   Min energy: ${minAbsoluteVoiceEnergyDb}dB")
                Log.i(TAG, "   Max ZCR: $maxZcrForVoice")
                Log.i(TAG, "   Baseline adjustment: $deltaBaselineAdjustmentFactor")
                Log.i(TAG, "   🎚️ Volume-based adjustment: ENABLED (threshold: 65%)")
            } else {
                // Usar valores por defecto
                baseDeltaVoiceThresholdDb = DEFAULT_DELTA_VOICE_THRESHOLD_DB
                baseMinAbsoluteVoiceEnergyDb = DEFAULT_MIN_ABSOLUTE_VOICE_ENERGY_DB
                baseMaxZcrForVoice = DEFAULT_MAX_ZCR_FOR_VOICE
                baseDeltaBaselineAdjustmentFactor = DEFAULT_DELTA_BASELINE_ADJUSTMENT_FACTOR

                deltaVoiceThresholdDb = baseDeltaVoiceThresholdDb
                minAbsoluteVoiceEnergyDb = baseMinAbsoluteVoiceEnergyDb
                maxZcrForVoice = baseMaxZcrForVoice
                deltaBaselineAdjustmentFactor = baseDeltaBaselineAdjustmentFactor

                Log.i(TAG, "ℹ️ Using default VAD thresholds")
            }

            energyThresholdDb = when (mode) {
                IVoiceActivityDetector.AggressivenessMode.QUALITY -> -48f
                IVoiceActivityDetector.AggressivenessMode.LOW_BITRATE -> -43f
                IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE -> -43f
                IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE -> -46f
            }

            isActive = true
            Log.i(TAG, "✅ Energy VAD initialized @ ${sampleRate}Hz")
            Log.i(TAG, "   Threshold: ${energyThresholdDb}dB")
            Log.i(TAG, "   Mode: $mode")
            Log.i(TAG, "   Consecutive frames required: $MIN_CONSECUTIVE_VOICE_FRAMES")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing Energy VAD", e)
            return false
        }
    }

    /**
     * ✅ NUEVO: Configurar parámetros de ajuste por volumen
     * Llamado desde BargeInEngine con estrategia específica
     */
    fun setVolumeAdjustmentParameters(
        enabled: Boolean,
        scaleMultiplier: Float
    ) {
        if (!enabled) {
            // Restaurar valores base
            deltaVoiceThresholdDb = baseDeltaVoiceThresholdDb
            minAbsoluteVoiceEnergyDb = baseMinAbsoluteVoiceEnergyDb
            maxZcrForVoice = baseMaxZcrForVoice
            deltaBaselineAdjustmentFactor = baseDeltaBaselineAdjustmentFactor
            return
        }

        // ✅ Aplicar escala según multiplicador
        deltaVoiceThresholdDb = baseDeltaVoiceThresholdDb * scaleMultiplier

        // MinEnergy: más negativo = más restrictivo
        val energyScale = 1.0f + ((scaleMultiplier - 1.0f) * 0.6f)
        minAbsoluteVoiceEnergyDb = baseMinAbsoluteVoiceEnergyDb * energyScale

        // MaxZCR: más bajo = más restrictivo
        val zcrScale = 1.0f - ((scaleMultiplier - 1.0f) * 0.4f)
        maxZcrForVoice = (baseMaxZcrForVoice * zcrScale).coerceAtLeast(0.01f)

        // BaselineFactor: hacia 1.0
        val factorIncrease = (scaleMultiplier - 1.0f) * 0.03f
        deltaBaselineAdjustmentFactor = (baseDeltaBaselineAdjustmentFactor + factorIncrease)
            .coerceAtMost(1.0f)
    }

    /**
     * ✅ NUEVO: Aplicar ajuste dinámico según volumen actual
     */

    fun setPlaybackActive(active: Boolean) {
        isPlaybackActive = active

        if (active) {
            energyBeforePlayback = lastEnergyDb
            isCalibrating = true
            calibrationFrames.clear()
            calibrationStartTime = System.currentTimeMillis()
            playbackEndTime = 0L

            // ✅ Limpiar buffer far-end
            synchronized(farEndBuffer) {
                farEndBuffer.clear()
                hasFarEndReference = false
            }

            // ✅ Reset ventana de energía reciente
            energyWindowIndex = 0
            energyWindowFilled = false
            for (i in recentEnergyWindow.indices) {
                recentEnergyWindow[i] = MIN_ENERGY_DB
            }

            Log.i(TAG, "🎯 Playback started - CALIBRATING baseline (200ms)...")
            Log.d(TAG, "   Pre-playback energy: ${String.format("%.1f", energyBeforePlayback)}dB")
        } else {
            isCalibrating = false
            consecutiveVoiceFrames = 0
            consecutiveRejectedFrames = 0

            playbackEndTime = System.currentTimeMillis()

            synchronized(farEndBuffer) {
                farEndBuffer.clear()
                hasFarEndReference = false
            }

            Log.i(TAG, "🔕 Playback ended - baseline: ${String.format("%.1f", calibratedBaselineDb)}dB")
            Log.i(TAG, "   Grace period: ${GRACE_PERIOD_MS}ms (ignore detections)")
        }
    }

    /**
     * ✅ Recibe el audio que se está reproduciendo (referencia far-end)
     */
    fun processFarEndReference(farEndAudio: ShortArray) {
        if (!isPlaybackActive) return

        synchronized(farEndBuffer) {
            farEndAudio.forEach { sample ->
                farEndBuffer.add(sample)
                if (farEndBuffer.size > MAX_FAR_END_BUFFER) {
                    farEndBuffer.removeAt(0)
                }
            }
            hasFarEndReference = farEndBuffer.size >= 512
        }
    }

    override fun processFrame(audioData: ShortArray, length: Int): IVoiceActivityDetector.VadResult {
        val timestamp = System.nanoTime()

        if (!isActive) {
            return IVoiceActivityDetector.VadResult(false, 0f, -100f, timestamp)
        }

        // ✅ Verificar periodo de gracia post-playback
        if (playbackEndTime > 0L) {
            val timeSinceEnd = System.currentTimeMillis() - playbackEndTime
            if (timeSinceEnd < GRACE_PERIOD_MS) {
                framesProcessed.incrementAndGet()
                return IVoiceActivityDetector.VadResult(false, 0f, lastEnergyDb, timestamp)
            }
        }

        val startTime = System.nanoTime()

        // ✅ 1. CALCULAR ENERGÍA
        val rms = calculateRMS(audioData, length)
        val energyDb = if (rms > 1e-10f) {
            (20 * log10(rms)).coerceAtLeast(MIN_ENERGY_DB)
        } else {
            MIN_ENERGY_DB
        }

        lastEnergyDb = energyDb
        updateEnergyHistory(energyDb)
        noiseFloorDb = estimateNoiseFloor()

        // ✅ 2. CALIBRACIÓN INICIAL (primeros 200ms de playback)
        if (isCalibrating) {
            calibrationFrames.add(energyDb)

            val elapsed = System.currentTimeMillis() - calibrationStartTime
            if (elapsed >= CALIBRATION_DURATION_MS) {
                isCalibrating = false

                if (calibrationFrames.isNotEmpty()) {
                    calibratedBaselineDb = calibrationFrames.average().toFloat()
                    Log.i(TAG, "✅ Calibration complete: baseline = ${String.format("%.1f", calibratedBaselineDb)}dB (${calibrationFrames.size} frames)")
                }
            }

            framesProcessed.incrementAndGet()
            return IVoiceActivityDetector.VadResult(false, 0f, energyDb, timestamp)
        }

        // ✅ 3. ANÁLISIS DE FRECUENCIAS
        val freqAnalysis = AudioFilter.analyzeFrequencyBands(audioData, length, 44100)

        // ✅ 4. DETECCIÓN DE ECO DEL ALTAVOZ
        if (isPlaybackActive && freqAnalysis.isLikelySpeakerEcho()) {
            consecutiveVoiceFrames = 0
            consecutiveRejectedFrames++
            rejectedFrames.incrementAndGet()
            framesProcessed.incrementAndGet()
            return IVoiceActivityDetector.VadResult(false, 0f, energyDb, timestamp)
        }

        // ✅ 5. ACTUALIZAR VENTANA DE ENERGÍA RECIENTE
        updateRecentEnergyWindow(energyDb)

        // ✅ 6. CALCULAR DELTA DE ENERGÍA (respecto al promedio reciente)
        val avgRecentEnergy = calculateAverageRecentEnergy()
        val deltaEnergy = energyDb - avgRecentEnergy

        // ✅ 7. CRITERIOS DE VOZ (usando parámetros ajustados por volumen)

        // Criterio 1: Energía absoluta
        val hasAbsoluteEnergy = energyDb >= minAbsoluteVoiceEnergyDb

        // Criterio 2: Delta súbito (incremento de energía)
        val hasDelta = deltaEnergy >= deltaVoiceThresholdDb

        // Criterio 3: ZCR dentro del rango de voz
        val hasValidZcr = freqAnalysis.zeroCrossingRate <= maxZcrForVoice

        // Criterio 4: Parece voz real (no solo ruido)
        val seemsLikeVoice = freqAnalysis.isLikelyRealVoice()

        // Criterio 5: Energía por encima del baseline calibrado (con ajuste dinámico)
        val adjustedBaselineThreshold = if (isPlaybackActive && calibratedBaselineDb > -60f) {
            calibratedBaselineDb + (deltaVoiceThresholdDb * deltaBaselineAdjustmentFactor)
        } else {
            MIN_ENERGY_DB
        }
        val aboveBaseline = energyDb >= adjustedBaselineThreshold

        // ✅ DECISIÓN FINAL
        val hasVoice = hasAbsoluteEnergy &&
                (hasDelta || aboveBaseline) &&
                hasValidZcr &&
                seemsLikeVoice

        // ✅ 8. CONTADOR DE FRAMES CONSECUTIVOS
        val confirmedVoice = if (hasVoice) {
            consecutiveVoiceFrames++
            consecutiveRejectedFrames = 0

            if (consecutiveVoiceFrames >= MIN_CONSECUTIVE_VOICE_FRAMES) {
                voiceFrames.incrementAndGet()
                true
            } else {
                false
            }
        } else {
            if (consecutiveVoiceFrames > 0) {
                consecutiveVoiceFrames = 0
            }
            consecutiveRejectedFrames++
            false
        }

        // ✅ 9. LOGGING CONDICIONAL
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLogTime >= LOG_INTERVAL_MS) {
            lastLogTime = currentTime

            Log.d(TAG, "📊 VAD Frame @ ${(currentVolume * 100).toInt()}% volume:")
            Log.d(TAG, "   Energy: ${String.format("%.1f", energyDb)}dB (need: >= ${String.format("%.1f", minAbsoluteVoiceEnergyDb)}dB)")
            Log.d(TAG, "   Delta: ${String.format("%.1f", deltaEnergy)}dB (need: >= ${String.format("%.1f", deltaVoiceThresholdDb)}dB)")
            Log.d(TAG, "   ZCR: ${String.format("%.3f", freqAnalysis.zeroCrossingRate)} (max: ${String.format("%.3f", maxZcrForVoice)})")
            Log.d(TAG, "   Baseline: ${String.format("%.1f", calibratedBaselineDb)}dB + ${String.format("%.1f", deltaVoiceThresholdDb * deltaBaselineAdjustmentFactor)}dB = ${String.format("%.1f", adjustedBaselineThreshold)}dB")
            Log.d(TAG, "   Voice: $hasVoice | Confirmed: $confirmedVoice | Consecutive: $consecutiveVoiceFrames")

            if (!hasVoice) {
                val failures = mutableListOf<String>()
                if (!hasAbsoluteEnergy) failures.add("Energy")
                if (!hasDelta && !aboveBaseline) failures.add("Delta/Baseline")
                if (!hasValidZcr) failures.add("ZCR")
                if (!seemsLikeVoice) failures.add("Frequency")
                Log.d(TAG, "   ❌ Failed: ${failures.joinToString(", ")}")
            }
        }

        // ✅ 10. CALCULAR CONFIANZA
        val confidence = if (hasVoice) {
            calculateConfidence(
                energyDb = energyDb,
                threshold = energyThresholdDb,
                consecutiveFrames = consecutiveVoiceFrames,
                freqAnalysis = freqAnalysis,
                isPlaybackActive = isPlaybackActive,
                baselineDb = calibratedBaselineDb,
                deltaEnergy = if (isPlaybackActive) energyDb - calculateAverageRecentEnergy() else 0f
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
     * ✅ Actualizar ventana de energía reciente
     */
    private fun updateRecentEnergyWindow(energyDb: Float) {
        recentEnergyWindow[energyWindowIndex] = energyDb
        energyWindowIndex = (energyWindowIndex + 1) % recentEnergyWindow.size

        if (energyWindowIndex == 0 && !energyWindowFilled) {
            energyWindowFilled = true
        }
    }

    /**
     * ✅ Calcular promedio de energía reciente
     */
    private fun calculateAverageRecentEnergy(): Float {
        val size = if (energyWindowFilled) recentEnergyWindow.size else energyWindowIndex
        if (size == 0) return MIN_ENERGY_DB

        var sum = 0f
        for (i in 0 until size) {
            sum += recentEnergyWindow[i]
        }
        return sum / size
    }

    /**
     * ✅ Calcula confianza considerando baseline calibrado y delta
     */
    private fun calculateConfidence(
        energyDb: Float,
        threshold: Float,
        consecutiveFrames: Int,
        freqAnalysis: AudioFilter.FrequencyAnalysis,
        isPlaybackActive: Boolean,
        baselineDb: Float,
        deltaEnergy: Float
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

        // ✅ Gran bonus por delta súbito (usando variable configurable ajustada por volumen)
        if (isPlaybackActive && deltaEnergy > deltaVoiceThresholdDb) {
            confidence += 0.12f
        }

        // Bonus si está claramente por encima del baseline
        if (isPlaybackActive && baselineDb > -60f) {
            val marginVsBaseline = energyDb - baselineDb
            if (marginVsBaseline > 10f && marginVsBaseline < MAX_ENERGY_ABOVE_BASELINE_DB) {
                confidence += 0.08f
            }
        }

        // Penalización durante playback (solo si no hay delta fuerte)
        if (isPlaybackActive && deltaEnergy < deltaVoiceThresholdDb) {
            confidence *= 0.88f
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

        Log.i(TAG, "📊 VAD Final Stats:")
        Log.i(TAG, "   Total frames: $totalFrames")
        Log.i(TAG, "   Voice frames: $acceptedFrames (${String.format("%.1f%%", acceptanceRate)})")
        Log.i(TAG, "   Rejected frames: $rejected")
        Log.i(TAG, "   Calibrated baseline: ${String.format("%.1f", calibratedBaselineDb)}dB")
        Log.d(TAG, "🔧 Energy VAD released")
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
     * Mantiene historial de energía para estimar ruido de fondo.
     */
    private fun updateEnergyHistory(energyDb: Float) {
        energyHistory[historyIndex] = energyDb
        historyIndex = (historyIndex + 1) % HISTORY_SIZE

        if (historyIndex == 0 && !historyFilled) {
            historyFilled = true
        }
    }

    /**
     * Estima ruido de fondo usando percentil 25.
     */
    private fun estimateNoiseFloor(): Float {
        val size = if (historyFilled) HISTORY_SIZE else historyIndex
        if (size == 0) return MIN_ENERGY_DB

        val sorted = energyHistory.copyOf(size).sortedArray()
        val percentile25Index = (size * 0.25).toInt()

        return sorted[percentile25Index]
    }

    /**
     * ✅ Calcula correlación cruzada entre micrófono y far-end
     */
    private fun calculateCorrelationWithFarEnd(micSamples: ShortArray, length: Int): Float {
        synchronized(farEndBuffer) {
            if (farEndBuffer.size < length) return 0f

            val farEndArray = farEndBuffer.takeLast(length)

            var sumMic = 0.0
            var sumFar = 0.0
            var sumMicFar = 0.0
            var sumMicSq = 0.0
            var sumFarSq = 0.0

            for (i in 0 until length) {
                val mic = micSamples[i] / 32768.0
                val far = farEndArray[i] / 32768.0

                sumMic += mic
                sumFar += far
                sumMicFar += mic * far
                sumMicSq += mic * mic
                sumFarSq += far * far
            }

            val n = length.toDouble()
            val numerator = n * sumMicFar - sumMic * sumFar
            val denominator = sqrt((n * sumMicSq - sumMic * sumMic) * (n * sumFarSq - sumFar * sumFar))

            return if (denominator > 1e-10) {
                (numerator / denominator).toFloat().absoluteValue.coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }
}