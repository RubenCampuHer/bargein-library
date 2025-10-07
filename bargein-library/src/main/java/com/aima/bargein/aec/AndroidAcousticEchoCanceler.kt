package com.aima.bargein.aec

import android.media.audiofx.AcousticEchoCanceler
import timber.log.Timber

class AndroidAcousticEchoCanceler(
    private val audioSessionId: Int
) : IAcousticEchoCanceler {

    private var aec: AcousticEchoCanceler? = null
    private var framesProcessed = 0L
    private var isActive = false

    override fun initialize(): Boolean {
        try {
            if (!AcousticEchoCanceler.isAvailable()) {
                Timber.w("Android AEC not available on this device")
                return false
            }

            aec = AcousticEchoCanceler.create(audioSessionId)

            if (aec == null) {
                Timber.e("Failed to create AcousticEchoCanceler")
                return false
            }

            aec?.enabled = true
            isActive = aec?.enabled == true

            if (isActive) {
                Timber.i("Android AEC initialized successfully (session=$audioSessionId)")
            } else {
                Timber.e("Failed to enable Android AEC")
            }

            return isActive

        } catch (e: Exception) {
            Timber.e(e, "Error initializing Android AEC")
            return false
        }
    }

    override fun processFrame(audioData: ShortArray, length: Int): Int {
        if (isActive) {
            framesProcessed++
        }
        return length
    }

    override fun release() {
        try {
            aec?.enabled = false
            aec?.release()
            aec = null
            isActive = false
            Timber.d("Android AEC released")
        } catch (e: Exception) {
            Timber.e(e, "Error releasing Android AEC")
        }
    }

    override fun isEnabled(): Boolean = isActive

    override fun getType(): IAcousticEchoCanceler.Type =
        IAcousticEchoCanceler.Type.ANDROID_NATIVE

    override fun getMetrics(): IAcousticEchoCanceler.AecMetrics {
        return IAcousticEchoCanceler.AecMetrics(
            framesProcessed = framesProcessed,
            averageProcessingTimeUs = 0,
            echoReturnLoss = 0f,
            isActive = isActive
        )
    }

    companion object {
        fun isSupported(): Boolean {
            return AcousticEchoCanceler.isAvailable()
        }
    }
}