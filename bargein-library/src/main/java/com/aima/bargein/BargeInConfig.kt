package com.aima.bargein

import com.aima.bargein.aec.IAcousticEchoCanceler
import com.aima.bargein.vad.IVoiceActivityDetector

/**
 * Configuración del motor de Barge-In
 */
data class BargeInConfig(
    /**
     * Frecuencia de muestreo (Hz)
     */
    val sampleRate: Int = 16000,

    /**
     * Modo de agresividad del VAD
     */
    val vadMode: IVoiceActivityDetector.AggressivenessMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,

    /**
     * Duración mínima de voz para detectar barge-in (ms)
     */
    val minVoiceDurationMs: Long = 100,

    /**
     * Umbral de confianza para detectar voz (0.0 - 1.0)
     */
    val voiceConfidenceThreshold: Float = 0.60f,

    /**
     * Preferencia de tipo de AEC
     */
    val aecPreference: IAcousticEchoCanceler.Type = IAcousticEchoCanceler.Type.ANDROID_BUILTIN,

    /**
     * Preferencia de tipo de VAD
     */
    val vadPreference: IVoiceActivityDetector.Type = IVoiceActivityDetector.Type.ENERGY,

    // ✅ NUEVO: Parámetros específicos del VAD por modo
    /**
     * Umbral de incremento de energía para detectar voz (dB)
     */
    val deltaVoiceThresholdDb: Float = 15f,

    /**
     * Energía mínima absoluta para considerar voz (dB)
     */
    val minAbsoluteVoiceEnergyDb: Float = -25f,

    /**
     * ZCR máximo permitido para voz (0.0 - 1.0)
     */
    val maxZcrForVoice: Float = 0.18f,

    /**
     * Factor de ajuste del delta según baseline (0.0 - 1.0)
     */
    val deltaBaselineAdjustmentFactor: Float = 0.6f
) {
    companion object {
        /**
         * Configuración por defecto
         */
        val DEFAULT = BargeInConfig()

        /**
         * Configuración de baja latencia (más sensible)
         */
        val LOW_LATENCY = BargeInConfig(
            vadMode = IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE,
            minVoiceDurationMs = 150,
            voiceConfidenceThreshold = 0.50f
        )

        /**
         * Configuración de alta calidad (menos falsos positivos)
         */
        val HIGH_QUALITY = BargeInConfig(
            vadMode = IVoiceActivityDetector.AggressivenessMode.QUALITY,
            minVoiceDurationMs = 300,
            voiceConfidenceThreshold = 0.75f
        )
    }

    init {
        require(sampleRate > 0) { "Sample rate must be positive" }
        require(minVoiceDurationMs > 0) { "Min voice duration must be positive" }
        require(voiceConfidenceThreshold in 0.0f..1.0f) {
            "Voice confidence threshold must be between 0.0 and 1.0"
        }
    }
}