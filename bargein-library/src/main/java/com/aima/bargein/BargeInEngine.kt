package com.aima.bargein

import android.Manifest
import android.content.Context
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
    private val minVoiceFrames = (config.minVoiceDurationMs / 10).coerceAtLeast(1)

    private val engineScope = CoroutineScope(AudioThreadConfig.audioDispatcher + SupervisorJob())

    init {
        Timber.i("🚀 BargeInEngine created with config: $config")
        Timber.i("📊 minVoiceFrames = $minVoiceFrames (${config.minVoiceDurationMs}ms)")
    }

    fun initialize() {
        Timber.d("🔧 Initializing BargeInEngine...")

        if (!PermissionHelper.hasRequiredPermissions(context)) {
            val error = BargeInError(
                ErrorCode.PERMISSION_DENIED,
                "Missing RECORD_AUDIO permission"
            )
            notifyError(error)
            throw SecurityException(error.message)
        }

        try {
            // 1. Inicializar componentes de audio PRIMERO
            initializeAudioComponents()
            Timber.d("✅ Audio components initialized")

            // 2. Inicializar AEC (NO CRÍTICO - puede fallar)
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

            // 3. Inicializar VAD (CRÍTICO)
            vad = VoiceActivityDetectorFactory.create(
                sampleRate = config.sampleRate,
                mode = config.vadMode,
                preference = config.vadPreference
            )

            if (!vad!!.initialize(config.sampleRate, config.vadMode)) {
                val error = BargeInError(
                    ErrorCode.INITIALIZATION_FAILED,
                    "Failed to initialize VAD - this is critical"
                )
                notifyError(error)
                throw IllegalStateException(error.message)
            }

            Timber.i("✅ VAD initialized: type=${vad?.getType()}")

            updateState(BargeInState.IDLE)
            Timber.i("✅ BargeInEngine initialized successfully")

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
            Timber.i("🎤 Listening started")

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

        // Notificar al VAD que NO hay reproducción
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
            // Asegurar que está escuchando
            if (!isListening.get()) {
                Timber.i("🎤 Starting listening before playback")
                startListening()
            }

            // Reset estado
            bargeInTriggered.set(false)
            consecutiveVoiceFrames = 0
            voiceDetectionStartTime = 0

            isPlaying.set(true)

            // Notificar al VAD que HAY reproducción
            (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(true)
            Timber.i("🔊 VAD notified: playback ACTIVE")

            // Iniciar reproducción
            audioPlayback.playWav(audioStream)

            Timber.i("🎵 Audio playback started")

        } catch (e: Exception) {
            Timber.e(e, "❌ Error playing audio")
            isPlaying.set(false)

            // Revertir estado del VAD
            (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)

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

        val stopTimestamp = System.nanoTime()
        audioPlayback.stopImmediately()
        isPlaying.set(false)

        // Notificar al VAD que NO hay reproducción
        (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)
        Timber.i("🔕 VAD notified: playback INACTIVE")

        Timber.i("✅ Playback stopped")
        return stopTimestamp
    }

    /**
     * Detiene solo la reproducción de audio, mantiene el micrófono activo.
     */
    fun stopAudioPlayback() {
        stopPlayback()
    }

    fun release() {
        Timber.d("🔧 Releasing BargeInEngine...")

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
            }
        )
    }

    private fun processAudioFrame(audioData: ShortArray, timestamp: Long) {
        if (!isListening.get()) return

        engineScope.launch {
            try {
                // 1. Aplicar AEC
                aec?.processFrame(audioData, audioData.size)

                // 2. Aplicar VAD
                val vadResult = vad?.processFrame(audioData, audioData.size) ?: return@launch

                // 3. Verificar si hay voz con suficiente confianza
                if (vadResult.hasVoice && vadResult.confidence >= config.voiceConfidenceThreshold) {
                    handleVoiceDetected(vadResult, timestamp)
                } else {
                    // Reset contador si no hay voz
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
        // Registrar primer frame de voz
        if (consecutiveVoiceFrames == 0L) {
            voiceDetectionStartTime = timestamp
            Timber.d("🗣️ First voice frame detected at ${timestamp}ns")
        }

        consecutiveVoiceFrames++

        Timber.v("🔊 Voice frame #${consecutiveVoiceFrames} (need $minVoiceFrames), " +
                "conf=${String.format("%.2f", vadResult.confidence)}, " +
                "energy=${String.format("%.1f", vadResult.energyDb)}dB")

        // Verificar si se alcanzó el mínimo de frames
        if (consecutiveVoiceFrames >= minVoiceFrames) {
            triggerBargeIn(vadResult, timestamp)
        }
    }

    private fun triggerBargeIn(
        vadResult: IVoiceActivityDetector.VadResult,
        detectionTimestamp: Long
    ) {
        // Evitar múltiples triggers
        if (bargeInTriggered.getAndSet(true)) {
            Timber.v("⚠️ Barge-in already triggered, ignoring")
            return
        }

        // Solo si está reproduciendo
        if (!isPlaying.get()) {
            Timber.w("⚠️ Not playing, ignoring barge-in trigger")
            bargeInTriggered.set(false)
            return
        }

        Timber.i("🚨 BARGE-IN TRIGGERED! " +
                "frames=$consecutiveVoiceFrames, " +
                "conf=${String.format("%.2f", vadResult.confidence)}, " +
                "energy=${String.format("%.1f", vadResult.energyDb)}dB")

        // Cambiar estado
        updateState(BargeInState.INTERRUPTED)

        // Detener reproducción INMEDIATAMENTE
        val stopTimestamp = stopPlayback()

        // Calcular latencia
        val latencyNs = stopTimestamp - voiceDetectionStartTime
        val latencyMs = latencyNs / 1_000_000f

        Timber.i("⏱️ Latency: ${String.format("%.1f", latencyMs)}ms " +
                "(from first voice frame to stop)")

        // Crear evento
        val event = BargeInEvent(
            detectionTimestamp = voiceDetectionStartTime,
            stopTimestamp = stopTimestamp,
            latencyMs = latencyMs,
            confidence = vadResult.confidence,
            energyDb = vadResult.energyDb
        )

        // Notificar listener
        try {
            listener.onUserInterruption(event)
            Timber.i("✅ Listener notified of barge-in")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error notifying listener")
        }

        Timber.i("🎉 Barge-in completed: latency=${String.format("%.1f", latencyMs)}ms, " +
                "confidence=${String.format("%.0f", vadResult.confidence * 100)}%%")

        // Reset contador
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