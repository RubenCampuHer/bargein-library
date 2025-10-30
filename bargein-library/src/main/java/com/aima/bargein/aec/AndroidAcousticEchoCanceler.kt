package com.aima.bargein.aec

import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Implementación de AEC usando la API nativa de Android.
 */
class AndroidAcousticEchoCanceler(
    private val audioSessionId: Int
) : IAcousticEchoCanceler {

    private var aec: AcousticEchoCanceler? = null
    private val framesProcessed = AtomicLong(0)
    private val totalProcessingTimeUs = AtomicLong(0)

    companion object {
        private const val TAG = "BargeInEngine_AndroidAcousticEchoCanceler"

        /**
         * Verifica si el AEC nativo está disponible en el dispositivo.
         */
        fun isSupported(): Boolean {
            return try {
                AcousticEchoCanceler.isAvailable()
            } catch (e: Exception) {
                Log.w(TAG, "Error checking AEC availability", e)
                false
            }
        }
    }

    override fun initialize(): Boolean {
        try {
            if (!isSupported()) {
                Log.w(TAG, "⚠️ Android AEC not supported on this device")
                return false
            }

            aec = AcousticEchoCanceler.create(audioSessionId)

            if (aec == null) {
                Log.e(TAG, "❌ Failed to create AEC instance")
                return false
            }

            aec?.enabled = true

            val enabled = aec?.enabled ?: false
            if (!enabled) {
                Log.e(TAG, "❌ AEC created but not enabled")
                return false
            }

            Log.i(TAG, "✅ Android AEC initialized successfully (session=$audioSessionId)")
            return true

        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "⚠️ AEC not supported on this device: ${e.message}")
            return false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "❌ AEC in illegal state")
            return false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "❌ Invalid audio session ID: $audioSessionId")
            return false
        } catch (e: RuntimeException) {
            Log.e(TAG, "❌ Runtime error initializing AEC")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error initializing AEC")
            return false
        }
    }

    override fun processFrame(audioData: ShortArray, length: Int): Int {
        val startTime = System.nanoTime()

        try {
            // El AEC de Android procesa automáticamente,
            // no necesita procesamiento manual por frame
            framesProcessed.incrementAndGet()

            val processingTime = (System.nanoTime() - startTime) / 1000
            totalProcessingTimeUs.addAndGet(processingTime)

            return length

        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame")
            return length
        }
    }

    override fun release() {
        try {
            aec?.apply {
                enabled = false
                release()
            }
            aec = null

            Log.d(TAG, "✅ Android AEC released")

        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AEC")
        }
    }

    override fun isEnabled(): Boolean {
        return try {
            aec?.enabled ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking AEC enabled state")
            false
        }
    }

    override fun getType(): IAcousticEchoCanceler.Type =
        IAcousticEchoCanceler.Type.ANDROID_BUILTIN

    override fun getMetrics(): IAcousticEchoCanceler.AecMetrics {
        val frames = framesProcessed.get()
        val avgTime = if (frames > 0) {
            totalProcessingTimeUs.get() / frames
        } else {
            0L
        }

        return IAcousticEchoCanceler.AecMetrics(
            framesProcessed = frames,
            averageProcessingTimeUs = avgTime
        )
    }
}