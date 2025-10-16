package com.aima.bargein.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aima.bargein.*
import com.aima.bargein.audio.AudioCapture
import com.aima.bargein.audio.AudioPlayback
import com.aima.bargein.vad.AdaptiveEnergyVAD
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

class TestActivity : AppCompatActivity(), BargeInListener {

    private lateinit var engine: BargeInEngine
    private lateinit var txtStatus: TextView
    private lateinit var txtEnergy: TextView
    private lateinit var txtDelta: TextView
    private lateinit var txtBaseline: TextView
    private lateinit var progressBar: ProgressBar

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val REQUEST_RECORD_AUDIO = 1234
    private var updateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Timber.forest().isEmpty()) Timber.plant(Timber.DebugTree())
        Timber.i("🚀 TestActivity started")

        title = "🎙️ Barge-In Test (44.1kHz)"
        setupUI()

        if (checkPermission()) initializeEngine()
        else requestPermission()
    }

    private fun checkPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            initializeEngine()
        } else {
            txtStatus.text = "❌ Permiso de micrófono denegado"
            Toast.makeText(this, "❌ Sin permiso de micrófono", Toast.LENGTH_LONG).show()
        }
    }

    private fun initializeEngine() {
        try {
            Timber.i("🚀 Inicializando motor Barge-In...")
            engine = BargeInEngine(this, this)
            engine.initialize()
            txtStatus.text = "✅ Motor inicializado\nPresiona INICIAR TEST para comenzar"
            startUIUpdates()
        } catch (e: Exception) {
            Timber.e(e, "❌ Error inicializando engine")
            txtStatus.text = "❌ Error: ${e.message}"
        }
    }

    // === Construcción de UI básica ===
    private fun setupUI() {
        val root = ScrollView(this).apply {
            setBackgroundColor(0xFFF4F6F7.toInt())
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        // ==== CABECERA ====
        val header = TextView(this).apply {
            text = "🎙️ BARGE-IN TEST SUITE"
            textSize = 22f
            setPadding(0, 0, 0, 20)
        }
        container.addView(header)

        // ==== ESTADO Y MÉTRICAS ====
        txtStatus = TextView(this).apply {
            text = "Inicializando..."
            textSize = 16f
            setPadding(0, 0, 0, 20)
        }

        txtEnergy = TextView(this).apply { text = "📊 Energía: -- dB" }
        txtBaseline = TextView(this).apply { text = "📍 Baseline: -- dB" }
        txtDelta = TextView(this).apply { text = "📈 Delta: -- dB" }

        val metricsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xFFE8F5E9.toInt())
            addView(txtEnergy)
            addView(txtBaseline)
            addView(txtDelta)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 60
            ).apply { setMargins(0, 20, 0, 20) }
        }

        container.addView(txtStatus)
        container.addView(metricsBox)
        container.addView(progressBar)

        // ==== BOTONES PRINCIPALES ====
        val mainControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 10)
        }

        val btnStart = Button(this).apply {
            text = "▶️ Iniciar Test"
            setOnClickListener { startTest() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnStop = Button(this).apply {
            text = "⏹️ Detener"
            setOnClickListener { stopTest() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        mainControls.addView(btnStart)
        mainControls.addView(btnStop)
        container.addView(mainControls)

        // ==== SECCIÓN DIAGNÓSTICO ====
        val diagTitle = TextView(this).apply {
            text = "\n🧪 Diagnóstico de Audio"
            textSize = 18f
            setPadding(0, 20, 0, 10)
        }
        container.addView(diagTitle)

        val diagButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val btnSweep = Button(this).apply {
            text = "🎧 Sweep (20Hz–20kHz)"
            setOnClickListener { playSweep() }
        }

        val btnPilot = Button(this).apply {
            text = "📊 Pilot Tone 15kHz"
            setOnClickListener { playPilot() }
        }

        val btnSweepCapture = Button(this).apply {
            text = "🎙️ Capturar Sweep (ver energía)"
            setOnClickListener { runSweepCaptureTest() }
        }

        diagButtons.addView(btnSweep)
        diagButtons.addView(btnPilot)
        diagButtons.addView(btnSweepCapture)
        container.addView(diagButtons)

        // ==== SECCIÓN AVANZADA ====
        val advTitle = TextView(this).apply {
            text = "\n⚙️ Opciones avanzadas"
            textSize = 18f
            setPadding(0, 20, 0, 10)
        }
        container.addView(advTitle)

        val advButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnToggleAec = Button(this).apply {
            text = "🎛️ AEC: ON"
            setOnClickListener {
                scope.launch {
                    try {
                        val capture = AudioCapture(44100) { _, _ -> }
                        capture.aecEnabled = !capture.aecEnabled
                        capture.toggleAEC()
                        val state = if (capture.aecEnabled) "ON" else "OFF"
                        runOnUiThread {
                            text = "🎛️ AEC: $state"
                            Toast.makeText(this@TestActivity, "AEC: $state", Toast.LENGTH_SHORT).show()
                            txtStatus.text = "🔁 AEC cambiado a $state"
                        }
                        capture.release()
                    } catch (e: Exception) {
                        Timber.e(e, "Error toggling AEC")
                    }
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnMode = Button(this).apply {
            text = "🎚️ Modo: Normal"
            setOnClickListener {
                // alterna modo de sensibilidad
                val current = text.contains("Normal")
                text = if (current) "🎚️ Modo: Super" else "🎚️ Modo: Normal"
                val mode = if (current)
                    com.aima.bargein.vad.AdaptiveEnergyVAD.SensitivityMode.SUPER_SENSITIVE
                else
                    com.aima.bargein.vad.AdaptiveEnergyVAD.SensitivityMode.NORMAL
                engine.setSensitivityMode(mode)
                txtStatus.text = "⚙️ Sensibilidad cambiada a $mode"
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        advButtons.addView(btnToggleAec)
        advButtons.addView(btnMode)
        container.addView(advButtons)

        // ==== FINAL ====
        root.addView(container)
        setContentView(root)
    }


    // ==========================================================
    // == 🧪 TEST #1: Sweep + Captura simultánea ==
    // ==========================================================
    private fun runSweepCaptureTest() {
        Timber.i("🎧 Iniciando Sweep Capture Test...")
        txtStatus.text = "🎧 Iniciando prueba de sweep..."

        // Inicia captura de micrófono
        val capture = AudioCapture(44100) { samples, _ ->
            var sum = 0.0
            for (s in samples) {
                val norm = s / 32768.0
                sum += norm * norm
            }
            val rms = sqrt(sum / samples.size)
            val db = (20 * log10(rms + 1e-10)).toFloat()
            Timber.d("Mic Energy: ${String.format("%.1f", db)} dB")
        }

        try {
            capture.startCapture()
        } catch (e: SecurityException) {
            Timber.e(e, "❌ Sin permiso de micrófono")
            Toast.makeText(this, "Permiso de micrófono denegado", Toast.LENGTH_SHORT).show()
            return
        }

        // Reproduce sweep 20Hz–20kHz
        val playback = AudioPlayback(scope)
        playback.playSweep(20f, 20000f, 8000)

        // Espera y detiene ambos después de 9s
        scope.launch {
            delay(9000)
            playback.stop()
            capture.stopCapture()
            txtStatus.text = "✅ Sweep test finalizado — revisa los logs 🎙️"
            Timber.i("✅ Sweep Capture Test terminado.")
        }
    }

    // ==========================================================
    // == FUNCIONES DE PRUEBA NORMAL ==
    // ==========================================================
    private fun startTest() {
        Timber.i("▶️ Iniciando test...")
        txtStatus.text = "⏳ Preparando audio..."

        scope.launch {
            val wavData = loadWavFromAssets("aima_voice.wav")
            if (wavData != null) {
                engine.startListening()
                delay(300)
                engine.playAudio(wavData)
                txtStatus.text = "🎧 Audio reproduciéndose...\nHabla para interrumpir"
            } else {
                txtStatus.text = "❌ WAV no encontrado"
            }
        }
    }

    private fun stopTest() {
        Timber.i("🛑 Deteniendo test...")
        txtStatus.text = "⏹️ Detenido"
        engine.stop()
    }

    private fun playSweep() {
        Timber.i("🎧 Sweep activado")
        txtStatus.text = "🎧 Reproduciendo sweep..."
        engine.playSweep()
    }

    private fun playPilot() {
        Timber.i("📊 Pilot tone activado")
        txtStatus.text = "📊 Reproduciendo tono piloto 15kHz..."
        engine.playPilotTone()
    }

    // ==========================================================
    // == LISTENER CALLBACKS ==
    // ==========================================================
    override fun onUserInterruption(event: BargeInEvent) {
        val confPercent = (event.confidence * 100).roundToInt()
        runOnUiThread {
            txtStatus.text = """
                🎉 ¡Barge-in detectado!
                Latencia: ${String.format("%.1f", event.latencyMs)}ms
                Confianza: ${confPercent}%
                Energía: ${String.format("%.1f", event.energyDb)}dB
            """.trimIndent()
            progressBar.progress = confPercent
        }
    }

    override fun onPlaybackEnded() {
        runOnUiThread { txtStatus.text = "✅ Reproducción finalizada" }
    }

    override fun onStateChanged(state: BargeInState) {
        Timber.d("📊 Estado cambiado: $state")
    }

    override fun onError(error: BargeInError) {
        Timber.e("❌ Error: ${error.code} - ${error.message}")
        runOnUiThread { txtStatus.text = "❌ Error: ${error.message}" }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
        scope.cancel()
        if (::engine.isInitialized) engine.release()
        Timber.i("✅ TestActivity destruida")
    }

    // ==========================================================
    // == UTILIDAD ==
    // ==========================================================
    private fun loadWavFromAssets(fileName: String): ShortArray? {
        return try {
            val inputStream: InputStream = assets.open(fileName)
            val bytes = inputStream.readBytes()
            val dataBytes = bytes.copyOfRange(44, bytes.size)
            val bb = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            val shortArray = ShortArray(dataBytes.size / 2)
            var i = 0
            while (bb.hasRemaining()) shortArray[i++] = bb.short
            Timber.i("✅ WAV cargado: ${shortArray.size} muestras")
            shortArray
        } catch (e: Exception) {
            Timber.e(e, "❌ Error cargando WAV")
            null
        }
    }

    private fun startUIUpdates() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (isActive) {
                delay(100)
            }
        }
    }
}
