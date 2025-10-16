package com.aima.bargein.audio

import android.media.*
import kotlinx.coroutines.*
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.pow

/**
 * AudioPlayback - reproducir audio PCM16 a 44.1 kHz (mono)
 * Añade soporte para reproducir tonos sintéticos y sweep (20Hz–20kHz)
 * ✅ Expone AudioSessionId para sincronización con AudioCapture
 */
class AudioPlayback(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val bufferSize = (minBufferSize * 2).coerceAtLeast(sampleRate / 10)

    @Volatile
    private var isPlaying = false

    fun isPlaying(): Boolean = isPlaying

    /**
     * ✅ NUEVO: Exponer el AudioSessionId para sincronización con AudioCapture
     */
    fun getAudioSessionId(): Int = audioTrack?.audioSessionId ?: 0

    fun stop() {
        scope.launch {
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        try {
            Timber.i("⏸️ Stopping playback...")
            isPlaying = false
            playbackJob?.cancel()

            audioTrack?.apply {
                flush()
                stop()
                release()
            }
            audioTrack = null
            Timber.i("✅ Playback stopped")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error stopping playback")
        }
    }

    // =====================================================
    // ==  WAV / PCM playback (para WAVs existentes)
    // =====================================================
    fun playPcmData(pcmData: ShortArray) {
        stopPlayback()
        playbackJob = scope.launch {
            try {
                Timber.i("▶️ Playback STARTED (PCM data, ${pcmData.size} samples)")
                audioTrack = AudioTrack(
                    AudioAttributes.Builder()
                        // ⚙️ USAGE_VOICE_COMMUNICATION activa mejor AEC
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build(),
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                // ✅ Guarda sessionId para el AEC
                val sessionId = audioTrack?.audioSessionId ?: 0
                Timber.i("🎛️ AudioTrack creado con sessionId=$sessionId")

                audioTrack?.play()
                isPlaying = true

                val buffer = ShortArray(bufferSize / 2)
                var offset = 0
                while (isPlaying && offset < pcmData.size) {
                    val length = minOf(buffer.size, pcmData.size - offset)
                    System.arraycopy(pcmData, offset, buffer, 0, length)
                    audioTrack?.write(buffer, 0, length)
                    offset += length
                }
                stopPlayback()
            } catch (e: Exception) {
                Timber.e(e, "❌ Error playing PCM data")
                stopPlayback()
            }
        }
    }


    // =====================================================
    // ==  Tone generator (single tone)
    // =====================================================
    fun playTone(frequencyHz: Float, durationMs: Int, amplitudeDb: Float = -20f) {
        stopPlayback()
        playbackJob = scope.launch {
            try {
                Timber.i("🎵 Playing tone: ${frequencyHz}Hz, ${durationMs}ms, amp=${amplitudeDb}dB")
                val samplesCount = (sampleRate * (durationMs / 1000.0)).toInt()
                val amplitude = 10.0.pow(amplitudeDb / 20.0)
                val pcm = ShortArray(samplesCount) { i ->
                    (sin(2.0 * PI * frequencyHz * i / sampleRate) * amplitude * Short.MAX_VALUE).toInt().toShort()
                }
                playPcmData(pcm)
            } catch (e: Exception) {
                Timber.e(e, "❌ Error generating tone")
            }
        }
    }

    // =====================================================
    // ==  Frequency sweep (20Hz–20kHz)
    // =====================================================
    fun playSweep(startHz: Float = 20f, endHz: Float = 20000f, durationMs: Int = 8000) {
        stopPlayback()
        playbackJob = scope.launch {
            try {
                Timber.i("🎧 Playing sweep: ${startHz}Hz → ${endHz}Hz, duration=${durationMs}ms")
                val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val pcm = ShortArray(totalSamples)

                // 🔧 Calcular sweep exponencial en Double
                val start = startHz.toDouble()
                val end = endHz.toDouble()
                val k = (end / start).pow(1.0 / totalSamples)
                var f = start

                for (i in 0 until totalSamples) {
                    val s = sin(2.0 * Math.PI * f * i / sampleRate)
                    pcm[i] = (s * Short.MAX_VALUE).toInt().toShort()
                    f *= k
                }

                playPcmData(pcm)
            } catch (e: Exception) {
                Timber.e(e, "❌ Error generating sweep")
            }
        }
    }


    // =====================================================
    // ==  Quick test: 15kHz pilot tone
    // =====================================================
    fun playPilotTone() {
        playTone(frequencyHz = 15000f, durationMs = 5000, amplitudeDb = -20f)
    }

    // =====================================================
    // ==  Cleanup
    // =====================================================
    fun release() {
        stopPlayback()
        scope.cancel()
    }
}