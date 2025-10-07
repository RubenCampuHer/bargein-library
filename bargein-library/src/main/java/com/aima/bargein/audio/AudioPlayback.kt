package com.aima.bargein.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val playbackScope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var isPlaying = false

    @Volatile
    private var stopRequested = false

    companion object {
        private const val SAMPLE_RATE = 16000
    }

    fun playWav(inputStream: InputStream) {
        if (isPlaying) {
            Timber.w("Playback already active")
            return
        }

        stopRequested = false

        playbackJob = playbackScope.launch {
            try {
                val header = ByteArray(44)
                inputStream.read(header)

                val wavHeader = parseWavHeader(header)
                Timber.d("WAV: sampleRate=${wavHeader.sampleRate}, channels=${wavHeader.numChannels}")

                val bufferSize = AudioTrack.getMinBufferSize(
                    wavHeader.sampleRate,
                    if (wavHeader.numChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

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
                isPlaying = true

                Timber.i("Playback started")

                val buffer = ByteArray(4096)
                var bytesRead: Int
                var wasStopped = false

                while (playbackScope.isActive && !stopRequested) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead <= 0) break

                    if (stopRequested) {
                        wasStopped = true
                        break
                    }

                    audioTrack?.write(buffer, 0, bytesRead)
                }

                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
                isPlaying = false

                inputStream.close()

                if (wasStopped) {
                    Timber.i("Playback stopped by barge-in")
                    onPlaybackStopped()
                } else {
                    Timber.i("Playback completed")
                    onPlaybackComplete()
                }

            } catch (e: Exception) {
                Timber.e(e, "Error during playback")
                cleanup()
            }
        }
    }

    fun stopImmediately(): Long {
        val stopTime = System.currentTimeMillis()

        if (!isPlaying) {
            Timber.w("No playback active to stop")
            return 0
        }

        stopRequested = true

        audioTrack?.pause()
        audioTrack?.flush()

        Timber.d("Playback stop requested")

        return 0
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
            Timber.e(e, "Error cleaning up AudioTrack")
        }
        isPlaying = false
    }

    fun release() {
        stopImmediately()
        playbackJob?.cancel()
        playbackScope.cancel()
        cleanup()
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