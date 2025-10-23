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
                deltaVoiceThresholdDb = config.deltaVoiceThresholdDb
                minAbsoluteVoiceEnergyDb = config.minAbsoluteVoiceEnergyDb
                maxZcrForVoice = config.maxZcrForVoice
                deltaBaselineAdjustmentFactor = config.deltaBaselineAdjustmentFactor

                Log.i(TAG, "✅ Custom VAD thresholds loaded:")
                Log.i(TAG, "   Delta threshold: ${deltaVoiceThresholdDb}dB")
                Log.i(TAG, "   Min energy: ${minAbsoluteVoiceEnergyDb}dB")
                Log.i(TAG, "   Max ZCR: $maxZcrForVoice")
                Log.i(TAG, "   Baseline adjustment: $deltaBaselineAdjustmentFactor")
            } else {
                // Usar valores por defecto
                deltaVoiceThresholdDb = DEFAULT_DELTA_VOICE_THRESHOLD_DB
                minAbsoluteVoiceEnergyDb = DEFAULT_MIN_ABSOLUTE_VOICE_ENERGY_DB
                maxZcrForVoice = DEFAULT_MAX_ZCR_FOR_VOICE
                deltaBaselineAdjustmentFactor = DEFAULT_DELTA_BASELINE_ADJUSTMENT_FACTOR

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
            Log.e(TAG, "❌ Error initializing Energy VAD")
            return false
        }
    }

    /**
     * ✅ Inicia calibración cuando comienza el playback
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
            } else if (timeSinceEnd >= GRACE_PERIOD_MS && timeSinceEnd < GRACE_PERIOD_MS + 100) {
                Log.d(TAG, "✅ Grace period ended - resuming normal detection")
                playbackEndTime = 0L
            }
        }

        val startTime = System.nanoTime()

        // ===== PASO 1: FILTRO PASO-ALTO (600Hz) =====
        val filteredSamples = audioData.copyOf()
        AudioFilter.applyHighPassFilter(
            samples = filteredSamples,
            length = length,
            cutoffFreq = 600f,
            sampleRate = 44100
        )

        // ===== PASO 2: ANÁLISIS DE FRECUENCIAS =====
        val freqAnalysis = AudioFilter.analyzeFrequencyBands(
            samples = filteredSamples,
            length = length,
            sampleRate = 44100
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

        // ✅ PASO 3.5: ACTUALIZAR VENTANA DE ENERGÍA RECIENTE
        updateRecentEnergyWindow(energyDb)

        // ✅ PASO 3.6: CALIBRACIÓN DINÁMICA
        if (isCalibrating) {
            val elapsedMs = System.currentTimeMillis() - calibrationStartTime

            if (elapsedMs < CALIBRATION_DURATION_MS) {
                calibrationFrames.add(energyDb)
                lastEnergyDb = energyDb
                framesProcessed.incrementAndGet()

                if (calibrationFrames.size == 1) {
                    Log.d(TAG, "🎯 Calibrating... (frame 1/${(CALIBRATION_DURATION_MS / 11.6f).toInt()})")
                }

                return IVoiceActivityDetector.VadResult(false, 0f, energyDb, timestamp)
            } else {
                isCalibrating = false
                if (calibrationFrames.isNotEmpty()) {
                    val sorted = calibrationFrames.sorted()
                    val p75Index = (sorted.size * 0.75).toInt().coerceIn(0, sorted.size - 1)
                    calibratedBaselineDb = sorted[p75Index]

                    Log.i(TAG, "✅ Calibration complete!")
                    Log.i(TAG, "   Frames analyzed: ${calibrationFrames.size}")
                    Log.i(TAG, "   Baseline (P75): ${String.format("%.1f", calibratedBaselineDb)}dB")
                    Log.i(TAG, "   Range: ${String.format("%.1f", sorted.first())} to ${String.format("%.1f", sorted.last())}dB")
                }
            }
        }

        // ===== PASO 4: THRESHOLD ADAPTATIVO =====
        var adaptiveThreshold = noiseFloorDb + VOICE_THRESHOLD_MARGIN_DB

        if (isPlaybackActive && calibratedBaselineDb > -60f) {
            val playbackThreshold = calibratedBaselineDb + PLAYBACK_SUPPRESSION_DB
            adaptiveThreshold = adaptiveThreshold.coerceAtLeast(playbackThreshold)
        }

        adaptiveThreshold = adaptiveThreshold.coerceAtLeast(energyThresholdDb)

        // ===== PASO 5: DETECCIÓN CON FILTROS =====
        var hasVoice = false
        var rejectionReason = ""

        // 5.1: Energía mínima absoluta (usando variable configurable)
        if (energyDb <= minAbsoluteVoiceEnergyDb) {
            rejectionReason = "Below absolute minimum (${String.format("%.1f", energyDb)}dB < ${minAbsoluteVoiceEnergyDb}dB)"
        }
        // 5.2: Durante playback - DETECCIÓN POR DELTA (PRINCIPAL)
        else if (isPlaybackActive && !isCalibrating) {
            val avgRecentEnergy = calculateAverageRecentEnergy()
            val deltaEnergy = energyDb - avgRecentEnergy

            // ✅ Delta adaptativo usando variables configurables
            val adjustedDeltaThreshold = if (calibratedBaselineDb > -60f) {
                val baselineAdjustment = (calibratedBaselineDb + 20f) * deltaBaselineAdjustmentFactor
                val adjusted = (deltaVoiceThresholdDb - baselineAdjustment).coerceIn(10f, deltaVoiceThresholdDb)

                if (deltaEnergy > 8f) {
                    Log.d(TAG, "🎚️ Delta threshold adjusted: ${String.format("%.1f", adjusted)}dB " +
                            "(baseline: ${String.format("%.1f", calibratedBaselineDb)}dB, " +
                            "base threshold: ${deltaVoiceThresholdDb}dB, factor: $deltaBaselineAdjustmentFactor)")
                }
                adjusted
            } else {
                deltaVoiceThresholdDb
            }

            // ✅ CRITERIO PRINCIPAL: Incremento súbito de energía (usando threshold ajustado)
            if (deltaEnergy > adjustedDeltaThreshold) {
                // Verificar energía absoluta mínima (usando variable)
                if (energyDb < minAbsoluteVoiceEnergyDb) {
                    rejectionReason = "Delta spike (${String.format("%.1f", deltaEnergy)}dB) but energy too low (${String.format("%.1f", energyDb)}dB < ${minAbsoluteVoiceEnergyDb}dB)"
                    rejectedFrames.incrementAndGet()
                }
                // Verificar ZCR (usando variable)
                else if (freqAnalysis.zeroCrossingRate > maxZcrForVoice) {
                    rejectionReason = "Delta sufficient but ZCR too high (${String.format("%.3f", freqAnalysis.zeroCrossingRate)} > $maxZcrForVoice) - likely noise"
                    rejectedFrames.incrementAndGet()
                }
                // Verificar que no sea eco obvio
                else if (freqAnalysis.isLikelySpeakerEcho()) {
                    rejectionReason = "Delta sufficient (${String.format("%.1f", deltaEnergy)}dB) but echo-like [${freqAnalysis.getDebugInfo()}]"
                    rejectedFrames.incrementAndGet()
                }
                // Verificar correlación con far-end
                else if (hasFarEndReference) {
                    val correlation = calculateCorrelationWithFarEnd(filteredSamples, length)
                    if (correlation > MAX_CORRELATION_THRESHOLD) {
                        rejectionReason = "Delta sufficient but high correlation (${String.format("%.2f", correlation)})"
                        rejectedFrames.incrementAndGet()
                    } else {
                        hasVoice = true
                        consecutiveVoiceFrames++
                        consecutiveRejectedFrames = 0
                        Log.i(TAG, "🎯 DELTA VOICE: Δ=${String.format("%.1f", deltaEnergy)}dB " +
                                "(threshold: ${String.format("%.1f", adjustedDeltaThreshold)}dB, " +
                                "E=${String.format("%.1f", energyDb)}dB, " +
                                "corr=${String.format("%.2f", correlation)})")
                    }
                } else {
                    if (freqAnalysis.isLikelyRealVoice()) {
                        hasVoice = true
                        consecutiveVoiceFrames++
                        consecutiveRejectedFrames = 0
                        Log.i(TAG, "🎯 DELTA VOICE: Δ=${String.format("%.1f", deltaEnergy)}dB " +
                                "(threshold: ${String.format("%.1f", adjustedDeltaThreshold)}dB, " +
                                "E=${String.format("%.1f", energyDb)}dB)")
                    } else {
                        rejectionReason = "Delta sufficient but not voice-like [${freqAnalysis.getDebugInfo()}]"
                        rejectedFrames.incrementAndGet()
                    }
                }
            }
            // Energía alta pero sin incremento súbito
            else if (energyDb > adaptiveThreshold) {
                if (calibratedBaselineDb > -60f &&
                    energyDb - calibratedBaselineDb > MAX_ENERGY_ABOVE_BASELINE_DB) {
                    rejectionReason = "No delta spike (${String.format("%.1f", deltaEnergy)}dB < ${String.format("%.1f", adjustedDeltaThreshold)}dB), too loud vs baseline"
                    rejectedFrames.incrementAndGet()
                } else if (freqAnalysis.isLikelyRealVoice()) {
                    hasVoice = true
                    consecutiveVoiceFrames++
                    consecutiveRejectedFrames = 0
                } else {
                    rejectionReason = "Above threshold but not voice-like [${freqAnalysis.getDebugInfo()}]"
                }
            } else {
                rejectionReason = "No delta spike (${String.format("%.1f", deltaEnergy)}dB < ${String.format("%.1f", adjustedDeltaThreshold)}dB, avg=${String.format("%.1f", avgRecentEnergy)}dB)"
            }
        }
        // 5.3: Sin playback - usar criterios normales
        else if (!isPlaybackActive) {
            if (energyDb > adaptiveThreshold && freqAnalysis.isLikelyRealVoice()) {
                hasVoice = true
                consecutiveVoiceFrames++
                consecutiveRejectedFrames = 0
            } else if (energyDb <= adaptiveThreshold) {
                rejectionReason = "No playback: energy=${String.format("%.1f", energyDb)}dB < threshold=${String.format("%.1f", adaptiveThreshold)}dB"
            } else {
                rejectionReason = "No playback: not voice-like [${freqAnalysis.getDebugInfo()}]"
            }
        }

        lastEnergyDb = energyDb

        if (!hasVoice) {
            consecutiveVoiceFrames = 0
            consecutiveRejectedFrames++
        }

        // Requiere frames consecutivos
        val confirmedVoice = hasVoice && consecutiveVoiceFrames >= MIN_CONSECUTIVE_VOICE_FRAMES

        // ===== PASO 6: LOGGING CONTROLADO =====
        val currentTime = System.currentTimeMillis()
        val shouldLog = (currentTime - lastLogTime) >= LOG_INTERVAL_MS

        if (confirmedVoice) {
            Log.i(TAG, "✅ VOICE CONFIRMED [frame #${consecutiveVoiceFrames}]")
            Log.i(TAG, "   Energy: ${String.format("%.1f", energyDb)}dB (threshold: ${String.format("%.1f", adaptiveThreshold)}dB)")
            Log.i(TAG, "   Playback: $isPlaybackActive | Baseline: ${String.format("%.1f", calibratedBaselineDb)}dB")
            if (isPlaybackActive) {
                val avgRecent = calculateAverageRecentEnergy()
                val delta = energyDb - avgRecent
                Log.i(TAG, "   Delta: ${String.format("%.1f", delta)}dB above recent average")
            }
            Log.i(TAG, "   Analysis: ${freqAnalysis.getDebugInfo()}")
            voiceFrames.incrementAndGet()
            lastLogTime = currentTime
        } else if (shouldLog && rejectionReason.isNotEmpty() && energyDb > -35f) {
            Log.d(TAG, "❌ REJECTED: $rejectionReason")
            lastLogTime = currentTime
        }

        // ===== PASO 7: CALCULAR CONFIANZA =====
        val confidence = if (confirmedVoice) {
            calculateConfidence(
                energyDb = energyDb,
                threshold = adaptiveThreshold,
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

        // ✅ Gran bonus por delta súbito (usando variable configurable)
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