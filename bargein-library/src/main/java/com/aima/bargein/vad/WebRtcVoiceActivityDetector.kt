package com.aima.bargein.vad

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt

class WebRtcVoiceActivityDetector : IVoiceActivityDetector {

    private var nativeHandle: Long = 0
    private val framesProcessed = AtomicLong(0)
    private val voiceFrames = AtomicLong(0)
    private val totalProcessingTimeUs = AtomicLong(0)
    private var totalConfidence = 0.0

    @Volatile
    private var isActive = false

    private var currentSampleRate = 0
    private var currentMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE

    companion object {
        private const val TAG = "BargeInEngine_WebRtcVoiceActivityDetector"

        init {
            try {
                System.loadLibrary("webrtc_vad")
                Log.d(TAG, "WebRTC VAD native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load WebRTC VAD native library")
            }
        }

        @JvmStatic
        private external fun nativeCreate(): Long

        @JvmStatic
        private external fun nativeInit(handle: Long, sampleRate: Int, mode: Int): Boolean

        @JvmStatic
        private external fun nativeProcess(handle: Long, audioData: ShortArray, length: Int): Int

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }

    override fun initialize(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): Boolean {
        try {
            if (sampleRate !in listOf(8000, 16000, 32000, 48000)) {
                Log.e(TAG, "Invalid sample rate for WebRTC VAD: $sampleRate")
                return false
            }

            // Por ahora, simulamos que no está disponible
            Log.w(TAG, "WebRTC VAD native library not available (not compiled yet)")
            return false

            // Este código se activará cuando compilemos WebRTC:
            // nativeHandle = nativeCreate()
            // if (nativeHandle == 0L) {
            //     Log.e(TAG, "Failed to create WebRTC VAD instance")
            //     return false
            // }
            //
            // val success = nativeInit(nativeHandle, sampleRate, mode.value)
            // if (!success) {
            //     Log.e(TAG, "Failed to initialize WebRTC VAD")
            //     nativeDestroy(nativeHandle)
            //     nativeHandle = 0
            //     return false
            // }
            //
            // currentSampleRate = sampleRate
            // currentMode = mode
            // isActive = true
            //
            // Log.i(TAG, "WebRTC VAD initialized: sampleRate=$sampleRate, mode=$mode")
            // return true

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WebRTC VAD")
            return false
        }
    }

    override fun processFrame(audioData: ShortArray, length: Int): IVoiceActivityDetector.VadResult {
        val timestamp = System.nanoTime()

        if (!isActive || nativeHandle == 0L) {
            return IVoiceActivityDetector.VadResult(
                hasVoice = false,
                confidence = 0f,
                energyDb = -100f,
                timestamp = timestamp
            )
        }

        return try {
            val startTime = System.nanoTime()

            val vadResult = nativeProcess(nativeHandle, audioData, length)

            val processingTime = (System.nanoTime() - startTime) / 1000
            totalProcessingTimeUs.addAndGet(processingTime)
            framesProcessed.incrementAndGet()

            val hasVoice = vadResult == 1
            if (hasVoice) {
                voiceFrames.incrementAndGet()
            }

            val energy = calculateEnergy(audioData, length)
            val energyDb = if (energy > 0) {
                20 * log10(energy)
            } else {
                -100f
            }

            val confidence = if (hasVoice) {
                when {
                    energyDb > -10 -> 1.0f
                    energyDb > -20 -> 0.9f
                    energyDb > -30 -> 0.8f
                    energyDb > -40 -> 0.7f
                    else -> 0.6f
                }
            } else {
                0.0f
            }

            totalConfidence += confidence

            IVoiceActivityDetector.VadResult(
                hasVoice = hasVoice,
                confidence = confidence,
                energyDb = energyDb,
                timestamp = timestamp
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame with WebRTC VAD")
            IVoiceActivityDetector.VadResult(
                hasVoice = false,
                confidence = 0f,
                energyDb = -100f,
                timestamp = timestamp
            )
        }
    }

    override fun release() {
        if (nativeHandle != 0L) {
            try {
                nativeDestroy(nativeHandle)
                nativeHandle = 0
                isActive = false
                Log.d(TAG, "WebRTC VAD released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing WebRTC VAD")
            }
        }
    }

    override fun getType(): IVoiceActivityDetector.Type =
        IVoiceActivityDetector.Type.WEBRTC

    override fun getMetrics(): IVoiceActivityDetector.VadMetrics {
        val frames = framesProcessed.get()
        val avgTime = if (frames > 0) {
            totalProcessingTimeUs.get() / frames
        } else {
            0L
        }

        val avgConfidence = if (frames > 0) {
            (totalConfidence / frames).toFloat()
        } else {
            0f
        }

        return IVoiceActivityDetector.VadMetrics(
            framesProcessed = frames,
            voiceFrames = voiceFrames.get(),
            averageProcessingTimeUs = avgTime,
            averageConfidence = avgConfidence
        )
    }

    private fun calculateEnergy(audioData: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = audioData[i] / 32768.0
            sum += sample * sample
        }
        return sqrt(sum / length).toFloat()
    }
}