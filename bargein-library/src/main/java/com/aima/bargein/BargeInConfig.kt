package com.aima.bargein

import com.aima.bargein.aec.AcousticEchoCancelerFactory
import com.aima.bargein.vad.IVoiceActivityDetector
import com.aima.bargein.vad.VoiceActivityDetectorFactory

data class BargeInConfig(
    val sampleRate: Int = 16000,
    val vadMode: IVoiceActivityDetector.AggressivenessMode =
        IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
    val aecPreference: AcousticEchoCancelerFactory.Preference =
        AcousticEchoCancelerFactory.Preference.AUTO,
    val vadPreference: VoiceActivityDetectorFactory.Preference =
        VoiceActivityDetectorFactory.Preference.AUTO,
    val minVoiceDurationMs: Long = 200,
    val voiceConfidenceThreshold: Float = 0.7f,
    val enableMetrics: Boolean = true
) {
    init {
        require(sampleRate in listOf(8000, 16000, 32000, 48000)) {
            "Sample rate must be 8000, 16000, 32000 or 48000"
        }
        require(minVoiceDurationMs > 0) {
            "Min voice duration must be positive"
        }
        require(voiceConfidenceThreshold in 0f..1f) {
            "Voice confidence threshold must be between 0.0 and 1.0"
        }
    }

    companion object {
        val DEFAULT = BargeInConfig()

        val HIGH_QUALITY = BargeInConfig(
            vadMode = IVoiceActivityDetector.AggressivenessMode.QUALITY,
            minVoiceDurationMs = 300,
            voiceConfidenceThreshold = 0.8f
        )

        val AGGRESSIVE = BargeInConfig(
            vadMode = IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE,
            minVoiceDurationMs = 150,
            voiceConfidenceThreshold = 0.6f
        )
    }
}