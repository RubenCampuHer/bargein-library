package com.aima.bargein.aec

import timber.log.Timber
import kotlin.math.abs
import kotlin.math.min

/**
 * AEC por Software Simplificado usando Sustracción Adaptativa
 *
 * Principio: Resta la señal del altavoz (far-end) de la señal del micrófono (near-end)
 * para eliminar el eco, pero preserva las diferencias (voz del usuario).
 */
class SimpleAcousticEchoCanceler {

    // Buffer circular para far-end (audio del altavoz)
    private val farEndBuffer = mutableListOf<Short>()
    private val MAX_BUFFER_SIZE = 8192  // ~185ms @ 44.1kHz

    // Coeficiente de adaptación (qué tanto del eco estimamos que llega al mic)
    private var echoGain = 0.5f  // Empezamos con 50%
    private val MIN_GAIN = 0.2f
    private val MAX_GAIN = 0.9f
    private val ADAPTATION_RATE = 0.01f

    // Delay estimado (cuántos samples de retraso entre altavoz y micrófono)
    private var estimatedDelay = 0
    private val MAX_DELAY = 2048  // ~46ms @ 44.1kHz

    @Volatile
    private var isActive = false

    // Estadísticas
    private var framesProcessed = 0L
    private var totalEchoReduction = 0.0

    /**
     * Añade muestras del altavoz al buffer far-end
     */
    fun addFarEndReference(farEndSamples: ShortArray) {
        synchronized(farEndBuffer) {
            farEndSamples.forEach { sample ->
                farEndBuffer.add(sample)
                if (farEndBuffer.size > MAX_BUFFER_SIZE) {
                    farEndBuffer.removeAt(0)
                }
            }
        }
    }

    /**
     * Procesa frame del micrófono, eliminando el eco del altavoz
     *
     * @param nearEndSamples Audio del micrófono (entrada/salida)
     * @param length Número de muestras
     * @return Reducción de eco en dB
     */
    fun processFrame(nearEndSamples: ShortArray, length: Int): Float {
        if (!isActive || farEndBuffer.isEmpty()) {
            return 0f
        }

        synchronized(farEndBuffer) {
            if (farEndBuffer.size < estimatedDelay + length) {
                // No hay suficiente historia del far-end
                return 0f
            }

            // Calcular energía original
            val originalEnergy = calculateEnergy(nearEndSamples, length)

            // Aplicar cancelación de eco
            for (i in 0 until length) {
                val nearEnd = nearEndSamples[i].toInt()

                // Obtener muestra del far-end con delay
                val farEndIndex = farEndBuffer.size - length + i - estimatedDelay
                if (farEndIndex >= 0 && farEndIndex < farEndBuffer.size) {
                    val farEnd = farEndBuffer[farEndIndex].toInt()

                    // Restar el eco estimado
                    val echoEstimate = (farEnd * echoGain).toInt()
                    val cleaned = nearEnd - echoEstimate

                    // Limitar a rango válido
                    nearEndSamples[i] = cleaned.coerceIn(-32768, 32767).toShort()
                }
            }

            // Calcular energía después de cancelación
            val cleanedEnergy = calculateEnergy(nearEndSamples, length)

            // Adaptar ganancia si es necesario
            adaptEchoGain(originalEnergy, cleanedEnergy)

            // Calcular reducción en dB
            val reduction = if (originalEnergy > 0 && cleanedEnergy > 0) {
                10f * kotlin.math.log10(originalEnergy / cleanedEnergy)
            } else {
                0f
            }

            framesProcessed++
            totalEchoReduction += reduction

            if (framesProcessed % 100 == 0L) {
                val avgReduction = totalEchoReduction / framesProcessed
                Timber.v("🔇 AEC: frames=$framesProcessed, gain=${String.format("%.2f", echoGain)}, " +
                        "delay=$estimatedDelay, avgReduction=${String.format("%.1f", avgReduction)}dB")
            }

            return reduction
        }
    }

