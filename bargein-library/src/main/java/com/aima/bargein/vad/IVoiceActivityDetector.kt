package com.aima.bargein.vad

/**
 * Interface para detectores de actividad de voz (VAD)
 */
interface IVoiceActivityDetector {

    /**
     * Inicializa el detector
     * @return true si la inicialización fue exitosa
     */
    fun initialize(sampleRate: Int, mode: AggressivenessMode): Boolean

    /**
     * Procesa un frame de audio
     * @param samples Array de muestras PCM16
     * @param length Número de muestras válidas
     * @return Resultado de la detección
     */
    fun processFrame(samples: ShortArray, length: Int): VadResult

    /**
     * Notifica si hay reproducción activa (para VAD adaptativo)
     */
    fun setPlaybackActive(active: Boolean)

    /**
     * Libera recursos
     */
    fun release()

    /**
     * Obtiene el tipo de VAD
     */
    fun getType(): Type

    /**
     * Obtiene métricas de rendimiento
     */
    fun getMetrics(): VadMetrics

    // ========== TIPOS ==========

    enum class Type {
        ENERGY,        // Basado en energía
        WEBRTC,        // WebRTC VAD
        SILERO,        // Silero VAD (ML)
        LOW_BITRATE,   // VAD de baja tasa de bits
        CUSTOM,        // Personalizado
        NONE           // Sin VAD (NoOp)
    }

    enum class AggressivenessMode {
        QUALITY,           // Menos falsos positivos, más latencia
        AGGRESSIVE,        // Balance
        VERY_AGGRESSIVE    // Más sensible, menos latencia
    }

    // ========== RESULTADO ==========

    data class VadResult(
        val hasVoice: Boolean,
        val confidence: Float,      // 0.0 - 1.0
        val energyDb: Float,
        val timestamp: Long,
        val metadata: Map<String, String> = emptyMap()
    )

    // ========== MÉTRICAS ==========

    data class VadMetrics(
        val framesProcessed: Long,
        val voiceFrames: Long,
        val averageConfidence: Float,
        val averageProcessingTimeUs: Long,
        val metadata: Map<String, Any> = emptyMap()
    )
}