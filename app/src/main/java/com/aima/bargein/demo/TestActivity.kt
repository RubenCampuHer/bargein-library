package com.aima.bargein.demo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aima.bargein.BargeInConfig
import com.aima.bargein.BargeInEngine
import com.aima.bargein.BargeInError
import com.aima.bargein.BargeInEvent
import com.aima.bargein.BargeInListener
import com.aima.bargein.BargeInState
import com.aima.bargein.vad.IVoiceActivityDetector
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

class TestActivity : AppCompatActivity(), BargeInListener {

    private lateinit var engine: BargeInEngine

    // UI Components
    private lateinit var statusText: TextView
    private lateinit var audioLevelText: TextView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var frequencyInfoText: TextView
    private lateinit var btnPlayTest: Button
    private lateinit var btnStopTest: Button
    private lateinit var btnModeSuperSensitive: Button
    private lateinit var btnModeSensitive: Button
    private lateinit var btnModeNormal: Button

    private var wavFile: File? = null
    private var isTestRunning = false

    // ✅ Modos calibrados para 44.1kHz (512 samples/frame = ~11.6ms)
    private enum class SensitivityMode {
        SUPER_SENSITIVE,  // 2 frames (~25ms) - conf 0.40
        SENSITIVE,        // 3 frames (~36ms) - conf 0.45
        NORMAL            // 4 frames (~48ms) - conf 0.50
    }