    /**
     * Adapta el gain basándose en la diferencia de energía
     */
    private fun adaptEchoGain(originalEnergy: Float, cleanedEnergy: Float) {
        if (originalEnergy <= 0f) return

        val ratio = cleanedEnergy / originalEnergy

        when {
            // Si la señal limpia es MUY baja, estamos cancelando demasiado
            ratio < 0.1f -> {
                echoGain = (echoGain - ADAPTATION_RATE).coerceAtLeast(MIN_GAIN)
            }
            // Si la señal limpia es casi igual, no estamos cancelando suficiente
            ratio > 0.8f -> {
                echoGain = (echoGain + ADAPTATION_RATE).coerceAtMost(MAX_GAIN)
            }
            // Ratio entre 0.1 y 0.8 es bueno, mantener gain
        }
    }

    /**
     * Estima el delay entre altavoz y micrófono usando correlación cruzada
     */
    fun estimateDelay(nearEndSamples: ShortArray, length: Int) {
        synchronized(farEndBuffer) {
            if (farEndBuffer.size < MAX_DELAY + length) return

            var maxCorrelation = 0.0
            var bestDelay = 0

            // Probar diferentes delays
            for (delay in 0 until min(MAX_DELAY, farEndBuffer.size - length)) {
                var correlation = 0.0

                for (i in 0 until length) {
                    val farEndIndex = farEndBuffer.size - length + i - delay
                    if (farEndIndex >= 0) {
                        val near = nearEndSamples[i] / 32768.0
                        val far = farEndBuffer[farEndIndex] / 32768.0
                        correlation += near * far
                    }
                }

                if (abs(correlation) > abs(maxCorrelation)) {
                    maxCorrelation = correlation
                    bestDelay = delay
                }
            }

            // Solo actualizar si la correlación es significativa
            if (abs(maxCorrelation) > 0.3) {
                estimatedDelay = bestDelay
                Timber.d("🎯 AEC delay estimated: ${estimatedDelay} samples " +
                        "(${String.format("%.1f", estimatedDelay * 1000f / 44100f)}ms), " +
                        "correlation=${String.format("%.2f", maxCorrelation)}")
            }
        }
    }

    /**
     * Activa/desactiva el AEC
     */
    fun setActive(active: Boolean) {
        isActive = active

        if (active) {
            Timber.i("🔇 Software AEC activated")
            Timber.i("   Initial gain: ${echoGain}")
            Timber.i("   Max delay: ${MAX_DELAY} samples (~${MAX_DELAY * 1000f / 44100f}ms)")
        } else {
            synchronized(farEndBuffer) {
                farEndBuffer.clear()
            }

            if (framesProcessed > 0) {
                val avgReduction = totalEchoReduction / framesProcessed
                Timber.i("🔇 Software AEC deactivated")
                Timber.i("   Frames processed: $framesProcessed")
                Timber.i("   Average reduction: ${String.format("%.1f", avgReduction)}dB")
                Timber.i("   Final gain: ${String.format("%.2f", echoGain)}")
                Timber.i("   Final delay: $estimatedDelay samples")
            }

            framesProcessed = 0L
            totalEchoReduction = 0.0
        }
    }

    /**
     * Reinicia la adaptación (útil cuando cambia el volumen del altavoz)
     */
    fun reset() {
        echoGain = 0.5f
        estimatedDelay = 0
        Timber.d("🔄 AEC reset to defaults")
    }

    /**
     * Calcula la energía RMS de una señal
     */
    private fun calculateEnergy(samples: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val normalized = samples[i] / 32768.0
            sum += normalized * normalized
        }
        return (sum / length).toFloat()
    }

    /**
     * Obtiene el gain actual (para debugging)
     */
    fun getCurrentGain(): Float = echoGain

    /**
     * Obtiene el delay actual (para debugging)
     */
    fun getCurrentDelay(): Int = estimatedDelay
}