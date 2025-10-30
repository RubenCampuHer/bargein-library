package com.aima.bargein.vad

import android.util.Log
import com.aima.bargein.PythonVadConfig
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * VAD basado en el método Python con:
 * - Leak Compensation (compensación de fuga de altavoces)
 * - Rise Factor (detección por subida vs baseline)
 * - RMS Threshold dinámico
 */
class PythonVoiceActivityDetector : IVoiceActivityDetector {

    private val framesProcessed = AtomicLong(0)
    private val voiceFrames = AtomicLong(0)
    private val rejectedFrames = AtomicLong(0)
    private val totalProcessingTimeUs = AtomicLong(0)
    private var totalConfidence = 0.0

    @Volatile
    private var isActive = false

    @Volatile
    private var isPlaybackActive = false

    // Configuración
    private lateinit var config: PythonVadConfig

    // Leak compensation (calibración de fuga)
    private var leakMean: Float = 0f
    private var leakStd: Float = 0f
    private var leakThreshold: Float = 0f
    private var isCalibrated = false

    // EMA baseline (línea base adaptativa)
    private var emaBaseline: Float = 0f

    // Histeresis
    private var consecVoice = 0
    private var consecNonVoice = 0
    private var inSegment = false

    // Logging
    private var lastLogTime = 0L
    private val LOG_INTERVAL_MS = 500L

    companion object {
        private const val TAG = "PythonVAD"
    }

    override fun initialize(
        sampleRate: Int,
        mode: IVoiceActivityDetector.AggressivenessMode
    ): Boolean {
        // Usar configuración por defecto
        return initializeWithConfig(PythonVadConfig.DEFAULT)
    }

    /**
     * Inicialización con configuración personalizada
     */
    fun initializeWithConfig(config: PythonVadConfig): Boolean {
        try {
            this.config = config
            isActive = true

            Log.i(TAG, "✅ Python VAD initialized @ ${config.sampleRate}Hz")
            Log.i(TAG, "   VAD Aggr: ${config.vadAggr}")
            Log.i(TAG, "   Open/Close: ${config.openConsec}/${config.closeHang}")
            Log.i(TAG, "   RMS Threshold: ${config.rmsThreshold}")
            Log.i(TAG, "   Leak: calib=${config.leakCalib}s, k=${config.leakK}, margin=${config.leakMargin}")
            Log.i(TAG, "   Rise Factor: ${config.riseFactor}")
            Log.i(TAG, "   Auto Resume: ${config.autoResume}s")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing Python VAD", e)
            return false
        }
    }

    /**
     * Calibrar la fuga de altavoces
     * Debe llamarse ANTES de iniciar el barge-in, mientras solo hay audio de altavoces
     */
    fun calibrateLeakCompensation(audioFrames: List<FloatArray>) {
        if (audioFrames.isEmpty()) {
            Log.w(TAG, "⚠️ No frames for leak calibration, using defaults")
            leakMean = config.rmsThreshold / 2
            leakStd = 0.001f
            leakThreshold = leakMean + config.leakK * leakStd + config.leakMargin
            isCalibrated = true
            return
        }

        // Calcular RMS de cada frame
        val rmsValues = audioFrames.map { frame ->
            calculateRMS(frame)
        }

        // Estadísticas
        leakMean = rmsValues.average().toFloat()
        val variance = rmsValues.map { (it - leakMean) * (it - leakMean) }.average()
        leakStd = sqrt(variance).toFloat()

        // Umbral dinámico: mean + k*std + margin
        leakThreshold = leakMean + config.leakK * leakStd + config.leakMargin

        // Inicializar baseline
        emaBaseline = leakMean

        isCalibrated = true

        Log.i(TAG, "📏 Leak calibration complete:")
        Log.i(TAG, "   Mean: ${"%.4f".format(leakMean)}")
        Log.i(TAG, "   Std: ${"%.4f".format(leakStd)}")
        Log.i(TAG, "   Threshold: ${"%.4f".format(leakThreshold)}")
        Log.i(TAG, "   🔊 Estimated speaker level: ${(leakMean * 1000).toInt()}%")
    }

    fun setPlaybackActive(active: Boolean) {
        isPlaybackActive = active
        if (!active) {
            Log.d(TAG, "🔇 Playback inactive")
        }
    }

