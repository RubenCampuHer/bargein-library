package com.aima.bargein.audio

import timber.log.Timber
import kotlin.math.*

object AudioFilter {

    /**
     * Filtro paso-alto mejorado para eliminar graves del altavoz.
     * Cutoff aumentado a 450Hz para ser más agresivo.
     */
    fun applyHighPassFilter(
        samples: ShortArray,
        length: Int,
        cutoffFreq: Float = 450f, // ✅ Más agresivo contra altavoz
        sampleRate: Int = 16000
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
     *
     * Combina:
     * 1. Zero-Crossing Rate (ZCR) - mide contenido de alta frecuencia
     * 2. Autocorrelación - detecta periodicidad (voz tiene más estructura)
     * 3. Análisis de ventanas - verifica consistencia temporal
     */
    fun analyzeFrequencyBands(
        samples: ShortArray,
        length: Int,
        sampleRate: Int = 16000
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
        val periodicity = calculatePeriodicity(samples, length)

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

            if (windowZCR > 0.15f) {
                highFreqWindows++
            } else if (windowZCR < 0.08f) {
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
            zcr < 0.065f && periodicity < 0.3f -> {
                lowEnergy = 0.75f
                midEnergy = 0.20f
                highEnergy = 0.05f
            }

            // Caso 2: Voz real (ZCR medio-alto, alta periodicidad)
            zcr in 0.072f..0.35f && periodicity > 0.4f -> {
                lowEnergy = 0.20f
                midEnergy = 0.48f
                highEnergy = 0.32f
            }

            // Caso 3: Posible mezcla (eco + voz)
            zcr in 0.065f..0.15f -> {
                // Usar ventanas para decidir
                if (highFreqRatio > 0.5f) {
                    // Más alta frecuencia = voz
                    lowEnergy = 0.30f
                    midEnergy = 0.45f
                    highEnergy = 0.25f
                } else {
                    // Más baja frecuencia = altavoz
                    lowEnergy = 0.60f
                    midEnergy = 0.30f
                    highEnergy = 0.10f
                }
            }

            // Caso 4: Señal muy aguda o sibilante
            zcr > 0.35f -> {
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
     * Valores altos (>0.5) indican señal periódica (voz estructurada).
     * Valores bajos (<0.3) indican señal caótica (altavoz/ruido).
     */
    private fun calculatePeriodicity(samples: ShortArray, length: Int): Float {
        if (length < 40) return 0f

        // Buscar autocorrelación en el rango de pitch de voz (80-300Hz @ 16kHz)
        val minLag = 50  // ~320Hz
        val maxLag = 200 // ~80Hz

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
         * ✅ MÁS ESTRICTO - Rechaza ruido y altavoz mejor
         */
        fun isLikelySpeakerEcho(): Boolean {
            // Criterio 1: ZCR bajo (típico de altavoz/ruido)
            if (zeroCrossingRate < 0.065f) {
                return true
            }

            // Criterio 2: Energía MUY dominada por graves
            if (lowBandEnergy > 0.65f) {
                return true
            }

            // Criterio 3: Combinación de ZCR bajo-medio + muchos graves
            if (zeroCrossingRate < 0.090f && lowBandEnergy > 0.58f) {
                return true
            }

            // Criterio 4: Casi SIN energía en agudos + muchos graves
            if (highBandEnergy < 0.10f && lowBandEnergy > 0.55f) {
                return true
            }

            // Criterio 5: Baja periodicidad Y baja-media frecuencia
            if (periodicity < 0.15f && zeroCrossingRate < 0.080f) {
                return true
            }

            // Criterio 6: Mayoría de ventanas con baja frecuencia
            if (lowFreqWindowRatio > 0.80f) {
                return true
            }

            // Criterio 7: SIN ventanas de alta frecuencia (típico ruido ambiente)
            if (highFreqWindowRatio == 0f && zeroCrossingRate < 0.100f) {
                return true
            }

            return false
        }

        /**
         * Detecta voz humana real con MÚLTIPLES criterios.
         * ✅ MÁS ESTRICTO - Requiere señales claras de voz
         */
        fun isLikelyRealVoice(): Boolean {
            // Criterio 1: ZCR debe estar en rango típico de voz
            if (zeroCrossingRate < 0.070f) {  // Subido desde 0.058
                return false
            }

            if (zeroCrossingRate > 0.45f) {
                return false
            }

            // Criterio 2: Debe tener energía razonable en medias-altas
            val combinedMidHigh = midBandEnergy + highBandEnergy
            if (combinedMidHigh < 0.32f) {  // Subido desde 0.28
                return false
            }

            // Criterio 3: No debe ser dominado por graves
            if (lowBandEnergy > 0.68f) {  // Bajado desde 0.72
                return false
            }

            // Criterio 4: Debe tener AL MENOS 1 ventana con alta frecuencia
            if (highFreqWindowRatio == 0f) {
                return false
            }

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