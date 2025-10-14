package com.aima.bargein.audio

import timber.log.Timber
import kotlin.math.*

object AudioFilter {

    fun applyHighPassFilter(
        samples: ShortArray,
        length: Int,
        cutoffFreq: Float = 300f,
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
     * Análisis de frecuencias SIMPLIFICADO pero CORRECTO.
     * Usa Zero-Crossing Rate + Energy Distribution.
     */
    fun analyzeFrequencyBands(
        samples: ShortArray,
        length: Int,
        sampleRate: Int = 16000
    ): FrequencyAnalysis {

        // Calcular Zero-Crossing Rate (indica contenido de alta frecuencia)
        var zeroCrossings = 0
        for (i in 1 until length) {
            if ((samples[i - 1] >= 0 && samples[i] < 0) ||
                (samples[i - 1] < 0 && samples[i] >= 0)) {
                zeroCrossings++
            }
        }

        val zcr = zeroCrossings.toFloat() / length

        // Calcular energía por bandas usando filtros simples
        var totalEnergy = 0.0
        var lowEnergy = 0.0
        var midEnergy = 0.0
        var highEnergy = 0.0

        // Calcular energía total primero
        for (i in 0 until length) {
            val sample = samples[i] / 32768.0
            totalEnergy += sample * sample
        }

        // Estimar distribución de energía usando ZCR y autocorrelación
        // ZCR alto = más alta frecuencia
        // ZCR bajo = más baja frecuencia

        if (zcr < 0.05f) {
            // Señal muy grave (altavoz típico)
            lowEnergy = totalEnergy * 0.70
            midEnergy = totalEnergy * 0.25
            highEnergy = totalEnergy * 0.05
        } else if (zcr < 0.15f) {
            // Señal mixta (posible voz con eco)
            lowEnergy = totalEnergy * 0.40
            midEnergy = totalEnergy * 0.45
            highEnergy = totalEnergy * 0.15
        } else if (zcr < 0.30f) {
            // Voz normal
            lowEnergy = totalEnergy * 0.20
            midEnergy = totalEnergy * 0.50
            highEnergy = totalEnergy * 0.30
        } else {
            // Voz aguda o sibilante
            lowEnergy = totalEnergy * 0.10
            midEnergy = totalEnergy * 0.40
            highEnergy = totalEnergy * 0.50
        }

        // Calcular energía en ventanas para refinar
        val windowSize = length / 4
        var highFreqContent = 0.0

        for (window in 0 until 4) {
            val start = window * windowSize
            val end = minOf(start + windowSize, length)

            var windowZC = 0
            for (i in (start + 1) until end) {
                if ((samples[i - 1] >= 0 && samples[i] < 0) ||
                    (samples[i - 1] < 0 && samples[i] >= 0)) {
                    windowZC++
                }
            }

            val windowZCR = windowZC.toFloat() / (end - start)
            if (windowZCR > 0.20f) {
                highFreqContent += 1.0
            }
        }

        // Ajustar energía alta según contenido real
        highEnergy *= (0.5 + highFreqContent / 4.0)

        // Normalizar
        val sum = lowEnergy + midEnergy + highEnergy
        if (sum > 0) {
            lowEnergy /= sum
            midEnergy /= sum
            highEnergy /= sum
        }

        Timber.v("ZCR: ${String.format("%.3f", zcr)}, " +
                "L: ${String.format("%.2f", lowEnergy)}, " +
                "M: ${String.format("%.2f", midEnergy)}, " +
                "H: ${String.format("%.2f", highEnergy)}")

        return FrequencyAnalysis(
            lowBandEnergy = lowEnergy.toFloat(),
            midBandEnergy = midEnergy.toFloat(),
            highBandEnergy = highEnergy.toFloat(),
            veryHighBandEnergy = 0f, // No usado en este método
            zeroCrossingRate = zcr
        )
    }

    data class FrequencyAnalysis(
        val lowBandEnergy: Float,
        val midBandEnergy: Float,
        val highBandEnergy: Float,
        val veryHighBandEnergy: Float,
        val zeroCrossingRate: Float = 0f
    ) {
        /**
         * Detectar si es eco del altavoz.
         */
        fun isLikelySpeakerEcho(): Boolean {
            // Criterio 1: ZCR muy bajo = graves = altavoz
            if (zeroCrossingRate < 0.08f) {
                Timber.v("→ Speaker echo: Low ZCR (${String.format("%.3f", zeroCrossingRate)})")
                return true
            }

            // Criterio 2: Mucha energía en graves
            if (lowBandEnergy > 0.50f) {
                Timber.v("→ Speaker echo: High low energy (${String.format("%.0f%%", lowBandEnergy * 100)})")
                return true
            }

            // Criterio 3: Poca energía en agudos
            if (highBandEnergy < 0.10f && lowBandEnergy > 0.30f) {
                Timber.v("→ Speaker echo: Low high energy (${String.format("%.0f%%", highBandEnergy * 100)})")
                return true
            }

            return false
        }

        /**
         * Detectar voz humana real.
         */
        fun isLikelyRealVoice(): Boolean {
            // Criterio 1: ZCR moderado-alto
            if (zeroCrossingRate < 0.10f) {
                Timber.v("→ Not voice: ZCR too low (${String.format("%.3f", zeroCrossingRate)})")
                return false
            }

            // Criterio 2: Energía en medias-altas
            val combinedMidHigh = midBandEnergy + highBandEnergy
            if (combinedMidHigh < 0.50f) {
                Timber.v("→ Not voice: Low mid+high (${String.format("%.0f%%", combinedMidHigh * 100)})")
                return false
            }

            // Criterio 3: No debe tener demasiados graves
            if (lowBandEnergy > 0.60f) {
                Timber.v("→ Not voice: Too much low (${String.format("%.0f%%", lowBandEnergy * 100)})")
                return false
            }

            Timber.v("→ IS VOICE: ZCR=${String.format("%.3f", zeroCrossingRate)}, " +
                    "mid+high=${String.format("%.0f%%", combinedMidHigh * 100)}")
            return true
        }

        fun getDebugInfo(): String {
            return "ZCR=${String.format("%.3f", zeroCrossingRate)}, " +
                    "L=${String.format("%.0f%%", lowBandEnergy * 100)}, " +
                    "M=${String.format("%.0f%%", midBandEnergy * 100)}, " +
                    "H=${String.format("%.0f%%", highBandEnergy * 100)}"
        }
    }
}