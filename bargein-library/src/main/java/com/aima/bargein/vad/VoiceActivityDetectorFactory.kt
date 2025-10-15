package com.aima.bargein.vad

import timber.log.Timber

object VoiceActivityDetectorFactory {

    /**
     * Crea una instancia de VAD según el tipo preferido.
     * @param sampleRate Frecuencia de muestreo
     * @param mode Modo de agresividad del VAD
     * @param preference Tipo de VAD preferido
     * @return Instancia de IVoiceActivityDetector
     */
    fun create(
        sampleRate: Int = 16000,
        mode: IVoiceActivityDetector.AggressivenessMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
        preference: IVoiceActivityDetector.Type = IVoiceActivityDetector.Type.ENERGY
    ): IVoiceActivityDetector {
        return when (preference) {
            IVoiceActivityDetector.Type.WEBRTC -> {
                createWebRtcVad(sampleRate, mode)
            }

            IVoiceActivityDetector.Type.ENERGY -> {
                createEnergyVad(sampleRate, mode)
            }

            IVoiceActivityDetector.Type.NONE -> {
                createNoOpVad()
            }
        }
    }

    /**
     * Crea el mejor VAD disponible automáticamente.
     */
    private fun createBestAvailable(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): IVoiceActivityDetector {
        // Intentar WebRTC VAD primero (si está disponible)
        try {
            val webrtcVad = createWebRtcVad(sampleRate, mode)
            if (webrtcVad.initialize(sampleRate, mode)) {
                Timber.i("✅ Using WebRTC VAD")
                return webrtcVad
            } else {
                Timber.w("⚠️ WebRTC VAD failed to initialize")
                webrtcVad.release()
            }
        } catch (e: Exception) {
            Timber.w("⚠️ WebRTC VAD not available: ${e.message}")
        }

        // Fallback a Energy VAD (siempre disponible)
        Timber.i("✅ Using Energy VAD (fallback)")
        return createEnergyVad(sampleRate, mode)
    }

    /**
     * Crea VAD basado en WebRTC.
     */
    private fun createWebRtcVad(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): IVoiceActivityDetector {
        return try {
            val vad = WebRtcVoiceActivityDetector()
            Timber.d("WebRTC VAD created")
            vad
        } catch (e: Exception) {
            Timber.e(e, "Failed to create WebRTC VAD, falling back to Energy VAD")
            createEnergyVad(sampleRate, mode)
        }
    }

    /**
     * Crea VAD basado en energía.
     */
    private fun createEnergyVad(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): IVoiceActivityDetector {
        val vad = EnergyVoiceActivityDetector()
        Timber.d("Energy VAD created")
        return vad
    }

    /**
     * Crea VAD sin operación (no hace nada).
     */
    private fun createNoOpVad(): IVoiceActivityDetector {
        Timber.d("NoOp VAD created")
        return NoOpVoiceActivityDetector()
    }

    /**
     * Implementación de VAD que no hace nada.
     * Útil para pruebas o cuando no se requiere detección.
     */
    private class NoOpVoiceActivityDetector : IVoiceActivityDetector {

        override fun initialize(
            sampleRate: Int,
            mode: IVoiceActivityDetector.AggressivenessMode
        ): Boolean {
            Timber.d("NoOp VAD initialized")
            return true
        }

        override fun processFrame(
            audioData: ShortArray,
            length: Int
        ): IVoiceActivityDetector.VadResult {
            // Siempre devuelve "sin voz"
            return IVoiceActivityDetector.VadResult(
                hasVoice = false,
                confidence = 0f,
                energyDb = -60f,
                timestamp = System.nanoTime()
            )
        }

        override fun release() {
            Timber.d("NoOp VAD released")
        }

        override fun getType(): IVoiceActivityDetector.Type =
            IVoiceActivityDetector.Type.NONE

        override fun getMetrics(): IVoiceActivityDetector.VadMetrics =
            IVoiceActivityDetector.VadMetrics(
                framesProcessed = 0,
                voiceFrames = 0,
                averageProcessingTimeUs = 0,
                averageConfidence = 0f
            )
    }
}