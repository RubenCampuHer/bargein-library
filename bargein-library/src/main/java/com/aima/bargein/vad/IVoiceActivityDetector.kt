package com.aima.bargein.vad

interface IVoiceActivityDetector {

    enum class Type {
        WEBRTC,
        ENERGY,
        NONE
    }

    enum class AggressivenessMode(val value: Int) {
        QUALITY(0),
        LOW_BITRATE(1),
        AGGRESSIVE(2),
        VERY_AGGRESSIVE(3)
    }

    data class VadResult(
        val hasVoice: Boolean,
        val confidence: Float,
        val energyDb: Float,
        val timestamp: Long
    )

    fun initialize(
        sampleRate: Int = 16000,
        mode: AggressivenessMode = AggressivenessMode.AGGRESSIVE
    ): Boolean

    fun processFrame(audioData: ShortArray, length: Int): VadResult

    fun release()

    fun getType(): Type

    fun getMetrics(): VadMetrics

    data class VadMetrics(
        val framesProcessed: Long = 0,
        val voiceFrames: Long = 0,
        val averageProcessingTimeUs: Long = 0,
        val averageConfidence: Float = 0f
    )
}