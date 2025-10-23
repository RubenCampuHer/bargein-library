package com.aima.bargein.demo

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.*

class PresetDialog(
    private val activity: TestActivity,
    private val configManager: ConfigManager,
    private val presetManager: PresetManager
) {

    companion object {
        private const val TAG = "PresetDialog"
    }

    fun showSave() {
        val input = EditText(activity).apply {
            hint = "Nombre del preset (ej: Volumen Alto)"
            setPadding(40, 20, 40, 20)
            setText(generatePresetName())
            selectAll()
        }

        AlertDialog.Builder(activity)
            .setTitle("💾 Guardar Preset")
            .setMessage("Guardará la configuración Custom actual")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()

                when {
                    name.isEmpty() -> {
                        Toast.makeText(activity, "❌ El nombre no puede estar vacío", Toast.LENGTH_SHORT).show()
                    }
                    name.length > 30 -> {
                        Toast.makeText(activity, "❌ Nombre demasiado largo (máx 30)", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        val preset = configManager.createCurrentPreset(name)
                        val existed = presetManager.presetExists(name)

                        if (presetManager.savePreset(preset)) {
                            val message = if (existed) {
                                "✅ Preset '$name' actualizado"
                            } else {
                                "✅ Preset '$name' guardado"
                            }
                            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                            Log.i(TAG, message)
                        } else {
                            Toast.makeText(activity, "❌ Error guardando preset", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun showLoad() {
        val presets = presetManager.getAllPresets()

        if (presets.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("📂 Cargar Preset")
                .setMessage("No hay presets guardados aún.\n\nPrimero ajusta los parámetros en modo Custom y luego usa '💾 Guardar'.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val scrollView = ScrollView(activity)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        presets.forEach { preset ->
            layout.addView(createPresetCard(preset))
        }

        scrollView.addView(layout)

        AlertDialog.Builder(activity)
            .setTitle("📂 Cargar Preset (${presets.size})")
            .setView(scrollView)
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("🗑️ Gestionar") { _, _ ->
                showManage()
            }
            .show()
    }

    private fun createPresetCard(preset: PresetManager.VadPreset): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }

            addView(TextView(activity).apply {
                text = "📌 ${preset.name}"
                textSize = 16f
                setTextColor(Color.parseColor("#1B5E20"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })

            addView(TextView(activity).apply {
                text = preset.getDescription()
                textSize = 12f
                setTextColor(Color.parseColor("#2E7D32"))
                setPadding(0, 4, 0, 8)
            })

            addView(TextView(activity).apply {
                val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    .format(Date(preset.timestamp))
                text = "🕐 $date"
                textSize = 11f
                setTextColor(Color.parseColor("#558B2F"))
                setPadding(0, 0, 0, 8)
            })

            addView(Button(activity).apply {
                text = "✅ Cargar este preset"
                setBackgroundColor(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    loadPreset(preset)
                    (parent?.parent?.parent as? AlertDialog)?.dismiss()
                }
            })
        }
    }

    private fun loadPreset(preset: PresetManager.VadPreset) {
        configManager.loadPreset(preset)

        if (configManager.currentMode != SensitivityMode.CUSTOM) {
            activity.changeSensitivityMode(SensitivityMode.CUSTOM)
        }

        Toast.makeText(activity, "✅ Preset '${preset.name}' cargado", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "📂 Preset '${preset.name}' loaded")
    }

    private fun showManage() {
        val presets = presetManager.getAllPresets()

        if (presets.isEmpty()) {
            Toast.makeText(activity, "No hay presets para gestionar", Toast.LENGTH_SHORT).show()
            return
        }

        val presetNames = presets.map { it.name }.toTypedArray()
        val checkedItems = BooleanArray(presets.size) { false }

        AlertDialog.Builder(activity)
            .setTitle("🗑️ Gestionar Presets")
            .setMultiChoiceItems(presetNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("🗑️ Eliminar seleccionados") { _, _ ->
                val selectedCount = checkedItems.count { it }

                if (selectedCount == 0) {
                    Toast.makeText(activity, "No se seleccionó ningún preset", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                AlertDialog.Builder(activity)
                    .setTitle("⚠️ Confirmar")
                    .setMessage("¿Eliminar $selectedCount preset(s)?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        var deletedCount = 0
                        presets.forEachIndexed { index, preset ->
                            if (checkedItems[index]) {
                                if (presetManager.deletePreset(preset.name)) {
                                    deletedCount++
                                }
                            }
                        }
                        Toast.makeText(activity, "✅ $deletedCount preset(s) eliminado(s)", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNeutralButton("📤 Exportar todos") { _, _ ->
                exportPresets()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun exportPresets() {
        val json = presetManager.exportPresetsAsJson()
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Barge-In Presets", json)
        clipboard.setPrimaryClip(clip)

        val count = presetManager.getPresetCount()

        AlertDialog.Builder(activity)
            .setTitle("📤 Presets Exportados")
            .setMessage("$count preset(s) copiados al portapapeles en formato JSON.")
            .setPositiveButton("OK", null)
            .setNeutralButton("📥 Importar") { _, _ ->
                showImport()
            }
            .show()

        Toast.makeText(activity, "✅ Presets copiados al portapapeles", Toast.LENGTH_LONG).show()
    }

    private fun showImport() {
        val input = EditText(activity).apply {
            hint = "Pega aquí el JSON de presets"
            setPadding(40, 20, 40, 20)
            minLines = 5
            maxLines = 10
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        AlertDialog.Builder(activity)
            .setTitle("📥 Importar Presets")
            .setMessage("Pega el JSON exportado previamente:")
            .setView(input)
            .setPositiveButton("Importar") { _, _ ->
                val json = input.text.toString().trim()

                when {
                    json.isEmpty() -> {
                        Toast.makeText(activity, "❌ No se pegó ningún contenido", Toast.LENGTH_SHORT).show()
                    }
                    presetManager.importPresetsFromJson(json) -> {
                        val count = presetManager.getPresetCount()
                        Toast.makeText(activity, "✅ Presets importados. Total: $count", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        Toast.makeText(activity, "❌ Error: JSON inválido", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun generatePresetName(): String {
        val deltaStr = configManager.customDeltaVoiceThresholdDb.toInt()
        val factorStr = (configManager.customDeltaBaselineAdjustmentFactor * 100).toInt()
        return "Config Δ${deltaStr} F${factorStr}"
    }
}