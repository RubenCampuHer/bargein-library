package com.aima.bargein.vad

import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * WebRTC VAD - Implementación usando librería nativa de Google
 * NOTA: Actualmente sin compilar, retorna false en initialize()
 */
class WebRtcVoiceActivityDetector : IVoiceActivityDetector {

    private var nativeHandle: Long = 0
    private val framesProcessed = AtomicLong(0)
    private val voiceFrames = AtomicLong(0)
    private val totalProcessingTimeUs = AtomicLong(0)
    private var totalConfidence = 0.0

    @Volatile
    private var isActive = false

    // ✅ NUEVO: Para compatibilidad con nueva interfaz
    @Volatile
    private var isPlaybackActive = false

    private var currentSampleRate = 0
    private var currentMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE

    companion object {
        init {
            try {
                System.loadLibrary("webrtc_vad")
                Timber.d("✅ WebRTC VAD native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Timber.w("⚠️ WebRTC VAD native library not available")
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
                Timber.e("❌ Invalid sample rate for WebRTC VAD: $sampleRate")
                Timber.i("ℹ️ WebRTC VAD only supports: 8000, 16000, 32000, 48000 Hz")
                return false
            }

            // Por ahora, simulamos que no está disponible
            Timber.w("⚠️ WebRTC VAD native library not compiled yet")
            Timber.i("ℹ️ Falling back to AdaptiveEnergyVAD")
            return false

            // ✅ Este código se activará cuando compilemos WebRTC:
            /*
            nativeHandle = nativeCreate()
            if (nativeHandle == 0L) {
                Timber.e("❌ Failed to create WebRTC VAD instance")
                return false
            }

            val modeValue = when (mode) {
                IVoiceActivityDetector.AggressivenessMode.QUALITY -> 0
                IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE -> 2
                IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE -> 3
            }

            val success = nativeInit(nativeHandle, sampleRate, modeValue)
            if (!success) {
                Timber.e("❌ Failed to initialize WebRTC VAD")
                nativeDestroy(nativeHandle)
                nativeHandle = 0
                return false
            }

            currentSampleRate = sampleRate
            currentMode = mode
            isActive = true

            Timber.i("✅ WebRTC VAD initialized: sampleRate=$sampleRate, mode=$mode")
            return true
            */

        } catch (e: Exception) {
            Timber.e(e, "❌ Error initializing WebRTC VAD")
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
                timestamp = timestamp,
                metadata = mapOf("error" to "not_initialized")
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
                20 * log10(energy).toFloat()
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
                timestamp = timestamp,
                metadata = mapOf(
                    "vadResult" to vadResult.toString(),
                    "playbackActive" to isPlaybackActive.toString()
                )
            )

        } catch (e: Exception) {
            Timber.e(e, "❌ Error processing frame with WebRTC VAD")
            IVoiceActivityDetector.VadResult(
                hasVoice = false,
                confidence = 0f,
                energyDb = -100f,
                timestamp = timestamp,
                metadata = mapOf("error" to e.message.orEmpty())
            )
        }
    }

    // ✅ IMPLEMENTADO: Método requerido por la nueva interfaz
    override fun setPlaybackActive(active: Boolean) {
        isPlaybackActive = active
        if (active) {
            Timber.d("🔊 WebRTC VAD: Playback active")
        } else {
            Timber.d("🔕 WebRTC VAD: Playback inactive")
        }
        // WebRTC VAD no adapta su comportamiento según playback
        // (a diferencia de AdaptiveEnergyVAD que sí lo hace)
    }

    override fun release() {
        if (nativeHandle != 0L) {
            try {
                nativeDestroy(nativeHandle)
                nativeHandle = 0
                isActive = false
                Timber.d("✅ WebRTC VAD released")
            } catch (e: Exception) {
                Timber.e(e, "❌ Error releasing WebRTC VAD")
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
            averageConfidence = avgConfidence,
            metadata = mapOf(
                "isActive" to isActive,
                "nativeHandle" to (nativeHandle != 0L),
                "sampleRate" to currentSampleRate,
                "mode" to currentMode.name
            )
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