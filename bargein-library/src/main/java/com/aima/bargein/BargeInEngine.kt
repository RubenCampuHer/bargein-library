package com.aima.bargein

import android.Manifest
import android.content.Context
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
    private val context: Context,
    private val config: BargeInConfig = BargeInConfig.DEFAULT
) {
    companion object {
        private const val TAG = "BargeInEngine"
    }

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

    // ✅ Contador de frames
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

    fun initialize() {
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
            initializeAudioComponents()
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
            frameCounter = 0 // ✅ Reset contador

            updateState(BargeInState.LISTENING)
            Log.i(TAG, "🎤 Listening started @ 44.1kHz")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting listening")
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

        audioCapture.stopCapture()
        audioFocusManager.abandonAudioFocus()
        isListening.set(false)

        (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)

        updateState(BargeInState.STOPPED)
        Log.i(TAG, "✅ Listening stopped")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun playAudio(audioStream: InputStream) {
        if (isPlaying.get()) {
            Log.w(TAG, "⚠️ Already playing audio, stopping previous")
            stopPlayback()
        }

        try {
            if (!isListening.get()) {
                Log.i(TAG, "🎤 Starting listening before playback")
                startListening()
            }

            bargeInTriggered.set(false)
            consecutiveVoiceFrames = 0
            voiceDetectionStartTime = 0

            isPlaying.set(true)

            Log.i(TAG, "🎵 Starting audio playback @ 44.1kHz")
            Log.i(TAG, "⏳ Waiting 350ms for AudioTrack to start before calibration...")

            audioPlayback.playWav(audioStream)

            handler.postDelayed({
                if (isPlaying.get()) {
                    (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(true)
                    Log.i(TAG, "🎯 VAD notified: playback ACTIVE (calibrating NOW with real audio...)")
                }
            }, 350)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error playing audio")
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
            Log.d(TAG, "ℹ️ No active playback to stop")
            return 0
        }

        Log.i(TAG, "⏸️ Stopping playback...")

        handler.removeCallbacksAndMessages(null)

        val stopTimestamp = System.nanoTime()
        audioPlayback.stopImmediately()
        isPlaying.set(false)

        (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)
        Log.i(TAG, "🔕 VAD notified: playback INACTIVE")

        Log.i(TAG, "✅ Playback stopped")
        return stopTimestamp
    }

    fun stopAudioPlayback() {
        stopPlayback()
    }

    fun release() {
        Log.d(TAG, "🔧 Releasing BargeInEngine...")
        handler.removeCallbacksAndMessages(null)
        stopListening()
        cleanup()
        engineScope.cancel()

        updateState(BargeInState.IDLE)
        Log.i(TAG, "✅ BargeInEngine released")
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
                Log.d(TAG, "✅ Playback completed normally")
            },
            onPlaybackStopped = {
                isPlaying.set(false)
                (vad as? EnergyVoiceActivityDetector)?.setPlaybackActive(false)
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

                // ✅ LOGGING DETALLADO - Solo cada 20 frames para no saturar
                frameCounter++
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

                    // Diagnóstico de por qué no se detecta
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

                // Log SIEMPRE cuando detecta voz (sin importar el contador)
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
                Log.e(TAG, "❌ Error processing audio frame")
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

        if (!isPlaying.get()) {
            Timber.w("⚠️ Not playing, ignoring barge-in trigger")
            bargeInTriggered.set(false)
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
            Log.e(TAG, "❌ Error notifying listener")
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
            Log.e(TAG, "❌ Error during cleanup")
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