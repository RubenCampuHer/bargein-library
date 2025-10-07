package com.aima.bargein.demo

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.LinearLayout
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
    private lateinit var btnInit: Button
    private lateinit var btnGenerate: Button
    private lateinit var btnTest: Button
    private lateinit var btnStop: Button

    private var ttsReady = false
    private var wavFile: File? = null

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

                // Configurar listener para saber cuándo termina
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
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            text = "Esperando permisos..."
            textSize = 16f
            setPadding(0, 0, 0, 32)
        }

        btnInit = Button(this).apply {
            text = "1️⃣ Inicializar Motor"
            isEnabled = false
            setPadding(16, 24, 16, 24)
            setOnClickListener { initializeEngine() }
        }

        btnGenerate = Button(this).apply {
            text = "2️⃣ Generar Audio WAV"
            isEnabled = false
            setPadding(16, 24, 16, 24)
            setOnClickListener { generateWavFile() }
        }

        btnTest = Button(this).apply {
            text = "3️⃣ PROBAR CON WAV (¡Interrúmpeme!)"
            isEnabled = false
            setPadding(16, 24, 16, 24)
            setOnClickListener { startTestWithWav() }
        }

        btnStop = Button(this).apply {
            text = "⏸️ Detener"
            isEnabled = false
            setPadding(16, 24, 16, 24)
            setOnClickListener { stopTest() }
        }

        layout.addView(statusText)
        layout.addView(btnInit)
        layout.addView(btnGenerate)
        layout.addView(btnTest)
        layout.addView(btnStop)

        setContentView(layout)
        checkPermissions()
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
                config = BargeInConfig.AGGRESSIVE, // Más sensible
                listener = this
            )

            engine.initialize()

            statusText.text = "✅ Motor listo\nPresiona 'Generar Audio WAV'"
            btnInit.isEnabled = false
            btnGenerate.isEnabled = true

            Timber.i("Engine initialized")

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
            // Crear archivo WAV temporal
            wavFile = File(cacheDir, "test_speech.wav")

            statusText.text = "⏳ Generando audio WAV...\n(Esto tarda unos segundos)"
            btnGenerate.isEnabled = false

            val text = """
                Te voy a explicar el Imperio Romano. 
                El Imperio Romano fue una de las civilizaciones más poderosas de la historia antigua.
                Fundado en el año setecientos cincuenta y tres antes de Cristo, Roma comenzó como una pequeña ciudad.
                Con el tiempo, se expandió por toda Europa, el norte de África y el Medio Oriente.
                Los romanos construyeron impresionantes acueductos, carreteras y anfiteatros.
                Su legado incluye el derecho romano, la arquitectura y el latín, que influyó en muchas lenguas modernas.
                El imperio alcanzó su máxima extensión bajo el emperador Trajano en el año ciento diecisiete después de Cristo.
                Finalmente cayó en el año cuatrocientos setenta y seis, marcando el fin de la antigüedad clásica.
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
            statusText.text = "❌ No hay archivo WAV\nGenera primero el audio"
            return
        }

        try {
            statusText.text = """
                📢 REPRODUCIENDO AUDIO...
                🎤 Micrófono activo
                
                ¡INTERRUMPE DICIENDO ALGO!
                Ejemplo: "Cállate", "Espera", "Para"
            """.trimIndent()

            btnTest.isEnabled = false
            btnStop.isEnabled = true

            // Iniciar escucha
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            engine.startListening()

            // Reproducir WAV con la librería
            val inputStream = FileInputStream(wavFile)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            engine.playAudio(inputStream)

            Timber.i("🎤 Started listening + 📢 Playing WAV")

        } catch (e: Exception) {
            statusText.text = "❌ Error: ${e.message}"
            Timber.e(e, "Failed to start test")
        }
    }

    private fun stopTest() {
        engine.stopListening()

        statusText.text = "⏸️ Detenido\nPresiona 'PROBAR CON WAV' de nuevo"
        btnTest.isEnabled = true
        btnStop.isEnabled = false
    }

    // ========== BargeInListener ==========

    override fun onUserInterruption(event: BargeInEvent) {
        runOnUiThread {
            val emoji = if (event.latencyMs < 300) "✅" else "⚠️"
            val rating = when {
                event.latencyMs < 200 -> "¡EXCELENTE!"
                event.latencyMs < 300 -> "¡MUY BIEN!"
                event.latencyMs < 500 -> "BIEN"
                else -> "LENTO"
            }

            statusText.text = """
                🎉 ¡TE INTERRUMPIÓ! $rating
                
                $emoji Latencia: ${String.format("%.0f", event.latencyMs)} ms
                📊 Confianza: ${(event.confidence * 100).toInt()}%
                🔊 Energía: ${String.format("%.1f", event.energyDb)} dB
                
                El audio se detuvo en ${String.format("%.0f", event.latencyMs)} ms
                ¡Funciona perfectamente!
                
                Presiona 'PROBAR CON WAV' para repetir
            """.trimIndent()

            btnTest.isEnabled = true
            btnStop.isEnabled = false
        }

        Timber.i("🎉 BARGE-IN! latency=${event.latencyMs}ms, stopped audio playback")
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