    override fun processFrame(audioData: ShortArray, length: Int): IVoiceActivityDetector.VadResult {
        if (!isActive) {
            return IVoiceActivityDetector.VadResult(
                hasVoice = false,
                confidence = 0f,
                energyDb = -120f,
                timestamp = System.nanoTime()
            )
        }

        val startTime = System.nanoTime()
        val timestamp = startTime

        // Convertir a float32 normalizado
        val audioFloat = FloatArray(length) { i ->
            audioData[i] / 32768.0f
        }

        // Calcular RMS
        val rms = calculateRMS(audioFloat)
        val rmsDb = if (rms > 1e-12f) 20f * log10(rms) else -120f

        // Actualizar EMA baseline
        emaBaseline = (1 - config.emaAlpha) * emaBaseline + config.emaAlpha * rms

        // Triple check para voz (como en Python):
        // 1. VAD check (se simula con umbral RMS por ahora - en producción usar WebRTC VAD)
        val vadOk = rms >= config.rmsThreshold

        // 2. Leak check: RMS debe superar el umbral dinámico de fuga
        val minRmsNeeded = maxOf(config.rmsThreshold, leakThreshold)
        val leakOk = rms >= minRmsNeeded

        // 3. Rise check: debe haber subida significativa vs baseline
        val riseOk = rms > (maxOf(emaBaseline, 1e-6f) * config.riseFactor)

        // Decisión final
        val isVoice = vadOk && leakOk && riseOk

        // Histeresis
        if (isVoice) {
            consecVoice++
            consecNonVoice = 0

            // Abrir segmento si alcanzamos open_consec
            if (!inSegment && consecVoice >= config.openConsec) {
                inSegment = true
                Log.i(TAG, "🎙️ Segmento de voz ABIERTO (consec=${consecVoice})")
            }
        } else {
            consecNonVoice++
            consecVoice = 0

            // Cerrar segmento si alcanzamos close_hang
            if (inSegment && consecNonVoice >= config.closeHang) {
                inSegment = false
                Log.i(TAG, "⏹️ Segmento de voz CERRADO (consec=${consecNonVoice})")
            }
        }

        // Calcular confidence
        val confidence = if (inSegment) {
            calculateConfidence(rms, rmsDb, vadOk, leakOk, riseOk, consecVoice)
        } else {
            0f
        }

        // Logging periódico
        val now = System.currentTimeMillis()
        if (now - lastLogTime >= LOG_INTERVAL_MS) {
            Log.d(TAG, "📊 Frame: rms=${"%.4f".format(rms)} " +
                    "| rmsDb=${"%.1f".format(rmsDb)}dB " +
                    "| baseline=${"%.4f".format(emaBaseline)} " +
                    "| vad=$vadOk leak=$leakOk rise=$riseOk " +
                    "| voice=$isVoice seg=$inSegment " +
                    "| consec_v=$consecVoice consec_nv=$consecNonVoice")
            lastLogTime = now
        }

        // Actualizar estadísticas
        val processingTime = (System.nanoTime() - startTime) / 1000
        totalProcessingTimeUs.addAndGet(processingTime)
        framesProcessed.incrementAndGet()
        totalConfidence += confidence

        if (inSegment) {
            voiceFrames.incrementAndGet()
        } else {
            rejectedFrames.incrementAndGet()
        }

        return IVoiceActivityDetector.VadResult(
            hasVoice = inSegment,
            confidence = confidence,
            energyDb = rmsDb,
            timestamp = timestamp,
            // Campos adicionales para diagnóstico
            deltaDb = if (emaBaseline > 0) {
                20f * log10(rms / emaBaseline)
            } else {
                0f
            },
            zcr = 0f, // No usado en método Python
            baselineDb = if (emaBaseline > 0) 20f * log10(emaBaseline) else -120f
        )
    }

    private fun calculateRMS(audioData: FloatArray): Float {
        var sum = 0.0
        for (sample in audioData) {
            sum += sample * sample
        }
        return sqrt(sum / audioData.size).toFloat()
    }

    private fun calculateConfidence(
        rms: Float,
        rmsDb: Float,
        vadOk: Boolean,
        leakOk: Boolean,
        riseOk: Boolean,
        consecFrames: Int
    ): Float {
        var conf = 0.5f

        // Bonus por cumplir cada criterio
        if (vadOk) conf += 0.15f
        if (leakOk) conf += 0.15f
        if (riseOk) conf += 0.15f

        // Bonus por frames consecutivos (hasta +0.15)
        conf += (consecFrames * 0.02f).coerceAtMost(0.15f)

        // Bonus por margen sobre umbral
        val margin = rms - leakThreshold
        if (margin > 0.005f) conf += 0.05f
        if (margin > 0.010f) conf += 0.05f

        return conf.coerceIn(0f, 1f)
    }

    override fun release() {
        isActive = false

        val totalFrames = framesProcessed.get()
        val acceptedFrames = voiceFrames.get()
        val acceptanceRate = if (totalFrames > 0) {
            (acceptedFrames * 100f / totalFrames)
        } else {
            0f
        }

        Log.i(TAG, "📊 Python VAD Final Stats:")
        Log.i(TAG, "   Total frames: $totalFrames")
        Log.i(TAG, "   Voice frames: $acceptedFrames (${"%.1f".format(acceptanceRate)}%)")
        Log.i(TAG, "   Rejected frames: ${rejectedFrames.get()}")
        Log.i(TAG, "   Leak threshold: ${"%.4f".format(leakThreshold)}")
        Log.i(TAG, "   EMA baseline: ${"%.4f".format(emaBaseline)}")
        Log.d(TAG, "🔧 Python VAD released")
    }

    override fun getType(): IVoiceActivityDetector.Type =
        IVoiceActivityDetector.Type.ENERGY // Reusa el tipo ENERGY

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
}