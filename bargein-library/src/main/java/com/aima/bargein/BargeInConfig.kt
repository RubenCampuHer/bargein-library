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
    val minVoiceDurationMs: Long = 100, // Reducido de 200 a 100ms = ~10 frames

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
    val vadPreference: IVoiceActivityDetector.Type = IVoiceActivityDetector.Type.ENERGY
) {
    companion object {
        /**
         * Configuración por defecto
         */
        val DEFAULT = BargeInConfig(
            sampleRate = 16000,
            vadMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
            minVoiceDurationMs = 20,  // 2 frames (adaptive VAD confirms fast)
            voiceConfidenceThreshold = 0.55f  // Higher = less false positives
        )

        val LOW_LATENCY = BargeInConfig(
            vadMode = IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE,
            minVoiceDurationMs = 20,
            voiceConfidenceThreshold = 0.45f  // More sensitive
        )

        val HIGH_QUALITY = BargeInConfig(
            vadMode = IVoiceActivityDetector.AggressivenessMode.QUALITY,
            minVoiceDurationMs = 30,  // 3 frames
            voiceConfidenceThreshold = 0.70f  // Very selective
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