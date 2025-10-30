package com.aima.bargein

/**
 * Configuración para el método Python de detección de voz
 * Basado en: leak compensation + rise factor + RMS threshold
 */
data class PythonVadConfig(
    /**
     * Sample rate (Hz)
     */
    val sampleRate: Int = 44100,

    /**
     * Agresividad del VAD WebRTC (0-3)
     * 0 = menos estricto, 3 = más estricto
     */
    val vadAggr: Int = 2,

    /**
     * Frames consecutivos de VOZ para abrir segmento
     */
    val openConsec: Int = 4,

    /**
     * Frames consecutivos de NO-VOZ para cerrar segmento
     */
    val closeHang: Int = 6,

    /**
     * Umbral RMS mínimo (0.0 - 1.0)
     * Valores típicos: 0.010 - 0.015
     */
    val rmsThreshold: Float = 0.012f,

    /**
     * Duración de calibración de fuga (segundos)
     * Durante este tiempo NO se debe hablar
     */
    val leakCalib: Float = 0.8f,

    /**
     * Multiplicador de desviación estándar para umbral de fuga
     * leak_thr = leak_mean + leak_k * leak_std + leak_margin
     */
    val leakK: Float = 2.5f,

    /**
     * Margen adicional al umbral de fuga
     */
    val leakMargin: Float = 0.003f,

    /**
     * Factor de subida vs baseline para detectar voz
     * rms debe ser > (ema_baseline * rise_factor)
     * Valores típicos: 1.3 - 2.0
     */
    val riseFactor: Float = 1.6f,

    /**
     * Auto-resume tras N segundos de silencio
     * 0.0 = desactivado
     */
    val autoResume: Float = 1.5f,

    /**
     * Alpha para EMA (Exponential Moving Average) del baseline
     * baseline = (1-α)*baseline + α*rms
     * Valores típicos: 0.05 - 0.10
     */
    val emaAlpha: Float = 0.05f
) {
    companion object {
        /**
         * Preset QUIET - Ambientes silenciosos
         */
        val QUIET = PythonVadConfig(
            vadAggr = 1,
            openConsec = 3,
            closeHang = 5,
            rmsThreshold = 0.010f,
            leakCalib = 0.6f,
            leakK = 2.0f,
            leakMargin = 0.002f,
            riseFactor = 1.3f,
            autoResume = 1.0f
        )

        /**
         * Preset OFFICE - Ambientes normales de oficina
         */
        val OFFICE = PythonVadConfig(
            vadAggr = 2,
            openConsec = 4,
            closeHang = 6,
            rmsThreshold = 0.012f,
            leakCalib = 0.8f,
            leakK = 2.5f,
            leakMargin = 0.003f,
            riseFactor = 1.6f,
            autoResume = 1.5f
        )

        /**
         * Preset NOISY - Ambientes ruidosos
         */
        val NOISY = PythonVadConfig(
            vadAggr = 3,
            openConsec = 5,
            closeHang = 7,
            rmsThreshold = 0.015f,
            leakCalib = 1.0f,
            leakK = 3.0f,
            leakMargin = 0.004f,
            riseFactor = 2.0f,
            autoResume = 2.0f
        )

        /**
         * Preset por defecto (OFFICE)
         */
        val DEFAULT = OFFICE
    }

    init {
        require(sampleRate > 0) { "Sample rate must be positive" }
        require(vadAggr in 0..3) { "VAD aggr must be 0-3" }
        require(openConsec > 0) { "Open consec must be positive" }
        require(closeHang > 0) { "Close hang must be positive" }
        require(rmsThreshold >= 0f) { "RMS threshold must be non-negative" }
        require(leakCalib >= 0f) { "Leak calib must be non-negative" }
        require(riseFactor > 0f) { "Rise factor must be positive" }
        require(autoResume >= 0f) { "Auto resume must be non-negative" }
        require(emaAlpha in 0f..1f) { "EMA alpha must be between 0 and 1" }
    }
}

/**
 * Presets disponibles
 */
enum class PythonVadPreset {
    QUIET,
    OFFICE,
    NOISY,
    CUSTOM;

    fun toConfig(): PythonVadConfig = when (this) {
        QUIET -> PythonVadConfig.QUIET
        OFFICE -> PythonVadConfig.OFFICE
        NOISY -> PythonVadConfig.NOISY
        CUSTOM -> PythonVadConfig.DEFAULT // Se sobrescribirá con valores custom
    }
}