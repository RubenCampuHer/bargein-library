package com.aima.bargein.audio

import android.media.*
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayback(
    private val onPlaybackComplete: (() -> Unit)? = null,
    private val onPlaybackStopped: (() -> Unit)? = null,
    private val onPlaybackBuffer: ((ShortArray) -> Unit)? = null // 🧠 NUEVO: callback far-end
) {
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
        return WavHeader(sampleRate, channels)
    }

    fun playWav(stream: InputStream) {
        stopRequested.set(false)

        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val header = parseHeader(stream)
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
                Timber.i("▶️ Playback STARTED (rate=${header.sampleRate}, ch=${header.channels})")

                val buffer = ByteArray(bufferSize)
                var bytesRead = stream.read(buffer)

                while (!stopRequested.get() && bytesRead > 0) {
                    // Convertir a ShortArray para callback VAD
                    val shortBuffer = ShortArray(bytesRead / 2)
                    ByteBuffer.wrap(buffer, 0, bytesRead)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(shortBuffer)

                    // 🔊 Enviar al altavoz
                    val written = track.write(shortBuffer, 0, shortBuffer.size)
                    if (written <= 0 || stopRequested.get()) break

                    // 🧠 Enviar al VAD la referencia far-end
                    onPlaybackBuffer?.invoke(shortBuffer)

                    bytesRead = stream.read(buffer)
                }

                if (stopRequested.get()) {
                    Timber.w("🛑 Playback interrupted")
                    onPlaybackStopped?.invoke()
                } else {
                    Timber.i("✅ Playback COMPLETED normally")
                    onPlaybackComplete?.invoke()
                }

            } catch (e: Exception) {
                Timber.e(e, "Playback error")
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
        Timber.i("🧨 Forcing AudioTrack stop...")

        GlobalScope.launch(Dispatchers.Default) {
            try {
                audioTrack?.let { track ->
                    try {
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.pause()
                            track.flush()
                            track.stop()
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "AudioTrack stop exception")
                    } finally {
                        try {
                            track.release()
                            Timber.i("✅ AudioTrack released forcibly")
                        } catch (e: Exception) {
                            Timber.w(e, "release() failed")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during forced stop")
            }
        }

        playbackJob?.cancel("forced stop")
        playbackJob = null
        audioTrack = null

        Timber.i("✅ stopImmediately finished at ${timestamp}ms")
        return timestamp
    }

    private fun cleanup() {
        try {
            audioTrack?.apply {
                if (playState != AudioTrack.PLAYSTATE_STOPPED) stop()
                release()
            }
        } catch (e: Exception) {
            Timber.w(e, "cleanup() error")
        } finally {
            audioTrack = null
        }
    }

    fun release() {
        stopImmediately()
    }
}
