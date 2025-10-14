package com.aima.bargein

import com.aima.bargein.aec.AcousticEchoCancelerFactory
import com.aima.bargein.vad.IVoiceActivityDetector
import com.aima.bargein.vad.VoiceActivityDetectorFactory

/**
 * Configuración de Barge-In.
 * - Mantiene compatibilidad con BargeInEngine actual (sampleRate, vadMode, *Preference, etc.)
 * - Añade parámetros espectrales para separar voz cercana vs altavoz.
 */
data class BargeInConfig(

    // ===== Audio base =====
    val sampleRate: Int = 16_000,              // <- el Engine usa sampleRate
    val frameMs: Int = 20,
    val hopMs: Int = 10,

    // ===== VAD / lógica de decisión clásica =====
    val vadMode: IVoiceActivityDetector.AggressivenessMode =
        IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
    val minVoiceDurationMs: Int = 150,         // usado por el Engine (minVoiceFrames = /10)
    val voiceConfidenceThreshold: Float = 0.6f,

    // ===== Preferencias de fábrica (Engine las pasa a los factories) =====
    val vadPreference: VoiceActivityDetectorFactory.Preference =
        VoiceActivityDetectorFactory.Preference.AUTO,
    val aecPreference: AcousticEchoCancelerFactory.Preference =
        AcousticEchoCancelerFactory.Preference.AUTO,

    // ===== Parámetros espectrales (para tu VAD mejorado) =====
    // Banda “altas” útil para voz cercana (fricativas, formantes altos)
    val highBandMinHz: Int = 2_800,
    val highBandMaxHz: Int = 7_000,
    // Umbral de aceptación por % de altas (tras suavizado)
    val highRatioThreshold: Float = 0.12f,
    // Rechazo por coherencia con far-end (anti-eco)
    val coherenceRejectThreshold: Float = 0.25f,
    // Válvula de silencio
    val minRmsDb: Float = -47f,
    // Confirmación de 2 frames consecutivos (con hop de 10 ms => ~20 ms)
    val decisionConsecutiveFrames: Int = 2,
    // Búsqueda de retardo para coherencia (eco directo)
    val maxEchoLagMs: Int = 12,
    // Paso en muestras para reducir cómputo en correlación
    val coherenceStrideSamples: Int = 4
) {
    init {
        require(sampleRate in listOf(8_000, 16_000, 32_000, 48_000)) {
            "sampleRate must be 8000, 16000, 32000 or 48000"
        }
        require(frameMs in 5..60) { "frameMs must be between 5 and 60 ms" }
        require(hopMs in 1..frameMs) { "hopMs must be >0 and <= frameMs" }

        require(minVoiceDurationMs > 0) { "minVoiceDurationMs must be positive" }
        require(voiceConfidenceThreshold in 0f..1f) {
            "voiceConfidenceThreshold must be between 0.0 and 1.0"
        }

        require(highBandMinHz in 50..(sampleRate / 2 - 200)) {
            "highBandMinHz out of range for sampleRate"
        }
        require(highBandMaxHz in (highBandMinHz + 200)..(sampleRate / 2)) {
            "highBandMaxHz must be > highBandMinHz and <= Nyquist"
        }
        require(highRatioThreshold in 0f..1f) {
            "highRatioThreshold must be between 0 and 1"
        }
        require(coherenceRejectThreshold in 0f..1f) {
            "coherenceRejectThreshold must be between 0 and 1"
        }
        require(decisionConsecutiveFrames >= 1) {
            "decisionConsecutiveFrames must be >= 1"
        }
        require(maxEchoLagMs in 0..50) { "maxEchoLagMs must be between 0 and 50 ms" }
        require(coherenceStrideSamples >= 1) { "coherenceStrideSamples must be >= 1" }
    }

    companion object {
        val DEFAULT = BargeInConfig()

        val HIGH_QUALITY = BargeInConfig(
            vadMode = IVoiceActivityDetector.AggressivenessMode.QUALITY,
            minVoiceDurationMs = 300,
            voiceConfidenceThreshold = 0.8f,
            // espectral: algo más conservador
            highRatioThreshold = 0.12f,
            coherenceRejectThreshold = 0.25f
        )

        val AGGRESSIVE = BargeInConfig(
            vadMode = IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE,
            minVoiceDurationMs = 150,
            voiceConfidenceThreshold = 0.6f,
            // espectral: más sensible a voz cercana
            highRatioThreshold = 0.10f,
            coherenceRejectThreshold = 0.30f
        )
    }
}
