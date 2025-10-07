package com.aima.bargein.aec

import timber.log.Timber

object AcousticEchoCancelerFactory {

    enum class Preference {
        AUTO,
        ANDROID_NATIVE,
        WEBRTC,
        NONE
    }

    fun create(
        audioSessionId: Int,
        sampleRate: Int = 16000,
        preference: Preference = Preference.AUTO
    ): IAcousticEchoCanceler? {
        return when (preference) {
            Preference.ANDROID_NATIVE -> createAndroidAec(audioSessionId)
            Preference.WEBRTC -> createWebRtcAec(sampleRate)
            Preference.NONE -> createNoOpAec()
            Preference.AUTO -> createBestAvailable(audioSessionId, sampleRate)
        }
    }

    private fun createBestAvailable(
        audioSessionId: Int,
        sampleRate: Int
    ): IAcousticEchoCanceler? {
        // Intentar AEC nativo primero
        if (AndroidAcousticEchoCanceler.isSupported()) {
            val androidAec = createAndroidAec(audioSessionId)
            if (androidAec != null && androidAec.initialize()) {
                Timber.i("Using Android native AEC")
                return androidAec
            } else {
                Timber.w("Android AEC available but failed to initialize")
                androidAec?.release()
            }
        }

        // Fallback a WebRTC (no disponible por ahora)
        val webrtcAec = createWebRtcAec(sampleRate)
        if (webrtcAec != null && webrtcAec.initialize()) {
            Timber.i("Using WebRTC AEC (fallback)")
            return webrtcAec
        } else {
            Timber.e("WebRTC AEC failed to initialize")
            webrtcAec?.release()
        }

        // Sin AEC disponible
        Timber.w("No AEC implementation available, using NoOp")
        return createNoOpAec()
    }

    private fun createAndroidAec(audioSessionId: Int): AndroidAcousticEchoCanceler? {
        return try {
            AndroidAcousticEchoCanceler(audioSessionId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create Android AEC")
            null
        }
    }

    private fun createWebRtcAec(sampleRate: Int): WebRtcAcousticEchoCanceler? {
        return try {
            WebRtcAcousticEchoCanceler(sampleRate)
        } catch (e: Exception) {
            Timber.e(e, "Failed to create WebRTC AEC")
            null
        }
    }

    private fun createNoOpAec(): IAcousticEchoCanceler {
        return NoOpAcousticEchoCanceler()
    }

    private class NoOpAcousticEchoCanceler : IAcousticEchoCanceler {
        override fun initialize(): Boolean = true

        override fun processFrame(audioData: ShortArray, length: Int): Int = length

        override fun release() {}

        override fun isEnabled(): Boolean = false

        override fun getType(): IAcousticEchoCanceler.Type =
            IAcousticEchoCanceler.Type.NONE

        override fun getMetrics(): IAcousticEchoCanceler.AecMetrics =
            IAcousticEchoCanceler.AecMetrics()
    }
}