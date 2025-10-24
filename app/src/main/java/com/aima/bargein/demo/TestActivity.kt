package com.aima.bargein.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aima.bargein.BargeInEngine
import com.aima.bargein.BargeInError
import com.aima.bargein.BargeInEvent
import com.aima.bargein.BargeInState
import kotlinx.coroutines.launch
import java.io.File

class TestActivity : AppCompatActivity() {

    private lateinit var engine: BargeInEngine
    private lateinit var uiManager: UIManager
    lateinit var configManager: ConfigManager
    private lateinit var presetManager: PresetManager
    private lateinit var audioManager: AudioManager
    private lateinit var tutorialManager: TutorialManager // ✅ NUEVO
    private lateinit var tutorialDialog: TutorialDialog   // ✅ NUEVO

    private val handler = Handler(Looper.getMainLooper())
    private var wavFile: File? = null
    private var isTestRunning = false

    companion object {
        private const val TAG = "TestActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "🚀 TestActivity started @ 44.1kHz")

        initializeManagers()
        configManager.loadSettings()
        uiManager.setupUI()
        checkPermissions()
    }

    private fun observeEngine() {
        lifecycleScope.launch {
            launch {
                engine.userInterruption.collect { event ->
                    onUserInterruption(event)
                }
            }
            launch {
                engine.onStateChanged.collect { state ->
                    onStateChanged(state)
                }
            }
            launch {
                engine.bargeInError.collect { error ->
                    onError(error)
                }
            }
        }
    }

