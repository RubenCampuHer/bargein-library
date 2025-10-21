package com.aima.bargein

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.aima.bargein.aec.AcousticEchoCancelerFactory
import com.aima.bargein.aec.IAcousticEchoCanceler
import com.aima.bargein.audio.AudioCapture
import com.aima.bargein.audio.AudioFocusManager
import com.aima.bargein.audio.AudioPlayback
import com.aima.bargein.audio.AudioThreadConfig
import com.aima.bargein.permissions.PermissionHelper
import com.aima.bargein.vad.EnergyVoiceActivityDetector
import com.aima.bargein.vad.IVoiceActivityDetector
import com.aima.bargein.vad.VoiceActivityDetectorFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    private val bargeInTriggered = AtomicBoolean(false)

    private var voiceDetectionStartTime = 0L
    private var consecutiveVoiceFrames = 0L
    private val minVoiceFrames = (config.minVoiceDurationMs / 11.6).toLong().coerceAtLeast(1)

    private val engineScope = CoroutineScope(AudioThreadConfig.audioDispatcher + SupervisorJob())

    private val handler = Handler(Looper.getMainLooper())

    init {
        Timber.i("🚀 BargeInEngine created with config: $config")
        Timber.i("📊 minVoiceFrames = $minVoiceFrames (${config.minVoiceDurationMs}ms @ 44.1kHz)")
    }

    fun initialize() {
        Timber.d("🔧 Initializing BargeInEngine @ 44.1kHz...")

        if (!PermissionHelper.hasRequiredPermissions(context)) {
            val error = BargeInError(
                ErrorCode.PERMISSION_DENIED,
                "Missing RECORD_AUDIO permission"
            )
            notifyError(error)
            throw SecurityException(error.message)
        }

        try {
            // 1. Inicializar audio @ 44.1kHz
            initializeAudioComponents()
            Timber.d("✅ Audio components initialized @ 44.1kHz")

            // 2. Inicializar AEC (NO CRÍTICO)
            try {
                aec = AcousticEchoCancelerFactory.create(
                    audioSessionId = 0,
                    sampleRate = config.sampleRate,
                    preference = config.aecPreference
                )

                if (aec != null && aec!!.initialize()) {
                    Timber.i("✅ AEC initialized: type=${aec?.getType()}")
                } else {
                    Timber.w("⚠️ AEC not available, using NoOp AEC")
                    aec = AcousticEchoCancelerFactory.create(
                        audioSessionId = 0,
                        sampleRate = config.sampleRate,
                        preference = IAcousticEchoCanceler.Type.NONE
                    )
                    aec?.initialize()
                }
            } catch (e: Exception) {
                Timber.w(e, "⚠️ AEC initialization failed, using NoOp AEC")
                aec = AcousticEchoCancelerFactory.create(
                    audioSessionId = 0,
                    sampleRate = config.sampleRate,
                    preference = IAcousticEchoCanceler.Type.NONE
                )
                aec?.initialize()
            }

            // 3. Inicializar VAD (CRÍTICO) ✅ CAMBIO: Pasar config
            vad = VoiceActivityDetectorFactory.create(
                sampleRate = config.sampleRate,
                mode = config.vadMode,
                preference = config.vadPreference
            )

            // ✅ CAMBIO: Si es EnergyVAD, usar initializeWithConfig
            val initSuccess = if (vad is EnergyVoiceActivityDetector) {
                (vad as EnergyVoiceActivityDetector).initializeWithConfig(
                    config.sampleRate,
                    config.vadMode,
                    config
                )
            } else {
                vad!!.initialize(config.sampleRate, config.vadMode)
            }

            if (!initSuccess) {
                val error = BargeInError(
                    ErrorCode.INITIALIZATION_FAILED,
                    "Failed to initialize VAD - this is critical"
                )
                notifyError(error)
                throw IllegalStateException(error.message)
            }

            Timber.i("✅ VAD initialized: type=${vad?.getType()} @ ${config.sampleRate}Hz")

            updateState(BargeInState.IDLE)
            Timber.i("✅ BargeInEngine initialized successfully @ 44.1kHz")

        } catch (e: Exception) {
            Timber.e(e, "❌ Error initializing BargeInEngine")
            cleanup()
            throw e
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        if (state.get() == BargeInState.LISTENING) {
            Timber.w("⚠️ Already listening")
            return
        }

        try {
            if (!audioFocusManager.requestAudioFocus()) {
                val error = BargeInError(
                    ErrorCode.AUDIO_FOCUS_FAILED,
                    "Failed to obtain audio focus"
                )
                notifyError(error)
                throw IllegalStateException(error.message)
            }

            audioCapture.startCapture()
            isListening.set(true)

            consecutiveVoiceFrames = 0
            voiceDetectionStartTime = 0
            bargeInTriggered.set(false)

            updateState(BargeInState.LISTENING)
            Timber.i("🎤 Listening started @ 44.1kHz")

        } catch (e: Exception) {
            Timber.e(e, "❌ Error starting listening")
            val error = BargeInError(
                ErrorCode.AUDIO_CAPTURE_FAILED,
                "Failed to start audio capture: ${e.message}",
                e
            )
            notifyError(error)
            throw e
        }
    }

    fun stopListening() {
        if (!isListening.get()) {
            Timber.d("ℹ️ Not listening, nothing to stop")
            return
        }

        Timber.i("🛑 Stopping listening...")

        audioCapture.stopCapture()
        audioFocusManager.abandonAudioFocus()
        isListening.set(false)

        (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)

        updateState(BargeInState.STOPPED)
        Timber.i("✅ Listening stopped")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun playAudio(audioStream: InputStream) {
        if (isPlaying.get()) {
            Timber.w("⚠️ Already playing audio, stopping previous")
            stopPlayback()
        }

        try {
            if (!isListening.get()) {
                Timber.i("🎤 Starting listening before playback")
                startListening()
            }

            bargeInTriggered.set(false)
            consecutiveVoiceFrames = 0
            voiceDetectionStartTime = 0

            isPlaying.set(true)

            Timber.i("🎵 Starting audio playback @ 44.1kHz")
            Timber.i("⏳ Waiting 350ms for AudioTrack to start before calibration...")

            audioPlayback.playWav(audioStream)

            handler.postDelayed({
                if (isPlaying.get()) {
                    (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(true)
                    Timber.i("🎯 VAD notified: playback ACTIVE (calibrating NOW with real audio...)")
                }
            }, 350)

        } catch (e: Exception) {
            Timber.e(e, "❌ Error playing audio")
            isPlaying.set(false)

            val error = BargeInError(
                ErrorCode.AUDIO_PLAYBACK_FAILED,
                "Failed to play audio: ${e.message}",
                e
            )
            notifyError(error)
        }
    }

    private fun stopPlayback(): Long {
        if (!isPlaying.get()) {
            Timber.d("ℹ️ No active playback to stop")
            return 0
        }

        Timber.i("⏸️ Stopping playback...")

        handler.removeCallbacksAndMessages(null)

        val stopTimestamp = System.nanoTime()
        audioPlayback.stopImmediately()
        isPlaying.set(false)

        (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)
        Timber.i("🔕 VAD notified: playback INACTIVE")

        Timber.i("✅ Playback stopped")
        return stopTimestamp
    }

    fun stopAudioPlayback() {
        stopPlayback()
    }

    fun release() {
        Timber.d("🔧 Releasing BargeInEngine...")

        handler.removeCallbacksAndMessages(null)
        stopListening()
        cleanup()
        engineScope.cancel()

        updateState(BargeInState.IDLE)
        Timber.i("✅ BargeInEngine released")
    }

    fun getMetrics(): BargeInMetrics {
        return BargeInMetrics(
            aecMetrics = aec?.getMetrics(),
            vadMetrics = vad?.getMetrics(),
            state = state.get(),
            isListening = isListening.get(),
            isPlaying = isPlaying.get()
        )
    }

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
                (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)
                Timber.d("✅ Playback completed normally")
            },
            onPlaybackStopped = {
                isPlaying.set(false)
                (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)
                Timber.d("🛑 Playback stopped by barge-in")
            },
            onPlaybackBuffer = { farEndBuffer ->
                (vad as? EnergyVoiceActivityDetector)?.processFarEndReference(farEndBuffer)
            }
        )
    }

    private fun processAudioFrame(audioData: ShortArray, timestamp: Long) {
        if (!isListening.get()) return

        engineScope.launch {
            try {
                aec?.processFrame(audioData, audioData.size)

                val vadResult = vad?.processFrame(audioData, audioData.size) ?: return@launch

                if (vadResult.hasVoice && vadResult.confidence >= config.voiceConfidenceThreshold) {
                    handleVoiceDetected(vadResult, timestamp)
                } else {
                    consecutiveVoiceFrames = 0
                    voiceDetectionStartTime = 0
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ Error processing audio frame")
            }
        }
    }

    private fun handleVoiceDetected(
        vadResult: IVoiceActivityDetector.VadResult,
        timestamp: Long
    ) {
        if (consecutiveVoiceFrames == 0L) {
            voiceDetectionStartTime = timestamp
            Timber.d("🗣️ First voice frame detected at ${timestamp}ns")
        }

        consecutiveVoiceFrames++

        if (consecutiveVoiceFrames >= minVoiceFrames) {
            triggerBargeIn(vadResult, timestamp)
        }
    }

    private fun triggerBargeIn(
        vadResult: IVoiceActivityDetector.VadResult,
        detectionTimestamp: Long
    ) {
        if (bargeInTriggered.getAndSet(true)) {
            return
        }

        if (!isPlaying.get()) {
            Timber.w("⚠️ Not playing, ignoring barge-in trigger")
            bargeInTriggered.set(false)
            return
        }

        Timber.i("🚨 BARGE-IN TRIGGERED! " +
                "frames=$consecutiveVoiceFrames, " +
                "minRequired=$minVoiceFrames, " +
                "conf=${String.format("%.2f", vadResult.confidence)}, " +
                "energy=${String.format("%.1f", vadResult.energyDb)}dB")

        updateState(BargeInState.INTERRUPTED)

        val stopTimestamp = stopPlayback()

        val latencyNs = stopTimestamp - voiceDetectionStartTime
        val latencyMs = latencyNs / 1_000_000f

        Timber.i("⏱️ Latency: ${String.format("%.1f", latencyMs)}ms " +
                "(from first voice frame to stop)")

        val event = BargeInEvent(
            detectionTimestamp = voiceDetectionStartTime,
            stopTimestamp = stopTimestamp,
            latencyMs = latencyMs,
            confidence = vadResult.confidence,
            energyDb = vadResult.energyDb
        )

        try {
            listener.onUserInterruption(event)
            Timber.i("✅ Listener notified of barge-in")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error notifying listener")
        }

        Timber.i("🎉 Barge-in completed: latency=${String.format("%.1f", latencyMs)}ms, " +
                "confidence=${String.format("%.0f", vadResult.confidence * 100)}%%")

        consecutiveVoiceFrames = 0
        voiceDetectionStartTime = 0
    }

    private fun updateState(newState: BargeInState) {
        val oldState = state.getAndSet(newState)
        if (oldState != newState) {
            Timber.d("📊 State: $oldState → $newState")
            listener.onStateChanged(newState)
        }
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
            Timber.e(e, "❌ Error during cleanup")
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