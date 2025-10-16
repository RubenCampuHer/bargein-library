package com.aima.bargein.demo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aima.bargein.audio.AudioCapture
import kotlinx.coroutines.*
import timber.log.Timber
import kotlin.math.*

class AudioAnalyzerActivity : AppCompatActivity() {

    private var capture: AudioCapture? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var txtDb: TextView
    private lateinit var txtFreq: TextView
    private lateinit var txtStatus: TextView
    private lateinit var spectrumView: LinearLayout

    private var jobFFT: Job? = null
    private val sampleRate = 44100
    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.plant(Timber.DebugTree())
        title = "🎧 Análisis Acústico"
        setupUI()
        checkAndRequestPermission()
    }

    private fun checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
            txtStatus.text = "Solicitando permiso de micrófono..."
        } else {
            txtStatus.text = "Micrófono listo ✅"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                txtStatus.text = "Micrófono listo ✅"
            } else {
                txtStatus.text = "❌ Permiso de micrófono denegado"
            }
        }
    }

    private fun setupUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
        }

        txtStatus = TextView(this).apply {
            text = "Esperando micrófono..."
            textSize = 18f
            setTextColor(Color.DKGRAY)
        }

        txtDb = TextView(this).apply {
            text = "Nivel: -- dB"
            textSize = 20f
            setTextColor(Color.parseColor("#1E88E5"))
        }

        txtFreq = TextView(this).apply {
            text = "Frecuencia: -- Hz"
            textSize = 20f
            setTextColor(Color.parseColor("#43A047"))
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                60
            ).apply { setMargins(0, 16, 0, 16) }
        }

        val btnStart = Button(this).apply {
            text = "🎙️ Iniciar captura"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener { startAnalysis() }
        }

        val btnStop = Button(this).apply {
            text = "⏹️ Detener"
            setBackgroundColor(Color.parseColor("#F44336"))
            setTextColor(Color.WHITE)
            setOnClickListener { stopAnalysis() }
        }

        spectrumView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(Color.parseColor("#ECEFF1"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 400
            )
        }

        root.addView(txtStatus)
        root.addView(txtDb)
        root.addView(txtFreq)
        root.addView(progressBar)
        root.addView(spectrumView)
        root.addView(btnStart)
        root.addView(btnStop)
        setContentView(root)
    }

    private fun startAnalysis() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            checkAndRequestPermission()
            Toast.makeText(this, "Se necesita permiso de micrófono", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            txtStatus.text = "🎙️ Grabando..."
            capture = AudioCapture(sampleRate) { data, _ ->
                analyzeAudioFrame(data)
            }
            capture?.startCapture()
        } catch (e: SecurityException) {
            Timber.e(e, "❌ Permiso de micrófono denegado en tiempo de ejecución")
            txtStatus.text = "❌ Permiso denegado"
        } catch (e: Exception) {
            Timber.e(e, "❌ Error iniciando captura")
            txtStatus.text = "❌ Error al iniciar captura"
        }
    }

    private fun stopAnalysis() {
        txtStatus.text = "⏸️ Detenido"
        jobFFT?.cancel()
        capture?.stopCapture()
    }

    private fun analyzeAudioFrame(samples: ShortArray) {
        jobFFT?.cancel()
        jobFFT = CoroutineScope(Dispatchers.Default).launch {
            val energyDb = calculateDb(samples)
            val freq = estimateDominantFreq(samples, sampleRate)
            val spectrum = calculateSpectrum(samples)

            withContext(Dispatchers.Main) {
                updateUI(energyDb, freq, spectrum)
            }
        }
    }

    private fun calculateDb(samples: ShortArray): Float {
        var sum = 0.0
        for (s in samples) {
            val norm = s / 32768.0
            sum += norm * norm
        }
        val rms = sqrt(sum / samples.size)
        return (20 * log10(rms + 1e-10)).toFloat()
    }

    private fun estimateDominantFreq(samples: ShortArray, sampleRate: Int): Float {
        val n = samples.size
        var maxMag = 0.0
        var maxIndex = 0
        for (i in 1 until n) {
            val mag = abs(samples[i].toDouble())
            if (mag > maxMag) {
                maxMag = mag
                maxIndex = i
            }
        }
        return maxIndex.toFloat() / n * sampleRate
    }

    private fun calculateSpectrum(samples: ShortArray): List<Float> {
        // 1️⃣ Ajusta tamaño a potencia de 2
        var n = samples.size
        var pow2 = 1
        while (pow2 < n) pow2 *= 2
        if (pow2 != n) {
            n = pow2
        }

        val padded = FloatArray(n)
        for (i in samples.indices) padded[i] = samples[i].toFloat()

        // 2️⃣ Ventana Hanning
        for (i in 0 until n) {
            padded[i] *= (0.5f - 0.5f * cos(2 * Math.PI * i / n)).toFloat()
        }

        val re = padded.copyOf()
        val im = FloatArray(n)
        fft(re, im)

        return re.take(64).map { abs(it) }
    }


    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var i = 0
        var j = 0
        while (i < n) {
            if (i < j) {
                val tr = re[i]
                val ti = im[i]
                re[i] = re[j]
                im[i] = im[j]
                re[j] = tr
                im[j] = ti
            }
            var m = n / 2
            while (m >= 1 && j >= m) {
                j -= m
                m /= 2
            }
            j += m
            i++
        }
        var mmax = 1
        while (n > mmax) {
            val step = mmax * 2
            val theta = (-2.0 * Math.PI / step)
            val wtemp = sin(0.5 * theta)
            val wpr = -2.0 * wtemp * wtemp
            val wpi = sin(theta)
            var wr = 1.0
            var wi = 0.0
            var m = 0
            while (m < mmax) {
                var i = m
                while (i < n) {
                    val j = i + mmax
                    val tr = (wr * re[j] - wi * im[j]).toFloat()
                    val ti = (wr * im[j] + wi * re[j]).toFloat()
                    re[j] = re[i] - tr
                    im[j] = im[i] - ti
                    re[i] += tr
                    im[i] += ti
                    i += step
                }
                val wtemp2 = wr
                wr = wr * wpr - wi * wpi + wr
                wi = wi * wpr + wtemp2 * wpi + wi
                m++
            }
            mmax = step
        }
    }

    private fun updateUI(db: Float, freq: Float, spectrum: List<Float>) {
        txtDb.text = "Nivel: ${String.format("%.1f", db)} dB"
        txtFreq.text = "Frecuencia: ${String.format("%.0f", freq)} Hz"
        progressBar.progress = ((db + 60) / 60 * 100).toInt().coerceIn(0, 100)

        spectrumView.removeAllViews()
        val maxVal = spectrum.maxOrNull() ?: 1f
        spectrum.forEach {
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, (it / maxVal * 400).toInt().coerceAtLeast(2), 1f)
                setBackgroundColor(Color.parseColor("#1976D2"))
            }
            spectrumView.addView(bar)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAnalysis()
    }
}
