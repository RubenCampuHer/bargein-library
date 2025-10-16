package com.aima.bargein.audio

import timber.log.Timber
import kotlin.math.*

/**
 * Generador de tono ultrasónico (piloto) para marcar el audio del altavoz.
 *
 * Añade una señal de alta frecuencia (inaudible) que permite distinguir
 * el audio reproducido por el altavoz de la voz real del usuario.
 */
object UltrasonicToneGenerator {

    /**
     * Frecuencia del tono piloto.
     * Debe ser:
     * - > 8kHz para ser menos audible
     * - < Nyquist frequency (sampleRate / 2)
     * - Compatible con el micrófono del dispositivo
     *
     * A 16kHz sample rate, máximo es 8kHz (Nyquist)
     * Usamos 7kHz para estar seguros.
     */
    private const val PILOT_FREQUENCY_HZ = 7000f  // 7kHz - audible pero aceptable

    /**
     * Amplitud del tono piloto (0.0 - 1.0)
     * Aumentada para mejor detección.
     */
    private const val PILOT_AMPLITUDE = 0.15f  // 15% de la señal (aumentado)

    private var phase = 0.0

    /**
     * Mezcla un tono ultrasónico con el audio original.
     *
     * @param audioSamples Audio original (modificado in-place)
     * @param length Número de samples a procesar
     * @param sampleRate Sample rate del audio (Hz)
     */
    fun addPilotTone(
        audioSamples: ShortArray,
        length: Int,
        sampleRate: Int = 16000
    ) {
        // Verificar que la frecuencia piloto es válida
        val nyquistFreq = sampleRate / 2f
        if (PILOT_FREQUENCY_HZ >= nyquistFreq) {
            Timber.w("⚠️ Pilot frequency ${PILOT_FREQUENCY_HZ}Hz exceeds Nyquist ${nyquistFreq}Hz")
            return
        }

        val phaseIncrement = 2.0 * PI * PILOT_FREQUENCY_HZ / sampleRate

        for (i in 0 until length) {
            // Generar muestra del tono piloto
            val pilotSample = (sin(phase) * PILOT_AMPLITUDE * 32767).toInt()

            // Mezclar con el audio original (saturación segura)
            val mixed = audioSamples[i].toInt() + pilotSample
            audioSamples[i] = mixed.coerceIn(-32768, 32767).toShort()

            // Avanzar fase
            phase += phaseIncrement
            if (phase >= 2.0 * PI) {
                phase -= 2.0 * PI
            }
        }
    }

    /**
     * Detecta si hay tono piloto presente en las muestras de audio.
     * Usa correlación para detectar la frecuencia específica.
     *
     * @param audioSamples Muestras de audio capturadas del micrófono
     * @param length Número de samples
     * @param sampleRate Sample rate del audio
     * @return true si se detecta el tono piloto (= audio del altavoz)
     */
    fun detectPilotTone(
        audioSamples: ShortArray,
        length: Int,
        sampleRate: Int = 16000,
        threshold: Float = 0.3f  // Umbral de detección (ajustable)
    ): Boolean {
        if (length < 160) return false  // Mínimo 10ms de datos

        // Correlación con coseno y seno de la frecuencia piloto
        var cosSum = 0.0
        var sinSum = 0.0
        var energySum = 0.0

        val phaseIncrement = 2.0 * PI * PILOT_FREQUENCY_HZ / sampleRate
        var currentPhase = 0.0

        for (i in 0 until length) {
            val normalized = audioSamples[i] / 32768.0

            cosSum += normalized * cos(currentPhase)
            sinSum += normalized * sin(currentPhase)
            energySum += normalized * normalized

            currentPhase += phaseIncrement
            if (currentPhase >= 2.0 * PI) {
                currentPhase -= 2.0 * PI
            }
        }

        // Magnitud de la correlación
        val magnitude = sqrt(cosSum * cosSum + sinSum * sinSum) / length

        // Normalizar por la energía de la señal
        val normalizedMagnitude = if (energySum > 0) {
            magnitude / sqrt(energySum / length)
        } else {
            0.0
        }

        val detected = normalizedMagnitude > threshold

        // Logging ocasional para debugging
        if (detected || normalizedMagnitude > threshold * 0.7) {
            Timber.v("🎵 Pilot tone: mag=${String.format("%.3f", normalizedMagnitude)}, " +
                    "detected=$detected (threshold=$threshold)")
        }

        return detected
    }

    /**
     * Reinicia la fase del generador.
     * Útil al iniciar una nueva reproducción.
     */
    fun reset() {
        phase = 0.0
    }

    /**
     * Información de configuración.
     */
    fun getInfo(): String {
        return "Pilot Tone: ${PILOT_FREQUENCY_HZ}Hz @ ${PILOT_AMPLITUDE * 100}% amplitude"
    }
}