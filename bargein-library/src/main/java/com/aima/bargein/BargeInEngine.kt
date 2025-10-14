package com.aima.bargein

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.aima.bargein.aec.AcousticEchoCancelerFactory
import com.aima.bargein.aec.IAcousticEchoCanceler
import com.aima.bargein.audio.*
import com.aima.bargein.permissions.PermissionHelper
import com.aima.bargein.vad.*
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

class BargeInEngine(
    private val context: Context,
    private val config: BargeInConfig = BargeInConfig.DEFAULT,
    private val listener: BargeInListener
) {
    private lateinit var audioCapture: AudioCapture
    private lateinit var audioPlayback: AudioPlayback
    private val audioFocusManager = AudioFocusManager(context)

    private var aec: IAcousticEchoCanceler? = null
    private var vad: IVoiceActivityDetector? = null

    private val state = AtomicReference(BargeInState.IDLE)
    private val isListening = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    private var voiceDetectionStartTime = 0L
    private var consecutiveVoiceFrames = 0L
    private val minVoiceFrames = config.minVoiceDurationMs / 10

    private val engineScope = CoroutineScope(AudioThreadConfig.audioDispatcher + SupervisorJob())

    // 🔹 Valor público para UI (nivel de confianza actual)
    @Volatile
    var lastConfidence: Float = 0f
        private set

    init {
        Timber.i("BargeInEngine created with config: $config")
    }

    fun initialize() {
        Timber.d("Initializing BargeInEngine...")

        if (!PermissionHelper.hasRequiredPermissions(context)) {
            val error = BargeInError(ErrorCode.PERMISSION_DENIED, "Missing RECORD_AUDIO permission")
            notifyError(error)
            throw SecurityException(error.message)
        }

        try {
            aec = AcousticEchoCancelerFactory.create(
                audioSessionId = 0,
                sampleRate = config.sampleRate,
                preference = config.aecPreference
            )

            if (aec == null || !aec!!.initialize()) {
                val error = BargeInError(ErrorCode.AEC_INIT_FAILED, "Failed to initialize AEC")
                notifyError(error)
                throw IllegalStateException(error.message)
            }

            Timber.i("AEC initialized: type=${aec?.getType()}")

            vad = SpectralVoiceActivityDetector(config)
            if (!vad!!.initialize(config.sampleRate, config.vadMode)) {
                val error = BargeInError(ErrorCode.VAD_INIT_FAILED, "Failed to initialize VAD")
                notifyError(error)
                throw IllegalStateException(error.message)
            }

            Timber.i("VAD initialized: type=${vad?.getType()}")

            initializeAudioComponents()
            updateState(BargeInState.IDLE)
            Timber.i("✅ BargeInEngine initialized successfully")

        } catch (e: Exception) {
            Timber.e(e, "Error initializing BargeInEngine")
            cleanup()
            throw e
        }
    }

    // ---------------------------
    // AUDIO CONTROL
    // ---------------------------

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        if (state.get() == BargeInState.LISTENING) {
            Timber.w("Already listening")
            return
        }

        try {
            if (!audioFocusManager.requestAudioFocus()) {
                val error = BargeInError(ErrorCode.AUDIO_FOCUS_FAILED, "Failed to obtain audio focus")
                notifyError(error)
                throw IllegalStateException(error.message)
            }

            audioCapture.startCapture()
            isListening.set(true)
            consecutiveVoiceFrames = 0
            voiceDetectionStartTime = 0

            updateState(BargeInState.LISTENING)
            Timber.i("🎤 Listening started")

        } catch (e: Exception) {
            Timber.e(e, "Error starting listening")
            val error = BargeInError(ErrorCode.AUDIO_CAPTURE_FAILED, "Failed to start audio capture: ${e.message}", e)
            notifyError(error)
            throw e
        }
    }

    fun stopListening() {
        if (!isListening.get()) return
        try {
            audioCapture.stopCapture()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping capture")
        }
        audioFocusManager.abandonAudioFocus()
        isListening.set(false)
        updateState(BargeInState.STOPPED)
        Timber.i("🎧 Listening stopped")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun playAudio(audioStream: InputStream) {
        if (isPlaying.get()) {
            Timber.w("Already playing audio")
            return
        }

        try {
            if (!isListening.get()) startListening()
            isPlaying.set(true)
            audioPlayback.playWav(audioStream)
            Timber.i("▶️ Audio playback started")
        } catch (e: Exception) {
            Timber.e(e, "Error playing audio")
            isPlaying.set(false)
            val error = BargeInError(ErrorCode.AUDIO_PLAYBACK_FAILED, "Failed to play audio: ${e.message}", e)
            notifyError(error)
        }
    }

    private fun stopPlayback(): Long {
        if (!isPlaying.get()) return 0
        Timber.i("🛑 Stopping playback ONLY (mic stays active)")
        val stopTimestamp = System.nanoTime()
        try {
            audioPlayback.stopImmediately()
        } catch (e: Exception) {
            Timber.e(e, "Error stopping AudioPlayback")
        }
        isPlaying.set(false)
        Timber.i("✅ Playback stopped, mic continues listening")
        return stopTimestamp
    }

    private fun triggerBargeIn(vadResult: IVoiceActivityDetector.VadResult, detectionTimestamp: Long) {
        if (!isPlaying.get()) return
        Timber.i("🚨 BARGE-IN TRIGGERED! Stopping playback, keeping mic active...")
        updateState(BargeInState.INTERRUPTED)
        val stopTimestamp = stopPlayback()
        val latencyNs = stopTimestamp - voiceDetectionStartTime
        val latencyMs = latencyNs / 1_000_000f
        listener.onUserInterruption(
            BargeInEvent(
                detectionTimestamp = voiceDetectionStartTime,
                stopTimestamp = stopTimestamp,
                latencyMs = latencyMs,
                confidence = vadResult.confidence,
                energyDb = vadResult.energyDb
            )
        )
        consecutiveVoiceFrames = 0
        voiceDetectionStartTime = 0
    }

    fun forceStopPlayback(): Boolean {
        return try {
            if (isPlaying.get()) {
                stopPlayback()
                true
            } else {
                Timber.w("⚠️ No active playback to stop")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error in forceStopPlayback")
            false
        }
    }

    fun release() {
        Timber.d("Releasing BargeInEngine...")
        forceStopPlayback()
        stopListening()
        cleanup()
        engineScope.cancel()
        updateState(BargeInState.IDLE)
        Timber.i("BargeInEngine released")
    }

    // ---------------------------
    // AUDIO PIPELINE
    // ---------------------------

    private fun initializeAudioComponents() {
        audioCapture = AudioCapture(
            sampleRate = config.sampleRate,
            onAudioData = { audioData, timestamp ->
                processAudioFrame(audioData, timestamp)
            }
        )

        audioPlayback = AudioPlayback(
            onPlaybackComplete = {
                isPlaying.set(false)
                Timber.d("Playback completed normally")
            },
            onPlaybackStopped = {
                isPlaying.set(false)
                Timber.d("Playback stopped early (barge-in or manual)")
            },
            onPlaybackBuffer = { buffer ->
                (vad as? SpectralVoiceActivityDetector)?.onFarEndPcm(buffer)
            }
        )
    }

    private fun processAudioFrame(audioData: ShortArray, timestamp: Long) {
        if (!isListening.get()) return

        engineScope.launch {
            try {
                aec?.processFrame(audioData, audioData.size)
                val vadResult = vad?.processFrame(audioData, audioData.size) ?: return@launch

                // 🔹 Guardar confianza para la progress bar
                lastConfidence = vadResult.confidence

                if (vadResult.hasVoice && vadResult.confidence >= config.voiceConfidenceThreshold) {
                    handleVoiceDetected(vadResult, timestamp)
                } else {
                    consecutiveVoiceFrames = 0
                    voiceDetectionStartTime = 0
                }

            } catch (e: Exception) {
                Timber.e(e, "Error processing audio frame")
            }
        }
    }

    private fun handleVoiceDetected(vadResult: IVoiceActivityDetector.VadResult, timestamp: Long) {
        if (consecutiveVoiceFrames == 0L) voiceDetectionStartTime = timestamp
        consecutiveVoiceFrames++
        if (consecutiveVoiceFrames >= minVoiceFrames) triggerBargeIn(vadResult, timestamp)
    }

    // ---------------------------
    // AUXILIARES
    // ---------------------------

    fun getMetrics(): BargeInMetrics {
        return BargeInMetrics(
            aecMetrics = aec?.getMetrics(),
            vadMetrics = vad?.getMetrics(),
            state = state.get(),
            isListening = isListening.get(),
            isPlaying = isPlaying.get()
        )
    }

    private fun updateState(newState: BargeInState) {
        val oldState = state.getAndSet(newState)
        if (oldState != newState) listener.onStateChanged(newState)
    }

    private fun notifyError(error: BargeInError) {
        updateState(BargeInState.ERROR)
        listener.onError(error)
    }

    private fun cleanup() {
        try {
            audioCapture.release()
            audioPlayback.release()
            aec?.release()
            vad?.release()
            audioFocusManager.abandonAudioFocus()
        } catch (e: Exception) {
            Timber.e(e, "Error during cleanup")
        }
    }
}

data class BargeInMetrics(
    val aecMetrics: IAcousticEchoCanceler.AecMetrics?,
    val vadMetrics: IVoiceActivityDetector.VadMetrics?,
    val state: BargeInState,
    val isListening: Boolean,
    val isPlaying: Boolean
)
