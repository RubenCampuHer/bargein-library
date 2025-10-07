package com.aima.bargein.vad

import timber.log.Timber

object VoiceActivityDetectorFactory {

    enum class Preference {
        AUTO,
        WEBRTC,
        ENERGY,
        NONE
    }

    fun create(
        sampleRate: Int = 16000,
        mode: IVoiceActivityDetector.AggressivenessMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
        preference: Preference = Preference.AUTO
    ): IVoiceActivityDetector {
        return when (preference) {
            Preference.WEBRTC -> createWebRtcVad(sampleRate, mode)
            Preference.ENERGY -> createEnergyVad(sampleRate, mode)
            Preference.NONE -> createNoOpVad()
            Preference.AUTO -> createBestAvailable(sampleRate, mode)
        }
    }

    private fun createBestAvailable(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): IVoiceActivityDetector {
        // Intentar WebRTC primero
        val webrtcVad = createWebRtcVad(sampleRate, mode)
        if (webrtcVad.initialize(sampleRate, mode)) {
            Timber.i("Using WebRTC VAD")
            return webrtcVad
        } else {
            Timber.w("WebRTC VAD failed to initialize")
            webrtcVad.release()
        }

        // Fallback a Energy VAD
        val energyVad = createEnergyVad(sampleRate, mode)
        if (energyVad.initialize(sampleRate, mode)) {
            Timber.i("Using Energy VAD (fallback)")
            return energyVad
        } else {
            Timber.e("Energy VAD failed to initialize")
            energyVad.release()
        }

        Timber.w("No VAD implementation available, using NoOp")
        return createNoOpVad()
    }

    private fun createWebRtcVad(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): IVoiceActivityDetector {
        return WebRtcVoiceActivityDetector()
    }

    private fun createEnergyVad(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): IVoiceActivityDetector {
        return EnergyVoiceActivityDetector()
    }

    private fun createNoOpVad(): IVoiceActivityDetector {
        return NoOpVoiceActivityDetector()
    }

    private class NoOpVoiceActivityDetector : IVoiceActivityDetector {
        override fun initialize(
            sampleRate: Int,
            mode: IVoiceActivityDetector.AggressivenessMode
        ): Boolean = true

        override fun processFrame(
            audioData: ShortArray,
            length: Int
        ): IVoiceActivityDetector.VadResult {
            return IVoiceActivityDetector.VadResult(
                hasVoice = false,
                confidence = 0f,
                energyDb = -100f,
                timestamp = System.nanoTime()
            )
        }

        override fun release() {}

        override fun getType(): IVoiceActivityDetector.Type =
            IVoiceActivityDetector.Type.NONE

        override fun getMetrics(): IVoiceActivityDetector.VadMetrics =
            IVoiceActivityDetector.VadMetrics()
    }
}