    private var currentMode = SensitivityMode.SENSITIVE

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.plant(Timber.DebugTree())
        Timber.i("🚀 TestActivity started @ 44.1kHz - Delta detection mode")

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }

        // ===== TÍTULO =====
        layout.addView(TextView(this).apply {
            text = "🎤 Barge-In Live Monitor"
            textSize = 24f
            setTextColor(Color.parseColor("#1976D2"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        layout.addView(TextView(this).apply {
            text = "@ 44.1kHz • Delta Detection • 350ms Pre-Cal"
            textSize = 14f
            setTextColor(Color.parseColor("#757575"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })

        // ===== MEDIDOR DE NIVEL DE AUDIO =====
        val audioContainer = createCard()

        audioLevelText = TextView(this).apply {
            text = "🔇 Inicializando @ 44.1kHz..."
            textSize = 16f
            setTextColor(Color.parseColor("#212121"))
            setPadding(0, 0, 0, 12)
        }

        audioLevelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                60
            ).apply {
                setMargins(0, 0, 0, 12)
            }
        }

        val metricsText = TextView(this).apply {
            text = "Esperando datos..."
            textSize = 13f
            setTextColor(Color.parseColor("#616161"))
            setPadding(0, 0, 0, 8)
        }

        val infoText = TextView(this).apply {
            text = """
                Análisis @ 44.1kHz:
                • High-Pass: 600Hz (preserva voz)
                • Detección por Delta: >12dB = voz
                • Calibración: 200ms con audio real
                • Pre-delay: 350ms antes de calibrar
            """.trimIndent()
            textSize = 12f
            setTextColor(Color.parseColor("#757575"))
        }

        frequencyInfoText = metricsText

        audioContainer.addView(audioLevelText)
        audioContainer.addView(audioLevelBar)
        audioContainer.addView(metricsText)
        audioContainer.addView(infoText)
        layout.addView(audioContainer)

        // ===== STATUS =====
        statusText = TextView(this).apply {
            text = "Verificando permisos..."
            textSize = 14f
            setTextColor(Color.parseColor("#424242"))
            gravity = Gravity.CENTER
            setPadding(16, 20, 16, 10)
        }
        layout.addView(statusText)

        // ===== SELECTOR DE MODO =====
        val modeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(Color.parseColor("#FFF3E0"))
        }

        modeContainer.addView(TextView(this).apply {
            text = "🎚️ MODO DE SENSIBILIDAD"
            textSize = 14f
            setTextColor(Color.parseColor("#E65100"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })

        val modeButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnModeSuperSensitive = createModeButton("🔴 Super", Color.parseColor("#F44336")) {
            changeSensitivityMode(SensitivityMode.SUPER_SENSITIVE)
        }

        btnModeSensitive = createModeButton("🟡 Sensible", Color.parseColor("#FF9800")) {
            changeSensitivityMode(SensitivityMode.SENSITIVE)
        }

        btnModeNormal = createModeButton("🟢 Normal", Color.parseColor("#4CAF50")) {
            changeSensitivityMode(SensitivityMode.NORMAL)
        }

        modeButtonsRow.addView(btnModeSuperSensitive)
        modeButtonsRow.addView(btnModeSensitive)
        modeButtonsRow.addView(btnModeNormal)

        modeContainer.addView(modeButtonsRow)
        layout.addView(modeContainer)

        // ===== BOTONES DE CONTROL =====
        btnPlayTest = createButton(
            "▶️ INICIAR TEST (Reproduce Audio)",
            Color.parseColor("#4CAF50"),
            false
        ) {
            startBargeInTest()
        }
        layout.addView(btnPlayTest)

        btnStopTest = createButton(
            "⏹️ DETENER TEST",
            Color.parseColor("#F44336"),
            false
        ) {
            stopBargeInTest()
        }
        layout.addView(btnStopTest)

        setContentView(layout)
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
    }

    private fun createButton(text: String, color: Int, enabled: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 15f
            setBackgroundColor(color)
            setTextColor(Color.WHITE)
            isEnabled = enabled
            setPadding(20, 32, 20, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createModeButton(text: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            setBackgroundColor(color)
            setTextColor(Color.WHITE)
            setPadding(8, 24, 8, 24)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener { onClick() }
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
                statusText.text = "❌ Permiso de micrófono denegado\n\nLa aplicación no puede funcionar"
                Timber.e("Permission denied")
            }
        }
    }

    private fun onPermissionsGranted() {
        Timber.i("✅ Permissions granted - Starting initialization @ 44.1kHz")

        statusText.text = "⏳ Inicializando @ 44.1kHz..."

        try {
            copyWavFromAssets()
            currentMode = SensitivityMode.SENSITIVE
            updateModeButtons()
            initializeEngine()

            statusText.text = """
                ✅ Sistema listo @ 44.1kHz
                
                🎤 Micrófono: LISTO (inactivo)
                🎚️ High-pass: 600Hz
                🎯 Delta detection: >12dB
                ⏱️ Pre-calibración: 350ms
                
                Presiona "INICIAR TEST" para comenzar
            """.trimIndent()

            btnPlayTest.isEnabled = true

            Timber.i("✅ System ready (idle mode)")

        } catch (e: Exception) {
            Timber.e(e, "Error in initialization")
            statusText.text = """
                ❌ Error al inicializar
                
                ${e.message}
                
                Por favor revisa los logs
            """.trimIndent()
        }
    }

    private fun copyWavFromAssets() {
        try {
            wavFile = File(cacheDir, "test_audio.wav")

            if (wavFile!!.exists()) {
                Timber.i("🗑️ Deleting existing WAV file...")
                wavFile!!.delete()
            }

            var copiedFromAssets = false
            try {
                Timber.i("📂 Attempting to copy test_audio.wav from assets...")

                val assetManager = assets
                val inputStream = assetManager.open("test_audio.wav")
                val outputStream = FileOutputStream(wavFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytes = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                }

                inputStream.close()
                outputStream.flush()
                outputStream.close()

                copiedFromAssets = true
                Timber.i("✅ WAV file copied successfully from assets!")
                Timber.i("   Size: ${totalBytes / 1024}KB")

            } catch (e: java.io.FileNotFoundException) {
                Timber.w("⚠️ test_audio.wav NOT FOUND in assets")
                Timber.w("   Will generate synthetic audio at 44.1kHz")
            }

            if (!copiedFromAssets) {
                Timber.i("🔧 Generating synthetic WAV @ 44.1kHz...")
                WavGenerator.generateTestWav(wavFile!!, durationSeconds = 15)
            }

        } catch (e: Exception) {
            Timber.e(e, "❌ CRITICAL ERROR preparing WAV file")
            wavFile = null
        }
    }

    private fun initializeEngine() {
        try {
            Timber.i("🔧 Initializing BargeInEngine @ 44.1kHz with mode: $currentMode")

            val config = getConfigForMode(currentMode)

            engine = BargeInEngine(
                context = applicationContext,
                config = config,
                listener = this
            )

            engine.initialize()

            Timber.i("✅ Engine initialized successfully @ 44.1kHz with mode: $currentMode")

        } catch (e: Exception) {
            statusText.text = """
                ❌ Error al inicializar motor
                
                ${e.message}
            """.trimIndent()

            Timber.e(e, "Engine initialization failed")
            throw e
        }
    }

    private fun getConfigForMode(mode: SensitivityMode): BargeInConfig {
        return when (mode) {
            SensitivityMode.SUPER_SENSITIVE -> BargeInConfig(
                sampleRate = 44100,
                vadMode = IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE,
                minVoiceDurationMs = 25,
                voiceConfidenceThreshold = 0.40f
            )

            SensitivityMode.SENSITIVE -> BargeInConfig(
                sampleRate = 44100,
                vadMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
                minVoiceDurationMs = 36,
                voiceConfidenceThreshold = 0.45f
            )

            SensitivityMode.NORMAL -> BargeInConfig(
                sampleRate = 44100,
                vadMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
                minVoiceDurationMs = 48,
                voiceConfidenceThreshold = 0.50f
            )
        }
    }

    private fun changeSensitivityMode(newMode: SensitivityMode) {
        if (!::engine.isInitialized) {
            Timber.w("Engine not initialized yet")
            return
        }

        if (isTestRunning) {
            statusText.text = "⚠️ Detén el test antes de cambiar el modo"
            return
        }

        currentMode = newMode
        updateModeButtons()

        try {
            Timber.i("🔄 Changing mode to: $newMode @ 44.1kHz")

            engine.release()
            initializeEngine()

            val modeText = when (newMode) {
                SensitivityMode.SUPER_SENSITIVE -> "🔴 SUPER SENSIBLE\n25ms • 2 frames • conf=0.40"
                SensitivityMode.SENSITIVE -> "🟡 SENSIBLE\n36ms • 3 frames • conf=0.45"
                SensitivityMode.NORMAL -> "🟢 NORMAL\n48ms • 4 frames • conf=0.50"
            }

            statusText.text = """
                ✅ Modo cambiado @ 44.1kHz
                
                $modeText
                
                🎤 Sistema listo (inactivo)
                Presiona "INICIAR TEST" para probar
            """.trimIndent()

            Timber.i("✅ Mode changed successfully to: $newMode")

        } catch (e: Exception) {
            statusText.text = "❌ Error cambiando modo:\n${e.message}"
            Timber.e(e, "Failed to change mode")
        }
    }

    private fun updateModeButtons() {
        btnModeSuperSensitive.alpha = 0.5f
        btnModeSensitive.alpha = 0.5f
        btnModeNormal.alpha = 0.5f

        when (currentMode) {
            SensitivityMode.SUPER_SENSITIVE -> btnModeSuperSensitive.alpha = 1.0f
            SensitivityMode.SENSITIVE -> btnModeSensitive.alpha = 1.0f
            SensitivityMode.NORMAL -> btnModeNormal.alpha = 1.0f
        }
    }

    private fun startUIUpdates() {
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isTestRunning) {
                    updateAudioVisualizer()
                    handler.postDelayed(this, 50)
                }
            }
        }
        handler.post(updateRunnable)
    }

    private fun updateAudioVisualizer() {
        if (!::engine.isInitialized) return

        try {
            val metrics = engine.getMetrics()
            val vadMetrics = metrics.vadMetrics ?: return

            val totalFrames = vadMetrics.framesProcessed.toFloat()
            if (totalFrames == 0f) return

            val voiceRatio = vadMetrics.voiceFrames.toFloat() / totalFrames
            val avgConfidence = vadMetrics.averageConfidence

            val estimatedDb = -60f + (avgConfidence * 60f)
            val barProgress = ((estimatedDb + 60f) * 100f / 60f).toInt().coerceIn(0, 100)

            audioLevelBar.progress = barProgress

            val color = when {
                barProgress > 70 -> Color.parseColor("#4CAF50")
                barProgress > 50 -> Color.parseColor("#8BC34A")
                barProgress > 30 -> Color.parseColor("#FFC107")
                barProgress > 15 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#F44336")
            }
            audioLevelBar.progressTintList = android.content.res.ColorStateList.valueOf(color)

            val icon = when {
                barProgress > 70 -> "🔊"
                barProgress > 50 -> "🔉"
                barProgress > 30 -> "🔉"
                barProgress > 10 -> "🔈"
                else -> "🔇"
            }

            audioLevelText.text = "$icon Audio: ${String.format("%.1f", estimatedDb)} dB | " +
                    "Confianza: ${String.format("%.2f", avgConfidence)}"

            val stateEmoji = when (metrics.state) {
                BargeInState.IDLE -> "💤"
                BargeInState.LISTENING -> "🎤"
                BargeInState.INTERRUPTED -> "🚨"
                BargeInState.STOPPED -> "⏸️"
                BargeInState.ERROR -> "❌"
            }

            val listeningStatus = if (metrics.isListening) "🟢 ACTIVO" else "🔴 INACTIVO"
            val playingStatus = if (metrics.isPlaying) "🟢 SÍ" else "⚪ NO"

            frequencyInfoText.text = """
                📊 Frames: ${vadMetrics.framesProcessed} | Voz: ${vadMetrics.voiceFrames} (${String.format("%.1f", voiceRatio * 100)}%)
                ⏱️ Proc: ${vadMetrics.averageProcessingTimeUs}µs/frame (~11.6ms)
                🎯 Estado: $stateEmoji ${metrics.state} | Mic: $listeningStatus | Audio: $playingStatus
            """.trimIndent()

        } catch (e: Exception) {
            Timber.e(e, "Error updating visualizer")
        }
    }

    @Suppress("MissingPermission")
    private fun startBargeInTest() {
        if (wavFile == null || !wavFile!!.exists()) {
            statusText.text = """
                ❌ No hay archivo de audio
                
                Error generando el WAV
                Revisa los logs
            """.trimIndent()
            return
        }

        if (isTestRunning) {
            Timber.w("Test already running")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "❌ No hay permiso de micrófono"
            return
        }

        try {
            isTestRunning = true

            // Iniciar captura primero
            engine.startListening()

            statusText.text = """
                🎵 REPRODUCIENDO AUDIO @ 44.1kHz
                
                ¡Interrumpe hablando FUERTE!
                
                🎤 Micrófono: ACTIVO
                ⏳ Pre-delay: 350ms (esperando AudioTrack)
                🎯 Calibración: 200ms después del delay
                📊 Detección: Delta >12dB
            """.trimIndent()

            btnPlayTest.isEnabled = false
            btnStopTest.isEnabled = true

            // Iniciar UI updates
            startUIUpdates()

            // Iniciar reproducción (con delay interno de 350ms antes de calibrar)
            handler.postDelayed({
                val inputStream = wavFile!!.inputStream()
                engine.playAudio(inputStream)
                Timber.i("▶️ Barge-in test started @ 44.1kHz")
            }, 100)

        } catch (e: SecurityException) {
            statusText.text = "❌ Error de permisos:\n${e.message}"
            isTestRunning = false
            btnPlayTest.isEnabled = true
            btnStopTest.isEnabled = false
            Timber.e(e, "Permission error")
        } catch (e: Exception) {
            statusText.text = "❌ Error iniciando test:\n${e.message}"
            isTestRunning = false
            btnPlayTest.isEnabled = true
            btnStopTest.isEnabled = false
            Timber.e(e, "Failed to start test")
        }
    }

    private fun stopBargeInTest() {
        if (!isTestRunning) {
            Timber.w("No test running")
            return
        }

        try {
            Timber.i("🛑 User requested to stop test...")

            // Detener reproducción
            engine.stopAudioPlayback()

            // Detener captura
            engine.stopListening()

            // Detener UI updates
            handler.removeCallbacksAndMessages(null)

            isTestRunning = false

            statusText.text = """
                ⏸️ Test detenido
                
                🎤 Micrófono: INACTIVO
                📊 Sistema en reposo
                
                Presiona "INICIAR TEST" para otra prueba
            """.trimIndent()

            btnPlayTest.isEnabled = true
            btnStopTest.isEnabled = false

            Timber.i("✅ Test stopped, system idle")

        } catch (e: Exception) {
            statusText.text = "❌ Error deteniendo:\n${e.message}"
            Timber.e(e, "Error stopping test")
        }
    }

    // ========== BargeInListener ==========

    @Suppress("MissingPermission")
    override fun onUserInterruption(event: BargeInEvent) {
        runOnUiThread {
            // Detener todo
            engine.stopAudioPlayback()
            engine.stopListening()
            handler.removeCallbacksAndMessages(null)

            isTestRunning = false

            val latencyOk = event.latencyMs < 300
            val emoji = if (latencyOk) "✅" else "⚠️"
            val colorIndicator = if (latencyOk) "🟢" else "🟡"

            statusText.text = """
                🎉 ¡BARGE-IN DETECTADO!
                
                $emoji Latencia: ${String.format("%.0f", event.latencyMs)} ms $colorIndicator
                📊 Confianza: ${String.format("%.0f", event.confidence * 100)}%
                📊 Energía: ${String.format("%.1f", event.energyDb)} dB
                
                ${if (latencyOk) "¡Excelente respuesta! <300ms" else "Mejorable (>300ms)"}
                
                🎤 Sistema en reposo
                Presiona "INICIAR TEST" para otra prueba
            """.trimIndent()

            btnPlayTest.isEnabled = true
            btnStopTest.isEnabled = false
        }

        Timber.i("🎉 BARGE-IN! latency=${String.format("%.1f", event.latencyMs)}ms, " +
                "conf=${String.format("%.0f", event.confidence * 100)}%, " +
                "energy=${String.format("%.1f", event.energyDb)}dB")
    }

    override fun onStateChanged(state: BargeInState) {
        Timber.d("📊 State: $state")
    }

    override fun onError(error: BargeInError) {
        runOnUiThread {
            statusText.text = """
                ❌ ERROR
                
                ${error.code}
                ${error.message}
            """.trimIndent()

            isTestRunning = false
            btnPlayTest.isEnabled = true
            btnStopTest.isEnabled = false
        }

        Timber.e("❌ Error: ${error.code} - ${error.message}")
    }

    override fun onDestroy() {
        super.onDestroy()

        Timber.i("🔧 Destroying TestActivity...")

        handler.removeCallbacksAndMessages(null)

        try {
            if (::engine.isInitialized) {
                engine.release()
                Timber.d("✅ Engine released")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error releasing engine")
        }

        Timber.i("✅ TestActivity destroyed")
    }
}
