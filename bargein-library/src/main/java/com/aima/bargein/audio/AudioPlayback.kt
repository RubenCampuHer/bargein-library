package com.aima.bargein.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPlayback(
    private val onPlaybackComplete: () -> Unit = {},
    private val onPlaybackStopped: () -> Unit = {}
) {
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val playbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isPlaying = false

    @Volatile
    private var stopRequested = false

    companion object {
        private const val SAMPLE_RATE = 16000
    }

    fun playWav(inputStream: InputStream) {
        if (isPlaying) {
            Timber.w("⚠️ Playback already active, stopping previous")
            stopImmediately()
        }

        stopRequested = false
        isPlaying = true

        playbackJob = playbackScope.launch {
            try {
                Timber.i("🎵 Starting playback...")

                val header = ByteArray(44)
                inputStream.read(header)

                val wavHeader = parseWavHeader(header)
                Timber.d("WAV: rate=${wavHeader.sampleRate}, channels=${wavHeader.numChannels}")

                val bufferSize = AudioTrack.getMinBufferSize(
                    wavHeader.sampleRate,
                    if (wavHeader.numChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setSampleRate(wavHeader.sampleRate)
                    .setChannelMask(if (wavHeader.numChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()
                Timber.i("▶️ Playback STARTED")

                val buffer = ByteArray(4096)
                var bytesRead: Int
                var wasStopped = false

                while (isActive && !stopRequested) {
                    bytesRead = inputStream.read(buffer)

                    if (bytesRead <= 0) {
                        Timber.d("📭 End of stream reached")
                        break
                    }

                    if (stopRequested) {
                        Timber.i("⏸️ Stop requested during playback")
                        wasStopped = true
                        break
                    }

                    audioTrack?.write(buffer, 0, bytesRead)
                }

                // Cleanup
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                    audioTrack = null
                } catch (e: Exception) {
                    Timber.e(e, "Error stopping AudioTrack")
                }

                isPlaying = false
                inputStream.close()

                withContext(Dispatchers.Main) {
                    if (wasStopped) {
                        Timber.i("🛑 Playback STOPPED by barge-in")
                        onPlaybackStopped()
                    } else {
                        Timber.i("✅ Playback COMPLETED normally")
                        onPlaybackComplete()
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "❌ Error during playback")
                cleanup()
            }
        }
    }

    fun stopImmediately(): Long {
        val stopTime = System.currentTimeMillis()

        if (!isPlaying && audioTrack == null) {
            Timber.w("⚠️ No active playback to stop")
            return 0
        }

        Timber.i("⏸️ STOPPING PLAYBACK IMMEDIATELY")

        stopRequested = true

        // Detener AudioTrack INMEDIATAMENTE
        try {
            audioTrack?.apply {
                when (playState) {
                    AudioTrack.PLAYSTATE_PLAYING -> {
                        pause()
                        flush()
                        Timber.d("✅ AudioTrack paused and flushed")
                    }
                    AudioTrack.PLAYSTATE_PAUSED -> {
                        flush()
                        Timber.d("✅ AudioTrack flushed (was paused)")
                    }
                    else -> {
                        Timber.d("ℹ️ AudioTrack state: $playState")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error stopping AudioTrack")
        }

        // Cancelar job
        playbackJob?.cancel()

        isPlaying = false

        Timber.i("✅ Playback stopped at ${stopTime}ms")
        return stopTime
    }

    fun isPlaying(): Boolean = isPlaying

    private fun cleanup() {
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    stop()
                }
                release()
            }
            audioTrack = null
        } catch (e: Exception) {
            Timber.e(e, "Error in cleanup")
        }
        isPlaying = false
    }

    fun release() {
        Timber.d("🔧 Releasing AudioPlayback")
        stopImmediately()

        runBlocking {
            playbackJob?.cancelAndJoin()
        }

        playbackScope.cancel()
        cleanup()

        Timber.d("✅ AudioPlayback released")
    }

    private data class WavHeader(
        val sampleRate: Int,
        val numChannels: Int,
        val bitsPerSample: Int
    )

    private fun parseWavHeader(header: ByteArray): WavHeader {
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        val riff = ByteArray(4)
        buffer.get(riff)
        if (String(riff) != "RIFF") {
            throw IllegalArgumentException("Invalid WAV: missing RIFF")
        }

        buffer.getInt()

        val wave = ByteArray(4)
        buffer.get(wave)
        if (String(wave) != "WAVE") {
            throw IllegalArgumentException("Invalid WAV: missing WAVE")
        }

        val fmt = ByteArray(4)
        buffer.get(fmt)
        if (String(fmt) != "fmt ") {
            throw IllegalArgumentException("Invalid WAV: missing fmt")
        }

        buffer.getInt()
        buffer.getShort()
        val numChannels = buffer.getShort().toInt()
        val sampleRate = buffer.getInt()
        buffer.getInt()
        buffer.getShort()
        val bitsPerSample = buffer.getShort().toInt()

        return WavHeader(sampleRate, numChannels, bitsPerSample)
    }
}