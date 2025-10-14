package com.aima.bargein.simple

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.aima.bargein.BargeInConfig
import com.aima.bargein.audio.AudioCapture
import com.aima.bargein.vad.*
import kotlinx.coroutines.*
import timber.log.Timber

class VoiceDetectionEngine(
    private val context: Context,
    private val config: BargeInConfig = BargeInConfig.DEFAULT,
    private val listener: (confidence: Float, rmsDb: Float, detected: Boolean) -> Unit
) {

    private lateinit var audioCapture: AudioCapture
    private var vad: IVoiceActivityDetector? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isListening = false

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (isListening) return
        isListening = true

        Timber.i("🎤 Iniciando detección de voz simple...")

        vad = SpectralVoiceActivityDetector(config).apply {
            initialize(config.sampleRate, IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE)
        }

        audioCapture = AudioCapture(
            sampleRate = config.sampleRate,
            onAudioData = { audioData, _ ->
                scope.launch {
                    val result = vad?.processFrame(audioData, audioData.size) ?: return@launch
                    listener(result.confidence, result.energyDb, result.hasVoice)
                }
            }
        )

        audioCapture.startCapture()
    }

    fun stop() {
        if (!isListening) return
        Timber.i("🛑 Detección de voz detenida")
        isListening = false
        audioCapture.stopCapture()
        vad?.release()
        scope.cancel()
    }
}
