package com.aima.bargein.demo

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class TutorialManager(private val context: Context) {

    companion object {
        private const val TAG = "TutorialManager"
    }

    private val logFile: File?
    private var fileOutputStream: FileOutputStream? = null
    private var currentStep = 0
    private var testNumber = 0
    private var isCapturing = false

    enum class TutorialStep(val title: String, val instruction: String) {
        WELCOME(
            "Bienvenido al Tutorial",
            "Este tutorial te guiará para capturar logs de diagnóstico.\n\n" +
                    "Los logs se guardarán automáticamente en archivos .txt que podrás compartir.\n\n" +
                    "Presiona 'Siguiente' para comenzar."
        ),

        TEST_1_SETUP(
            "Test 1: Super Sensitive",
            "Vamos a probar el modo Super Sensitive.\n\n" +
                    "Cuando presiones el botón verde:\n" +
                    "1. Se cambiará automáticamente a modo 🔴 SUPER SENSITIVE\n" +
                    "2. Se iniciará la captura de logs\n" +
                    "3. El audio comenzará a reproducirse\n\n" +
                    "⚠️ IMPORTANTE: NO hables hasta que veas el paso siguiente.\n\n" +
                    "Presiona el botón cuando estés listo:"
        ),

        TEST_1_SPEAK(
            "Test 1: Habla Ahora",
            "🎵 El audio se está reproduciéndose.\n\n" +
                    "🎤 AHORA SÍ: HABLA FUERTE durante 3 segundos:\n" +
                    "Di: 'HOLA HOLA HOLA'\n\n" +
                    "Si el audio se detiene = ✅ Funciona\n" +
                    "Si el audio NO se detiene = ❌ No funciona\n\n" +
                    "Cuando el audio termine o se detenga,\npresiona el botón rojo de abajo."
        ),

        TEST_1_COMPLETE(
            "Test 1: Completado ✅",
            "¡Perfecto! Los logs del Test 1 han sido guardados.\n\n" +
                    "Archivo: test1_super_sensitive.txt\n\n" +
                    "ANOTA AQUÍ TU RESULTADO:\n" +
                    "¿El audio se detuvo cuando hablaste?\n" +
                    "• SÍ = Funciona correctamente ✅\n" +
                    "• NO = Hay un problema ❌\n" +
                    "• Se detuvo SOLO (sin hablar) = 🐛 BUG CRÍTICO\n\n" +
                    "Presiona 'Siguiente' para Test 2."
        ),

        TEST_2_SETUP(
            "Test 2: Custom (copiado de Super)",
            "Ahora vamos a probar Custom con valores idénticos.\n\n" +
                    "Cuando presiones el botón verde:\n" +
                    "1. Se cambiará a modo ⚙️ CUSTOM\n" +
                    "2. Se copiarán valores de Super\n" +
                    "3. Se iniciará la captura de logs\n" +
                    "4. El audio comenzará\n\n" +
                    "⚠️ IMPORTANTE: NO hables hasta el paso siguiente.\n\n" +
                    "Presiona cuando estés listo:"
        ),

        TEST_2_SPEAK(
            "Test 2: Habla Ahora",
            "🎵 El audio se está reproduciéndose en modo CUSTOM.\n\n" +
                    "🎤 AHORA SÍ: HABLA FUERTE durante 3 segundos:\n" +
                    "Di: 'HOLA HOLA HOLA'\n\n" +
                    "Si el audio se detiene = ✅ Funciona\n" +
                    "Si el audio NO se detiene = ❌ No funciona\n\n" +
                    "Cuando termine o se detenga,\npresiona el botón rojo."
        ),

        TEST_2_COMPLETE(
            "Test 2: Completado ✅",
            "¡Excelente! Los logs del Test 2 han sido guardados.\n\n" +
                    "Archivo: test2_custom_super.txt\n\n" +
                    "ANOTA AQUÍ TU RESULTADO:\n" +
                    "¿El audio se detuvo cuando hablaste?\n" +
                    "• SÍ = Custom funciona igual ✅\n" +
                    "• NO = Hay diferencia con Super ❌\n" +
                    "• Se detuvo SOLO (sin hablar) = 🐛 BUG CRÍTICO\n\n" +
                    "Presiona 'Siguiente' para finalizar."
        ),

        FINISH(
            "Tutorial Completado ✅",
            "¡Has completado el tutorial!\n\n" +
                    "Archivos generados:\n" +
                    "• test1_super_sensitive.txt\n" +
                    "• test2_custom_super.txt\n\n" +
                    "🔍 Los logs contienen información detallada sobre:\n" +
                    "- Configuración aplicada\n" +
                    "- Frames de audio procesados\n" +
                    "- Detecciones de voz\n" +
                    "- Por qué se aceptó o rechazó cada frame\n\n" +
                    "Comparte estos archivos para diagnóstico."
        )
    }

    init {
        // Crear directorio de logs
        val logsDir = File(context.getExternalFilesDir(null), "BargeInLogs")
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
        logFile = logsDir
    }

    fun getCurrentStep(): TutorialStep {
        return TutorialStep.values()[currentStep.coerceIn(0, TutorialStep.values().size - 1)]
    }

    fun nextStep(): TutorialStep {
        if (currentStep < TutorialStep.values().size - 1) {
            currentStep++
        }
        return getCurrentStep()
    }

    fun previousStep(): TutorialStep {
        if (currentStep > 0) {
            currentStep--
        }
        return getCurrentStep()
    }

    fun startLogCapture(testName: String): Boolean {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "${testName}_${timestamp}.txt"
            val logFileInstance = File(logFile, filename)

            fileOutputStream = FileOutputStream(logFileInstance, false)
            isCapturing = true
            testNumber++

            // Escribir header
            val header = buildString {
                appendLine("═══════════════════════════════════════")
                appendLine("BARGE-IN DIAGNOSTIC LOG")
                appendLine("Test Number: $testNumber")
                appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("Current Step: ${getCurrentStep().title}")
                appendLine("═══════════════════════════════════════")
                appendLine()
            }

            fileOutputStream?.write(header.toByteArray())
            fileOutputStream?.flush()

            Log.i(TAG, "📝 Log capture started: $filename")
            Log.i(TAG, "═══════════════════════════════════════")

            return true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting log capture")
            isCapturing = false
            return false
        }
    }

    fun stopLogCapture(): File? {
        try {
            if (!isCapturing) {
                Log.w(TAG, "⚠️ Log capture was not active")
                return null
            }

            Log.i(TAG, "═══════════════════════════════════════")
            Log.i(TAG, "📝 Log capture stopped")

            fileOutputStream?.flush()
            fileOutputStream?.close()

            isCapturing = false

            // Buscar el archivo más reciente
            val files = logFile?.listFiles()?.sortedByDescending { it.lastModified() }
            val mostRecent = files?.firstOrNull()

            if (mostRecent != null) {
                Log.i(TAG, "✅ Logs saved to: ${mostRecent.name}")
                Log.i(TAG, "   Size: ${mostRecent.length()} bytes")
            }

            fileOutputStream = null

            return mostRecent

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping log capture")
            isCapturing = false
            fileOutputStream = null
            return null
        }
    }

    fun getLogFilesDirectory(): File {
        return logFile ?: File(context.getExternalFilesDir(null), "BargeInLogs")
    }

    fun getAllLogFiles(): List<File> {
        val dir = getLogFilesDirectory()
        return if (dir.exists()) {
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }

    fun isAtStep(step: TutorialStep): Boolean {
        return getCurrentStep() == step
    }

    fun reset() {
        stopLogCapture()
        currentStep = 0
        testNumber = 0
    }
}