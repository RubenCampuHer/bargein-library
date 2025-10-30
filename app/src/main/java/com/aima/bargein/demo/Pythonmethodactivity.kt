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
import com.aima.bargein.PythonVadConfig
import com.aima.bargein.PythonVadPreset
import kotlinx.coroutines.launch
import java.io.File

/**
 * Activity para testing del método Python de barge-in
 * Usa: Leak Compensation + Rise Factor + RMS Threshold
 */
class PythonMethodActivity : AppCompatActivity() {

    lateinit var engine: BargeInEngine
    private lateinit var uiManager: PythonMethodUIManager
    private lateinit var configManager: PythonMethodConfigManager
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private var wavFile: File? = null
    private var isTestRunning = false
    private var isCalibrating = false

    companion object {
        private const val TAG = "PythonMethodActivity"
        private const val PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "🐍 PythonMethodActivity started")

        initializeManagers()
        configManager.loadSettings()
        uiManager.setupUI()
        checkPermissions()
    }

    private fun initializeManagers() {
        configManager = PythonMethodConfigManager(this)
        audioManager = AudioManager(this)
        uiManager = PythonMethodUIManager(this, configManager, audioManager)
    }

    fun isEngineInitialized(): Boolean = this::engine.isInitialized

    fun getEngineOrNull(): BargeInEngine? =
        if (this::engine.isInitialized) engine else null

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        configManager.saveSettings()
        outState.putBoolean("isTestRunning", isTestRunning)
        outState.putString("currentPreset", configManager.currentPreset.name)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        configManager.loadSettings()
        savedInstanceState.getString("currentPreset")?.let {
            try {
                configManager.currentPreset = PythonVadPreset.valueOf(it)
                uiManager.updatePresetButtons()
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring preset", e)
            }
        }
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
            uiManager.showReady(configManager.currentPreset)
            uiManager.enablePlayButton(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error in initialization", e)
            uiManager.showError("Error al inicializar: ${e.message}")
        }
    }

    private fun initializeEngine() {
        val config = configManager.getConfigForCurrentPreset()
        // TODO: Crear BargeInEngine que acepte PythonVadConfig
        // Por ahora usamos el engine existente con configuración convertida
        val androidConfig = configManager.convertToAndroidConfig(config)
        engine = BargeInEngine(androidConfig)
        engine.initialize(applicationContext)
        Log.i(TAG, "✅ Engine initialized with preset: ${configManager.currentPreset}")
    }

    fun changePreset(newPreset: PythonVadPreset) {
        if (!::engine.isInitialized) return
        if (isTestRunning) {
            uiManager.showError("⚠️ Detén el test antes de cambiar el preset")
            return
        }

        configManager.currentPreset = newPreset
        configManager.saveSettings()

        try {
            engine.release()
            Thread.sleep(100)
            initializeEngine()
            observeEngine()

            uiManager.updatePresetButtons()
            uiManager.showPresetChanged(newPreset, configManager.getPresetDescription())

        } catch (e: Exception) {
            uiManager.showError("Error cambiando preset: ${e.message}")
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

        if (configManager.currentPreset != PythonVadPreset.CUSTOM) {
            Log.w(TAG, "Not in CUSTOM mode, skipping apply")
            return
        }

        try {
            Log.i(TAG, "🔧 Applying custom settings changes...")
            Log.i(TAG, "   RMS Threshold: ${configManager.customRmsThreshold}")
            Log.i(TAG, "   Rise Factor: ${configManager.customRiseFactor}")
            Log.i(TAG, "   Leak K: ${configManager.customLeakK}")

            configManager.saveSettings()

            engine.release()
            Thread.sleep(100)
            initializeEngine()
            observeEngine()

            uiManager.showPresetChanged(PythonVadPreset.CUSTOM, configManager.getPresetDescription())

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

            // TODO: Implementar calibración de leak compensation
            // Por ahora usamos la configuración directamente

            engine.startListening()

            uiManager.showTestRunning(configManager.currentPreset, configManager.getPresetDescription())
            uiManager.enablePlayButton(false)
            uiManager.enableStopButton(true)

            startUIUpdates()

            handler.postDelayed({
                wavFile?.inputStream()?.let { engine.playAudio(it) }
            }, 100)

            Log.i(TAG, "▶️ Test started with preset: ${configManager.currentPreset}")
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

    fun onPlaybackComplete() {
        runOnUiThread {
            Log.i(TAG, "🎵 Playback completed normally")

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
        Log.i(TAG, "🔧 PythonMethodActivity destroyed")
    }
}