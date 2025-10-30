package com.aima.bargein.demo

import android.graphics.Color
import android.view.Gravity
import android.widget.*
import com.aima.bargein.BargeInMetrics
import com.aima.bargein.BargeInEvent
import com.aima.bargein.PythonVadPreset

class PythonMethodUIManager(
    private val activity: PythonMethodActivity,
    private val configManager: PythonMethodConfigManager,
    private val audioManager: AudioManager
) {
    private lateinit var statusText: TextView
    private lateinit var audioLevelText: TextView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var frequencyInfoText: TextView
    private lateinit var diagnosticText: TextView
    private lateinit var btnPlayTest: Button
    private lateinit var btnStopTest: Button
    private lateinit var btnBackToMain: Button

    private val presetButtons = mutableMapOf<PythonVadPreset, Button>()

    fun setupUI() {
        val scrollView = ScrollView(activity)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }

        layout.addView(createHeader())
        layout.addView(createAudioMonitor())
        layout.addView(createDiagnosticPanel())
        statusText = createStatusText()
        layout.addView(statusText)
        layout.addView(createPresetSelector())
        layout.addView(createCustomControls())
        layout.addView(createControlButtons())

        scrollView.addView(layout)
        activity.setContentView(scrollView)
    }

    private fun createHeader(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            addView(TextView(activity).apply {
                text = "🐍 Python Method - Barge-In"
                textSize = 24f
                setTextColor(Color.parseColor("#1976D2"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 8)
            })

            addView(TextView(activity).apply {
                text = "Leak Compensation + Rise Factor"
                textSize = 14f
                setTextColor(Color.parseColor("#757575"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            })

            btnBackToMain = Button(activity).apply {
                text = "⬅️ Volver al Método Android"
                textSize = 13f
                setBackgroundColor(Color.parseColor("#9E9E9E"))
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
                    activity.finish()
                }
            }
            addView(btnBackToMain)
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
                text = "🔍 DIAGNÓSTICO MÉTODO PYTHON"
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
                    
                    ℹ️ Triple Check del Método Python:
                    • VAD Check: RMS ≥ rmsThreshold
                    • Leak Check: RMS ≥ leak_thr
                    • Rise Check: RMS > baseline × riseFactor
                    • TODOS deben cumplirse para detectar voz
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

    private fun createPresetSelector(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(Color.parseColor("#FFF3E0"))

            addView(TextView(activity).apply {
                text = "🎚️ PRESET DEL MÉTODO PYTHON"
                textSize = 14f
                setTextColor(Color.parseColor("#E65100"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 8)
            })

            addView(createPresetButtonsRow())
            addView(createPresetInfo())
        }
    }

    private fun createPresetButtonsRow(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL

            presetButtons[PythonVadPreset.QUIET] = createPresetButton(
                "🔵 Quiet", Color.parseColor("#2196F3")
            ) { activity.changePreset(PythonVadPreset.QUIET) }

            presetButtons[PythonVadPreset.OFFICE] = createPresetButton(
                "🟢 Office", Color.parseColor("#4CAF50")
            ) { activity.changePreset(PythonVadPreset.OFFICE) }

            presetButtons[PythonVadPreset.NOISY] = createPresetButton(
                "🔴 Noisy", Color.parseColor("#F44336")
            ) { activity.changePreset(PythonVadPreset.NOISY) }

            presetButtons[PythonVadPreset.CUSTOM] = createPresetButton(
                "⚙️ Custom", Color.parseColor("#FF9800")
            ) { activity.changePreset(PythonVadPreset.CUSTOM) }

            addView(presetButtons[PythonVadPreset.QUIET])
            addView(presetButtons[PythonVadPreset.OFFICE])
            addView(presetButtons[PythonVadPreset.NOISY])
            addView(presetButtons[PythonVadPreset.CUSTOM])
        }
    }

    private fun createPresetButton(
        text: String,
        baseColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(activity).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(baseColor)
            setPadding(12, 16, 12, 16)

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(4, 4, 4, 4)
            }

            setOnClickListener { onClick() }
        }
    }

    private fun createPresetInfo(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 12, 8, 8)

            addView(TextView(activity).apply {
                text = """
                    📊 Presets basados en tu configuración Python:
                    
                    🔵 QUIET: RMS=0.010, Rise=1.3x, Leak_k=2.0
                       • Ambientes silenciosos
                       • 3 frames open, 5 frames close
                    
                    🟢 OFFICE: RMS=0.012, Rise=1.6x, Leak_k=2.5
                       • Oficinas normales (DEFAULT)
                       • 4 frames open, 6 frames close
                    
                    🔴 NOISY: RMS=0.015, Rise=2.0x, Leak_k=3.0
                       • Ambientes ruidosos
                       • 5 frames open, 7 frames close
                """.trimIndent()
                textSize = 11f
                setTextColor(Color.parseColor("#424242"))
                typeface = android.graphics.Typeface.MONOSPACE
            })
        }
    }

    private fun createCustomControls(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            visibility = if (configManager.currentPreset == PythonVadPreset.CUSTOM) {
                LinearLayout.VISIBLE
            } else {
                LinearLayout.GONE
            }

            addView(TextView(activity).apply {
                text = "⚙️ CONFIGURACIÓN CUSTOM"
                textSize = 14f
                setTextColor(Color.parseColor("#2E7D32"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 12)
            })

            // RMS Threshold
            addView(createSliderRow(
                "RMS Threshold",
                configManager.customRmsThreshold,
                0.005f, 0.025f, 0.001f,
                "%.3f"
            ) { configManager.customRmsThreshold = it })

            // Rise Factor
            addView(createSliderRow(
                "Rise Factor",
                configManager.customRiseFactor,
                1.0f, 3.0f, 0.1f,
                "%.1fx"
            ) { configManager.customRiseFactor = it })

            // Leak K
            addView(createSliderRow(
                "Leak K",
                configManager.customLeakK,
                1.5f, 4.0f, 0.1f,
                "%.1f"
            ) { configManager.customLeakK = it })

            // Leak Margin
            addView(createSliderRow(
                "Leak Margin",
                configManager.customLeakMargin,
                0.001f, 0.010f, 0.001f,
                "%.3f"
            ) { configManager.customLeakMargin = it })

            // Leak Calibration
            addView(createSliderRow(
                "Leak Calib (s)",
                configManager.customLeakCalib,
                0.3f, 2.0f, 0.1f,
                "%.1fs"
            ) { configManager.customLeakCalib = it })

            // VAD Aggr
            addView(createSliderRow(
                "VAD Aggr",
                configManager.customVadAggr.toFloat(),
                0f, 3f, 1f,
                "%.0f"
            ) { configManager.customVadAggr = it.toInt() })

            // Open Consec
            addView(createSliderRow(
                "Open Frames",
                configManager.customOpenConsec.toFloat(),
                1f, 10f, 1f,
                "%.0f"
            ) { configManager.customOpenConsec = it.toInt() })

            // Close Hang
            addView(createSliderRow(
                "Close Frames",
                configManager.customCloseHang.toFloat(),
                1f, 15f, 1f,
                "%.0f"
            ) { configManager.customCloseHang = it.toInt() })

            // Botones de acción
            addView(createCustomActionButtons())
        }
    }

    // ✨ MODIFICADO: Ahora incluye botón de info
    private fun createSliderRow(
        label: String,
        initialValue: Float,
        rangeMin: Float,
        rangeMax: Float,
        step: Float,
        format: String,
        onValueChange: (Float) -> Unit
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)

            // Header con label e icono info
            val headerLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL

                val valueText = TextView(activity).apply {
                    text = "$label: ${format.format(initialValue)}"
                    textSize = 13f
                    setTextColor(Color.parseColor("#212121"))
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                addView(valueText)

                // Botón de información
                addView(Button(activity).apply {
                    text = "ℹ️"
                    textSize = 11f
                    setBackgroundColor(Color.parseColor("#2196F3"))
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(
                        65, 65
                    ).apply {
                        setMargins(8, 0, 0, 0)
                    }
                    setPadding(0, 0, 0, 0)

                    setOnClickListener {
                        showParameterInfo(label)
                    }
                })
            }
            addView(headerLayout)

            val seekBar = SeekBar(activity).apply {
                val maxProgress = ((rangeMax - rangeMin) / step).toInt()
                max = maxProgress
                progress = ((initialValue - rangeMin) / step).toInt()

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = rangeMin + progress * step
                        // Actualizar el TextView en el header
                        val parentLayout = this@apply.parent as? LinearLayout
                        val header = parentLayout?.getChildAt(0) as? LinearLayout
                        val textView = header?.getChildAt(0) as? TextView
                        textView?.text = "$label: ${format.format(value)}"
                        onValueChange(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            addView(seekBar)
        }
    }

    // ✨ NUEVA FUNCIÓN: Muestra información detallada de cada parámetro
    private fun showParameterInfo(label: String) {
        val info = when {
            label.contains("RMS Threshold") -> """
                🎤 Energía RMS mínima (0.0-1.0)
                
                Umbral de energía para considerar voz.
                
                Valores típicos:
                • 0.010 = Muy sensible (capta susurros)
                • 0.012 = ⭐ Equilibrado (oficinas)
                • 0.015 = Alto (solo voz clara)
                • 0.018 = Muy alto (ambientes ruidosos)
                
                🔽 Bajar: Si no detecta voz suave
                🔼 Subir: Si hay muchos falsos positivos
                
                Aprox. en dB:
                • 0.010 ≈ -40 dB
                • 0.012 ≈ -38 dB
                • 0.015 ≈ -36 dB
            """.trimIndent()

            label.contains("Rise Factor") -> """
                📈 Factor de subida vs baseline
                
                Detecta voz solo si hay subida significativa.
                Fórmula: rms > baseline × FACTOR
                
                Valores típicos:
                • 1.3 = Subida del 30% (muy sensible)
                • 1.6 = ⭐ Subida del 60% (equilibrado)
                • 2.0 = Subida del 100% (doble energía)
                • 2.5 = Subida del 150% (muy estricto)
                
                ⚠️ EL MÁS IMPORTANTE para separar voz de ruido
                
                🔽 Bajar: Si hablas con energía constante
                🔼 Subir: En ambientes con ruido variable
                
                Ejemplo:
                Si baseline=0.010 y factor=1.6
                → necesita rms > 0.016 para detectar voz
            """.trimIndent()

            label.contains("Leak K") -> """
                🔊 Multiplicador de desviación estándar
                
                Controla el margen sobre el ruido medio.
                Fórmula: leak_thr = mean + K×std + margin
                
                Valores típicos:
                • 2.0 = Más permisivo
                • 2.5 = ⭐ Equilibrado
                • 3.0 = Más estricto
                • 4.0 = Muy estricto
                
                🔽 Bajar: Si pierdes voz legítima
                🔼 Subir: Si hay ecos/reverberación
                
                Ejemplo de cálculo:
                Si mean=0.008, std=0.002, k=2.5, margin=0.003
                → leak_thr = 0.008 + 2.5×0.002 + 0.003 = 0.016
            """.trimIndent()

            label.contains("Leak Margin") -> """
                🛡️ Margen de seguridad adicional
                
                Margen extra sumado al umbral de fuga.
                Se suma a: mean + K×std + MARGIN
                
                Valores típicos:
                • 0.002 = Bajo (más sensible)
                • 0.003 = ⭐ Equilibrado
                • 0.005 = Alto (más robusto)
                
                🔽 Bajar: Si pierdes voz muy suave
                🔼 Subir: Si hay ecos que se cuelan
                
                ℹ️ Este valor es un "colchón" extra de seguridad
                para compensar variaciones imprevistas.
            """.trimIndent()

            label.contains("Leak Calib") -> """
                🔬 Tiempo de calibración de fuga
                
                Duración de la fase inicial donde el sistema
                aprende el nivel de ruido de los altavoces.
                
                ⚠️ IMPORTANTE: Durante este tiempo NO HABLAR
                
                Valores típicos:
                • 0.5s = Rápido (menos preciso)
                • 0.8s = ⭐ Equilibrado
                • 1.2s = Exhaustivo (más preciso)
                
                Durante la calibración:
                1. Solo reproduce audio por altavoces
                2. Captura micrófono
                3. Calcula estadísticas (media, std)
                4. Define umbral dinámico
                
                🔼 Subir: Para ambientes con ruido variable
            """.trimIndent()

            label.contains("VAD Aggr") -> """
                🎚️ Agresividad del filtro VAD
                
                Controla qué tan estricto es el filtro inicial
                WebRTC VAD (Voice Activity Detection).
                
                Valores:
                • 0 = Muy permisivo (acepta casi todo)
                • 1 = Permisivo (acepta ruidos suaves)
                • 2 = ⭐ Equilibrado (RECOMENDADO)
                • 3 = Muy estricto (solo voz clara)
                
                🔽 Bajar a 1: Si pierdes palabras suaves
                🔼 Subir a 3: Si detecta falsos positivos
                
                ℹ️ Este es el PRIMER check del triple check.
                Debe pasar este filtro + leak + rise.
            """.trimIndent()

            label.contains("Open Frames") -> """
                ⏱️ Frames para ABRIR segmento de voz
                
                Frames consecutivos con voz necesarios
                para considerar que empezó a hablar.
                
                Cada frame ≈ 11.6ms
                
                Valores típicos:
                • 3 frames = ~35ms (más rápido)
                • 4 frames = ~46ms ⭐ (equilibrado)
                • 6 frames = ~70ms (más robusto)
                
                🔽 Bajar: Más velocidad, más falsos positivos
                🔼 Subir: Más lento, más confiable
                
                Impacto en latencia:
                • 3 frames → latencia mínima ~35ms
                • 6 frames → latencia mínima ~70ms
            """.trimIndent()

            label.contains("Close Frames") -> """
                ⏱️ Frames para CERRAR segmento de voz
                
                Frames consecutivos SIN voz necesarios
                para considerar que dejaste de hablar.
                
                Valores típicos:
                • 5 frames = ~58ms (corta rápido)
                • 6 frames = ~70ms ⭐ (equilibrado)
                • 8 frames = ~93ms (tolera pausas)
                
                🔽 Bajar: Puede fragmentar palabras
                🔼 Subir: Tolera mejor pausas naturales
                
                Ejemplo:
                Si close=6 y haces una pausa de 50ms
                → el segmento NO se cierra (50 < 70ms)
            """.trimIndent()

            else -> "Información no disponible para este parámetro."
        }

        android.app.AlertDialog.Builder(activity)
            .setTitle("ℹ️ $label")
            .setMessage(info)
            .setPositiveButton("Entendido") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun createCustomActionButtons(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)

            addView(Button(activity).apply {
                text = "💾 Aplicar Custom"
                textSize = 13f
                setBackgroundColor(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(0, 0, 4, 0) }
                setOnClickListener {
                    activity.applyCustomSettings()
                }
            })

            addView(Button(activity).apply {
                text = "🔄 Resetear"
                textSize = 13f
                setBackgroundColor(Color.parseColor("#FF9800"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(4, 0, 0, 0) }
                setOnClickListener {
                    configManager.resetToDefaults()
                    Toast.makeText(activity, "✅ Reseteado a valores OFFICE", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun createControlButtons(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)

            btnPlayTest = Button(activity).apply {
                text = "▶️ INICIAR TEST"
                textSize = 16f
                setBackgroundColor(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
                setPadding(24, 32, 24, 32)
                isEnabled = false

                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(0, 0, 8, 0) }

                setOnClickListener {
                    activity.startBargeInTest()
                }
            }

            btnStopTest = Button(activity).apply {
                text = "⏹️ DETENER"
                textSize = 16f
                setBackgroundColor(Color.parseColor("#F44336"))
                setTextColor(Color.WHITE)
                setPadding(24, 32, 24, 32)
                isEnabled = false

                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(8, 0, 0, 0) }

                setOnClickListener {
                    activity.stopBargeInTest()
                }
            }

            addView(btnPlayTest)
            addView(btnStopTest)
        }
    }

    fun updateAudioVisualizer(metrics: BargeInMetrics) {
        val vadMetrics = metrics.vadMetrics ?: return

        val totalFrames = vadMetrics.framesProcessed
        if (totalFrames == 0L) return

        val avgConfidence = vadMetrics.averageConfidence
        val estimatedDb = -40f + (avgConfidence * 30f)
        val barProgress = ((estimatedDb + 60f) / 60f * 100f).toInt().coerceIn(0, 100)

        audioLevelBar.progress = barProgress

        val icon = when {
            barProgress > 70 -> "🔊"
            barProgress > 30 -> "🔉"
            else -> "🔇"
        }

        audioLevelText.text = "$icon Audio: %.1f dB | Confianza: %.2f".format(estimatedDb, avgConfidence)

        val voiceRatio = vadMetrics.voiceFrames.toFloat() / totalFrames
        val stateEmoji = when {
            metrics.isPlaying && metrics.isListening -> "🎵"
            metrics.isListening -> "🎤"
            else -> "💤"
        }

        frequencyInfoText.text = """
            📊 Frames: ${vadMetrics.framesProcessed} | Voz: ${vadMetrics.voiceFrames} (%.1f%%)
            ⏱️ Proc: ${vadMetrics.averageProcessingTimeUs}µs/frame
            🎯 Estado: $stateEmoji ${if (metrics.isPlaying) "PLAYING" else "STOPPED"}
        """.trimIndent().format(voiceRatio * 100)

        updateDiagnostics(metrics, estimatedDb, avgConfidence)
    }

    private fun updateDiagnostics(metrics: BargeInMetrics, currentDb: Float, confidence: Float) {
        val config = configManager.getConfigForCurrentPreset()

        // Simular valores (en producción vendrían del VAD)
        val currentRms = 0.012f // TODO: obtener del VAD
        val emaBaseline = 0.009f // TODO: obtener del VAD
        val leakMean = 0.008f // TODO: obtener de calibración
        val leakStd = 0.001f // TODO: obtener de calibración
        val leakThr = leakMean + config.leakK * leakStd + config.leakMargin

        // Triple check
        val vadOk = currentRms >= config.rmsThreshold
        val leakOk = currentRms >= leakThr
        val riseOk = currentRms > (emaBaseline * config.riseFactor)
        val isVoice = vadOk && leakOk && riseOk

        val diagnostic = buildString {
            appendLine("═══════════════════════════════")
            appendLine(if (isVoice) "✅ VOZ DETECTADA" else "❌ VOZ RECHAZADA")
            appendLine("═══════════════════════════════")
            appendLine()

            appendLine("🎚️ MÉTODO PYTHON - TRIPLE CHECK:")
            appendLine()

            appendLine("${if (vadOk) "✅" else "❌"} VAD Check (RMS ≥ threshold):")
            appendLine("   RMS actual: ${"%.4f".format(currentRms)}")
            appendLine("   RMS threshold: ${"%.4f".format(config.rmsThreshold)}")
            appendLine()

            appendLine("${if (leakOk) "✅" else "❌"} Leak Check (RMS ≥ leak_thr):")
            appendLine("   Leak mean: ${"%.4f".format(leakMean)}")
            appendLine("   Leak std: ${"%.4f".format(leakStd)}")
            appendLine("   Leak threshold: ${"%.4f".format(leakThr)}")
            appendLine("   (mean + ${config.leakK}×std + ${config.leakMargin})")
            appendLine()

            appendLine("${if (riseOk) "✅" else "❌"} Rise Check (RMS > baseline × factor):")
            appendLine("   EMA baseline: ${"%.4f".format(emaBaseline)}")
            appendLine("   Rise factor: ${"%.1f".format(config.riseFactor)}x")
            appendLine("   Required: ${"%.4f".format(emaBaseline * config.riseFactor)}")
            appendLine()

            appendLine("───────────────────────────────")
            appendLine("⚙️ Configuración actual:")
            appendLine("   Preset: ${configManager.currentPreset}")
            appendLine("   VAD Aggr: ${config.vadAggr}")
            appendLine("   Open/Close: ${config.openConsec}/${config.closeHang} frames")
            appendLine("   Leak calib: ${config.leakCalib}s")
            appendLine("   Auto resume: ${config.autoResume}s")
        }

        diagnosticText.text = diagnostic

        diagnosticText.setBackgroundColor(
            when {
                isVoice -> Color.parseColor("#C8E6C9") // Verde: voz detectada
                else -> Color.parseColor("#FFCCBC") // Rojo: voz rechazada
            }
        )
    }

    fun showReady(preset: PythonVadPreset) {
        val presetInfo = when (preset) {
            PythonVadPreset.QUIET -> "🔵 QUIET"
            PythonVadPreset.OFFICE -> "🟢 OFFICE"
            PythonVadPreset.NOISY -> "🔴 NOISY"
            PythonVadPreset.CUSTOM -> "⚙️ CUSTOM"
        }

        statusText.text = """
            ✅ Sistema listo @ 44.1kHz
            
            Preset: $presetInfo
            Método: Python (Leak + Rise)
            🎤 Micrófono: LISTO (inactivo)
            
            Presiona "INICIAR TEST" para comenzar
        """.trimIndent()

        diagnosticText.text = "Esperando inicio de test..."
        diagnosticText.setBackgroundColor(Color.parseColor("#FFFFFF"))
        updatePresetButtons()
    }

    fun showTestRunning(preset: PythonVadPreset, description: String) {
        statusText.text = """
            🎵 REPRODUCIENDO AUDIO @ 44.1kHz
            
            ¡Interrumpe hablando FUERTE!
            
            🎤 Micrófono: ACTIVO
            📊 Preset: ${preset.name}
            🐍 $description
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

    fun showPresetChanged(preset: PythonVadPreset, description: String) {
        val presetName = when (preset) {
            PythonVadPreset.QUIET -> "🔵 QUIET"
            PythonVadPreset.OFFICE -> "🟢 OFFICE"
            PythonVadPreset.NOISY -> "🔴 NOISY"
            PythonVadPreset.CUSTOM -> "⚙️ CUSTOM"
        }

        statusText.text = """
            ✅ Preset cambiado @ 44.1kHz
            
            $presetName
            $description
            
            🎤 Sistema listo (inactivo)
            Presiona "INICIAR TEST" para probar
        """.trimIndent()

        updatePresetButtons()

        // Mostrar/ocultar controles custom
        val customControls = (statusText.parent as? LinearLayout)?.getChildAt(5) as? LinearLayout
        customControls?.visibility = if (preset == PythonVadPreset.CUSTOM) {
            LinearLayout.VISIBLE
        } else {
            LinearLayout.GONE
        }
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
            🎉 INTERRUPCIÓN EXITOSA (Método Python)
            
            Energía detectada: %.1f dB
            Confianza final: %.0f%%
            Latencia: %.0f ms
            
            Triple check superado:
            ✅ VAD check: RMS ≥ threshold
            ✅ Leak check: RMS ≥ leak_thr
            ✅ Rise check: RMS > baseline × factor
        """.trimIndent().format(event.energyDb, event.confidence * 100, event.latencyMs)

        diagnosticText.setBackgroundColor(Color.parseColor("#C8E6C9"))
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

    fun updatePresetButtons() {
        presetButtons.forEach { (preset, button) ->
            if (preset == configManager.currentPreset) {
                button.alpha = 1.0f
                button.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                button.alpha = 0.6f
                button.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }
}