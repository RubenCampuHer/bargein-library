package com.aima.bargein.demo

import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Genera un archivo WAV sintético para pruebas.
 * Crea una voz simulada con frecuencias bajas y medias.
 */
object WavGenerator {

    /**
     * Genera un archivo WAV con audio sintético.
     * @param outputFile Archivo de salida
     * @param durationSeconds Duración en segundos
     */
    fun generateTestWav(outputFile: File, durationSeconds: Int = 10) {
        try {
            val sampleRate = 16000
            val numChannels = 1
            val bitsPerSample = 16
            val numSamples = sampleRate * durationSeconds

            Timber.i("Generating WAV: ${outputFile.absolutePath}, duration=${durationSeconds}s")

            // Crear buffer de audio
            val audioData = generateAudioSamples(numSamples, sampleRate)

            // Escribir archivo WAV
            writeWavFile(outputFile, audioData, sampleRate, numChannels, bitsPerSample)

            Timber.i("✅ WAV generated: ${outputFile.length()} bytes")

        } catch (e: Exception) {
            Timber.e(e, "Error generating WAV")
            throw e
        }
    }

    /**
     * Genera samples de audio simulando una voz.
     * Mezcla varias frecuencias para simular habla.
     */
    private fun generateAudioSamples(numSamples: Int, sampleRate: Int): ShortArray {
        val samples = ShortArray(numSamples)

        // Simular voz con múltiples frecuencias
        val frequencies = listOf(
            200f to 0.3f,  // Fundamental (grave)
            400f to 0.25f, // Primera armónica
            600f to 0.2f,  // Segunda armónica
            800f to 0.15f, // Tercera armónica
            1200f to 0.1f  // Cuarta armónica (medios)
        )

        for (i in samples.indices) {
            var value = 0.0

            // Sumar todas las frecuencias
            for ((freq, amplitude) in frequencies) {
                val phase = 2.0 * PI * freq * i / sampleRate
                value += amplitude * sin(phase)
            }

            // Aplicar envolvente (fade in/out y variación)
            val envelope = calculateEnvelope(i, numSamples)
            value *= envelope

            // Convertir a 16-bit PCM
            samples[i] = (value * 32767 * 0.7).toInt().coerceIn(-32768, 32767).toShort()
        }

        return samples
    }

    /**
     * Calcula envolvente para simular habla natural.
     */
    private fun calculateEnvelope(sampleIndex: Int, totalSamples: Int): Double {
        val position = sampleIndex.toDouble() / totalSamples

        // Fade in (primeros 5%)
        if (position < 0.05) {
            return position / 0.05
        }

        // Fade out (últimos 5%)
        if (position > 0.95) {
            return (1.0 - position) / 0.05
        }

        // En el medio, simular pausas naturales
        val cycle = (position * 8) % 1.0 // 8 ciclos
        return 0.7 + 0.3 * sin(cycle * 2 * PI)
    }

    /**
     * Escribe archivo WAV con header completo.
     */
    private fun writeWavFile(
        file: File,
        audioData: ShortArray,
        sampleRate: Int,
        numChannels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = (numChannels * bitsPerSample / 8).toShort()
        val dataSize = audioData.size * 2 // 2 bytes per short

        FileOutputStream(file).use { output ->
            val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            buffer.put("RIFF".toByteArray())
            buffer.putInt(36 + dataSize) // ChunkSize
            buffer.put("WAVE".toByteArray())

            // fmt subchunk
            buffer.put("fmt ".toByteArray())
            buffer.putInt(16) // Subchunk1Size (PCM)
            buffer.putShort(1) // AudioFormat (PCM = 1)
            buffer.putShort(numChannels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate)
            buffer.putShort(blockAlign)
            buffer.putShort(bitsPerSample.toShort())

            // data subchunk
            buffer.put("data".toByteArray())
            buffer.putInt(dataSize)

            // Audio data
            for (sample in audioData) {
                buffer.putShort(sample)
            }

            output.write(buffer.array())
        }
    }
}