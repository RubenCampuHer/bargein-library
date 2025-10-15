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

    // ✅ Modos de sensibilidad CALIBRADOS
    private enum class SensitivityMode {
        SUPER_SENSITIVE,  // Ultra rápido, acepta más fácil
        SENSITIVE,        // Equilibrado
        NORMAL            // Más conservador
    }

    private var currentMode = SensitivityMode.SENSITIVE

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.plant(Timber.DebugTree())
        Timber.i("🚀 TestActivity started - Auto-initialization mode")

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
            text = "Micrófono siempre activo • ZCR + Periodicity Method"
            textSize = 14f
            setTextColor(Color.parseColor("#757575"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })

        // ===== MEDIDOR DE NIVEL DE AUDIO =====
        val audioContainer = createCard()

        audioLevelText = TextView(this).apply {
            text = "🔇 Inicializando..."
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

        val zcrInfoText = TextView(this).apply {
            text = """
                Análisis Multi-Criterio:
                • ZCR (0.072-0.35) = Voz ✅
                • Periodicity (>0.3) = Estructura de voz
                • High Freq (>48%) = Contenido agudo
            """.trimIndent()
            textSize = 12f
            setTextColor(Color.parseColor("#757575"))
        }

        frequencyInfoText = metricsText

        audioContainer.addView(audioLevelText)
        audioContainer.addView(audioLevelBar)
        audioContainer.addView(metricsText)
        audioContainer.addView(zcrInfoText)
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
        Timber.i("✅ Permissions granted - Starting auto-initialization")

        statusText.text = "⏳ Inicializando automáticamente..."

        try {
            copyWavFromAssets()
            currentMode = SensitivityMode.SENSITIVE
            updateModeButtons()
            initializeEngine()
            startAutoMonitoring()

        } catch (e: Exception) {
            Timber.e(e, "Error in auto-initialization")
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
                Timber.i("✅ WAV file already exists: ${wavFile!!.absolutePath}")
                return
            }

            try {
                val assetManager = assets
                val inputStream = assetManager.open("test_audio.wav")
                val outputStream = FileOutputStream(wavFile)

                val buffer = ByteArray(1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }

                inputStream.close()
                outputStream.flush()
                outputStream.close()

                Timber.i("✅ WAV file copied from assets: ${wavFile!!.absolutePath}")

            } catch (e: Exception) {
                Timber.w("WAV not in assets, generating synthetic audio...")
                WavGenerator.generateTestWav(wavFile!!, durationSeconds = 15)
                Timber.i("✅ WAV file generated: ${wavFile!!.absolutePath}")
            }

        } catch (e: Exception) {
            Timber.e(e, "Error preparing WAV file")
            wavFile = null
        }
    }

    private fun initializeEngine() {
        try {
            Timber.i("🔧 Initializing BargeInEngine with mode: $currentMode")

            val config = getConfigForMode(currentMode)

            engine = BargeInEngine(
                context = applicationContext,
                config = config,
                listener = this
            )

            engine.initialize()

            Timber.i("✅ Engine initialized successfully with mode: $currentMode")

        } catch (e: Exception) {
            statusText.text = """
                ❌ Error al inicializar motor
                
                ${e.message}
                
                ${if (e.message?.contains("AEC") == true)
                "Nota: Algunos dispositivos no soportan AEC nativo"
            else ""}
            """.trimIndent()

            Timber.e(e, "Engine initialization failed")
            throw e
        }
    }

    // ✅ CONFIGURACIONES ULTRA SENSIBLES
    private fun getConfigForMode(mode: SensitivityMode): BargeInConfig {
        return when (mode) {
            SensitivityMode.SUPER_SENSITIVE -> BargeInConfig(
                sampleRate = 16000,
                vadMode = IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE,
                minVoiceDurationMs = 10, // ✅ 1 frame = ultra rápido
                voiceConfidenceThreshold = 0.30f // ✅ Muy bajo
            )

            SensitivityMode.SENSITIVE -> BargeInConfig(
                sampleRate = 16000,
                vadMode = IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE,
                minVoiceDurationMs = 20, // ✅ 2 frames
                voiceConfidenceThreshold = 0.35f // ✅ Bajo
            )

            SensitivityMode.NORMAL -> BargeInConfig(
                sampleRate = 16000,
                vadMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
                minVoiceDurationMs = 30, // ✅ 3 frames
                voiceConfidenceThreshold = 0.40f // ✅ Moderado
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
            Timber.i("🔄 Changing mode to: $newMode")

            val wasListening = engine.getMetrics().isListening

            engine.release()
            initializeEngine()

            if (wasListening) {
                startAutoMonitoring()
            }

            val modeText = when (newMode) {
                SensitivityMode.SUPER_SENSITIVE -> "🔴 SUPER SENSIBLE\n30ms • Muy rápido"
                SensitivityMode.SENSITIVE -> "🟡 SENSIBLE\n40ms • Equilibrado"
                SensitivityMode.NORMAL -> "🟢 NORMAL\n50ms • Conservador"
            }

            statusText.text = """
                ✅ Modo cambiado
                
                $modeText
                
                🎤 Micrófono activo
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

    @Suppress("MissingPermission")
    private fun startAutoMonitoring() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "❌ No hay permiso de micrófono"
            return
        }

        try {
            engine.startListening()

            statusText.text = """
                ✅ Sistema activo
                
                🎤 Micrófono: ESCUCHANDO
                📊 Analizando continuamente
                
                Presiona "INICIAR TEST" para probar
            """.trimIndent()

            btnPlayTest.isEnabled = true
            startUIUpdates()

            Timber.i("✅ Auto-monitoring started")

        } catch (e: SecurityException) {
            statusText.text = "❌ Error de permisos:\n${e.message}"
            Timber.e(e, "Permission error")
        } catch (e: Exception) {
            statusText.text = "❌ Error iniciando monitoreo:\n${e.message}"
            Timber.e(e, "Failed to start monitoring")
        }
    }

    private fun startUIUpdates() {
        val updateRunnable = object : Runnable {
            override fun run() {
                updateAudioVisualizer()
                handler.postDelayed(this, 50)
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
                ⏱️ Proc: ${vadMetrics.averageProcessingTimeUs}µs/frame
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

            statusText.text = """
                🎵 REPRODUCIENDO AUDIO
                
                ¡Interrumpe hablando FUERTE!
                
                Observa:
                • Barra de nivel de audio
                • Métricas en tiempo real
                • Logs con ZCR y periodicidad
            """.trimIndent()

            btnPlayTest.isEnabled = false
            btnStopTest.isEnabled = true

            val inputStream = wavFile!!.inputStream()
            engine.playAudio(inputStream)

            Timber.i("▶️ Barge-in test started")

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
            Timber.i("🛑 User requested to stop audio...")

            engine.stopAudioPlayback()

            isTestRunning = false

            statusText.text = """
                ⏸️ Audio detenido manualmente
                
                🎤 Micrófono: Sigue activo
                📊 Monitoreando continuamente
                
                Puedes iniciar otro test
            """.trimIndent()

            btnPlayTest.isEnabled = true
            btnStopTest.isEnabled = false

            Timber.i("✅ Audio stopped, microphone remains active")

        } catch (e: Exception) {
            statusText.text = "❌ Error deteniendo:\n${e.message}"
            Timber.e(e, "Error stopping audio")
        }
    }

    // ========== BargeInListener ==========

    @Suppress("MissingPermission")
    override fun onUserInterruption(event: BargeInEvent) {
        runOnUiThread {
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
                
                🎤 Micrófono sigue activo
            """.trimIndent()

            btnPlayTest.isEnabled = true
            btnStopTest.isEnabled = false

            handler.postDelayed({
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                    try {
                        engine.startListening()
                    } catch (e: Exception) {
                        Timber.e(e, "Error resuming monitoring")
                    }
                }
            }, 100)
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