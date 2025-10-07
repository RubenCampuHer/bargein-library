package com.aima.bargein

interface BargeInListener {

    fun onUserInterruption(event: BargeInEvent)

    fun onStateChanged(state: BargeInState) {}

    fun onError(error: BargeInError) {}
}

enum class BargeInState {
    IDLE,
    LISTENING,
    INTERRUPTED,
    STOPPED,
    ERROR
}

data class BargeInEvent(
    val detectionTimestamp: Long,
    val stopTimestamp: Long,
    val latencyMs: Float,
    val confidence: Float,
    val energyDb: Float
)

data class BargeInError(
    val code: ErrorCode,
    val message: String,
    val cause: Throwable? = null
)

enum class ErrorCode {
    PERMISSION_DENIED,
    AUDIO_FOCUS_FAILED,
    AEC_INIT_FAILED,
    VAD_INIT_FAILED,
    AUDIO_CAPTURE_FAILED,
    AUDIO_PLAYBACK_FAILED,
    UNKNOWN
}