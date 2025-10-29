package com.aima.bargein.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.aima.bargein.BargeInConfig
import com.aima.bargein.vad.IVoiceActivityDetector

class ConfigManager(
    private val context: Context,
    private val presetManager: PresetManager
) {
    companion object {
        private const val TAG = "ConfigManager"

        // ========= BASELINE (DEFAULT) =========
        // SENSITIVE = baseline. Toca estos números y el resto de modos
        // se recalculan automáticamente con offsets.
        private const val BASE_DELTA_DB = 20f
        private const val BASE_MIN_ENERGY_DB = -20f
        private const val BASE_MAX_ZCR = 0.13f
        private const val BASE_BASELINE_FACTOR = 0.90f
        private const val BASE_MIN_DURATION_MS = 55L
        private const val BASE_CONFIDENCE = 0.68f
        private const val BASE_CALIBRATION_MS = 300L
        private const val BASE_PRE_DELAY_MS = 350L

        // ========= OFFSETS (relativos al baseline) =========
        // SUPER_SENSITIVE = más fácil disparar (umbral más bajo, acepta más ZCR, menos duración/confianza)
        private const val SUPER_OFS_DELTA_DB = -2f
        private const val SUPER_OFS_MIN_ENERGY_DB = -2f     // más permisivo (más negativo)
        private const val SUPER_OFS_MAX_ZCR = +0.01f        // acepta algo más de cruce
        private const val SUPER_OFS_BASELINE_FACTOR = -0.02f
        private const val SUPER_OFS_MIN_DURATION_MS = -5L
        private const val SUPER_OFS_CONFIDENCE = -0.06f

        // NORMAL = más conservador (umbral más alto, menos ZCR, más duración/confianza)
        private const val NORMAL_OFS_DELTA_DB = +2f
        private const val NORMAL_OFS_MIN_ENERGY_DB = +2f    // exige más energía (menos negativo)
        private const val NORMAL_OFS_MAX_ZCR = -0.01f
        private const val NORMAL_OFS_BASELINE_FACTOR = +0.02f
        private const val NORMAL_OFS_MIN_DURATION_MS = +5L
        private const val NORMAL_OFS_CONFIDENCE = +0.04f
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("BargeInCustomPrefs", Context.MODE_PRIVATE)

    // Por defecto mantenemos SENSITIVE como modo actual
    var currentMode = SensitivityMode.SENSITIVE

    // Valores de CUSTOM; inicializamos con baseline para que "Custom = como Sensitive" de fábrica
    var customDeltaVoiceThresholdDb = BASE_DELTA_DB
    var customMinAbsoluteVoiceEnergyDb = BASE_MIN_ENERGY_DB
    var customMaxZcrForVoice = BASE_MAX_ZCR
    var customDeltaBaselineAdjustmentFactor = BASE_BASELINE_FACTOR
    var customMinVoiceDurationMs = BASE_MIN_DURATION_MS
    var customVoiceConfidenceThreshold = BASE_CONFIDENCE
    var customCalibrationDurationMs = BASE_CALIBRATION_MS
    var customPreDelayMs = BASE_PRE_DELAY_MS

    fun loadSettings() {
        customDeltaVoiceThresholdDb = prefs.getFloat("deltaVoiceThresholdDb", BASE_DELTA_DB)
        customMinAbsoluteVoiceEnergyDb = prefs.getFloat("minAbsoluteVoiceEnergyDb", BASE_MIN_ENERGY_DB)
        customMaxZcrForVoice = prefs.getFloat("maxZcrForVoice", BASE_MAX_ZCR)
        customDeltaBaselineAdjustmentFactor = prefs.getFloat("deltaBaselineAdjustmentFactor", BASE_BASELINE_FACTOR)
        customMinVoiceDurationMs = prefs.getLong("minVoiceDurationMs", BASE_MIN_DURATION_MS)
        customVoiceConfidenceThreshold = prefs.getFloat("voiceConfidenceThreshold", BASE_CONFIDENCE)
        customCalibrationDurationMs = prefs.getLong("calibrationDurationMs", BASE_CALIBRATION_MS)
        customPreDelayMs = prefs.getLong("preDelayMs", BASE_PRE_DELAY_MS)

        val savedMode = prefs.getString("currentMode", SensitivityMode.SENSITIVE.name) ?: SensitivityMode.SENSITIVE.name
        currentMode = try {
            SensitivityMode.valueOf(savedMode)
        } catch (e: Exception) {
            SensitivityMode.SENSITIVE
        }

        Log.i(TAG, "📥 Settings loaded: Mode=$currentMode")
        logCurrentCustomSettings()
    }

    fun saveSettings() {
        prefs.edit().apply {
            putString("currentMode", currentMode.name)
            putFloat("deltaVoiceThresholdDb", customDeltaVoiceThresholdDb)
            putFloat("minAbsoluteVoiceEnergyDb", customMinAbsoluteVoiceEnergyDb)
            putFloat("maxZcrForVoice", customMaxZcrForVoice)
            putFloat("deltaBaselineAdjustmentFactor", customDeltaBaselineAdjustmentFactor)
            putLong("minVoiceDurationMs", customMinVoiceDurationMs)
            putFloat("voiceConfidenceThreshold", customVoiceConfidenceThreshold)
            putLong("calibrationDurationMs", customCalibrationDurationMs)
            putLong("preDelayMs", customPreDelayMs)
            apply()
        }

        Log.i(TAG, "💾 Settings saved: Mode=$currentMode")
        logCurrentCustomSettings()
    }

    private fun logCurrentCustomSettings() {
        Log.i(TAG, "   Custom Parameters:")
        Log.i(TAG, "   - Delta Threshold: ${customDeltaVoiceThresholdDb}dB")
        Log.i(TAG, "   - Min Energy: ${customMinAbsoluteVoiceEnergyDb}dB")
        Log.i(TAG, "   - Max ZCR: ${customMaxZcrForVoice}")
        Log.i(TAG, "   - Baseline Factor: ${customDeltaBaselineAdjustmentFactor}")
        Log.i(TAG, "   - Min Duration: ${customMinVoiceDurationMs}ms")
        Log.i(TAG, "   - Confidence: ${customVoiceConfidenceThreshold}")
        Log.i(TAG, "   - Calibration: ${customCalibrationDurationMs}ms")
        Log.i(TAG, "   - Pre-Delay: ${customPreDelayMs}ms")
    }

    // ===== Helpers para derivar cada modo desde el baseline =====
    private data class ModeParams(
        val deltaDb: Float,
        val minEnergyDb: Float,
        val maxZcr: Float,
        val baselineFactor: Float,
        val minDurationMs: Long,
        val confidence: Float,
        val vadMode: IVoiceActivityDetector.AggressivenessMode
    )

    private fun derivedFromBaseline(
        deltaOfs: Float,
        energyOfs: Float,
        zcrOfs: Float,
        factorOfs: Float,
        minDurOfs: Long,
        confOfs: Float,
        vadMode: IVoiceActivityDetector.AggressivenessMode
    ): ModeParams {
        return ModeParams(
            deltaDb = BASE_DELTA_DB + deltaOfs,
            minEnergyDb = BASE_MIN_ENERGY_DB + energyOfs,
            maxZcr = (BASE_MAX_ZCR + zcrOfs).coerceIn(0.05f, 0.25f),
            baselineFactor = (BASE_BASELINE_FACTOR + factorOfs).coerceIn(0.75f, 0.98f),
            minDurationMs = (BASE_MIN_DURATION_MS + minDurOfs).coerceAtLeast(20L),
            confidence = (BASE_CONFIDENCE + confOfs).coerceIn(0.40f, 0.95f),
            vadMode = vadMode
        )
    }

    private fun paramsFor(mode: SensitivityMode): ModeParams {
        return when (mode) {
            SensitivityMode.SENSITIVE -> ModeParams(
                deltaDb = BASE_DELTA_DB,
                minEnergyDb = BASE_MIN_ENERGY_DB,
                maxZcr = BASE_MAX_ZCR,
                baselineFactor = BASE_BASELINE_FACTOR,
                minDurationMs = BASE_MIN_DURATION_MS,
                confidence = BASE_CONFIDENCE,
                vadMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE
            )
            SensitivityMode.SUPER_SENSITIVE -> derivedFromBaseline(
                SUPER_OFS_DELTA_DB,
                SUPER_OFS_MIN_ENERGY_DB,
                SUPER_OFS_MAX_ZCR,
                SUPER_OFS_BASELINE_FACTOR,
                SUPER_OFS_MIN_DURATION_MS,
                SUPER_OFS_CONFIDENCE,
                IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE
            )
            SensitivityMode.NORMAL -> derivedFromBaseline(
                NORMAL_OFS_DELTA_DB,
                NORMAL_OFS_MIN_ENERGY_DB,
                NORMAL_OFS_MAX_ZCR,
                NORMAL_OFS_BASELINE_FACTOR,
                NORMAL_OFS_MIN_DURATION_MS,
                NORMAL_OFS_CONFIDENCE,
                IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE
            )
            SensitivityMode.CUSTOM -> ModeParams(
                deltaDb = customDeltaVoiceThresholdDb,
                minEnergyDb = customMinAbsoluteVoiceEnergyDb,
                maxZcr = customMaxZcrForVoice,
                baselineFactor = customDeltaBaselineAdjustmentFactor,
                minDurationMs = customMinVoiceDurationMs,
                confidence = customVoiceConfidenceThreshold,
                vadMode = IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE
            )
        }
    }

    fun getConfigForCurrentMode(): BargeInConfig {
        val p = paramsFor(currentMode)
        val config = BargeInConfig(
            sampleRate = 44100,
            vadMode = p.vadMode,
            minVoiceDurationMs = p.minDurationMs,
            voiceConfidenceThreshold = p.confidence,
            deltaVoiceThresholdDb = p.deltaDb,
            minAbsoluteVoiceEnergyDb = p.minEnergyDb,
            maxZcrForVoice = p.maxZcr,
            deltaBaselineAdjustmentFactor = p.baselineFactor
        )

        Log.i(TAG, "🔧 Config created for mode: $currentMode")
        Log.i(TAG, "   ============ FINAL CONFIG ============")
        Log.i(TAG, "   Sample Rate: ${config.sampleRate}")
        Log.i(TAG, "   VAD Mode: ${config.vadMode}")
        Log.i(TAG, "   Delta: ${config.deltaVoiceThresholdDb}dB")
        Log.i(TAG, "   MinEnergy: ${config.minAbsoluteVoiceEnergyDb}dB")
        Log.i(TAG, "   MaxZCR: ${config.maxZcrForVoice}")
        Log.i(TAG, "   Factor: ${config.deltaBaselineAdjustmentFactor}")
        Log.i(TAG, "   MinDuration: ${config.minVoiceDurationMs}ms")
        Log.i(TAG, "   Confidence: ${config.voiceConfidenceThreshold}")
        Log.i(TAG, "   =====================================")

        return config
    }

    fun getModeDescription(): String {
        val p = paramsFor(currentMode)
        return "Δ=${p.deltaDb}dB • E=${p.minEnergyDb}dB • ZCR=${"%.2f".format(p.maxZcr)} • F=${"%.2f".format(p.baselineFactor)}"
    }

    fun resetToDefaults() {
        // Defaults = baseline (SENSITIVE)
        customDeltaVoiceThresholdDb = BASE_DELTA_DB
        customMinAbsoluteVoiceEnergyDb = BASE_MIN_ENERGY_DB
        customMaxZcrForVoice = BASE_MAX_ZCR
        customDeltaBaselineAdjustmentFactor = BASE_BASELINE_FACTOR
        customMinVoiceDurationMs = BASE_MIN_DURATION_MS
        customVoiceConfidenceThreshold = BASE_CONFIDENCE
        customCalibrationDurationMs = BASE_CALIBRATION_MS
        customPreDelayMs = BASE_PRE_DELAY_MS
        saveSettings()

        Log.i(TAG, "🔄 Settings reset to baseline (Sensitive)")
        logCurrentCustomSettings()
    }

    fun copyFromSuperSensitive() {
        val p = paramsFor(SensitivityMode.SUPER_SENSITIVE)
        applyParamsToCustom(p, "Super Sensitive")
    }

    fun copyFromSensitive() {
        val p = paramsFor(SensitivityMode.SENSITIVE)
        applyParamsToCustom(p, "Sensitive (Baseline)")
    }

    fun copyFromNormal() {
        val p = paramsFor(SensitivityMode.NORMAL)
        applyParamsToCustom(p, "Normal")
    }

    private fun applyParamsToCustom(p: ModeParams, label: String) {
        customDeltaVoiceThresholdDb = p.deltaDb
        customMinAbsoluteVoiceEnergyDb = p.minEnergyDb
        customMaxZcrForVoice = p.maxZcr
        customDeltaBaselineAdjustmentFactor = p.baselineFactor
        customMinVoiceDurationMs = p.minDurationMs
        customVoiceConfidenceThreshold = p.confidence
        customCalibrationDurationMs = BASE_CALIBRATION_MS
        customPreDelayMs = BASE_PRE_DELAY_MS

        Log.i(TAG, "📋 Copied $label to Custom")
        logCurrentCustomSettings()
    }

    fun loadPreset(preset: PresetManager.VadPreset) {
        customDeltaVoiceThresholdDb = preset.deltaVoiceThresholdDb
        customMinAbsoluteVoiceEnergyDb = preset.minAbsoluteVoiceEnergyDb
        customMaxZcrForVoice = preset.maxZcrForVoice
        customDeltaBaselineAdjustmentFactor = preset.deltaBaselineAdjustmentFactor
        customMinVoiceDurationMs = preset.minVoiceDurationMs
        customVoiceConfidenceThreshold = preset.voiceConfidenceThreshold
        customCalibrationDurationMs = preset.calibrationDurationMs
        customPreDelayMs = preset.preDelayMs
        saveSettings()
        presetManager.setLastUsedPreset(preset.name)

        Log.i(TAG, "📂 Preset loaded: ${preset.name}")
        logCurrentCustomSettings()
    }

    fun createCurrentPreset(name: String): PresetManager.VadPreset {
        return PresetManager.VadPreset(
            name = name,
            deltaVoiceThresholdDb = customDeltaVoiceThresholdDb,
            minAbsoluteVoiceEnergyDb = customMinAbsoluteVoiceEnergyDb,
            maxZcrForVoice = customMaxZcrForVoice,
            deltaBaselineAdjustmentFactor = customDeltaBaselineAdjustmentFactor,
            minVoiceDurationMs = customMinVoiceDurationMs,
            voiceConfidenceThreshold = customVoiceConfidenceThreshold,
            calibrationDurationMs = customCalibrationDurationMs,
            preDelayMs = customPreDelayMs
        )
    }
}
