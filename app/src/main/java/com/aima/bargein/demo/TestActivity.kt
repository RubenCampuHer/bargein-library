package com.aima.bargein.demo

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aima.bargein.*
import com.aima.bargein.vad.IVoiceActivityDetector
import com.aima.bargein.vad.SpectralVoiceActivityDetector
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.util.*

class TestActivity : AppCompatActivity(), BargeInListener {

    private lateinit var engine: BargeInEngine
    private lateinit var tts: TextToSpeech

    private lateinit var statusView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var infoText: TextView
    private lateinit var freqText: TextView
    private lateinit var btnInit: Button
    private lateinit var btnGenerate: Button
    private lateinit var btnTest: Button
    private lateinit var btnStop: Button

    private var ttsReady = false
    private var wavFile: File? = null
    private var colorAnimator: ValueAnimator? = null

    companion object {
        private const val PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.plant(Timber.DebugTree())

        // Fondo con gradiente azul → blanco
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#E3F2FD"), Color.WHITE)
        )

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = bg
            setPadding(48, 64, 48, 64)
        }

        // ======= Título =======
        val title = TextView(this).apply {
            text = "🎙️ Barge-In Demo"
            textSize = 28f
            setTextColor(Color.parseColor("#0D47A1"))
            gravity = Gravity.CENTER
        }

        // ======= Estado =======
        statusView = TextView(this).apply {
            text = "Esperando permisos..."
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 16)
        }

        // ======= Barra de audio =======
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48
            ).apply { setMargins(0, 24, 0, 24) }
            progressDrawable = ContextCompat.getDrawable(this@TestActivity, android.R.drawable.progress_horizontal)
        }

        infoText = TextView(this).apply {
            text = "🔇 Nivel: -- dB | Confianza: --"
            textSize = 16f
            gravity = Gravity.CENTER
        }

        freqText = TextView(this).apply {
            text = "🎧 Frecuencias altas: --%"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 32)
        }

        // ======= Botones =======
        val btnStyle: (String) -> Button = { text ->
            Button(this).apply {
                this.text = text
                textSize = 16f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1976D2"))
                stateListAnimator = null
            }
        }

        btnInit = btnStyle("1️⃣ Inicializar Motor").apply {
            setOnClickListener { initializeEngine() }
        }

        btnGenerate = btnStyle("2️⃣ Generar WAV").apply {
            isEnabled = false
            setOnClickListener { generateWavFile() }
        }

        btnTest = btnStyle("3️⃣ Reproducir + Detectar").apply {
            isEnabled = false
            setOnClickListener {
                // ✅ Comprobamos permiso antes de usar el micrófono
                if (ContextCompat.checkSelfPermission(
                        this@TestActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    startTest()
                } else {
                    // ❌ Sin permiso → mostramos aviso y pedimos permiso de nuevo
                    statusView.text = "⚠️ Permiso de micrófono no concedido"
                    ActivityCompat.requestPermissions(
                        this@TestActivity,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        PERMISSION_CODE
                    )
                }
            }
        }


        btnStop = btnStyle("⏹️ Detener").apply {
            isEnabled = false
            setBackgroundColor(Color.parseColor("#C62828"))
            setOnClickListener { stopTest() }
        }

        layout.addView(title)
        layout.addView(statusView)
        layout.addView(levelBar)
        layout.addView(infoText)
        layout.addView(freqText)
        layout.addView(btnInit)
        layout.addView(btnGenerate)
        layout.addView(btnTest)
        layout.addView(btnStop)

        setContentView(layout)

        initTts()
        checkPermissions()
        startUiUpdater()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("es", "ES")
                ttsReady = true
                Timber.i("TTS ready")
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    statusView.text = "✅ WAV generado. Listo para probar."
                    btnTest.isEnabled = true
                }
            }
            override fun onError(utteranceId: String?) {}
        })
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_CODE)
        } else {
            onPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) onPermissionsGranted()
        else statusView.text = "❌ Sin permiso de micrófono"
    }

    private fun onPermissionsGranted() {
        statusView.text = "✅ Permiso de micrófono concedido"
        btnInit.isEnabled = true
    }

    // === Inicializar motor ===
    private fun initializeEngine() {
        try {
            statusView.text = "⏳ Inicializando..."
            val config = BargeInConfig(
                sampleRate = 16000,
                vadMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
                minVoiceDurationMs = 150,
                voiceConfidenceThreshold = 0.5f,
                highBandMinHz = 2800,
                highBandMaxHz = 7000,
                highRatioThreshold = 0.12f
            )
            engine = BargeInEngine(applicationContext, config, this)
            engine.initialize()
            statusView.text = "✅ Motor listo\nHabla para probar detección"
            btnInit.isEnabled = false
            btnGenerate.isEnabled = true
        } catch (e: Exception) {
            statusView.text = "❌ Error: ${e.message}"
        }
    }

    // === Generar WAV ===
    private fun generateWavFile() {
        if (!ttsReady) {
            statusView.text = "⚠️ TTS no listo"
            return
        }

        wavFile = File(cacheDir, "demo.wav")
        val text = """
            Hola, soy AiMA. Estoy hablando para probar la detección de interrupción.
            Dime algo y te escucharé.
        """.trimIndent()
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "wavgen")
        statusView.text = "⏳ Generando audio..."
        btnGenerate.isEnabled = false
        tts.synthesizeToFile(text, params, wavFile, "wavgen")
    }

    // === Reproducir WAV y escuchar ===
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startTest() {
        if (wavFile == null || !wavFile!!.exists()) {
            statusView.text = "❌ No hay archivo WAV"
            return
        }

        try {
            statusView.text = "🎧 Reproduciendo y escuchando...\nHabla para interrumpir."
            btnTest.isEnabled = false
            btnStop.isEnabled = true

            val inputStream = FileInputStream(wavFile)
            engine.startListening()
            engine.playAudio(inputStream)
        } catch (e: Exception) {
            statusView.text = "❌ Error: ${e.message}"
        }
    }

    private fun stopTest() {
        try {
            val stopped = engine.forceStopPlayback()
            if (stopped) {
                statusView.text = "⏹️ Audio detenido (mic sigue activo)"
                Timber.i("🎧 Playback stopped manually, mic still active")
            } else {
                statusView.text = "⚠️ No había audio reproduciéndose"
            }
            btnStop.isEnabled = false
            btnTest.isEnabled = true
        } catch (e: Exception) {
            Timber.e(e, "Error stopping playback")
        }
    }


    // === UI Updater ===
    private fun startUiUpdater() {
        val handler = Handler(mainLooper)
        handler.post(object : Runnable {
            override fun run() {
                updateVisualizer()
                handler.postDelayed(this, 120)
            }
        })
    }

    private fun updateVisualizer() {
        if (!::engine.isInitialized) return
        val conf = engine.lastConfidence.coerceIn(0f, 1f)

        val db = -60f + (conf * 60f)
        val progress = ((db + 60f) * 100 / 60f).toInt()

        levelBar.progress = progress
        val color = ArgbEvaluator().evaluate(conf, Color.parseColor("#1976D2"), Color.parseColor("#4CAF50")) as Int
        levelBar.progressTintList = android.content.res.ColorStateList.valueOf(color)

        infoText.text = "🎚️ Nivel: ${"%.1f".format(db)} dB | Confianza: ${"%.2f".format(conf)}"
        freqText.text = if (conf > 0.12f) "🎧 Alta energía detectada (${(conf * 100).toInt()}%)"
        else "🔇 Esperando voz..."
    }

    // === BargeInListener ===
    override fun onUserInterruption(event: BargeInEvent) {
        runOnUiThread {
            animateStatus("🚨 Interrupción detectada", "#4CAF50")
            btnStop.isEnabled = false
            btnTest.isEnabled = true
            statusView.text = """
                ✅ Interrupción: ${"%.0f".format(event.latencyMs)} ms
                Confianza ${(event.confidence * 100).toInt()}%
            """.trimIndent()
        }
    }

    override fun onStateChanged(state: BargeInState) {
        Timber.d("State: $state")
    }

    override fun onError(error: BargeInError) {
        runOnUiThread { animateStatus("❌ Error: ${error.code}", "#C62828") }
    }

    private fun animateStatus(text: String, colorHex: String) {
        statusView.text = text
        val from = (statusView.currentTextColor)
        val to = Color.parseColor(colorHex)
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
            addUpdateListener { statusView.setTextColor(it.animatedValue as Int) }
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::engine.isInitialized) engine.release()
        tts.stop()
        tts.shutdown()
    }
}
