package com.aima.bargein.aec

import android.util.Log


object AcousticEchoCancelerFactory {

    private const val TAG = "BargeInEngine_AcousticEchoCancelerFactory"

    /**
     * Crea una instancia de AEC según el tipo preferido.
     * @param audioSessionId ID de sesión de audio para AEC nativo de Android
     * @param sampleRate Frecuencia de muestreo
     * @param preference Tipo de AEC preferido
     * @return Instancia de IAcousticEchoCanceler o null si falla
     */
    fun create(
        audioSessionId: Int,
        sampleRate: Int = 16000,
        preference: IAcousticEchoCanceler.Type = IAcousticEchoCanceler.Type.ANDROID_BUILTIN
    ): IAcousticEchoCanceler? {
        return when (preference) {
            IAcousticEchoCanceler.Type.ANDROID_BUILTIN,
            IAcousticEchoCanceler.Type.ANDROID_NATIVE -> {
                createAndroidAec(audioSessionId)
            }

            IAcousticEchoCanceler.Type.WEBRTC -> {
                createWebRtcAec(sampleRate)
            }

            IAcousticEchoCanceler.Type.NONE -> {
                createNoOpAec()
            }
        }
    }

    /**
     * Crea el mejor AEC disponible automáticamente.
     */
    private fun createBestAvailable(
        audioSessionId: Int,
        sampleRate: Int
    ): IAcousticEchoCanceler {
        // Intentar AEC nativo de Android primero
        if (AndroidAcousticEchoCanceler.isSupported()) {
            val androidAec = createAndroidAec(audioSessionId)
            if (androidAec != null && androidAec.initialize()) {
                Log.i(TAG, "✅ Using Android native AEC")
                return androidAec
            } else {
                Log.w(TAG, "⚠️ Android AEC available but failed to initialize")
                androidAec?.release()
            }
        }

        // Fallback a WebRTC (si está disponible)
        val webrtcAec = createWebRtcAec(sampleRate)
        if (webrtcAec != null && webrtcAec.initialize()) {
            Log.i(TAG, "✅ Using WebRTC AEC (fallback)")
            return webrtcAec
        } else {
            Log.w(TAG, "⚠️ WebRTC AEC failed to initialize")
            webrtcAec?.release()
        }

        // Sin AEC disponible - usar NoOp
        Log.w(TAG, "⚠️ No AEC implementation available, using NoOp")
        return createNoOpAec()
    }

    /**
     * Crea AEC nativo de Android.
     */
    private fun createAndroidAec(audioSessionId: Int): AndroidAcousticEchoCanceler? {
        return try {
            if (!AndroidAcousticEchoCanceler.isSupported()) {
                Log.w(TAG, "Android AEC not supported on this device")
                return null
            }

            val aec = AndroidAcousticEchoCanceler(audioSessionId)
            Log.d(TAG, "Android AEC created")
            aec

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Android AEC")
            null
        }
    }

    /**
     * Crea AEC de WebRTC.
     */
    private fun createWebRtcAec(sampleRate: Int): WebRtcAcousticEchoCanceler? {
        return try {
            val aec = WebRtcAcousticEchoCanceler(sampleRate)
            Log.d(TAG, "WebRTC AEC created")
            aec

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebRTC AEC")
            null
        }
    }

    /**
     * Crea AEC sin operación (no hace nada).
     */
    private fun createNoOpAec(): IAcousticEchoCanceler {
        Log.d(TAG, "NoOp AEC created")
        return NoOpAcousticEchoCanceler()
    }

    /**
     * Implementación de AEC que no hace nada.
     * Útil como fallback cuando no hay AEC disponible.
     */
    private class NoOpAcousticEchoCanceler : IAcousticEchoCanceler {

        override fun initialize(): Boolean {
            Log.d(TAG, "NoOp AEC initialized")
            return true
        }

        override fun processFrame(audioData: ShortArray, length: Int): Int {
            // No procesa nada, solo devuelve la longitud
            return length
        }

        override fun release() {
            Log.d(TAG, "NoOp AEC released")
        }

        override fun isEnabled(): Boolean = false

        override fun getType(): IAcousticEchoCanceler.Type =
            IAcousticEchoCanceler.Type.NONE

        override fun getMetrics(): IAcousticEchoCanceler.AecMetrics =
            IAcousticEchoCanceler.AecMetrics(
                framesProcessed = 0,
                averageProcessingTimeUs = 0
            )
    }
}