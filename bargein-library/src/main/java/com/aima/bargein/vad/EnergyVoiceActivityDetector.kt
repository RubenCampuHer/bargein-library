package com.aima.bargein.vad

import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt

class EnergyVoiceActivityDetector : IVoiceActivityDetector {

    private val framesProcessed = AtomicLong(0)
    private val voiceFrames = AtomicLong(0)
    private val totalProcessingTimeUs = AtomicLong(0)
    private var totalConfidence = 0.0

    @Volatile
    private var isActive = false

    private var energyThresholdDb = -40f
    private var noiseFloorDb = -60f

    private val energyHistory = FloatArray(HISTORY_SIZE)
    private var historyIndex = 0
    private var historyFilled = false

    companion object {
        private const val HISTORY_SIZE = 100
        private const val VOICE_THRESHOLD_MARGIN_DB = 10f
        private const val MIN_ENERGY_DB = -60f
    }

    override fun initialize(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): Boolean {
        try {
            energyThresholdDb = when (mode) {
                IVoiceActivityDetector.AggressivenessMode.QUALITY -> -45f
                IVoiceActivityDetector.AggressivenessMode.LOW_BITRATE -> -40f
                IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE -> -35f
                IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE -> -30f
            }

            isActive = true
            Timber.i("Energy VAD initialized: threshold=${energyThresholdDb}dB, mode=$mode")
            return true

        } catch (e: Exception) {
            Timber.e(e, "Error initializing Energy VAD")
            return false
        }
    }

    override fun processFrame(audioData: ShortArray, length: Int): IVoiceActivityDetector.VadResult {
        val timestamp = System.nanoTime()

        if (!isActive) {
            return IVoiceActivityDetector.VadResult(
                hasVoice = false,
                confidence = 0f,
                energyDb = -100f,
                timestamp = timestamp
            )
        }

        val startTime = System.nanoTime()

        val rmsEnergy = calculateRMS(audioData, length)
        val energyDb = if (rmsEnergy > 0) {
            20 * log10(rmsEnergy).coerceAtLeast(MIN_ENERGY_DB)
        } else {
            MIN_ENERGY_DB
        }

        updateEnergyHistory(energyDb)

        noiseFloorDb = estimateNoiseFloor()

        val adaptiveThreshold = noiseFloorDb + VOICE_THRESHOLD_MARGIN_DB

        val hasVoice = energyDb > adaptiveThreshold.coerceAtLeast(energyThresholdDb)

        val confidence = if (hasVoice) {
            val margin = energyDb - adaptiveThreshold
            when {
                margin > 15 -> 1.0f
                margin > 10 -> 0.9f
                margin > 5 -> 0.8f
                margin > 0 -> 0.7f
                else -> 0.6f
            }
        } else {
            0.0f
        }

        val processingTime = (System.nanoTime() - startTime) / 1000
        totalProcessingTimeUs.addAndGet(processingTime)
        framesProcessed.incrementAndGet()

        if (hasVoice) {
            voiceFrames.incrementAndGet()
        }

        totalConfidence += confidence

        return IVoiceActivityDetector.VadResult(
            hasVoice = hasVoice,
            confidence = confidence,
            energyDb = energyDb,
            timestamp = timestamp
        )
    }

    override fun release() {
        isActive = false
        Timber.d("Energy VAD released")
    }

    override fun getType(): IVoiceActivityDetector.Type =
        IVoiceActivityDetector.Type.ENERGY

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

    private fun calculateRMS(audioData: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val normalized = audioData[i] / 32768.0
            sum += normalized * normalized
        }
        return sqrt(sum / length).toFloat()
    }

    private fun updateEnergyHistory(energyDb: Float) {
        energyHistory[historyIndex] = energyDb
        historyIndex = (historyIndex + 1) % HISTORY_SIZE

        if (historyIndex == 0 && !historyFilled) {
            historyFilled = true
        }
    }

    private fun estimateNoiseFloor(): Float {
        val size = if (historyFilled) HISTORY_SIZE else historyIndex
        if (size == 0) return MIN_ENERGY_DB

        val sorted = energyHistory.copyOf(size).sortedArray()
        val percentile25Index = (size * 0.25).toInt()

        return sorted[percentile25Index]
    }
}