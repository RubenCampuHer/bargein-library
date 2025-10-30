package com.aima.bargein.aec

interface IAcousticEchoCanceler {

    enum class Type {
        ANDROID_NATIVE,
        ANDROID_BUILTIN,
        WEBRTC,
        NONE
    }

    fun initialize(): Boolean

    fun processFrame(audioData: ShortArray, length: Int): Int

    fun release()

    fun isEnabled(): Boolean

    fun getType(): Type

    fun getMetrics(): AecMetrics

    data class AecMetrics(
        val framesProcessed: Long = 0,
        val averageProcessingTimeUs: Long = 0,
        val echoReturnLoss: Float = 0f,
        val isActive: Boolean = false
    )
}