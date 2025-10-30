package com.aima.bargein.audio

import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayback(
    private val onPlaybackComplete: (() -> Unit)? = null,
    private val onPlaybackStopped: (() -> Unit)? = null,
    private val onPlaybackBuffer: ((ShortArray) -> Unit)? = null // 🧠 Callback far-end
) {
    companion object {
        private const val TAG = "BargeInEngine_AudioPlayback"
    }

    @Volatile
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val stopRequested = AtomicBoolean(false)

    private data class WavHeader(
        val sampleRate: Int,
        val channels: Int
    )

    private fun parseHeader(input: InputStream): WavHeader {
        val header = ByteArray(44)
        val read = input.read(header)
        if (read < 44) throw IllegalArgumentException("Invalid WAV header")

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(22)
        val channels = buffer.short.toInt()
        val sampleRate = buffer.int

        Log.i(TAG, "📄 WAV Header: sampleRate=$sampleRate, channels=$channels")

        return WavHeader(sampleRate, channels)
    }

    fun playWav(stream: InputStream) {
        stopRequested.set(false)

        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val header = parseHeader(stream)

                // ✅ CRÍTICO: Verificar que sea 44.1kHz
                if (header.sampleRate != 44100) {
                    Log.w(TAG, "⚠️ WAV file is ${header.sampleRate}Hz, not 44100Hz!")
                    Log.w(TAG, "   This may cause sync issues with VAD @ 44.1kHz")
                }

                val bufferSize = AudioTrack.getMinBufferSize(
                    header.sampleRate,
                    if (header.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val track = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(header.sampleRate)
                        .setChannelMask(
                            if (header.channels == 1)
                                AudioFormat.CHANNEL_OUT_MONO
                            else
                                AudioFormat.CHANNEL_OUT_STEREO
                        )
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                audioTrack = track
                track.play()
                Log.i(TAG, "▶️ Playback STARTED (rate=${header.sampleRate}, ch=${header.channels})")

                val buffer = ByteArray(bufferSize)
                var bytesRead = stream.read(buffer)
                var totalFramesSent = 0

                while (!stopRequested.get() && bytesRead > 0) {
                    // Convertir a ShortArray para callback VAD
                    val shortBuffer = ShortArray(bytesRead / 2)
                    ByteBuffer.wrap(buffer, 0, bytesRead)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(shortBuffer)

                    // 📊 Enviar al altavoz
                    val written = track.write(shortBuffer, 0, shortBuffer.size)
                    if (written <= 0 || stopRequested.get()) break

                    // 🧠 CRÍTICO: Enviar al VAD la referencia far-end
                    try {
                        onPlaybackBuffer?.invoke(shortBuffer)
                        totalFramesSent++

                        // Log cada 100 frames (~1.16s @ 44.1kHz)
                        if (totalFramesSent % 100 == 0) {
                            Log.d(TAG, "📡 Sent $totalFramesSent far-end buffers to VAD")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error sending far-end buffer to VAD")
                    }

                    bytesRead = stream.read(buffer)
                }

                Log.i(TAG, "📡 Total far-end buffers sent: $totalFramesSent")

                if (stopRequested.get()) {
                    Log.w(TAG, "🛑 Playback interrupted")
                    onPlaybackStopped?.invoke()
                } else {
                    Log.i(TAG, "✅ Playback COMPLETED normally")
                    onPlaybackComplete?.invoke()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Playback error")
            } finally {
                cleanup()
            }
        }
    }

    /**
     * Parada inmediata del audio.
     */
    fun stopImmediately(): Long {
        val timestamp = System.currentTimeMillis()
        stopRequested.set(true)
        Log.i(TAG, "🧨 Forcing AudioTrack stop...")

        GlobalScope.launch(Dispatchers.Default) {
            try {
                audioTrack?.let { track ->
                    try {
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.pause()
                            track.flush()
                            track.stop()
                        } else {

                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioTrack stop exception", e)
                    } finally {
                        try {
                            track.release()
                            Log.i(TAG, "✅ AudioTrack released forcibly")
                        } catch (e: Exception) {
                            Log.w(TAG, "release() failed", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during forced stop")
            }
        }

        playbackJob?.cancel("forced stop")
        playbackJob = null
        audioTrack = null

        Log.i(TAG, "✅ stopImmediately finished at ${timestamp}ms")
        return timestamp
    }

    private fun cleanup() {
        try {
            audioTrack?.apply {
                if (playState != AudioTrack.PLAYSTATE_STOPPED) stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanup() error", e)
        } finally {
            audioTrack = null
        }
    }

    fun release() {
        stopImmediately()
    }
}