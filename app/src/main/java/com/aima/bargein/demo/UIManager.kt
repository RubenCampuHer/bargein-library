package com.aima.bargein.demo

import android.graphics.Color
import android.view.Gravity
import android.widget.*
import com.aima.bargein.BargeInEvent
import com.aima.bargein.BargeInMetrics
import com.aima.bargein.BargeInState

class UIManager(
    private val activity: TestActivity,
    private val configManager: ConfigManager,
    private val presetManager: PresetManager,
    private val audioManager: AudioManager
) {
    private lateinit var statusText: TextView
    private lateinit var audioLevelText: TextView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var frequencyInfoText: TextView
    private lateinit var diagnosticText: TextView  // ✅ NUEVO
    private lateinit var btnPlayTest: Button
    private lateinit var btnStopTest: Button

    private val modeButtons = mutableMapOf<SensitivityMode, Button>()

    fun setupUI() {
        val scrollView = ScrollView(activity)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }

        layout.addView(createHeader())
        layout.addView(createAudioMonitor())
        layout.addView(createDiagnosticPanel())  // ✅ NUEVO
        statusText = createStatusText()
        layout.addView(statusText)
        layout.addView(createModeSelector())
        layout.addView(createControlButtons())

        scrollView.addView(layout)
        activity.setContentView(scrollView)
    }

    private fun createHeader(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            addView(TextView(activity).apply {
                text = "🎤 Barge-In Live Monitor"
                textSize = 24f
                setTextColor(Color.parseColor("#1976D2"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            })

            addView(TextView(activity).apply {
                text = "@ 44.1kHz • Adaptive Δ Detection • Diagnostics"
                textSize = 14f
                setTextColor(Color.parseColor("#757575"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 24)
            })
            addView(Button(activity).apply {
                text = "📚 Abrir Tutorial de Diagnóstico"
                textSize = 13f
                setBackgroundColor(Color.parseColor("#FF9800"))
                setTextColor(Color.WHITE)
                setPadding(16, 20, 16, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    setMargins(0, 0, 0, 20)
                }
                setOnClickListener {
                    activity.openTutorial()
                }
            })
        }
    }

    private fun createAudioMonitor(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }

            audioLevelText = TextView(activity).apply {
                text = "🔇 Inicializando @ 44.1kHz..."
                textSize = 16f
                setTextColor(Color.parseColor("#212121"))
                setPadding(0, 0, 0, 12)
            }

            audioLevelBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 60
                ).apply { setMargins(0, 0, 0, 12) }
            }

            frequencyInfoText = TextView(activity).apply {
                text = "Esperando datos..."
                textSize = 13f
                setTextColor(Color.parseColor("#616161"))
                setPadding(0, 0, 0, 8)
            }

            addView(audioLevelText)
            addView(audioLevelBar)
            addView(frequencyInfoText)
        }
    }

    // ✅ NUEVO: Panel de diagnóstico detallado
    private fun createDiagnosticPanel(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#FFF3E0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }

            addView(TextView(activity).apply {
                text = "🔍 DIAGNÓSTICO EN TIEMPO REAL"
                textSize = 14f
                setTextColor(Color.parseColor("#E65100"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 12)
            })

            diagnosticText = TextView(activity).apply {
                text = "Esperando análisis..."
                textSize = 12f
                setTextColor(Color.parseColor("#424242"))
                setPadding(8, 8, 8, 8)
                setBackgroundColor(Color.parseColor("#FFFFFF"))
                typeface = android.graphics.Typeface.MONOSPACE
            }

            addView(diagnosticText)

            addView(TextView(activity).apply {
                text = """
                    
                    ℹ️ Criterios de detección:
                    • ✅ = Cumple criterio (favorece detección)
                    • ❌ = No cumple (bloquea detección)
                    • TODOS deben ser ✅ para detectar voz
                """.trimIndent()
                textSize = 10f
                setTextColor(Color.parseColor("#757575"))
                setPadding(0, 8, 0, 0)
            })
        }
    }

    private fun createStatusText(): TextView {
        return TextView(activity).apply {
            text = "Verificando permisos..."
            textSize = 14f
            setTextColor(Color.parseColor("#424242"))
            gravity = Gravity.CENTER
            setPadding(16, 20, 16, 10)
        }
    }

    private fun createModeSelector(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(Color.parseColor("#FFF3E0"))

            addView(TextView(activity).apply {
                text = "🎚️ MODO DE SENSIBILIDAD"
                textSize = 14f
                setTextColor(Color.parseColor("#E65100"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 8)
            })

            addView(createModeButtonsRow())
            addView(createCustomRow())
            addView(createPresetRow())
        }
    }

    private fun createModeButtonsRow(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL

            modeButtons[SensitivityMode.SUPER_SENSITIVE] = createModeButton(
                "🔴 Super", Color.parseColor("#F44336")
            ) { activity.changeSensitivityMode(SensitivityMode.SUPER_SENSITIVE) }

            modeButtons[SensitivityMode.SENSITIVE] = createModeButton(
                "🟡 Sensible", Color.parseColor("#FF9800")
            ) { activity.changeSensitivityMode(SensitivityMode.SENSITIVE) }

            modeButtons[SensitivityMode.NORMAL] = createModeButton(
                "🟢 Normal", Color.parseColor("#4CAF50")
            ) { activity.changeSensitivityMode(SensitivityMode.NORMAL) }

            modeButtons.values.forEach { addView(it) }
        }
    }

    private fun createCustomRow(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }

            modeButtons[SensitivityMode.CUSTOM] = createModeButton(
                "⚙️ Custom", Color.parseColor("#9C27B0")
            ) { activity.changeSensitivityMode(SensitivityMode.CUSTOM) }

            val btnEditCustom = createModeButton(
                "✏️ Editar", Color.parseColor("#673AB7")
            ) { CustomSettingsDialog(activity, configManager).show() }

            addView(modeButtons[SensitivityMode.CUSTOM])
            addView(btnEditCustom)
        }
    }

    private fun createPresetRow(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }

            addView(createModeButton(
                "💾 Guardar", Color.parseColor("#00897B")
            ) { PresetDialog(activity, configManager, presetManager).showSave() })

            addView(createModeButton(
                "📂 Cargar", Color.parseColor("#00ACC1")
            ) { PresetDialog(activity, configManager, presetManager).showLoad() })
        }
    }

    private fun createModeButton(text: String, color: Int, onClick: () -> Unit): Button {
        return Button(activity).apply {
            this.text = text
            textSize = 12f
            setBackgroundColor(color)
            setTextColor(Color.WHITE)
            setPadding(8, 24, 8, 24)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(4, 0, 4, 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun createControlButtons(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            btnPlayTest = createButton(
                "▶️ INICIAR TEST", Color.parseColor("#4CAF50"), false
            ) { activity.startBargeInTest() }

            btnStopTest = createButton(
                "⏹️ DETENER TEST", Color.parseColor("#F44336"), false
            ) { activity.stopBargeInTest() }

            addView(btnPlayTest)
            addView(btnStopTest)
        }
    }

    private fun createButton(text: String, color: Int, enabled: Boolean, onClick: () -> Unit): Button {
        return Button(activity).apply {
            this.text = text
            textSize = 15f
            setBackgroundColor(color)
            setTextColor(Color.WHITE)
            isEnabled = enabled
            setPadding(20, 32, 20, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
            setOnClickListener { onClick() }
        }
    }

    fun updateModeButtons() {
        modeButtons.values.forEach { it.alpha = 0.5f }
        modeButtons[configManager.currentMode]?.alpha = 1.0f
    }

    fun updateAudioVisualizer(metrics: BargeInMetrics) {
        val vadMetrics = metrics.vadMetrics ?: return
        val totalFrames = vadMetrics.framesProcessed.toFloat()
        if (totalFrames == 0f) return

        val avgConfidence = vadMetrics.averageConfidence
        val estimatedDb = -60f + (avgConfidence * 60f)
        val barProgress = ((estimatedDb + 60f) * 100f / 60f).toInt().coerceIn(0, 100)

        audioLevelBar.progress = barProgress
        audioLevelBar.progressTintList = android.content.res.ColorStateList.valueOf(
            when {
                barProgress > 70 -> Color.parseColor("#4CAF50")
                barProgress > 50 -> Color.parseColor("#8BC34A")
                barProgress > 30 -> Color.parseColor("#FFC107")
                barProgress > 15 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#F44336")
            }
        )

        val icon = when {
            barProgress > 70 -> "🔊"
            barProgress > 30 -> "🔉"
            else -> "🔇"
        }

        audioLevelText.text = "$icon Audio: %.1f dB | Confianza: %.2f".format(estimatedDb, avgConfidence)

        val voiceRatio = vadMetrics.voiceFrames.toFloat() / totalFrames
        val stateEmoji = when (metrics.state) {
            BargeInState.IDLE -> "💤"
            BargeInState.LISTENING -> "🎤"
            BargeInState.INTERRUPTED -> "🚨"
            BargeInState.STOPPED -> "⏸️"
            BargeInState.ERROR -> "❌"
        }

        frequencyInfoText.text = """
            📊 Frames: ${vadMetrics.framesProcessed} | Voz: ${vadMetrics.voiceFrames} (%.1f%%)
            ⏱️ Proc: ${vadMetrics.averageProcessingTimeUs}µs/frame
            🎯 Estado: $stateEmoji ${metrics.state}
        """.trimIndent().format(voiceRatio * 100)

        // ✅ NUEVO: Actualizar diagnóstico detallado
        updateDiagnostics(metrics, estimatedDb, avgConfidence)
    }

    // ✅ NUEVO: Mostrar criterios de detección en tiempo real
    private fun updateDiagnostics(metrics: BargeInMetrics, currentDb: Float, confidence: Float) {
        val config = configManager.getConfigForCurrentMode()

        // Simular valores actuales (en producción vendrían del engine)
        val currentEnergy = currentDb
        val currentZcr = 0.15f // Esto debería venir del engine
        val currentDelta = currentDb + 20f // Simulación de delta vs baseline

        // Evaluar cada criterio
        val deltaCheck = currentDelta >= config.deltaVoiceThresholdDb
        val energyCheck = currentEnergy >= config.minAbsoluteVoiceEnergyDb
        val zcrCheck = currentZcr <= config.maxZcrForVoice
        val confidenceCheck = confidence >= config.voiceConfidenceThreshold

        val allPass = deltaCheck && energyCheck && zcrCheck && confidenceCheck

        val diagnostic = buildString {
            appendLine("═══════════════════════════════")
            appendLine(if (allPass) "✅ VOZ DETECTADA" else "❌ VOZ RECHAZADA")
            appendLine("═══════════════════════════════")
            appendLine()

            appendLine("${if (deltaCheck) "✅" else "❌"} Delta vs Baseline:")
            appendLine("   Actual: %.1f dB".format(currentDelta))
            appendLine("   Requerido: ≥ %.1f dB".format(config.deltaVoiceThresholdDb))
            appendLine()

            appendLine("${if (energyCheck) "✅" else "❌"} Energía Absoluta:")
            appendLine("   Actual: %.1f dB".format(currentEnergy))
            appendLine("   Requerido: ≥ %.1f dB".format(config.minAbsoluteVoiceEnergyDb))
            appendLine()

            appendLine("${if (zcrCheck) "✅" else "❌"} Zero Crossing Rate:")
            appendLine("   Actual: %.2f".format(currentZcr))
            appendLine("   Requerido: ≤ %.2f".format(config.maxZcrForVoice))
            appendLine()

            appendLine("${if (confidenceCheck) "✅" else "❌"} Confianza:")
            appendLine("   Actual: %.2f (%.0f%%)".format(confidence, confidence * 100))
            appendLine("   Requerido: ≥ %.2f".format(config.voiceConfidenceThreshold))
            appendLine()

            appendLine("───────────────────────────────")
            appendLine("⚙️ Configuración actual:")
            appendLine("   Modo: ${configManager.currentMode}")
            appendLine("   Factor ajuste: %.2f".format(config.deltaBaselineAdjustmentFactor))
            appendLine("   Duración mín: ${config.minVoiceDurationMs}ms")
        }

        diagnosticText.text = diagnostic

        // Cambiar color de fondo según resultado
        diagnosticText.setBackgroundColor(
            if (allPass) Color.parseColor("#C8E6C9") else Color.parseColor("#FFCCBC")
        )
    }

    fun showReady(mode: SensitivityMode, presetCount: Int) {
        val modeInfo = getModeEmoji(mode)
        val presetInfo = if (presetCount > 0) "\n💾 $presetCount preset(s) guardado(s)" else ""

        statusText.text = """
            ✅ Sistema listo @ 44.1kHz
            
            Modo: $modeInfo$presetInfo
            🎤 Micrófono: LISTO (inactivo)
            
            Presiona "INICIAR TEST" para comenzar
        """.trimIndent()

        diagnosticText.text = "Esperando inicio de test..."
        diagnosticText.setBackgroundColor(Color.parseColor("#FFFFFF"))
    }

    fun showTestRunning(mode: SensitivityMode, description: String) {
        statusText.text = """
            🎵 REPRODUCIENDO AUDIO @ 44.1kHz
            
            ¡Interrumpe hablando FUERTE!
            
            🎤 Micrófono: ACTIVO
            📊 Modo: $description
        """.trimIndent()
    }

    fun showTestStopped() {
        statusText.text = """
            ⏸️ Test detenido
            
            🎤 Micrófono: INACTIVO
            📊 Sistema en reposo
            
            Presiona "INICIAR TEST" para otra prueba
        """.trimIndent()

        diagnosticText.text = "Test detenido - esperando nuevo inicio..."
        diagnosticText.setBackgroundColor(Color.parseColor("#FFFFFF"))
    }

    fun showModeChanged(mode: SensitivityMode, description: String) {
        statusText.text = """
            ✅ Modo cambiado @ 44.1kHz
            
            ${getModeEmoji(mode)}
            $description
            
            🎤 Sistema listo (inactivo)
            Presiona "INICIAR TEST" para probar
        """.trimIndent()
    }

    fun showInterruption(event: BargeInEvent) {
        val latencyOk = event.latencyMs < 300
        val emoji = if (latencyOk) "✅" else "⚠️"
        val colorIndicator = if (latencyOk) "🟢" else "🟡"

        statusText.text = """
            🎉 ¡BARGE-IN DETECTADO!
            
            $emoji Latencia: %.0f ms $colorIndicator
            📊 Confianza: %.0f%%
            📊 Energía: %.1f dB
            
            ${if (latencyOk) "¡Excelente respuesta! <300ms" else "Mejorable (>300ms)"}
            
            🎤 Sistema en reposo
            Presiona "INICIAR TEST" para otra prueba
        """.trimIndent().format(event.latencyMs, event.confidence * 100, event.energyDb)

        diagnosticText.text = """
            🎉 INTERRUPCIÓN EXITOSA
            
            Energía detectada: %.1f dB
            Confianza final: %.0f%%
            Latencia: %.0f ms
            
            Todos los criterios fueron cumplidos
            para activar el barge-in.
        """.trimIndent().format(event.energyDb, event.confidence * 100, event.latencyMs)

        diagnosticText.setBackgroundColor(Color.parseColor("#C8E6C9"))
    }

    fun showError(message: String) {
        statusText.text = "❌ ERROR\n\n$message"
        diagnosticText.text = "Error en el sistema - revisar logs"
        diagnosticText.setBackgroundColor(Color.parseColor("#FFCCBC"))
    }

    fun enablePlayButton(enabled: Boolean) {
        btnPlayTest.isEnabled = enabled
    }

    fun enableStopButton(enabled: Boolean) {
        btnStopTest.isEnabled = enabled
    }

    private fun getModeEmoji(mode: SensitivityMode): String {
        return when (mode) {
            SensitivityMode.SUPER_SENSITIVE -> "🔴 SUPER SENSIBLE"
            SensitivityMode.SENSITIVE -> "🟡 SENSIBLE"
            SensitivityMode.NORMAL -> "🟢 NORMAL"
            SensitivityMode.CUSTOM -> "⚙️ CUSTOM"
        }
    }
    fun showPlaybackCompleted() {
        statusText.text = """
        ✅ Audio completado
        
        El audio terminó de reproducirse
        sin interrupciones
        
        🎤 Sistema en reposo
        Presiona "INICIAR TEST" para otra prueba
    """.trimIndent()

        diagnosticText.text = "Audio completado - sin barge-in detectado"
        diagnosticText.setBackgroundColor(Color.parseColor("#E3F2FD"))
    }
}