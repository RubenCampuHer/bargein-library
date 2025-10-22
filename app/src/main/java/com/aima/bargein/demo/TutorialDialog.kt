package com.aima.bargein.demo

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File

class TutorialDialog(
    private val activity: TestActivity,
    private val tutorialManager: TutorialManager
) {

    private var currentDialog: AlertDialog? = null
    private var logCaptureFile: File? = null

    fun show() {
        showCurrentStep()
    }

    private fun showCurrentStep() {
        val step = tutorialManager.getCurrentStep()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
        }

        // Título
        layout.addView(TextView(activity).apply {
            text = step.title
            textSize = 20f
            setTextColor(Color.parseColor("#1976D2"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        })

        // Instrucciones
        layout.addView(TextView(activity).apply {
            text = step.instruction
            textSize = 15f
            setTextColor(Color.parseColor("#212121"))

        })

        // Botones especiales según el paso
        when (step) {
            TutorialManager.TutorialStep.TEST_1_SETUP,
            TutorialManager.TutorialStep.TEST_2_SETUP -> {
                layout.addView(createStartTestButton())
            }

            TutorialManager.TutorialStep.TEST_1_SPEAK,
            TutorialManager.TutorialStep.TEST_2_SPEAK -> {
                layout.addView(createWaitingMessage())
                layout.addView(createStopCaptureButton())
            }

            TutorialManager.TutorialStep.FINISH -> {
                layout.addView(createShareButton())
                layout.addView(createViewFilesButton())
            }

            else -> {}
        }

        val builder = AlertDialog.Builder(activity)
            .setView(layout)
            .setCancelable(false)

        // Botones de navegación
        if (step != TutorialManager.TutorialStep.WELCOME) {
            builder.setNegativeButton("← Anterior") { _, _ ->
                tutorialManager.previousStep()
                showCurrentStep()
            }
        }

        if (step != TutorialManager.TutorialStep.FINISH) {
            builder.setPositiveButton("Siguiente →") { _, _ ->
                tutorialManager.nextStep()
                showCurrentStep()
            }
        } else {
            builder.setPositiveButton("Finalizar") { _, _ ->
                currentDialog?.dismiss()
            }
        }

        builder.setNeutralButton("❌ Salir") { _, _ ->
            AlertDialog.Builder(activity)
                .setTitle("¿Salir del tutorial?")
                .setMessage("Puedes volver a iniciarlo desde el menú.")
                .setPositiveButton("Salir") { _, _ ->
                    currentDialog?.dismiss()
                }
                .setNegativeButton("Continuar", null)
                .show()
        }

        currentDialog?.dismiss()
        currentDialog = builder.create()
        currentDialog?.show()
    }

    private fun createStartTestButton(): Button {
        return Button(activity).apply {
            text = "▶️ INICIAR TEST Y CAPTURA"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 40, 20, 40)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 20, 0, 10)
            }

            setOnClickListener {
                val currentStep = tutorialManager.getCurrentStep()

                // Verificar que estamos en el modo correcto
                when (currentStep) {
                    TutorialManager.TutorialStep.TEST_1_SETUP -> {
                        // Cambiar a Super Sensitive si no está ya
                        if (activity.configManager.currentMode != SensitivityMode.SUPER_SENSITIVE) {
                            activity.changeSensitivityMode(SensitivityMode.SUPER_SENSITIVE)
                            Toast.makeText(activity, "🔴 Cambiado a Super Sensitive", Toast.LENGTH_SHORT).show()

                            // Esperar un momento para que se aplique
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                startTestWithCapture()
                            }, 500)
                        } else {
                            startTestWithCapture()
                        }
                    }

                    TutorialManager.TutorialStep.TEST_2_SETUP -> {
                        // Cambiar a Custom si no está ya
                        if (activity.configManager.currentMode != SensitivityMode.CUSTOM) {
                            activity.changeSensitivityMode(SensitivityMode.CUSTOM)
                            Toast.makeText(activity, "⚙️ Cambiado a Custom", Toast.LENGTH_SHORT).show()

                            // Esperar un momento para que se aplique
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                startTestWithCapture()
                            }, 500)
                        } else {
                            startTestWithCapture()
                        }
                    }

                    else -> {}
                }
            }
        }
    }

    private fun startTestWithCapture() {
        val currentStep = tutorialManager.getCurrentStep()

        // Determinar nombre del test
        val testName = when (currentStep) {
            TutorialManager.TutorialStep.TEST_1_SETUP -> "test1_super_sensitive"
            TutorialManager.TutorialStep.TEST_2_SETUP -> "test2_custom_super"
            else -> "test_unknown"
        }

        // 1. Iniciar captura de logs con nombre
        val captureStarted = tutorialManager.startLogCapture(testName)

        if (!captureStarted) {
            Toast.makeText(activity, "❌ Error iniciando captura de logs", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(activity, "✅ Captura de logs iniciada", Toast.LENGTH_SHORT).show()

        // 2. Cerrar diálogo
        currentDialog?.dismiss()

        // 3. Iniciar el test de audio
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                activity.startBargeInTest()
                Timber.i("▶️ Test started from tutorial")

                // 4. Mostrar siguiente paso después de 2 segundos
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    tutorialManager.nextStep()
                    showCurrentStep()
                }, 2000)

            } catch (e: Exception) {
                Timber.e(e, "Error starting test from tutorial")
                Toast.makeText(activity, "❌ Error iniciando test: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, 300)
    }

    private fun createWaitingMessage(): TextView {
        return TextView(activity).apply {
            text = "⏳ Esperando a que termines de hablar...\n\nCuando el audio se detenga o termine,\npresiona el botón de abajo."
            textSize = 14f
            setTextColor(Color.parseColor("#FF9800"))
            setTypeface(null, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#FFF3E0"))
        }
    }

    private fun createStopCaptureButton(): Button {
        return Button(activity).apply {
            text = "⏹️ GUARDAR LOGS DEL TEST"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#FF5722"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 30, 20, 30)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 20, 0, 10)
            }

            setOnClickListener {
                logCaptureFile = tutorialManager.stopLogCapture()

                if (logCaptureFile != null) {
                    Toast.makeText(activity, "✅ Logs guardados: ${logCaptureFile!!.name}", Toast.LENGTH_LONG).show()
                    Timber.i("✅ Log file saved: ${logCaptureFile!!.absolutePath}")

                    // Avanzar automáticamente
                    tutorialManager.nextStep()
                    showCurrentStep()
                } else {
                    Toast.makeText(activity, "❌ Error guardando logs", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun createShareButton(): Button {
        return Button(activity).apply {
            text = "📤 Compartir Logs"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            setPadding(20, 30, 20, 30)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 20, 0, 10)
            }

            setOnClickListener {
                shareLogFiles()
            }
        }
    }

    private fun createViewFilesButton(): Button {
        return Button(activity).apply {
            text = "📁 Ver Archivos Generados"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#607D8B"))
            setTextColor(Color.WHITE)
            setPadding(20, 30, 20, 30)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10, 0, 10)
            }

            setOnClickListener {
                showLogFilesList()
            }
        }
    }

    private fun shareLogFiles() {
        val logFiles = tutorialManager.getAllLogFiles()

        if (logFiles.isEmpty()) {
            Toast.makeText(activity, "No hay archivos de log para compartir", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uris = logFiles.map { file ->
                FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    file
                )
            }

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_SUBJECT, "Barge-In Diagnostic Logs")
                putExtra(Intent.EXTRA_TEXT, "Adjunto logs de diagnóstico de Barge-In (${logFiles.size} archivos)")
            }

            activity.startActivity(Intent.createChooser(shareIntent, "Compartir logs via..."))

        } catch (e: Exception) {
            Timber.e(e, "Error sharing files")
            Toast.makeText(activity, "Error compartiendo archivos: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showLogFilesList() {
        val logFiles = tutorialManager.getAllLogFiles()

        if (logFiles.isEmpty()) {
            Toast.makeText(activity, "No hay archivos de log", Toast.LENGTH_SHORT).show()
            return
        }

        val fileList = logFiles.joinToString("\n") { file ->
            "📄 ${file.name} (${file.length() / 1024}KB)"
        }

        AlertDialog.Builder(activity)
            .setTitle("📁 Archivos de Log (${logFiles.size})")
            .setMessage("Ubicación:\n${tutorialManager.getLogFilesDirectory().absolutePath}\n\n$fileList")
            .setPositiveButton("OK", null)
            .setNeutralButton("📤 Compartir") { _, _ ->
                shareLogFiles()
            }
            .show()
    }
}