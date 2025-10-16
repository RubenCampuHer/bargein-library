package com.aima.bargein.vad

import com.aima.bargein.audio.AudioFilter
import timber.log.Timber
import kotlin.math.*

/**
 * AdaptiveEnergyVAD - Detector de voz adaptativo a 44.1kHz
 *
 * Estrategia de detección:
 * 1. Durante playback: detecta INCREMENTOS de energía (voz se SUMA al altavoz)
 * 2. Sin playback: threshold fijo simple
 * 3. Múltiples criterios de confirmación
 * 4. Filtro temporal para evitar falsos positivos
 */
class AdaptiveEnergyVAD(
    private val sampleRate: Int = 44100
) : IVoiceActivityDetector {

    // ========== CONFIGURACIÓN ==========
    private val filter = AudioFilter(cutoffFreq = 800f, sampleRate = sampleRate)

    // Ventana para baseline (2 segundos = ~172 frames @ 512 samples)
    private val baselineWindow = ArrayDeque<Float>(200)
    private val baselineWindowSize = 172  // ✅ 2 segundos en lugar de 1

    // ✅ NUEVO: Frames mínimos para calibración (2 segundos completos)
    private val minCalibrationFrames = 172  // ✅ ~2 segundos a 44.1kHz

    // Historial de energía para variación temporal
    private val energyHistory = ArrayDeque<Float>(20)

    // Ventana de variación (10 frames = ~116ms)
    private val variationWindow = ArrayDeque<Float>(10)

    // Estado
    private var isPlaybackActive = false
    private var currentBaseline = -60f
    private var dynamicThreshold = -46f
    private var previousEnergy = -60f

    // ✅ NUEVO: Controlar inicio de calibración
    private var calibrationStarted = false
    private var calibrationCompleted = false  // ✅ NUEVO: Evitar spam de logs
    private val minEnergyToStartCalibration = -80f

    // ✅ NUEVO: Modo de sensibilidad configurable
    private var sensitivityMode = SensitivityMode.NORMAL

    enum class SensitivityMode(val minDelta: Float, val description: String) {
        SUPER_SENSITIVE(11.0f, "Ultra conservador - casi sin falsos positivos"),
        NORMAL(13.0f, "Balance óptimo - recomendado"),
        SENSITIVE(15.0f, "Muy estricto - requiere hablar fuerte")
    }

    // Contadores para confirmación temporal
    private var consecutiveVoiceFrames = 0
    private var consecutiveNoiseFrames = 0
    private var totalFrames = 0L
    private var voiceFrames = 0L

    // ========== THRESHOLDS AJUSTABLES ==========
    // ✅ Ahora se usa sensitivityMode en lugar de constantes fijas
    private val maxDeltaForNoise = 2.5f        // Fluctuación normal del altavoz
    private val minVariationForVoice = 4.0f    // Variación temporal típica de voz
    private val minFramesForConfirmation = 3   // Frames consecutivos requeridos

    // Logging controlado
    private var lastLogTime = System.currentTimeMillis()
    private val logIntervalMs = 500L

    // ========== INTERFACE IMPLEMENTATION ==========

    override fun initialize(sampleRate: Int, mode: IVoiceActivityDetector.AggressivenessMode): Boolean {
        Timber.i("✅ AdaptiveEnergyVAD initialized")
        Timber.i("   Sample rate: ${this.sampleRate}Hz")
        Timber.i("   Filter cutoff: 800Hz")
        Timber.i("   Sensitivity mode: ${sensitivityMode.name} (${sensitivityMode.minDelta}dB)")
        Timber.i("   Min delta: ${sensitivityMode.minDelta}dB")
        Timber.i("   Min variation: ${minVariationForVoice}dB")
        Timber.i("   Min frames: $minFramesForConfirmation")
        Timber.i("   Calibration time: ~2 seconds ($minCalibrationFrames frames)")
        return true
    }

    /**
     * ✅ NUEVO: Configurar modo de sensibilidad
     */
    fun setSensitivityMode(mode: SensitivityMode) {
        sensitivityMode = mode
        Timber.i("🎚️ Sensitivity mode changed to: ${mode.name}")
        Timber.i("   Min delta required: ${mode.minDelta}dB")
        Timber.i("   ${mode.description}")
    }

    override fun processFrame(samples: ShortArray, length: Int): IVoiceActivityDetector.VadResult {
        totalFrames++
        val timestamp = System.nanoTime()

        // 1. Aplicar filtro paso-alto (elimina graves <800Hz)
        val features = filter.analyzeFrame(samples)
        val currentEnergy = features.energyDb

        // 2. Actualizar historial
        energyHistory.addLast(currentEnergy)
        if (energyHistory.size > 20) {
            energyHistory.removeFirst()
        }

        // 3. Sin playback = threshold simple
        if (!isPlaybackActive) {
            val hasVoice = currentEnergy > -46f
            val confidence = if (hasVoice) {
                calculateConfidence(currentEnergy, -46f)
            } else {
                0f
            }

            if (hasVoice) voiceFrames++

            return IVoiceActivityDetector.VadResult(
                hasVoice = hasVoice,
                confidence = confidence,
                energyDb = currentEnergy,
                timestamp = timestamp,
                metadata = mapOf(
                    "mode" to "no_playback",
                    "threshold" to "-46dB"
                )
            )
        }

        // 4. Durante playback: actualizar baseline adaptativo

        // ✅ PASO 1: Detectar cuando el audio REALMENTE empieza
        if (!calibrationStarted) {
            if (currentEnergy > minEnergyToStartCalibration) {
                calibrationStarted = true
                Timber.i("═══════════════════════════════════════════════")
                Timber.i("🎵 AUDIO DETECTADO - Iniciando calibración")
                Timber.i("⏳ Calibrando durante 1 segundo (~86 frames)...")
                Timber.i("🚫 Detección de voz DESHABILITADA temporalmente")
                Timber.i("═══════════════════════════════════════════════")
            } else {
                // Silencio inicial - ignorar
                return IVoiceActivityDetector.VadResult(
                    hasVoice = false,
                    confidence = 0f,
                    energyDb = currentEnergy,
                    timestamp = timestamp,
                    metadata = mapOf(
                        "mode" to "waiting_for_audio",
                        "energy" to String.format("%.1f", currentEnergy)
                    )
                )
            }
        }

        // ✅ PASO 2: Actualizar baseline solo con audio real
        updateBaseline(currentEnergy)

        // ✅ PASO 3: CALIBRACIÓN OBLIGATORIA - 1 segundo completo
        if (baselineWindow.size < minCalibrationFrames) {
            val progress = (baselineWindow.size * 100 / minCalibrationFrames)

            // Log cada 10 frames durante calibración
            if (baselineWindow.size % 10 == 0) {
                Timber.i("⏳ CALIBRANDO: ${baselineWindow.size}/${minCalibrationFrames} frames (${progress}%) | " +
                        "E:${String.format("%.1f", currentEnergy)}dB | " +
                        "B:${String.format("%.1f", currentBaseline)}dB")
            }

            return IVoiceActivityDetector.VadResult(
                hasVoice = false,
                confidence = 0f,
                energyDb = currentEnergy,
                timestamp = timestamp,
                metadata = mapOf(
                    "mode" to "calibrating",
                    "progress" to "$progress%",
                    "frames" to "${baselineWindow.size}/$minCalibrationFrames",
                    "baseline" to String.format("%.1f", currentBaseline)
                )
            )
        }

        // ✅ Calibración completada - log una sola vez
        if (baselineWindow.size == minCalibrationFrames && !calibrationCompleted) {
            calibrationCompleted = true  // ✅ Marcar como completado
            Timber.i("✅ CALIBRACIÓN COMPLETADA!")
            Timber.i("   Baseline estabilizado en: ${String.format("%.1f", currentBaseline)}dB")
            Timber.i("   Threshold dinámico: ${String.format("%.1f", dynamicThreshold)}dB")
            Timber.i("   Delta mínimo requerido: ${sensitivityMode.minDelta}dB")
            Timber.i("   Modo: ${sensitivityMode.name}")
            Timber.i("🎯 Sistema listo para detectar voz")
        }

        // 5. Calcular delta de energía
        val deltaDb = currentEnergy - currentBaseline

        // 6. Calcular variación temporal
        val variation = calculateVariation()

        // 7. Detección multi-criterio
        val detection = detectVoiceMultiCriteria(currentEnergy, deltaDb, variation)

        // 8. Filtro temporal (requiere frames consecutivos)
        val finalResult = applyTemporalFilter(detection)

        // 9. Logging controlado
        val now = System.currentTimeMillis()
        if (now - lastLogTime > logIntervalMs || finalResult.hasVoice) {
            logDetectionState(currentEnergy, deltaDb, variation, finalResult, features.zcr)
            lastLogTime = now
        }

        previousEnergy = currentEnergy

        return IVoiceActivityDetector.VadResult(
            hasVoice = finalResult.hasVoice,
            confidence = finalResult.confidence,
            energyDb = currentEnergy,
            timestamp = timestamp,
            metadata = mapOf(
                "baseline" to String.format("%.1f", currentBaseline),
                "delta" to String.format("%.1f", deltaDb),
                "variation" to String.format("%.1f", variation),
                "reason" to finalResult.reason,
                "zcr" to String.format("%.3f", features.zcr)
            )
        )
    }

    override fun setPlaybackActive(active: Boolean) {
        isPlaybackActive = active

        if (active) {
            // Reset para nueva reproducción
            baselineWindow.clear()
            energyHistory.clear()
            variationWindow.clear()
            consecutiveVoiceFrames = 0
            consecutiveNoiseFrames = 0
            currentBaseline = -60f
            totalFrames = 0
            calibrationStarted = false
            calibrationCompleted = false  // ✅ NUEVO: Reset flag

            Timber.i("═══════════════════════════════════════════════")
            Timber.i("🔊 PLAYBACK INICIADO")
            Timber.i("⏳ Esperando que el audio comience...")
            Timber.i("🚫 Ignorando silencio inicial")
            Timber.i("🎚️ Modo: ${sensitivityMode.name} (Δ≥${sensitivityMode.minDelta}dB)")
            Timber.i("═══════════════════════════════════════════════")
        } else {
            calibrationStarted = false
            calibrationCompleted = false
            Timber.i("═══════════════════════════════════════════════")
            Timber.i("🔕 PLAYBACK DETENIDO")
            Timber.i("═══════════════════════════════════════════════")
        }
    }

    override fun release() {
        baselineWindow.clear()
        energyHistory.clear()
        variationWindow.clear()
        Timber.d("🔧 AdaptiveEnergyVAD released")
    }

    override fun getType(): IVoiceActivityDetector.Type {
        return IVoiceActivityDetector.Type.ENERGY
    }

    override fun getMetrics(): IVoiceActivityDetector.VadMetrics {
        return IVoiceActivityDetector.VadMetrics(
            framesProcessed = totalFrames,
            voiceFrames = voiceFrames,
            averageConfidence = if (voiceFrames > 0) voiceFrames / totalFrames.toFloat() else 0f,
            averageProcessingTimeUs = 0,
            metadata = mapOf(
                "baseline" to currentBaseline,
                "dynamicThreshold" to dynamicThreshold,
                "playbackActive" to isPlaybackActive
            )
        )
    }

    // ========== MÉTODOS PRIVADOS ==========

    private fun updateBaseline(currentEnergy: Float) {
        // ✅ Ignorar valores de silencio extremos
        if (currentEnergy < -100f) {
            return
        }

        baselineWindow.addLast(currentEnergy)
        if (baselineWindow.size > baselineWindowSize) {
            baselineWindow.removeFirst()
        }

        // Calcular mediana (robusta contra outliers de voz)
        if (baselineWindow.size >= 20) {
            val sorted = baselineWindow.sorted()
            currentBaseline = sorted[sorted.size / 2]

            // Threshold dinámico: baseline + margen
            dynamicThreshold = currentBaseline + 5.0f
        }
    }

    private fun calculateVariation(): Float {
        if (energyHistory.size < 5) return 0f

        // Desviación estándar de energía reciente (últimos 10 frames)
        val recent = energyHistory.takeLast(10)
        val mean = recent.average().toFloat()
        val variance = recent.map { (it - mean).pow(2) }.average()
        return sqrt(variance.toFloat())
    }

    private data class DetectionResult(
        val hasVoice: Boolean,
        val confidence: Float,
        val reason: String
    )

    private fun detectVoiceMultiCriteria(
        energy: Float,
        delta: Float,
        variation: Float
    ): DetectionResult {

        // ✅ Usar threshold del modo de sensibilidad
        val minDeltaForVoice = sensitivityMode.minDelta

        // ========== CRITERIO 1: Delta significativo (PRIMARY Y ÚNICO DURANTE PLAYBACK) ==========
        if (delta >= minDeltaForVoice) {
            return DetectionResult(
                hasVoice = true,
                confidence = min(1.0f, (delta - minDeltaForVoice) / 10f + 0.6f),
                reason = "Delta: +${String.format("%.1f", delta)}dB ✅"
            )
        }

        // ========== CRITERIO 2: Alta energía absoluta + delta moderado ==========
        // ⚠️ DESHABILITADO durante playback para evitar falsos positivos
        // Solo útil cuando NO hay playback
        if (!isPlaybackActive && energy > -25f && delta > 4.0f) {
            return DetectionResult(
                hasVoice = true,
                confidence = 0.70f,
                reason = "High energy (${String.format("%.1f", energy)}dB) + delta"
            )
        }

        // ========== CRITERIO 3: Alta variación temporal + delta ==========
        // ⚠️ DESHABILITADO - Causa falsos positivos con audio dinámico del asistente
        /*
        variationWindow.addLast(variation)
        if (variationWindow.size > 10) {
            variationWindow.removeFirst()
        }

        val avgVariation = if (variationWindow.isNotEmpty()) {
            variationWindow.average().toFloat()
        } else {
            0f
        }

        if (avgVariation >= minVariationForVoice && delta > 3.0f) {
            return DetectionResult(
                hasVoice = true,
                confidence = 0.65f,
                reason = "Variation (${String.format("%.1f", avgVariation)}dB) + delta"
            )
        }
        */

        // ========== CRITERIO 4: Spike repentino de energía ==========
        // ⚠️ DESHABILITADO durante playback - Causa falsos positivos
        if (!isPlaybackActive && energyHistory.size >= 3) {
            val recentChange = energy - energyHistory[energyHistory.size - 3]
            if (recentChange > 12f) {  // ✅ Subido de 10f a 12f
                return DetectionResult(
                    hasVoice = true,
                    confidence = 0.75f,
                    reason = "Sharp spike: +${String.format("%.1f", recentChange)}dB"
                )
            }
        }

        // ========== NO VOZ DETECTADA ==========
        // Calcular variación para logging
        variationWindow.addLast(variation)
        if (variationWindow.size > 10) {
            variationWindow.removeFirst()
        }
        val avgVariation = if (variationWindow.isNotEmpty()) {
            variationWindow.average().toFloat()
        } else {
            0f
        }

        val rejectReason = when {
            delta < 0 -> "Energy dropped (${String.format("%.1f", delta)}dB)"
            delta < maxDeltaForNoise -> "Delta too small (${String.format("%.1f", delta)}dB < ${maxDeltaForNoise}dB)"
            delta < minDeltaForVoice -> "Delta insufficient (${String.format("%.1f", delta)}dB < ${minDeltaForVoice}dB)"
            energy < currentBaseline -> "Below baseline"
            avgVariation < 1.0f -> "Low variation (${String.format("%.1f", avgVariation)}dB)"
            else -> "No clear voice indicators"
        }

        return DetectionResult(
            hasVoice = false,
            confidence = 0f,
            reason = rejectReason
        )
    }

    private fun applyTemporalFilter(result: DetectionResult): DetectionResult {
        if (result.hasVoice) {
            consecutiveVoiceFrames++
            consecutiveNoiseFrames = 0

            // Requiere N frames consecutivos para confirmar
            if (consecutiveVoiceFrames >= minFramesForConfirmation) {
                voiceFrames++

                // Aumentar confianza con frames consecutivos
                val boostedConfidence = min(
                    1.0f,
                    result.confidence * (1 + (consecutiveVoiceFrames - minFramesForConfirmation) * 0.1f)
                )

                return result.copy(
                    confidence = boostedConfidence,
                    reason = "${result.reason} [frame #$consecutiveVoiceFrames]"
                )
            } else {
                // Esperando confirmación
                return result.copy(
                    hasVoice = false,
                    confidence = result.confidence * 0.5f,
                    reason = "Waiting confirmation ($consecutiveVoiceFrames/$minFramesForConfirmation)"
                )
            }
        } else {
            // No hay voz
            if (consecutiveVoiceFrames > 0) {
                Timber.v("⏸️ Voice sequence ended after $consecutiveVoiceFrames frames")
            }
            consecutiveVoiceFrames = 0
            consecutiveNoiseFrames++
            return result
        }
    }

    private fun calculateConfidence(energy: Float, threshold: Float): Float {
        val delta = energy - threshold
        return min(1.0f, max(0f, delta / 15f))
    }

    private fun logDetectionState(
        energy: Float,
        delta: Float,
        variation: Float,
        result: DetectionResult,
        zcr: Float
    ) {
        val symbol = if (result.hasVoice) "✅" else "⏸️"
        val frames = totalFrames
        val voicePct = if (frames > 0) (voiceFrames * 100f / frames) else 0f

        Timber.d(
            "$symbol [#$frames] E:${String.format("%.1f", energy)}dB | " +
                    "B:${String.format("%.1f", currentBaseline)}dB | " +
                    "Δ:${String.format("%.1f", delta)}dB | " +
                    "V:${String.format("%.1f", variation)}dB | " +
                    "ZCR:${String.format("%.3f", zcr)} | " +
                    "Conf:${String.format("%.2f", result.confidence)} | " +
                    "Voice:${String.format("%.1f", voicePct)}% | " +
                    result.reason
        )
    }
}