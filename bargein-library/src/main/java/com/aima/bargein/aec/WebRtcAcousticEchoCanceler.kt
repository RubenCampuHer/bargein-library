package com.aima.bargein.aec

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

class WebRtcAcousticEchoCanceler(
    private val sampleRate: Int = 16000
) : IAcousticEchoCanceler {

    private var nativeHandle: Long = 0
    private val framesProcessed = AtomicLong(0)
    private val totalProcessingTimeUs = AtomicLong(0)

    @Volatile
    private var isActive = false

    companion object {
        private const val TAG = "BargeInEngine_WebRtcAcousticEchoCanceler"

        init {
            try {
                System.loadLibrary("webrtc_aec")
                Log.d(TAG, "WebRTC AEC native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load WebRTC AEC native library")
            }
        }

        @JvmStatic
        private external fun nativeCreate(sampleRate: Int): Long

        @JvmStatic
        private external fun nativeProcessFrame(handle: Long, audioData: ShortArray, length: Int): Int

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeGetEchoReturnLoss(handle: Long): Float
    }

    override fun initialize(): Boolean {
        try {
            if (sampleRate !in listOf(8000, 16000, 32000, 48000)) {
                Log.e(TAG, "Invalid sample rate for WebRTC AEC: $sampleRate")
                return false
            }

            // Por ahora, simulamos que no está disponible hasta que compilemos WebRTC
            Log.w(TAG, "WebRTC AEC native library not available (not compiled yet)")
            return false

            // Este código se activará cuando compilemos WebRTC:
            // nativeHandle = nativeCreate(sampleRate)
            // if (nativeHandle == 0L) {
            //     Log.e(TAG, "Failed to create WebRTC AEC instance")
            //     return false
            // }
            // isActive = true
            // Log.i(TAG, "WebRTC AEC initialized successfully (sampleRate=$sampleRate)")
            // return true

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WebRTC AEC")
            return false
        }
    }

    override fun processFrame(audioData: ShortArray, length: Int): Int {
        if (!isActive || nativeHandle == 0L) {
            return 0
        }

        return try {
            val startTime = System.nanoTime()

            val processed = nativeProcessFrame(nativeHandle, audioData, length)

            val processingTime = (System.nanoTime() - startTime) / 1000
            totalProcessingTimeUs.addAndGet(processingTime)
            framesProcessed.incrementAndGet()

            processed

        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame with WebRTC AEC")
            0
        }
    }

    override fun release() {
        if (nativeHandle != 0L) {
            try {
                nativeDestroy(nativeHandle)
                nativeHandle = 0
                isActive = false
                Log.d(TAG, "WebRTC AEC released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing WebRTC AEC")
            }
        }
    }

    override fun isEnabled(): Boolean = isActive

    override fun getType(): IAcousticEchoCanceler.Type =
        IAcousticEchoCanceler.Type.WEBRTC

    override fun getMetrics(): IAcousticEchoCanceler.AecMetrics {
        val frames = framesProcessed.get()
        val avgTime = if (frames > 0) {
            totalProcessingTimeUs.get() / frames
        } else {
            0L
        }

        val echoReturnLoss = if (isActive && nativeHandle != 0L) {
            try {
                nativeGetEchoReturnLoss(nativeHandle)
            } catch (e: Exception) {
                0f
            }
        } else {
            0f
        }

        return IAcousticEchoCanceler.AecMetrics(
            framesProcessed = frames,
            averageProcessingTimeUs = avgTime,
            echoReturnLoss = echoReturnLoss,
            isActive = isActive
        )
    }
}