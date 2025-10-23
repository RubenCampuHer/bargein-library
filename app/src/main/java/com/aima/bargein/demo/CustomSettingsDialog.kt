package com.aima.bargein.demo

import android.graphics.Color
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog

class CustomSettingsDialog(
    private val activity: TestActivity,
    private val configManager: ConfigManager
) {

    companion object {
        private const val TAG = "CustomSettingsDialog"
    }

    data class ParamConfig(
        val name: String,
        val getValue: () -> Float,
        val setValue: (Float) -> Unit,
        val min: Float,
        val max: Float,
        val format: String
    )

    // ✅ Guardar referencias a los sliders y labels
    private val seekBars = mutableListOf<Pair<TextView, SeekBar>>()
    private val params = mutableListOf<ParamConfig>()

    fun show() {
        val dialogLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // ✅ Crear botones de copia PRIMERO (antes de los sliders)
        dialogLayout.addView(createCopyButtons())

        // Crear lista de parámetros
        params.clear()
        params.addAll(listOf(
            ParamConfig("Delta Threshold (dB)",
                { configManager.customDeltaVoiceThresholdDb },
                { configManager.customDeltaVoiceThresholdDb = it },
                8f, 30f, "%.1f"),
            ParamConfig("Min Energy (dB)",
                { configManager.customMinAbsoluteVoiceEnergyDb },
                { configManager.customMinAbsoluteVoiceEnergyDb = it },
                -35f, -10f, "%.1f"),
            ParamConfig("Max ZCR",
                { configManager.customMaxZcrForVoice },
                { configManager.customMaxZcrForVoice = it },
                0.08f, 0.30f, "%.2f"),
            ParamConfig("Baseline Factor",
                { configManager.customDeltaBaselineAdjustmentFactor },
                { configManager.customDeltaBaselineAdjustmentFactor = it },
                0.5f, 0.98f, "%.2f"),
            ParamConfig("Min Duration (ms)",
                { configManager.customMinVoiceDurationMs.toFloat() },
                { configManager.customMinVoiceDurationMs = it.toLong() },
                20f, 150f, "%.0f"),
            ParamConfig("Confidence",
                { configManager.customVoiceConfidenceThreshold },
                { configManager.customVoiceConfidenceThreshold = it },
                0.40f, 0.95f, "%.2f"),
            ParamConfig("Calibration (ms)",
                { configManager.customCalibrationDurationMs.toFloat() },
                { configManager.customCalibrationDurationMs = it.toLong() },
                100f, 500f, "%.0f"),
            ParamConfig("Pre-Delay (ms)",
                { configManager.customPreDelayMs.toFloat() },
                { configManager.customPreDelayMs = it.toLong() },
                200f, 500f, "%.0f")
        ))

        seekBars.clear()

        params.forEach { param ->
            val label = TextView(activity).apply {
                text = "${param.name}: ${String.format(param.format, param.getValue())}"
                textSize = 14f
                setTextColor(Color.parseColor("#212121"))
                setPadding(0, 16, 0, 8)
            }
            dialogLayout.addView(label)

            val seekBar = SeekBar(activity).apply {
                max = 100
                val currentValue = param.getValue()
                val normalized = ((currentValue - param.min) / (param.max - param.min) * 100).toInt()
                progress = normalized

                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = param.min + (progress / 100f) * (param.max - param.min)
                        label.text = "${param.name}: ${String.format(param.format, value)}"
                    }
                    override fun onStartTrackingTouch(seek: SeekBar?) {}
                    override fun onStopTrackingTouch(seek: SeekBar?) {}
                })
            }
            dialogLayout.addView(seekBar)
            seekBars.add(Pair(label, seekBar))
        }

        dialogLayout.addView(createResetButton())
        dialogLayout.addView(createInfoText())

        val scrollView = ScrollView(activity).apply {
            addView(dialogLayout)
        }

        AlertDialog.Builder(activity)
            .setTitle("⚙️ Configuración Custom")
            .setView(scrollView)
            .setPositiveButton("💾 Guardar y Aplicar") { _, _ ->
                // Aplicar valores desde seekbars
                params.forEachIndexed { index, param ->
                    val progress = seekBars[index].second.progress
                    val value = param.min + (progress / 100f) * (param.max - param.min)
                    param.setValue(value)

                    Log.i(TAG, "   ${param.name}: ${String.format(param.format, value)}")
                }

                configManager.saveSettings()
                activity.applyCustomSettings()

                Toast.makeText(activity, "✅ Configuración guardada y aplicada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("❌ Cancelar", null)
            .show()
    }

    private fun createCopyButtons(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.parseColor("#E3F2FD"))

            addView(TextView(activity).apply {
                text = "📋 Copiar valores desde:"
                textSize = 13f
                setTextColor(Color.parseColor("#1976D2"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 8)
            })

            val buttonRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            buttonRow.addView(createSmallButton("🔴 Super") {
                copyValuesFrom(SensitivityMode.SUPER_SENSITIVE)
            })

            buttonRow.addView(createSmallButton("🟡 Sensible") {
                copyValuesFrom(SensitivityMode.SENSITIVE)
            })

            buttonRow.addView(createSmallButton("🟢 Normal") {
                copyValuesFrom(SensitivityMode.NORMAL)
            })

            addView(buttonRow)

            addView(TextView(activity).apply {
                text = "\n💡 Los sliders se actualizarán automáticamente"
                textSize = 11f
                setTextColor(Color.parseColor("#757575"))
                gravity = android.view.Gravity.CENTER
            })
        }
    }

    // ✅ NUEVO: Copiar valores y actualizar sliders en tiempo real
    private fun copyValuesFrom(mode: SensitivityMode) {
        // Copiar valores al configManager
        when (mode) {
            SensitivityMode.SUPER_SENSITIVE -> configManager.copyFromSuperSensitive()
            SensitivityMode.SENSITIVE -> configManager.copyFromSensitive()
            SensitivityMode.NORMAL -> configManager.copyFromNormal()
            else -> return
        }

        // ✅ Actualizar TODOS los sliders con los nuevos valores
        params.forEachIndexed { index, param ->
            val (label, seekBar) = seekBars[index]
            val newValue = param.getValue()

            // Calcular nueva posición del slider
            val normalized = ((newValue - param.min) / (param.max - param.min) * 100).toInt()

            // Actualizar slider y label
            seekBar.progress = normalized
            label.text = "${param.name}: ${String.format(param.format, newValue)}"
        }

        val modeName = when (mode) {
            SensitivityMode.SUPER_SENSITIVE -> "🔴 Super Sensitive"
            SensitivityMode.SENSITIVE -> "🟡 Sensitive"
            SensitivityMode.NORMAL -> "🟢 Normal"
            else -> ""
        }

        Toast.makeText(
            activity,
            "✅ Valores copiados de $modeName\n¡Los sliders se han actualizado!",
            Toast.LENGTH_SHORT
        ).show()

        Log.i(TAG, "📋 Values copied from $mode and sliders updated")
    }

    private fun createSmallButton(text: String, onClick: () -> Unit): Button {
        return Button(activity).apply {
            this.text = text
            textSize = 11f
            setPadding(8, 16, 8, 16)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createResetButton(): Button {
        return Button(activity).apply {
            text = "🔄 Restaurar Valores por Defecto"
            setOnClickListener {
                configManager.resetToDefaults()

                // ✅ Actualizar sliders después de resetear
                params.forEachIndexed { index, param ->
                    val (label, seekBar) = seekBars[index]
                    val value = param.getValue()
                    val normalized = ((value - param.min) / (param.max - param.min) * 100).toInt()
                    seekBar.progress = normalized
                    label.text = "${param.name}: ${String.format(param.format, value)}"
                }

                Toast.makeText(activity, "✅ Valores restaurados a defaults", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createInfoText(): TextView {
        return TextView(activity).apply {
            text = """
                
                ℹ️ Guía Rápida:
                • Delta ↑ = Más duro (evita eco del altavoz)
                • Min Energy ↑ = Requiere voz más fuerte
                • Max ZCR ↓ = Más selectivo
                • Factor ↑ = CLAVE para evitar eco (0.92-0.96)
                • Duration ↑ = Más lento pero seguro
                • Confidence ↑ = Más exigente
                
                🔊 Para evitar autodetección:
                   Factor alto (>0.90) es lo más importante
            """.trimIndent()
            textSize = 11f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, 16, 0, 0)
        }
    }
}
