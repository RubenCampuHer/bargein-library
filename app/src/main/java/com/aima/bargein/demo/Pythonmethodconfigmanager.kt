package com.aima.bargein.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.aima.bargein.BargeInConfig
import com.aima.bargein.PythonVadConfig
import com.aima.bargein.PythonVadPreset
import com.aima.bargein.vad.IVoiceActivityDetector

class PythonMethodConfigManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "PythonMethodConfigManager"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("PythonMethodPrefs", Context.MODE_PRIVATE)

    // Preset actual
    var currentPreset = PythonVadPreset.OFFICE

    // Valores CUSTOM
    var customVadAggr: Int = 2
    var customOpenConsec: Int = 4
    var customCloseHang: Int = 6
    var customRmsThreshold: Float = 0.012f
    var customLeakCalib: Float = 0.8f
    var customLeakK: Float = 2.5f
    var customLeakMargin: Float = 0.003f
    var customRiseFactor: Float = 1.6f
    var customAutoResume: Float = 1.5f
    var customEmaAlpha: Float = 0.05f

    fun loadSettings() {
        customVadAggr = prefs.getInt("vadAggr", 2)
        customOpenConsec = prefs.getInt("openConsec", 4)
        customCloseHang = prefs.getInt("closeHang", 6)
        customRmsThreshold = prefs.getFloat("rmsThreshold", 0.012f)
        customLeakCalib = prefs.getFloat("leakCalib", 0.8f)
        customLeakK = prefs.getFloat("leakK", 2.5f)
        customLeakMargin = prefs.getFloat("leakMargin", 0.003f)
        customRiseFactor = prefs.getFloat("riseFactor", 1.6f)
        customAutoResume = prefs.getFloat("autoResume", 1.5f)
        customEmaAlpha = prefs.getFloat("emaAlpha", 0.05f)

        val savedPreset = prefs.getString("currentPreset", PythonVadPreset.OFFICE.name)
            ?: PythonVadPreset.OFFICE.name
        currentPreset = try {
            PythonVadPreset.valueOf(savedPreset)
        } catch (e: Exception) {
            PythonVadPreset.OFFICE
        }

        Log.i(TAG, "📥 Settings loaded: Preset=$currentPreset")
        logCurrentCustomSettings()
    }

    fun saveSettings() {
        prefs.edit().apply {
            putString("currentPreset", currentPreset.name)
            putInt("vadAggr", customVadAggr)
            putInt("openConsec", customOpenConsec)
            putInt("closeHang", customCloseHang)
            putFloat("rmsThreshold", customRmsThreshold)
            putFloat("leakCalib", customLeakCalib)
            putFloat("leakK", customLeakK)
            putFloat("leakMargin", customLeakMargin)
            putFloat("riseFactor", customRiseFactor)
            putFloat("autoResume", customAutoResume)
            putFloat("emaAlpha", customEmaAlpha)
            apply()
        }

        Log.i(TAG, "💾 Settings saved: Preset=$currentPreset")
        logCurrentCustomSettings()
    }

    private fun logCurrentCustomSettings() {
        Log.i(TAG, "   Custom Parameters:")
        Log.i(TAG, "   - VAD Aggr: $customVadAggr")
        Log.i(TAG, "   - Open/Close: $customOpenConsec/$customCloseHang")
        Log.i(TAG, "   - RMS Threshold: $customRmsThreshold")
        Log.i(TAG, "   - Leak: calib=${customLeakCalib}s, k=$customLeakK, margin=$customLeakMargin")
        Log.i(TAG, "   - Rise Factor: $customRiseFactor")
        Log.i(TAG, "   - Auto Resume: ${customAutoResume}s")
        Log.i(TAG, "   - EMA Alpha: $customEmaAlpha")
    }

    fun getConfigForCurrentPreset(): PythonVadConfig {
        return when (currentPreset) {
            PythonVadPreset.QUIET -> PythonVadConfig.QUIET
            PythonVadPreset.OFFICE -> PythonVadConfig.OFFICE
            PythonVadPreset.NOISY -> PythonVadConfig.NOISY
            PythonVadPreset.CUSTOM -> PythonVadConfig(
                vadAggr = customVadAggr,
                openConsec = customOpenConsec,
                closeHang = customCloseHang,
                rmsThreshold = customRmsThreshold,
                leakCalib = customLeakCalib,
                leakK = customLeakK,
                leakMargin = customLeakMargin,
                riseFactor = customRiseFactor,
                autoResume = customAutoResume,
                emaAlpha = customEmaAlpha
            )
        }
    }

    fun convertToAndroidConfig(pythonConfig: PythonVadConfig): BargeInConfig {
        val minEnergyDb = when {
            pythonConfig.rmsThreshold <= 0.010f -> -30f
            pythonConfig.rmsThreshold <= 0.012f -> -25f
            else -> -20f
        }

        val deltaThresholdDb = when {
            pythonConfig.riseFactor <= 1.3f -> 12f
            pythonConfig.riseFactor <= 1.6f -> 15f
            pythonConfig.riseFactor <= 2.0f -> 18f
            else -> 20f
        }

        val vadMode = when (pythonConfig.vadAggr) {
            0 -> IVoiceActivityDetector.AggressivenessMode.QUALITY
            1 -> IVoiceActivityDetector.AggressivenessMode.LOW_BITRATE
            2 -> IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE
            3 -> IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE
            else -> IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE
        }

        return BargeInConfig(
            sampleRate = pythonConfig.sampleRate,
            vadMode = vadMode,
            minVoiceDurationMs = (pythonConfig.openConsec * 11.6).toLong(),
            voiceConfidenceThreshold = 0.65f,
            deltaVoiceThresholdDb = deltaThresholdDb,
            minAbsoluteVoiceEnergyDb = minEnergyDb,
            maxZcrForVoice = 0.15f,
            deltaBaselineAdjustmentFactor = 0.90f
        )
    }

    fun getPresetDescription(): String {
        val config = getConfigForCurrentPreset()
        return "RMS=${config.rmsThreshold} • Rise=${config.riseFactor}x • Leak_k=${config.leakK}"
    }

    fun resetToDefaults() {
        val office = PythonVadConfig.OFFICE
        customVadAggr = office.vadAggr
        customOpenConsec = office.openConsec
        customCloseHang = office.closeHang
        customRmsThreshold = office.rmsThreshold
        customLeakCalib = office.leakCalib
        customLeakK = office.leakK
        customLeakMargin = office.leakMargin
        customRiseFactor = office.riseFactor
        customAutoResume = office.autoResume
        customEmaAlpha = office.emaAlpha
        saveSettings()

        Log.i(TAG, "🔄 Settings reset to defaults (OFFICE preset)")
        logCurrentCustomSettings()
    }

    fun copyFromPreset(preset: PythonVadPreset) {
        val config = preset.toConfig()
        customVadAggr = config.vadAggr
        customOpenConsec = config.openConsec
        customCloseHang = config.closeHang
        customRmsThreshold = config.rmsThreshold
        customLeakCalib = config.leakCalib
        customLeakK = config.leakK
        customLeakMargin = config.leakMargin
        customRiseFactor = config.riseFactor
        customAutoResume = config.autoResume
        customEmaAlpha = config.emaAlpha

        Log.i(TAG, "📋 Copied ${preset.name} to Custom")
        logCurrentCustomSettings()
    }
}