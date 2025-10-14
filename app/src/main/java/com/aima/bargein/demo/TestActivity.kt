package com.aima.bargein.demo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.util.*

class TestActivity : AppCompatActivity(), BargeInListener {

    private lateinit var engine: BargeInEngine
    private lateinit var tts: TextToSpeech
    private lateinit var statusText: TextView
    private lateinit var audioLevelText: TextView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var frequencyInfoText: TextView
    private lateinit var btnInit: Button
    private lateinit var btnGenerate: Button
    private lateinit var btnTest: Button
    private lateinit var btnStop: Button
    private lateinit var btnMonitor: Button

    private var ttsReady = false
    private var wavFile: File? = null
    private var isMonitoring = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.plant(Timber.DebugTree())

        // Inicializar TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale("es", "ES"))
                ttsReady = (result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED)

                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Timber.d("TTS started")
                    }
                    override fun onDone(utteranceId: String?) {
                        Timber.d("TTS completed")
                        if (utteranceId == "generate_wav") {
                            runOnUiThread {
                                statusText.text = "✅ Audio generado\nPresiona 'PROBAR CON WAV'"
                                btnTest.isEnabled = true
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        Timber.e("TTS error")
                    }
                })

                Timber.i("TTS initialized: ready=$ttsReady")
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // ===== TÍTULO =====
        val titleText = TextView(this).apply {
            text = "🎤 Barge-In Monitor"
            textSize = 24f
            setTextColor(Color.parseColor("#2196F3"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        // ===== MEDIDOR DE AUDIO =====
        val audioMeterContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        audioLevelText = TextView(this).apply {
            text = "🔇 Nivel de Audio: -- dB"
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }

        audioLevelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48
            )
        }

        frequencyInfoText = TextView(this).apply {
            text = """
                Bandas de Frecuencia:
                🔵 Bajas: -- | 🟢 Medias: -- | 🟡 Altas: --
            """.trimIndent()
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }

        audioMeterContainer.addView(audioLevelText)
        audioMeterContainer.addView(audioLevelBar)
        audioMeterContainer.addView(frequencyInfoText)

        // ===== STATUS =====
        statusText = TextView(this).apply {
            text = "Esperando permisos..."
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }

        // ===== BOTONES =====
        btnInit = Button(this).apply {
            text = "1️⃣ Inicializar Motor"
            isEnabled = false
            setOnClickListener { initializeEngine() }
        }

        btnMonitor = Button(this).apply {
            text = "🎙️ Monitor de Audio (Solo Escuchar)"
            isEnabled = false
            setOnClickListener { toggleMonitoring() }
        }

        btnGenerate = Button(this).apply {
            text = "2️⃣ Generar Audio WAV"
            isEnabled = false
            setOnClickListener { generateWavFile() }
        }

        btnTest = Button(this).apply {
            text = "3️⃣ PROBAR (Reproduce + Detecta)"
            isEnabled = false
            setOnClickListener { startTestWithWav() }
        }

        btnStop = Button(this).apply {
            text = "⏸️ Detener"
            isEnabled = false
            setOnClickListener { stopTest() }
        }

        layout.addView(titleText)
        layout.addView(audioMeterContainer)
        layout.addView(statusText)
        layout.addView(btnInit)
        layout.addView(btnMonitor)
        layout.addView(btnGenerate)
        layout.addView(btnTest)
        layout.addView(btnStop)

        setContentView(layout)
        checkPermissions()

        // Iniciar actualización de UI
        startUIUpdates()
    }

    private fun startUIUpdates() {
        val handler = android.os.Handler(mainLooper)
        val updateRunnable = object : Runnable {
            override fun run() {
                updateAudioVisualizer()
                handler.postDelayed(this, 100) // Actualizar cada 100ms
            }
        }
        handler.post(updateRunnable)
    }

    private fun updateAudioVisualizer() {
        if (!::engine.isInitialized) return

        try {
            val metrics = engine.getMetrics()
            val vadMetrics = metrics.vadMetrics ?: return

            // Calcular nivel de audio aproximado (basado en frames con voz)
            val totalFrames = vadMetrics.framesProcessed.toFloat()
            if (totalFrames == 0f) return

            val voiceRatio = vadMetrics.voiceFrames.toFloat() / totalFrames
            val avgConfidence = vadMetrics.averageConfidence

            // Simular nivel de dB (de -60 a 0)
            val estimatedDb = -60f + (avgConfidence * 60f)

            // Actualizar barra de progreso (0-100)
            val barProgress = ((estimatedDb + 60f) * 100f / 60f).toInt().coerceIn(0, 100)

            audioLevelBar.progress = barProgress

            // Cambiar color según nivel
            val color = when {
                barProgress > 70 -> Color.parseColor("#4CAF50") // Verde - Alto
                barProgress > 40 -> Color.parseColor("#FF9800") // Naranja - Medio
                else -> Color.parseColor("#F44336") // Rojo - Bajo
            }
            audioLevelBar.progressTintList = android.content.res.ColorStateList.valueOf(color)

            // Actualizar texto
            val icon = when {
                barProgress > 70 -> "🔊"
                barProgress > 40 -> "🔉"
                barProgress > 10 -> "🔈"
                else -> "🔇"
            }

            audioLevelText.text = "$icon Nivel: ${String.format("%.1f", estimatedDb)} dB | " +
                    "Confianza: ${String.format("%.2f", avgConfidence)} | " +
                    "Frames voz: ${vadMetrics.voiceFrames}"

            // Info adicional
            if (vadMetrics.framesProcessed > 0) {
                frequencyInfoText.text = """
                    📊 Frames procesados: ${vadMetrics.framesProcessed}
                    ✅ Frames con voz: ${vadMetrics.voiceFrames}
                    ⏱️ Tiempo proc: ${vadMetrics.averageProcessingTimeUs}µs
                """.trimIndent()
            }

        } catch (e: Exception) {
            Timber.e(e, "Error updating visualizer")
        }
    }

    private fun toggleMonitoring() {
        if (!isMonitoring) {
            startMonitoring()
        } else {
            stopMonitoring()
        }
    }

    private fun startMonitoring() {
        try {
            engine.startListening()
            isMonitoring = true

            btnMonitor.text = "⏸️ Detener Monitor"
            btnMonitor.setBackgroundColor(Color.parseColor("#F44336"))

            statusText.text = """
                🎤 MONITOREANDO AUDIO
                
                Habla cerca del micrófono
                Observa la barra de audio
            """.trimIndent()

            Timber.i("Monitoring started")

        } catch (e: Exception) {
            statusText.text = "❌ Error: ${e.message}"
        }
    }

    private fun stopMonitoring() {
        engine.stopListening()
        isMonitoring = false

        btnMonitor.text = "🎙️ Monitor de Audio"
        btnMonitor.setBackgroundColor(Color.parseColor("#2196F3"))

        statusText.text = "Monitor detenido"
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
                statusText.text = "❌ Sin permiso de micrófono"
            }
        }
    }

    private fun onPermissionsGranted() {
        statusText.text = "✅ Permisos OK\nPresiona 'Inicializar Motor'"
        btnInit.isEnabled = true
    }

    private fun initializeEngine() {
        try {
            statusText.text = "⏳ Inicializando..."

            engine = BargeInEngine(
                context = applicationContext,
                config = BargeInConfig(
                    sampleRate = 16000,
                    vadMode = com.aima.bargein.vad.IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
                    minVoiceDurationMs = 150, // MÁS corto
                    voiceConfidenceThreshold = 0.50f, // MÁS bajo
                    enableMetrics = true
                ),
                listener = this
            )

            engine.initialize()

            statusText.text = """
            ✅ Motor inicializado
            
            Filtro: Solo frecuencias altas
            Voz debe tener > 12% energía alta
        """.trimIndent()
            btnInit.isEnabled = false
            btnMonitor.isEnabled = true
            btnGenerate.isEnabled = true

            Timber.i("Engine initialized with frequency-only filter")

        } catch (e: Exception) {
            statusText.text = "❌ Error:\n${e.message}"
            Timber.e(e, "Init failed")
        }
    }

    private fun generateWavFile() {
        if (!ttsReady) {
            statusText.text = "⚠️ TTS no listo"
            return
        }

        try {
            wavFile = File(cacheDir, "test_speech.wav")

            statusText.text = "⏳ Generando audio WAV..."
            btnGenerate.isEnabled = false

            val text = """
                Te voy a explicar el Imperio Romano. 
                El Imperio Romano fue una de las civilizaciones más poderosas de la historia antigua.
                Fundado en el año setecientos cincuenta y tres antes de Cristo.
                Con el tiempo, se expandió por toda Europa, el norte de África y el Medio Oriente.
                Los romanos construyeron impresionantes acueductos y carreteras.
            """.trimIndent()

            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "generate_wav")

            tts.synthesizeToFile(text, params, wavFile, "generate_wav")

        } catch (e: Exception) {
            statusText.text = "❌ Error generando WAV:\n${e.message}"
            Timber.e(e, "Failed to generate WAV")
        }
    }

    private fun startTestWithWav() {
        if (wavFile == null || !wavFile!!.exists()) {
            statusText.text = "❌ No hay archivo WAV"
            return
        }

        try {
            statusText.text = """
                📢 REPRODUCIENDO + ESCUCHANDO
                
                Observa la barra de audio
                ¡Interrumpe hablando fuerte!
            """.trimIndent()

            btnTest.isEnabled = false
            btnStop.isEnabled = true
            btnMonitor.isEnabled = false

            engine.startListening()

            val inputStream = FileInputStream(wavFile)
            engine.playAudio(inputStream)

            Timber.i("Started test with WAV")

        } catch (e: Exception) {
            statusText.text = "❌ Error: ${e.message}"
            Timber.e(e, "Failed to start test")
        }
    }

    private fun stopTest() {
        engine.stopListening()

        statusText.text = "⏸️ Detenido"
        btnTest.isEnabled = true
        btnStop.isEnabled = false
        btnMonitor.isEnabled = true
    }

    // ========== BargeInListener ==========

    override fun onUserInterruption(event: BargeInEvent) {
        runOnUiThread {
            val emoji = if (event.latencyMs < 300) "✅" else "⚠️"

            statusText.text = """
                🎉 ¡INTERRUMPIDO!
                
                $emoji Latencia: ${String.format("%.0f", event.latencyMs)} ms
                📊 Confianza: ${(event.confidence * 100).toInt()}%
                🔊 Energía: ${String.format("%.1f", event.energyDb)} dB
            """.trimIndent()

            btnTest.isEnabled = true
            btnStop.isEnabled = false
            btnMonitor.isEnabled = true
        }

        Timber.i("🎉 BARGE-IN! latency=${event.latencyMs}ms")
    }

    override fun onStateChanged(state: BargeInState) {
        Timber.d("State: $state")
    }

    override fun onError(error: BargeInError) {
        runOnUiThread {
            statusText.text = "❌ Error: ${error.code}\n${error.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        if (::engine.isInitialized) {
            engine.release()
        }
    }
}