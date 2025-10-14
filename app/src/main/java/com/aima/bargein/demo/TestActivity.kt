package com.aima.bargein.demo

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.*
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aima.bargein.BargeInConfig
import com.aima.bargein.vad.SpectralVoiceActivityDetector
import com.aima.bargein.audio.AudioCapture
import com.aima.bargein.vad.IVoiceActivityDetector
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import kotlin.math.log10
import kotlin.math.max

class TestActivity : AppCompatActivity() {

    private lateinit var vad: SpectralVoiceActivityDetector
    private lateinit var audioCapture: AudioCapture
    private var player: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var statusView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var infoText: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button

    private var colorAnimator: ValueAnimator? = null
    private var isListening = false
    private var isPlaying = false

    companion object {
        private const val PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.plant(Timber.DebugTree())

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

        val title = TextView(this).apply {
            text = "🎧 Barge-In Real (Ignora Eco)"
            textSize = 26f
            setTextColor(Color.parseColor("#0D47A1"))
            gravity = Gravity.CENTER
        }

        statusView = TextView(this).apply {
            text = "Esperando permisos..."
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 16)
        }

        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48
            ).apply { setMargins(0, 24, 0, 24) }
        }

        infoText = TextView(this).apply {
            text = "🔇 Nivel: -- dB | Confianza: --"
            textSize = 16f
            gravity = Gravity.CENTER
        }

        val btnStyle: (String, String) -> Button = { text, color ->
            Button(this).apply {
                this.text = text
                textSize = 16f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(color))
                stateListAnimator = null
            }
        }

        btnPlay = btnStyle("▶️ Reproducir + Escuchar", "#1976D2").apply {
            setOnClickListener { checkAndStart() }
        }

        btnStop = btnStyle("⏹️ Detener", "#C62828").apply {
            isEnabled = false
            setOnClickListener { stopAll() }
        }

        layout.addView(title)
        layout.addView(statusView)
        layout.addView(levelBar)
        layout.addView(infoText)
        layout.addView(btnPlay)
        layout.addView(btnStop)

        setContentView(layout)
        checkPermissions()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_CODE)
        } else onPermissionsGranted()
    }

    private fun onPermissionsGranted() {
        statusView.text = "✅ Permiso de micrófono concedido"
        btnPlay.isEnabled = true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) onPermissionsGranted()
        else statusView.text = "❌ Permiso de micrófono denegado"
    }

    // === Inicia barge-in real ===
    private fun checkAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) startBargeIn()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_CODE)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startBargeIn() {
        val wavFile = File(getExternalFilesDir(null), "demo.wav")
        if (!wavFile.exists()) {
            statusView.text = "⚠️ No se encontró demo.wav"
            return
        }

        val config = BargeInConfig(
            sampleRate = 16000,
            vadMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
            highBandMinHz = 2800,
            highBandMaxHz = 7000,
            minVoiceDurationMs = 120,
            voiceConfidenceThreshold = 0.4f
        )

        vad = SpectralVoiceActivityDetector(config)
        vad.initialize(config.sampleRate, IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE)

        audioCapture = AudioCapture(sampleRate = 16000) { micData, _ ->
            scope.launch {
                // Procesa frame del micrófono
                val result = vad.processFrame(micData, micData.size)

                runOnUiThread {
                    val conf = result.confidence.coerceIn(0f, 1f)
                    val db = result.energyDb
                    val progress = (conf * 100).toInt()
                    levelBar.progress = progress
                    infoText.text = "🎚️ Nivel: %.1f dB | Confianza: %.2f".format(db, conf)

                    val color = ArgbEvaluator().evaluate(conf, Color.parseColor("#1976D2"), Color.parseColor("#4CAF50")) as Int
                    levelBar.progressTintList = ColorStateList.valueOf(color)

                    if (result.hasVoice) {
                        animateStatus("🎤 Voz humana detectada!", "#4CAF50")
                        stopPlaybackOnly()
                    }
                }
            }
        }

        isListening = true
        audioCapture.startCapture()
        playAudio()
        btnPlay.isEnabled = false
        btnStop.isEnabled = true
    }

    // === Reproduce WAV y envía datos a VAD ===
    // === Reproduce WAV desde assets o ruta local y alimenta referencia al VAD ===
    private fun playAudio() {
        val wavPath = File(getExternalFilesDir(null), "demo.wav")
        val assetExists = wavPath.exists()

        try {
            // ✅ Carga el WAV desde archivo local o assets
            player = if (assetExists) {
                MediaPlayer().apply {
                    setDataSource(wavPath.absolutePath)
                    prepare()
                }
            } else {
                val afd = assets.openFd("demo.wav")
                MediaPlayer().apply {
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    prepare()
                }
            }

            player?.setOnCompletionListener {
                Timber.i("✅ Audio finalizado")
                statusView.text = "✅ Audio finalizado"
                btnPlay.isEnabled = true
                btnStop.isEnabled = false
                isPlaying = false
            }

            player?.start()
            isPlaying = true
            Timber.i("▶️ WAV reproducido correctamente")
            statusView.text = "▶️ Reproduciendo y escuchando micrófono..."

            // 🧠 Alimenta al VAD con referencia far-end (eco)
            scope.launch(Dispatchers.IO) {
                try {
                    val input = if (assetExists) FileInputStream(wavPath)
                    else assets.open("demo.wav")

                    val buffer = ByteArray(2048)
                    val shortBuf = ShortArray(1024)

                    while (isPlaying) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead <= 0) break

                        // Convierte los bytes en shorts (16-bit PCM)
                        for (i in 0 until bytesRead / 2) {
                            shortBuf[i] = ((buffer[i * 2].toInt() and 0xFF) or
                                    (buffer[i * 2 + 1].toInt() shl 8)).toShort()
                        }

                        // Enviar al detector como referencia far-end
                        vad.onFarEndPcm(shortBuf)
                        delay(20)
                    }

                    input.close()
                    Timber.d("🧩 Finalizó envío de referencia far-end")

                } catch (e: Exception) {
                    Timber.e(e, "Error enviando referencia far-end al VAD")
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "Error reproduciendo WAV")
            statusView.text = "❌ Error: ${e.message}"
        }
    }


    private fun stopPlaybackOnly() {
        if (isPlaying) {
            try {
                player?.stop()
                player?.release()
                player = null
                isPlaying = false
                Timber.i("🛑 Audio detenido por detección de voz humana")
                statusView.text = "🚨 Voz detectada: audio detenido"
            } catch (e: Exception) {
                Timber.e(e, "Error al detener audio")
            }
        }
    }

    private fun stopAll() {
        try {
            if (isListening) {
                audioCapture.stopCapture()
                scope.cancel()
                isListening = false
            }
            player?.stop()
            player?.release()
            player = null
            btnPlay.isEnabled = true
            btnStop.isEnabled = false
            statusView.text = "⏹️ Detección detenida"
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun animateStatus(text: String, colorHex: String) {
        statusView.text = text
        val from = (statusView.currentTextColor)
        val to = Color.parseColor(colorHex)
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
            addUpdateListener { statusView.setTextColor(it.animatedValue as Int) }
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAll()
    }
}
