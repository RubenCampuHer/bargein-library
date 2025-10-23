package com.aima.bargein.audio

import kotlin.math.*

object AudioFilter {

    /**
     * Filtro paso-alto para eliminar graves del altavoz.
     * ⚠️ Reducido a 600Hz para preservar más voz
     */
    fun applyHighPassFilter(
        samples: ShortArray,
        length: Int,
        cutoffFreq: Float = 600f, // ⚠️ Reducido de 800Hz a 600Hz
        sampleRate: Int = 44100
    ) {
        val rc = 1.0f / (2.0f * PI.toFloat() * cutoffFreq)
        val dt = 1.0f / sampleRate
        val alpha = rc / (rc + dt)

        var previousInput = 0f
        var previousOutput = 0f

        for (i in 0 until length) {
            val input = samples[i].toFloat()
            val output = alpha * (previousOutput + input - previousInput)
            samples[i] = output.toInt().coerceIn(-32768, 32767).toShort()
            previousInput = input
            previousOutput = output
        }
    }

    /**
     * Análisis de frecuencias MEJORADO usando ZCR + Autocorrelación.
     * ✅ AJUSTADO para 44.1kHz
     */
    fun analyzeFrequencyBands(
        samples: ShortArray,
        length: Int,
        sampleRate: Int = 44100
    ): FrequencyAnalysis {

        // ===== 1. CALCULAR ZCR =====
        var zeroCrossings = 0
        for (i in 1 until length) {
            if ((samples[i - 1] >= 0 && samples[i] < 0) ||
                (samples[i - 1] < 0 && samples[i] >= 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toFloat() / length

        // ===== 2. CALCULAR ENERGÍA TOTAL =====
        var totalEnergy = 0.0
        for (i in 0 until length) {
            val normalized = samples[i] / 32768.0
            totalEnergy += normalized * normalized
        }

        // ===== 3. AUTOCORRELACIÓN (detecta periodicidad) =====
        val periodicity = calculatePeriodicity(samples, length, sampleRate)

        // ===== 4. ANÁLISIS POR VENTANAS =====
        val numWindows = 4
        val windowSize = length / numWindows
        var highFreqWindows = 0
        var lowFreqWindows = 0

        for (w in 0 until numWindows) {
            val start = w * windowSize
            val end = minOf(start + windowSize, length)

            var windowZC = 0
            for (i in (start + 1) until end) {
                if ((samples[i - 1] >= 0 && samples[i] < 0) ||
                    (samples[i - 1] < 0 && samples[i] >= 0)) {
                    windowZC++
                }
            }

            val windowZCR = windowZC.toFloat() / (end - start)

            // ✅ Ajustado para 44.1kHz: umbrales más altos porque hay más samples
            if (windowZCR > 0.20f) {
                highFreqWindows++
            } else if (windowZCR < 0.10f) {
                lowFreqWindows++
            }
        }

        val highFreqRatio = highFreqWindows / numWindows.toFloat()
        val lowFreqRatio = lowFreqWindows / numWindows.toFloat()

        // ===== 5. ESTIMACIÓN DE BANDAS MEJORADA =====
        var lowEnergy: Float
        var midEnergy: Float
        var highEnergy: Float

        when {
            // Caso 1: Altavoz típico (ZCR bajo, baja periodicidad)
            zcr < 0.08f && periodicity < 0.3f -> {
                lowEnergy = 0.75f
                midEnergy = 0.20f
                highEnergy = 0.05f
            }

            // Caso 2: Voz real (ZCR medio-alto, alta periodicidad)
            zcr in 0.09f..0.40f && periodicity > 0.4f -> {
                lowEnergy = 0.20f
                midEnergy = 0.48f
                highEnergy = 0.32f
            }

            // Caso 3: Posible mezcla (eco + voz)
            zcr in 0.08f..0.18f -> {
                if (highFreqRatio > 0.5f) {
                    lowEnergy = 0.30f
                    midEnergy = 0.45f
                    highEnergy = 0.25f
                } else {
                    lowEnergy = 0.60f
                    midEnergy = 0.30f
                    highEnergy = 0.10f
                }
            }

            // Caso 4: Señal muy aguda o sibilante
            zcr > 0.40f -> {
                lowEnergy = 0.10f
                midEnergy = 0.35f
                highEnergy = 0.55f
            }

            // Caso 5: Por defecto
            else -> {
                lowEnergy = 0.40f
                midEnergy = 0.40f
                highEnergy = 0.20f
            }
        }

        return FrequencyAnalysis(
            lowBandEnergy = lowEnergy,
            midBandEnergy = midEnergy,
            highBandEnergy = highEnergy,
            veryHighBandEnergy = 0f,
            zeroCrossingRate = zcr,
            periodicity = periodicity,
            highFreqWindowRatio = highFreqRatio,
            lowFreqWindowRatio = lowFreqRatio
        )
    }

    /**
     * Calcula periodicidad usando autocorrelación simplificada.
     * ✅ AJUSTADO para 44.1kHz: lags escalados ~2.76x
     */
    private fun calculatePeriodicity(
        samples: ShortArray,
        length: Int,
        sampleRate: Int = 44100
    ): Float {
        if (length < 110) return 0f

        // ✅ Rango de pitch de voz a 44.1kHz:
        // 80-300Hz → lags de 147 a 551
        val minLag = 147  // ~300Hz
        val maxLag = 551  // ~80Hz

        var maxCorr = 0.0
        var energy = 0.0

        // Calcular energía
        for (i in 0 until length) {
            val normalized = samples[i] / 32768.0
            energy += normalized * normalized
        }

        if (energy < 1e-10) return 0f

        // Buscar máxima autocorrelación
        for (lag in minLag until minOf(maxLag, length / 2)) {
            var corr = 0.0
            val validLength = length - lag

            for (i in 0 until validLength) {
                val s1 = samples[i] / 32768.0
                val s2 = samples[i + lag] / 32768.0
                corr += s1 * s2
            }

            corr /= validLength
            if (corr > maxCorr) {
                maxCorr = corr
            }
        }

        return (maxCorr / energy).toFloat().coerceIn(0f, 1f)
    }

    data class FrequencyAnalysis(
        val lowBandEnergy: Float,
        val midBandEnergy: Float,
        val highBandEnergy: Float,
        val veryHighBandEnergy: Float,
        val zeroCrossingRate: Float = 0f,
        val periodicity: Float = 0f,
        val highFreqWindowRatio: Float = 0f,
        val lowFreqWindowRatio: Float = 0f
    ) {
        /**
         * Detecta si es eco del altavoz con MÚLTIPLES criterios.
         * ⚠️ ULTRA PERMISIVO - Solo rechaza eco OBVIO
         */
        fun isLikelySpeakerEcho(): Boolean {
            // ✅ SOLO rechazar si es EXTREMADAMENTE obvio que es eco

            // Criterio 1: ZCR EXTREMADAMENTE bajo + TODOS los demás indicadores
            if (zeroCrossingRate < 0.03f &&
                lowBandEnergy > 0.85f &&
                periodicity < 0.01f &&
                highFreqWindowRatio == 0f) {
                return true
            }

            // Criterio 2: Sin ninguna ventana de alta frecuencia + graves extremos
            if (highFreqWindowRatio == 0f &&
                lowFreqWindowRatio > 0.95f &&
                lowBandEnergy > 0.85f) {
                return true
            }

            // Por defecto: NO es eco (aceptar casi todo)
            return false
        }

        /**
         * Detecta voz humana real con MÚLTIPLES criterios.
         * ⚠️ ULTRA PERMISIVO - Acepta casi cualquier cosa con algo de energía
         */
        fun isLikelyRealVoice(): Boolean {
            // ✅ Solo verificar que NO sea silencio absoluto

            // Criterio único: Algo de energía en cualquier banda
            val totalEnergy = lowBandEnergy + midBandEnergy + highBandEnergy
            if (totalEnergy < 0.50f) {
                return false
            }

            // Todo lo demás es aceptado como posible voz
            return true
        }

        fun getDebugInfo(): String {
            return "ZCR=${String.format("%.3f", zeroCrossingRate)}, " +
                    "L=${String.format("%.0f%%", lowBandEnergy * 100)}, " +
                    "M=${String.format("%.0f%%", midBandEnergy * 100)}, " +
                    "H=${String.format("%.0f%%", highBandEnergy * 100)}, " +
                    "period=${String.format("%.2f", periodicity)}, " +
                    "hiWin=${String.format("%.0f%%", highFreqWindowRatio * 100)}"
        }
    }
}