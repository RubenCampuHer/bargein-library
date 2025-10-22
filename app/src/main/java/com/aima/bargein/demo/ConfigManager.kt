package com.aima.bargein.demo

import android.content.Context
import android.content.SharedPreferences
import com.aima.bargein.BargeInConfig
import com.aima.bargein.vad.IVoiceActivityDetector
import timber.log.Timber

class ConfigManager(
    private val context: Context,
    private val presetManager: PresetManager
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("BargeInCustomPrefs", Context.MODE_PRIVATE)

    var currentMode = SensitivityMode.SENSITIVE

    var customDeltaVoiceThresholdDb = 18f
    var customMinAbsoluteVoiceEnergyDb = -22f
    var customMaxZcrForVoice = 0.15f
    var customDeltaBaselineAdjustmentFactor = 0.85f
    var customMinVoiceDurationMs = 60L
    var customVoiceConfidenceThreshold = 0.68f
    var customCalibrationDurationMs = 300L
    var customPreDelayMs = 350L

    fun loadSettings() {
        customDeltaVoiceThresholdDb = prefs.getFloat("deltaVoiceThresholdDb", 18f)
        customMinAbsoluteVoiceEnergyDb = prefs.getFloat("minAbsoluteVoiceEnergyDb", -22f)
        customMaxZcrForVoice = prefs.getFloat("maxZcrForVoice", 0.15f)
        customDeltaBaselineAdjustmentFactor = prefs.getFloat("deltaBaselineAdjustmentFactor", 0.85f)
        customMinVoiceDurationMs = prefs.getLong("minVoiceDurationMs", 60L)
        customVoiceConfidenceThreshold = prefs.getFloat("voiceConfidenceThreshold", 0.68f)
        customCalibrationDurationMs = prefs.getLong("calibrationDurationMs", 300L)
        customPreDelayMs = prefs.getLong("preDelayMs", 350L)

        val savedMode = prefs.getString("currentMode", "SENSITIVE") ?: "SENSITIVE"
        currentMode = try {
            SensitivityMode.valueOf(savedMode)
        } catch (e: Exception) {
            SensitivityMode.SENSITIVE
        }

        Timber.i("📥 Settings loaded: Mode=$currentMode")
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

        Timber.i("💾 Settings saved: Mode=$currentMode")
        logCurrentCustomSettings()
    }

    private fun logCurrentCustomSettings() {
        Timber.i("   Custom Parameters:")
        Timber.i("   - Delta Threshold: ${customDeltaVoiceThresholdDb}dB")
        Timber.i("   - Min Energy: ${customMinAbsoluteVoiceEnergyDb}dB")
        Timber.i("   - Max ZCR: ${customMaxZcrForVoice}")
        Timber.i("   - Baseline Factor: ${customDeltaBaselineAdjustmentFactor}")
        Timber.i("   - Min Duration: ${customMinVoiceDurationMs}ms")
        Timber.i("   - Confidence: ${customVoiceConfidenceThreshold}")
        Timber.i("   - Calibration: ${customCalibrationDurationMs}ms")
        Timber.i("   - Pre-Delay: ${customPreDelayMs}ms")
    }

    fun getConfigForCurrentMode(): BargeInConfig {
        val config = when (currentMode) {
            SensitivityMode.SUPER_SENSITIVE -> BargeInConfig(
                sampleRate = 44100,
                vadMode = IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE,
                minVoiceDurationMs = 45,  // ✅ Equilibrado: 40→45ms
                voiceConfidenceThreshold = 0.62f,  // ✅ Equilibrado: 0.55→0.62
                deltaVoiceThresholdDb = 18f,  // ✅ Equilibrado: 16→18dB
                minAbsoluteVoiceEnergyDb = -22f,  // ✅ Equilibrado: -24→-22dB
                maxZcrForVoice = 0.14f,  // ✅ Equilibrado: 0.16→0.14
                deltaBaselineAdjustmentFactor = 0.88f  // ✅ CLAVE: 0.80→0.88 (moderado)
            )

            SensitivityMode.SENSITIVE -> BargeInConfig(
                sampleRate = 44100,
                vadMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
                minVoiceDurationMs = 55,  // ✅ Equilibrado: 48→55ms
                voiceConfidenceThreshold = 0.68f,  // ✅ Equilibrado: 0.58→0.68
                deltaVoiceThresholdDb = 20f,  // ✅ Equilibrado: 18→20dB
                minAbsoluteVoiceEnergyDb = -20f,  // ✅ Equilibrado: -22→-20dB
                maxZcrForVoice = 0.13f,  // ✅ Equilibrado: 0.15→0.13
                deltaBaselineAdjustmentFactor = 0.90f  // ✅ CLAVE: 0.85→0.90
            )

            SensitivityMode.NORMAL -> BargeInConfig(
                sampleRate = 44100,
                vadMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
                minVoiceDurationMs = 60,  // ✅ Equilibrado: 54→60ms
                voiceConfidenceThreshold = 0.72f,  // ✅ Equilibrado: 0.62→0.72
                deltaVoiceThresholdDb = 22f,  // ✅ Equilibrado: 20→22dB
                minAbsoluteVoiceEnergyDb = -18f,  // ✅ Equilibrado: -20→-18dB
                maxZcrForVoice = 0.12f,  // ✅ Equilibrado: 0.14→0.12
                deltaBaselineAdjustmentFactor = 0.92f  // ✅ CLAVE: 0.90→0.92
            )

            SensitivityMode.CUSTOM -> BargeInConfig(
                sampleRate = 44100,
                vadMode = IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE,
                minVoiceDurationMs = customMinVoiceDurationMs,
                voiceConfidenceThreshold = customVoiceConfidenceThreshold,
                deltaVoiceThresholdDb = customDeltaVoiceThresholdDb,
                minAbsoluteVoiceEnergyDb = customMinAbsoluteVoiceEnergyDb,
                maxZcrForVoice = customMaxZcrForVoice,
                deltaBaselineAdjustmentFactor = customDeltaBaselineAdjustmentFactor
            )
        }

        Timber.i("🔧 Config created for mode: $currentMode")
        Timber.i("   ============ FINAL CONFIG ============")
        Timber.i("   Sample Rate: ${config.sampleRate}")
        Timber.i("   VAD Mode: ${config.vadMode}")
        Timber.i("   Delta: ${config.deltaVoiceThresholdDb}dB")
        Timber.i("   MinEnergy: ${config.minAbsoluteVoiceEnergyDb}dB")
        Timber.i("   MaxZCR: ${config.maxZcrForVoice}")
        Timber.i("   Factor: ${config.deltaBaselineAdjustmentFactor}")
        Timber.i("   MinDuration: ${config.minVoiceDurationMs}ms")
        Timber.i("   Confidence: ${config.voiceConfidenceThreshold}")
        Timber.i("   =====================================")

        return config
    }

    fun getModeDescription(): String {
        return when (currentMode) {
            SensitivityMode.SUPER_SENSITIVE -> "Δ=18dB • E=-22dB • ZCR=0.14 • F=0.88"
            SensitivityMode.SENSITIVE -> "Δ=20dB • E=-20dB • ZCR=0.13 • F=0.90"
            SensitivityMode.NORMAL -> "Δ=22dB • E=-18dB • ZCR=0.12 • F=0.92"
            SensitivityMode.CUSTOM -> "Δ=${customDeltaVoiceThresholdDb}dB • E=${customMinAbsoluteVoiceEnergyDb}dB • ZCR=${customMaxZcrForVoice} • F=${customDeltaBaselineAdjustmentFactor}"
        }
    }

    fun resetToDefaults() {
        // ✅ Defaults equilibrados (basados en Sensitive)
        customDeltaVoiceThresholdDb = 20f
        customMinAbsoluteVoiceEnergyDb = -20f
        customMaxZcrForVoice = 0.13f
        customDeltaBaselineAdjustmentFactor = 0.90f
        customMinVoiceDurationMs = 55L
        customVoiceConfidenceThreshold = 0.68f
        customCalibrationDurationMs = 300L
        customPreDelayMs = 350L
        saveSettings()

        Timber.i("🔄 Settings reset to defaults")
        logCurrentCustomSettings()
    }

    fun copyFromSuperSensitive() {
        customDeltaVoiceThresholdDb = 18f
        customMinAbsoluteVoiceEnergyDb = -22f
        customMaxZcrForVoice = 0.14f
        customDeltaBaselineAdjustmentFactor = 0.88f
        customMinVoiceDurationMs = 45L
        customVoiceConfidenceThreshold = 0.62f
        customCalibrationDurationMs = 300L
        customPreDelayMs = 350L

        Timber.i("📋 Copied Super Sensitive to Custom")
        logCurrentCustomSettings()
    }

    fun copyFromSensitive() {
        customDeltaVoiceThresholdDb = 20f
        customMinAbsoluteVoiceEnergyDb = -20f
        customMaxZcrForVoice = 0.13f
        customDeltaBaselineAdjustmentFactor = 0.90f
        customMinVoiceDurationMs = 55L
        customVoiceConfidenceThreshold = 0.68f
        customCalibrationDurationMs = 300L
        customPreDelayMs = 350L

        Timber.i("📋 Copied Sensitive to Custom")
        logCurrentCustomSettings()
    }

    fun copyFromNormal() {
        customDeltaVoiceThresholdDb = 22f
        customMinAbsoluteVoiceEnergyDb = -18f
        customMaxZcrForVoice = 0.12f
        customDeltaBaselineAdjustmentFactor = 0.92f
        customMinVoiceDurationMs = 60L
        customVoiceConfidenceThreshold = 0.72f
        customCalibrationDurationMs = 300L
        customPreDelayMs = 350L

        Timber.i("📋 Copied Normal to Custom")
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

        Timber.i("📂 Preset loaded: ${preset.name}")
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