    private fun initializeManagers() {
        presetManager = PresetManager(this)
        configManager = ConfigManager(this, presetManager)
        audioManager = AudioManager(this)
        tutorialManager = TutorialManager(this)  // ✅ NUEVO
        tutorialDialog = TutorialDialog(this, tutorialManager)  // ✅ NUEVO
        uiManager = UIManager(this, configManager, presetManager, audioManager)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        configManager.saveSettings()
        outState.putBoolean("isTestRunning", isTestRunning)
        outState.putString("currentMode", configManager.currentMode.name)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        configManager.loadSettings()
        savedInstanceState.getString("currentMode")?.let {
            try {
                configManager.currentMode = SensitivityMode.valueOf(it)
                uiManager.updateModeButtons()
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring mode", e)
            }
        }
    }
    fun openTutorial() {
        tutorialDialog.show()
    }
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        } else {
            onPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onPermissionsGranted()
            } else {
                uiManager.showError("Permiso de micrófono denegado")
            }
        }
    }

    private fun onPermissionsGranted() {
        try {
            wavFile = audioManager.prepareAudioFile()
            initializeEngine()
            observeEngine()
            uiManager.showReady(configManager.currentMode, presetManager.getPresetCount())
            uiManager.enablePlayButton(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in initialization", e)
            uiManager.showError("Error al inicializar: ${e.message}")
        }
    }

    private fun initializeEngine() {
        val config = configManager.getConfigForCurrentMode()
        engine = BargeInEngine(config)
        engine.initialize(applicationContext)
        Log.i(TAG, "✅ Engine initialized with mode: ${configManager.currentMode}")
    }

    fun changeSensitivityMode(newMode: SensitivityMode) {
        if (!::engine.isInitialized) {
            Log.w(TAG, "Engine not initialized yet")
            return
        }

        if (isTestRunning) {
            uiManager.showError("⚠️ Detén el test antes de cambiar el modo")
            return
        }

        Log.i(TAG, "🔄 Changing mode from ${configManager.currentMode} to $newMode")

        configManager.currentMode = newMode
        configManager.saveSettings()

        try {
            engine.release()
            Log.i(TAG, "   Engine released")

            Thread.sleep(100)

            initializeEngine()
            observeEngine()
            Log.i(TAG, "   Engine reinitialized")

            uiManager.updateModeButtons()
            uiManager.showModeChanged(newMode, configManager.getModeDescription())

            Log.i(TAG, "✅ Mode changed successfully to: $newMode")
        } catch (e: Exception) {
            Log.e(TAG, "Error changing mode", e)
            uiManager.showError("Error cambiando modo: ${e.message}")
        }
    }

    fun applyCustomSettings() {
        if (!::engine.isInitialized) {
            Log.w(TAG, "Engine not initialized yet")
            return
        }

        if (isTestRunning) {
            uiManager.showError("⚠️ Detén el test antes de aplicar cambios")
            return
        }

        if (configManager.currentMode != SensitivityMode.CUSTOM) {
            Log.w(TAG, "Not in CUSTOM mode, skipping apply")
            return
        }

        try {
            Log.i(TAG, "🔧 Applying custom settings changes...")
            Log.i(TAG, "   Delta: ${configManager.customDeltaVoiceThresholdDb}dB")
            Log.i(TAG, "   Energy: ${configManager.customMinAbsoluteVoiceEnergyDb}dB")
            Log.i(TAG, "   Factor: ${configManager.customDeltaBaselineAdjustmentFactor}")

            configManager.saveSettings()

            engine.release()
            Thread.sleep(100)
            initializeEngine()
            observeEngine()

            uiManager.showModeChanged(SensitivityMode.CUSTOM, configManager.getModeDescription())

            Log.i(TAG, "✅ Custom settings applied successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying custom settings", e)
            uiManager.showError("Error aplicando configuración: ${e.message}")
        }
    }

    @Suppress("MissingPermission")
    fun startBargeInTest() {
        if (wavFile == null || isTestRunning) return

        try {
            isTestRunning = true
            engine.startListening()

            uiManager.showTestRunning(configManager.currentMode, configManager.getModeDescription())
            uiManager.enablePlayButton(false)
            uiManager.enableStopButton(true)

            startUIUpdates()

            handler.postDelayed({
                wavFile?.inputStream()?.let { engine.playAudio(it) }
            }, 100)

            Log.i(TAG, "▶️ Test started with mode: ${configManager.currentMode}")
        } catch (e: Exception) {
            isTestRunning = false
            uiManager.showError("Error iniciando test: ${e.message}")
            uiManager.enablePlayButton(true)
            Log.e(TAG, "Failed to start test", e)
        }
    }

    fun stopBargeInTest() {
        if (!isTestRunning) return

        try {
            engine.stopAudioPlayback()
            engine.stopListening()
            handler.removeCallbacksAndMessages(null)

            isTestRunning = false
            uiManager.showTestStopped()
            uiManager.enablePlayButton(true)
            uiManager.enableStopButton(false)

            Log.i(TAG, "⏹️ Test stopped")
        } catch (e: Exception) {
            uiManager.showError("Error deteniendo: ${e.message}")
            Log.e(TAG, "Failed to stop test", e)
        }
    }

    private fun startUIUpdates() {
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isTestRunning && ::engine.isInitialized) {
                    uiManager.updateAudioVisualizer(engine.getMetrics())
                    handler.postDelayed(this, 50)
                }
            }
        }
        handler.post(updateRunnable)
    }

    // ✅ NUEVO: Callback cuando el audio termina normalmente
    fun onPlaybackComplete() {
        runOnUiThread {
            Log.i(TAG, "🎵 Playback completed normally")

            // Detener todo
            engine.stopListening()
            handler.removeCallbacksAndMessages(null)

            isTestRunning = false

            uiManager.showPlaybackCompleted()
            uiManager.enablePlayButton(true)
            uiManager.enableStopButton(false)
        }
    }

    private fun onUserInterruption(event: BargeInEvent) {
        runOnUiThread {
            engine.stopAudioPlayback()
            engine.stopListening()
            handler.removeCallbacksAndMessages(null)

            isTestRunning = false
            uiManager.showInterruption(event)
            uiManager.enablePlayButton(true)
            uiManager.enableStopButton(false)
        }

        Log.i(TAG, "🎉 BARGE-IN! Latency: %.1fms, Confidence: %.0f%%, Energy: %.1fdB"
            .format(event.latencyMs, event.confidence * 100, event.energyDb))
    }

    private fun onStateChanged(state: BargeInState) {
        Log.d(TAG, "📊 State: $state")
    }

    private fun onError(error: BargeInError) {
        runOnUiThread {
            uiManager.showError("${error.code}\n${error.message}")
            isTestRunning = false
            uiManager.enablePlayButton(true)
            uiManager.enableStopButton(false)
        }

        Log.e(TAG, "❌ Error: ${error.code} - ${error.message}")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::engine.isInitialized) {
            engine.release()
        }
        Log.i(TAG, "🔧 TestActivity destroyed")
    }
}