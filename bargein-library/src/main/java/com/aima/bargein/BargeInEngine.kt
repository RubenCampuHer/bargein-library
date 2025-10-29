package com.aima.bargein

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class BargeInEngine(
    private val config: BargeInConfig = BargeInConfig.DEFAULT
) {
    companion object {
        private const val TAG = "BargeInEngine"
    }
   val HARD_BLOCK_VOLUME = 0.65f
    private lateinit var audioCapture: AudioCapture
    private lateinit var audioPlayback: AudioPlayback
    private lateinit var audioFocusManager: AudioFocusManager

    private var aec: IAcousticEchoCanceler? = null
    private var vad: IVoiceActivityDetector? = null

    // ✅ NUEVO: Modo Anti-Autointerferencia Manual
    private var audioManager: AudioManager? = null
    @Volatile
    private var currentVolume: Float = 0.5f
    @Volatile
    private var antiAutoInterferenceEnabled: Boolean = false  // ✅ OFF por defecto
    private var volumeMonitorRunnable: Runnable? = null
    private val VOLUME_CHECK_INTERVAL_MS = 100L

    private val state = AtomicReference(BargeInState.IDLE)
    private val isListening = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val bargeInTriggered = AtomicBoolean(false)

    private var voiceDetectionStartTime = 0L
    private var consecutiveVoiceFrames = 0L
    private val minVoiceFrames = (config.minVoiceDurationMs / 11.6).toLong().coerceAtLeast(1)

    private val engineScope = CoroutineScope(AudioThreadConfig.audioDispatcher + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var frameCounter = 0L
    private val _userInterruption = MutableSharedFlow<BargeInEvent>()
    val userInterruption = _userInterruption.asSharedFlow()

    private val _onStateChanged = MutableSharedFlow<BargeInState>()
    val onStateChanged = _onStateChanged.asSharedFlow()

    private val _bargeInError = MutableSharedFlow<BargeInError>()
    val bargeInError = _bargeInError.asSharedFlow()

    init {
        Log.i(TAG, "🚀 BargeInEngine created with config: $config")
        Log.i(TAG, "📊 minVoiceFrames = $minVoiceFrames (${config.minVoiceDurationMs}ms @ 44.1kHz)")
    }

    /**
     * ✅ NUEVO: Activar/Desactivar modo Anti-Autointerferencia
     */
    fun setAntiAutoInterferenceMode(enabled: Boolean) {
        antiAutoInterferenceEnabled = enabled

        if (enabled) {
            Log.i(TAG, "🛡️ ANTI-AUTOINTERFERENCIA ACTIVADO")
            Log.i(TAG, "   → A partir de 50% de volumen: IMPOSIBLE autointerferirse")
        } else {
            Log.i(TAG, "🔓 Anti-autointerferencia DESACTIVADO")
            Log.i(TAG, "   → Comportamiento normal del VAD")
        }

        // Aplicar cambio inmediatamente si estamos escuchando
        if (isListening.get()) {
            updateVolumeLevel()
        }
    }

    /**
     * ✅ NUEVO: Obtener estado actual del modo
     */
    fun isAntiAutoInterferenceModeEnabled(): Boolean = antiAutoInterferenceEnabled

    fun initialize(context: Context) {
        Log.d(TAG, "🔧 Initializing BargeInEngine @ 44.1kHz...")

        if (!PermissionHelper.hasRequiredPermissions(context)) {
            val error = BargeInError(
                ErrorCode.PERMISSION_DENIED,
                "Missing RECORD_AUDIO permission"
            )
            notifyError(error)
            throw SecurityException(error.message)
        }

        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                Log.i(TAG, "✅ AudioManager initialized for volume monitoring")
            } else {
                Log.w(TAG, "⚠️ AudioManager not available - anti-interference disabled")
            }

            initializeAudioComponents(context)
            Log.d(TAG, "✅ Audio components initialized @ 44.1kHz")

            try {
                aec = AcousticEchoCancelerFactory.create(
                    audioSessionId = 0,
                    sampleRate = config.sampleRate,
                    preference = config.aecPreference
                )

                if (aec != null && aec!!.initialize()) {
                    Log.i(TAG, "✅ AEC initialized: type=${aec?.getType()}")
                } else {
                    Log.w(TAG, "⚠️ AEC not available, using NoOp AEC")
                    aec = AcousticEchoCancelerFactory.create(
                        audioSessionId = 0,
                        sampleRate = config.sampleRate,
                        preference = IAcousticEchoCanceler.Type.NONE
                    )
                    aec?.initialize()
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ AEC initialization failed, using NoOp AEC", e)
                aec = AcousticEchoCancelerFactory.create(
                    audioSessionId = 0,
                    sampleRate = config.sampleRate,
                    preference = IAcousticEchoCanceler.Type.NONE
                )
                aec?.initialize()
            }

            vad = VoiceActivityDetectorFactory.create(
                sampleRate = config.sampleRate,
                mode = config.vadMode,
                preference = config.vadPreference
            )

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

            Log.i(TAG, "✅ VAD initialized: type=${vad?.getType()} @ ${config.sampleRate}Hz")

            updateState(BargeInState.IDLE)
            Log.i(TAG, "✅ BargeInEngine initialized successfully @ 44.1kHz")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing BargeInEngine", e)
            cleanup()
            throw e
        }
    }

    private fun startVolumeMonitoring() {
        stopVolumeMonitoring()

        volumeMonitorRunnable = object : Runnable {
            override fun run() {
                updateVolumeLevel()
                handler.postDelayed(this, VOLUME_CHECK_INTERVAL_MS)
            }
        }

        handler.post(volumeMonitorRunnable!!)
        Log.d(TAG, "🎚️ Volume monitoring started")
    }

    private fun stopVolumeMonitoring() {
        volumeMonitorRunnable?.let {
            handler.removeCallbacks(it)
            volumeMonitorRunnable = null
        }
        Log.d(TAG, "🎚️ Volume monitoring stopped")
    }

    /**
     * ✅ MODIFICADO: Aplica ajuste EXTREMO si modo anti-autointerferencia activo
     */
    private fun updateVolumeLevel() {
        audioManager?.let { am ->
            val streamType = AudioManager.STREAM_MUSIC
            val currentVol = am.getStreamVolume(streamType)
            val maxVol = am.getStreamMaxVolume(streamType)

            if (maxVol > 0) {
                val newVolume = currentVol.toFloat() / maxVol.toFloat()

                if (kotlin.math.abs(newVolume - currentVolume) > 0.01f) {
                    currentVolume = newVolume

                    // ✅ NUEVO: Lógica diferente según modo
                    if (antiAutoInterferenceEnabled) {
                        applyAntiAutoInterferenceAdjustment()
                    } else {
                        applyNormalAdjustment()
                    }
                }
            }
        }
    }

    /**
     * ✅ NUEVO: Ajuste EXTREMO para evitar autointerferencia
     * A partir de HARD_BLOCK_VOLUME% → IMPOSIBLE detectar
     * SOLO APLICA CUANDO HAY PLAYBACK ACTIVO
     */
    private fun applyAntiAutoInterferenceAdjustment() {
        val vadEnergy = vad as? EnergyVoiceActivityDetector ?: return

        // ✅ CRÍTICO: Solo aplicar ajuste extremo si hay playback activo
        if (isPlaying.get() && currentVolume >= HARD_BLOCK_VOLUME) {
            // Umbrales imposibles mientras el altavoz esté a partir del 50%
            val extremeScale = 9.99f  // <<— mucho más alto que 3.0
            vadEnergy.setVolumeAdjustmentParameters(
                enabled = true,
                scaleMultiplier = extremeScale
            )

            if (frameCounter % 100 == 0L) {
                Log.i(TAG, "🛡️ ANTI-AUTO (duro) @ ${(currentVolume * 100).toInt()}% | Scale=${String.format("%.2f", extremeScale)}x")
            }
        } else {
            // Sin playback o volumen bajo: sin ajuste especial
            vadEnergy.setVolumeAdjustmentParameters(
                enabled = false,
                scaleMultiplier = 1.0f
            )
        }
    }


    /**
     * ✅ NUEVO: Ajuste normal (más suave, solo si volumen muy alto)
     */
    private fun applyNormalAdjustment() {
        val vadEnergy = vad as? EnergyVoiceActivityDetector ?: return

        if (currentVolume > 0.65f) {
            val volumeExcess = (currentVolume - 0.65f) / 0.35f
            val normalScale = 1.0f + (volumeExcess * 0.64f)  // 1.0→1.64

            vadEnergy.setVolumeAdjustmentParameters(
                enabled = true,
                scaleMultiplier = normalScale
            )
        } else {
            vadEnergy.setVolumeAdjustmentParameters(
                enabled = false,
                scaleMultiplier = 1.0f
            )
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        if (state.get() == BargeInState.LISTENING) {
            Log.w(TAG, "⚠️ Already listening")
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
            frameCounter = 0

            startVolumeMonitoring()

            updateState(BargeInState.LISTENING)
            Log.i(TAG, "🎤 Listening started @ 44.1kHz")

            if (antiAutoInterferenceEnabled) {
                Log.i(TAG, "🛡️ Anti-Autointerferencia: ACTIVO")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting listening", e)
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
            Log.d(TAG, "ℹ️ Not listening, nothing to stop")
            return
        }

        Log.i(TAG, "🛑 Stopping listening...")

        stopVolumeMonitoring()

        audioCapture.stopCapture()
        audioFocusManager.abandonAudioFocus()
        isListening.set(false)

        (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)

        updateState(BargeInState.STOPPED)
        Log.i(TAG, "✅ Listening stopped")
    }

    fun playAudio(stream: InputStream) {
        if (isPlaying.get()) {
            Log.w(TAG, "⚠️ Already playing")
            return
        }

        try {
            isPlaying.set(true)
            (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(true)

            updateVolumeLevel()

            audioPlayback.playWav(stream)
            Log.i(TAG, "▶️ Audio playback started")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting playback", e)
            isPlaying.set(false)
            (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)
            val error = BargeInError(
                ErrorCode.AUDIO_PLAYBACK_FAILED,
                "Failed to start audio playback: ${e.message}",
                e
            )
            notifyError(error)
            throw e
        }
    }

    fun stopAudioPlayback(): Long {
        if (!isPlaying.get()) {
            Log.d(TAG, "ℹ️ Not playing, nothing to stop")
            return System.nanoTime()
        }

        val stopTimestamp = audioPlayback.stopImmediately()
        isPlaying.set(false)
        (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)
        updateVolumeLevel() // ✅ Desactivar ajuste extremo

        Log.i(TAG, "⏸️ Audio playback stopped at ${stopTimestamp}ms")
        return stopTimestamp * 1_000_000
    }

    private fun stopPlayback(): Long {
        return stopAudioPlayback()
    }

    fun release() {
        try {
            Log.i(TAG, "🔧 Releasing BargeInEngine...")

            stopListening()
            stopAudioPlayback()
            stopVolumeMonitoring()

            cleanup()
            engineScope.cancel()

            Log.i(TAG, "✅ BargeInEngine released successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error releasing BargeInEngine", e)
        }
    }

    fun getMetrics(): BargeInMetrics {
        return BargeInMetrics(
            aecMetrics = aec?.getMetrics(),
            vadMetrics = vad?.getMetrics(),
            state = state.get(),
            isListening = isListening.get(),
            isPlaying = isPlaying.get(),
            currentVolume = currentVolume,
            antiAutoInterferenceEnabled = antiAutoInterferenceEnabled
        )
    }

    private fun initializeAudioComponents(context: Context) {
        audioFocusManager = AudioFocusManager(context)

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
                updateVolumeLevel() // ✅ Desactivar ajuste extremo
                Log.d(TAG, "✅ Playback completed normally")
            },
            onPlaybackStopped = {
                isPlaying.set(false)
                (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)
                updateVolumeLevel() // ✅ Desactivar ajuste extremo
                Log.d(TAG, "🛑 Playback stopped by barge-in")
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

                // ✅ Incrementar frameCounter SIEMPRE, antes de cualquier lógica
                frameCounter++

                // ✅ Hard-block: bloquear detección cuando volumen alto
                if (antiAutoInterferenceEnabled && isPlaying.get() && currentVolume >= HARD_BLOCK_VOLUME) {
                    if (frameCounter % 50L == 0L) {
                        Log.i(TAG, "🛡️ Hard-block activo (@${(currentVolume * 100).toInt()}% volumen) → NO barge-in")
                    }
                    // Resetear contadores solo si hay acumulación
                    if (consecutiveVoiceFrames > 0) {
                        consecutiveVoiceFrames = 0
                        voiceDetectionStartTime = 0
                    }
                    return@launch
                }
                if (frameCounter % 20 == 0L) {
                    Log.d("", "═══════════════════════════════════════")
                    Log.d(TAG, "🎙️ Frame #$frameCounter @ ${timestamp}ms")
                    Log.d(TAG, "   hasVoice: ${vadResult.hasVoice}")
                    Log.d(TAG, "   confidence: %.3f (need: %.3f)".format(vadResult.confidence, config.voiceConfidenceThreshold))
                    Log.d(TAG, "   energyDb: %.1f (need: >= %.1f)".format(vadResult.energyDb, config.minAbsoluteVoiceEnergyDb))
                    Log.d(TAG, "   deltaDb: %.1f (need: >= %.1f)".format(vadResult.deltaDb, config.deltaVoiceThresholdDb))
                    Log.d(TAG, "   zcr: %.3f (max: %.3f)".format(vadResult.zcr, config.maxZcrForVoice))
                    Log.d(TAG, "   baselineDb: %.1f".format(vadResult.baselineDb))
                    Log.d(TAG, "   consecutiveVoiceFrames: $consecutiveVoiceFrames / $minVoiceFrames")
                    Log.d(TAG, "   isPlaying: ${isPlaying.get()}")
                    Log.d(TAG, "   currentVolume: ${(currentVolume * 100).toInt()}%")
                    if (antiAutoInterferenceEnabled) {
                        Log.d(TAG, "   🛡️ Anti-Autointerferencia: ON")
                    }

                    if (!vadResult.hasVoice) {
                        Log.w(TAG, "   ❌ NO VOICE - Failing criteria:")
                        val failures = mutableListOf<String>()

                        if (vadResult.confidence < config.voiceConfidenceThreshold) {
                            failures.add("Confidence: %.3f < %.3f".format(vadResult.confidence, config.voiceConfidenceThreshold))
                        }
                        if (vadResult.energyDb < config.minAbsoluteVoiceEnergyDb) {
                            failures.add("Energy: %.1fdB < %.1fdB".format(vadResult.energyDb, config.minAbsoluteVoiceEnergyDb))
                        }
                        if (vadResult.deltaDb < config.deltaVoiceThresholdDb) {
                            failures.add("Delta: %.1fdB < %.1fdB".format(vadResult.deltaDb, config.deltaVoiceThresholdDb))
                        }
                        if (vadResult.zcr > config.maxZcrForVoice) {
                            failures.add("ZCR: %.3f > %.3f".format(vadResult.zcr, config.maxZcrForVoice))
                        }

                        failures.forEach { Log.w(TAG, "      - $it") }
                    } else {
                        Log.i(TAG, "   ✅ VOICE DETECTED! All criteria passed")
                    }
                    Log.d(TAG, "═══════════════════════════════════════")
                }

                if (vadResult.hasVoice && vadResult.confidence >= config.voiceConfidenceThreshold) {
                    Log.i(TAG, "🎉 VOICE FRAME DETECTED!")
                    Log.i(TAG, "   Confidence: %.3f".format(vadResult.confidence))
                    Log.i(TAG, "   Energy: %.1fdB".format(vadResult.energyDb))
                    Log.i(TAG, "   Delta: %.1fdB".format(vadResult.deltaDb))
                    Log.i(TAG, "   Consecutive: $consecutiveVoiceFrames / $minVoiceFrames needed")

                    handleVoiceDetected(vadResult, timestamp)
                } else {
                    consecutiveVoiceFrames = 0
                    voiceDetectionStartTime = 0
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing audio frame", e)
            }
        }
    }

    private fun handleVoiceDetected(
        vadResult: IVoiceActivityDetector.VadResult,
        timestamp: Long
    ) {
        if (consecutiveVoiceFrames == 0L) {
            voiceDetectionStartTime = timestamp
            Log.d(TAG, "🗣️ First voice frame detected at ${timestamp}ns")
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

        Log.i(TAG, "🚨 BARGE-IN TRIGGERED! " +
                "frames=$consecutiveVoiceFrames, " +
                "minRequired=$minVoiceFrames, " +
                "conf=${String.format("%.2f", vadResult.confidence)}, " +
                "energy=${String.format("%.1f", vadResult.energyDb)}dB")

        updateState(BargeInState.INTERRUPTED)

        val stopTimestamp = stopPlayback()

        val latencyNs = stopTimestamp - voiceDetectionStartTime
        val latencyMs = latencyNs / 1_000_000f

        Log.i(TAG, "⏱️ Latency: ${String.format("%.1f", latencyMs)}ms " +
                "(from first voice frame to stop)")

        val event = BargeInEvent(
            detectionTimestamp = voiceDetectionStartTime,
            stopTimestamp = stopTimestamp,
            latencyMs = latencyMs,
            confidence = vadResult.confidence,
            energyDb = vadResult.energyDb
        )

        try {
            engineScope.launch {
                _userInterruption.emit(event)
            }
            Log.i(TAG, "✅ Listener notified of barge-in")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error notifying listener", e)
        }

        Log.i(TAG, "🎉 Barge-in completed: latency=${String.format("%.1f", latencyMs)}ms, " +
                "confidence=${String.format("%.0f", vadResult.confidence * 100)}%%")

        consecutiveVoiceFrames = 0
        voiceDetectionStartTime = 0
    }

    private fun updateState(newState: BargeInState) {
        val oldState = state.getAndSet(newState)
        if (oldState != newState) {
            Log.d(TAG, "📊 State: $oldState → $newState")
            engineScope.launch {
                _onStateChanged.emit(newState)
            }
        }
    }

    private fun notifyError(error: BargeInError) {
        updateState(BargeInState.ERROR)
        engineScope.launch {
            _bargeInError.emit(error)
        }
    }

    private fun cleanup() {
        try {
            audioCapture.release()
            audioPlayback.release()
            aec?.release()
            vad?.release()
            audioFocusManager.abandonAudioFocus()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup", e)
        }
    }
}

data class BargeInMetrics(
    val aecMetrics: IAcousticEchoCanceler.AecMetrics?,
    val vadMetrics: IVoiceActivityDetector.VadMetrics?,
    val state: BargeInState,
    val isListening: Boolean,
    val isPlaying: Boolean,
    val currentVolume: Float = 0.5f,
    val antiAutoInterferenceEnabled: Boolean = false
)