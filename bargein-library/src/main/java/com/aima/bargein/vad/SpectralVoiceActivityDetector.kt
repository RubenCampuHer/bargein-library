package com.aima.bargein.vad

import com.aima.bargein.BargeInConfig
import timber.log.Timber
import kotlin.math.*

/**
 * VAD espectral: analiza energía alta (2.8–7.0 kHz) y coherencia con far-end.
 * Distingue voz humana cercana vs eco del altavoz.
 */
class SpectralVoiceActivityDetector(
    private val cfg: BargeInConfig
) : IVoiceActivityDetector {

    private val sr = cfg.sampleRate
    private val frameSamples = sr * cfg.frameMs / 1000
    private val hopSamples = sr * cfg.hopMs / 1000

    // Ventana Hann
    private val hann = FloatArray(frameSamples) { i ->
        (0.5f * (1f - cos(2f * Math.PI.toFloat() * i / (frameSamples - 1)))).toFloat()
    }

    // Bins Goertzel (banda alta 2.8–7.0 kHz)
    private val highBins: IntArray = run {
        val binSpacing = sr / frameSamples.toFloat()
        val minK = round(cfg.highBandMinHz / binSpacing).toInt()
        val maxK = round(cfg.highBandMaxHz / binSpacing).toInt()
        (minK..maxK step 2).toList().toIntArray()
    }

    // Buffer far-end (~120 ms)
    private val maxLagSamples = (cfg.maxEchoLagMs * sr) / 1000
    private val farEndRing = FloatArray(frameSamples * 6)
    private var farEndWrite = 0

    // Estado interno
    private var emaHighRatio = 0f
    private var emaEnergy = 0f
    private val emaAlpha = exp(- (cfg.hopMs / 1000f) / 0.040f)  // τ ≈ 40 ms
    private var consecPass = 0
    private var framesProcessed = 0L
    private var voiceFrames = 0L
    private var avgConfidence = 0f
    private var initialized = false

    override fun initialize(sampleRate: Int, mode: IVoiceActivityDetector.AggressivenessMode): Boolean {
        initialized = true
        Timber.i("🎛️ SpectralVAD initialized: sr=$sampleRate frame=$frameSamples hop=$hopSamples bins=${highBins.size}")
        return true
    }

    override fun release() { initialized = false }

    override fun getType(): IVoiceActivityDetector.Type = IVoiceActivityDetector.Type.ENERGY

    override fun getMetrics(): IVoiceActivityDetector.VadMetrics =
        IVoiceActivityDetector.VadMetrics(framesProcessed, voiceFrames, 0L, avgConfidence)

    // === Far-end reference ===
    fun onFarEndPcm(buffer: ShortArray) {
        var w = farEndWrite
        for (n in buffer.indices) {
            farEndRing[w] = buffer[n] / 32768f
            w++
            if (w >= farEndRing.size) w = 0
        }
        farEndWrite = w
    }

    override fun processFrame(audioData: ShortArray, length: Int): IVoiceActivityDetector.VadResult {
        if (!initialized || length <= 0)
            return IVoiceActivityDetector.VadResult(false, 0f, -120f, System.nanoTime())

        framesProcessed++

        // ---- 1) Preprocesado ----
        val x = FloatArray(frameSamples)
        val available = min(length, frameSamples)
        var prev = 0f
        for (i in 0 until available) {
            val s = audioData[i] / 32768f
            val y = s - 0.97f * prev
            prev = s
            x[i] = y * hann[i]
        }
        if (available < frameSamples)
            for (i in available until frameSamples) x[i] = 0f

        // ---- 2) Energía total ----
        var energy = 0.0f
        for (v in x) energy += v * v
        val rms = sqrt(energy / frameSamples)
        val rmsDb = 20f * ln(max(rms, 1e-8f)) / ln(10f)
        if (rmsDb < -80f) return IVoiceActivityDetector.VadResult(false, 0f, rmsDb, System.nanoTime())

        // ---- 3) Energía alta ----
        var highEnergy = 0.0
        val N = frameSamples
        for (k in highBins) {
            val w = (2.0 * Math.PI * k / N)
            val coeff = 2.0 * cos(w)
            var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
            for (n in 0 until N) {
                s0 = x[n].toDouble() + coeff * s1 - s2
                s2 = s1; s1 = s0
            }
            val real = s1 - s2 * cos(w)
            val imag = s2 * sin(w)
            highEnergy += real * real + imag * imag
        }

        val highRatio = ((highEnergy / max(energy.toDouble(), 1e-9)) * 100.0).toFloat().coerceIn(0f, 200f)
        emaHighRatio = emaAlpha * emaHighRatio + (1 - emaAlpha) * highRatio
        emaEnergy = emaAlpha * emaEnergy + (1 - emaAlpha) * energy

        // ---- 4) Coherencia ----
        val coh = coherenceWithFarEnd(x)

        // ---- 5) Decisión ----
        val noiseFloor = 10f * ln(max(emaEnergy, 1e-9f)) / ln(10f)
        val dynamicMinDb = max(cfg.minRmsDb, noiseFloor + 10f)
        val isLoudEnough = rmsDb > dynamicMinDb
        val isBrightVoice = emaHighRatio > 20f
        val isNotEcho = coh < 0.3f && coh > 0.01f
        val pass = isLoudEnough && isBrightVoice && isNotEcho

        consecPass = if (pass) consecPass + 1 else 0
        val triggered = consecPass >= cfg.decisionConsecutiveFrames
        if (triggered) consecPass = 0

        // ---- 6) Logging resumido cada 2 segundos ----
        if (framesProcessed % (2000 / cfg.hopMs) == 0L) {
            Timber.i(
                "🧩 VAD summary: HR=%.1f%%, coh=%.2f, rms=%.1f dB, noise=%.1f dB, loud=%s, bright=%s, echo=%s",
                emaHighRatio, coh, rmsDb, noiseFloor, isLoudEnough, isBrightVoice, isNotEcho
            )
        }

        if (triggered) {
            Timber.i(
                "🎤 DETECTADA VOZ HUMANA (HR=%.1f%%, coh=%.2f, rms=%.1f dB)",
                emaHighRatio, coh, rmsDb
            )
        }

        // ---- 7) Confianza ----
        val confidence = when {
            !isLoudEnough -> 0f
            !isBrightVoice -> 0.2f
            isNotEcho -> min(1f, (emaHighRatio - 15f) / 25f)
            else -> 0.3f
        }

        avgConfidence = ((avgConfidence * (framesProcessed - 1)) + confidence) / framesProcessed

        return IVoiceActivityDetector.VadResult(triggered, confidence, rmsDb, System.nanoTime())
    }


    private fun coherenceWithFarEnd(mic: FloatArray): Float {
        val stride = max(cfg.coherenceStrideSamples, 1)
        var best = 0.0
        val N = mic.size
        val far = this.farEndRing
        val base = farEndWrite

        var normMic = 0.0
        for (i in 0 until N) normMic += mic[i] * mic[i]
        normMic = sqrt(max(normMic, 1e-12))

        if (normMic < 1e-9) return 0f

        var lag = 0
        while (lag <= maxLagSamples) {
            var dot = 0.0
            var normFar = 0.0
            var idx = base - lag
            for (i in 0 until N) {
                var j = idx
                if (j < 0) j += far.size
                val fv = far[j]
                val mv = mic[i]
                dot += fv * mv
                normFar += fv * fv
                idx++
                if (idx >= far.size) idx = 0
            }
            val denom = sqrt(max(normFar, 1e-12)) * normMic
            val c = if (denom > 0) abs(dot / denom) else 0.0
            if (c > best) best = c
            lag += stride
        }
        return best.toFloat()
    }
}
