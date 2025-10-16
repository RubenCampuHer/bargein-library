package com.aima.bargein.audio

import kotlin.math.*

/**
 * AudioFilter
 * Filtro paso-alto simple (Butterworth 1er orden) ajustado a 44.1kHz
 *
 * - Elimina graves < 800Hz (ruido de fondo, eco del altavoz)
 * - Mantiene el contenido relevante de voz (800Hz–8kHz)
 * - Se usa antes del cálculo de energía RMS y ZCR
 */
class AudioFilter(
    private val cutoffFreq: Float = 800f, // ✅ 800Hz para 44.1kHz
    private val sampleRate: Int = 44100
) {
    private var prevInput = 0.0
    private var prevOutput = 0.0

    private val rc = 1.0 / (2 * Math.PI * cutoffFreq)
    private val dt = 1.0 / sampleRate
    private val alpha = rc / (rc + dt)

    /**
     * Aplica el filtro paso-alto a un frame de audio PCM16.
     */
    fun process(samples: ShortArray): ShortArray {
        val filtered = ShortArray(samples.size)
        for (i in samples.indices) {
            val input = samples[i].toDouble()
            val output = alpha * (prevOutput + input - prevInput)
            filtered[i] = output.coerceIn(-32768.0, 32767.0).toInt().toShort()
            prevInput = input
            prevOutput = output
        }
        return filtered
    }

    /**
     * Calcula energía RMS en dBFS del frame.
     */
    fun calculateEnergyDb(samples: ShortArray): Float {
        var sum = 0.0
        for (s in samples) {
            val normalized = s / 32768.0
            sum += normalized * normalized
        }
        val rms = sqrt(sum / samples.size)
        return (20 * log10(rms + 1e-10)).toFloat()
    }

    /**
     * Calcula el Zero Crossing Rate (ZCR)
     * Útil para distinguir entre voz y ruido estacionario.
     */
    fun calculateZCR(samples: ShortArray): Float {
        var zeroCrossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i - 1] > 0 && samples[i] <= 0) ||
                (samples[i - 1] < 0 && samples[i] >= 0)
            ) {
                zeroCrossings++
            }
        }
        return zeroCrossings.toFloat() / samples.size
    }

    /**
     * Devuelve la energía promedio y el ZCR para un frame.
     */
    fun analyzeFrame(samples: ShortArray): FrameFeatures {
        val filtered = process(samples)
        val energyDb = calculateEnergyDb(filtered)
        val zcr = calculateZCR(filtered)
        return FrameFeatures(energyDb, zcr)
    }

    data class FrameFeatures(
        val energyDb: Float,
        val zcr: Float
    )
}