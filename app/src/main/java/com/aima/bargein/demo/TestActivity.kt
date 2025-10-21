package com.aima.bargein.demo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aima.bargein.BargeInConfig
import com.aima.bargein.BargeInEngine
import com.aima.bargein.BargeInError
import com.aima.bargein.BargeInEvent
import com.aima.bargein.BargeInListener
import com.aima.bargein.BargeInState
import com.aima.bargein.vad.IVoiceActivityDetector
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

class TestActivity : AppCompatActivity(), BargeInListener {

    private lateinit var engine: BargeInEngine

    // UI Components
    private lateinit var statusText: TextView
    private lateinit var audioLevelText: TextView
    private lateinit var audioLevelBar: ProgressBar
    private lateinit var frequencyInfoText: TextView
    private lateinit var btnPlayTest: Button
    private lateinit var btnStopTest: Button
    private lateinit var btnModeSuperSensitive: Button
    private lateinit var btnModeSensible: Button
    private lateinit var btnModeNormal: Button
    private lateinit var btnModeCustom: Button
    private lateinit var btnEditCustom: Button
    private lateinit var btnSavePreset: Button
    private lateinit var btnLoadPreset: Button

    private var wavFile: File? = null
    private var isTestRunning = false

    // Modos con CUSTOM
    private enum class SensitivityMode {
        SUPER_SENSITIVE,
        SENSITIVE,
        NORMAL,
        CUSTOM
    }

    private var currentMode = SensitivityMode.SENSITIVE

    // Parámetros custom editables
    private var customDeltaVoiceThresholdDb = 13f
    private var customMinAbsoluteVoiceEnergyDb = -26f
    private var customMaxZcrForVoice = 0.18f
    private var customDeltaBaselineAdjustmentFactor = 0.72f
    private var customMinVoiceDurationMs = 48L
    private var customVoiceConfidenceThreshold = 0.56f
    private var customCalibrationDurationMs = 200L
    private var customPreDelayMs = 350L

    private val handler = Handler(Looper.getMainLooper())

    // SharedPreferences para persistencia
    private val prefs by lazy {
        getSharedPreferences("BargeInCustomPrefs", Context.MODE_PRIVATE)
    }

    // ✅ NUEVO: Gestor de presets
    private lateinit var presetManager: PresetManager

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100

        // Claves para SharedPreferences
        private const val PREF_CURRENT_MODE = "currentMode"
        private const val PREF_DELTA_THRESHOLD = "deltaVoiceThresholdDb"
        private const val PREF_MIN_ENERGY = "minAbsoluteVoiceEnergyDb"
        private const val PREF_MAX_ZCR = "maxZcrForVoice"
        private const val PREF_BASELINE_FACTOR = "deltaBaselineAdjustmentFactor"
        private const val PREF_MIN_DURATION = "minVoiceDurationMs"
        private const val PREF_CONFIDENCE = "voiceConfidenceThreshold"
        private const val PREF_CALIBRATION_TIME = "calibrationDurationMs"
        private const val PREF_PRE_DELAY = "preDelayMs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.plant(Timber.DebugTree())
        Timber.i("🚀 TestActivity started @ 44.1kHz - Custom Mode + Presets")

        // ✅ Inicializar PresetManager
        presetManager = PresetManager(this)

        // Cargar configuración guardada ANTES de setupUI
        loadCustomSettings()

        setupUI()
        checkPermissions()
    }

    /**
     * ✅ Carga configuración guardada desde SharedPreferences
     */
    private fun loadCustomSettings() {
        customDeltaVoiceThresholdDb = prefs.getFloat(PREF_DELTA_THRESHOLD, 13f)
        customMinAbsoluteVoiceEnergyDb = prefs.getFloat(PREF_MIN_ENERGY, -26f)
        customMaxZcrForVoice = prefs.getFloat(PREF_MAX_ZCR, 0.18f)
        customDeltaBaselineAdjustmentFactor = prefs.getFloat(PREF_BASELINE_FACTOR, 0.72f)
        customMinVoiceDurationMs = prefs.getLong(PREF_MIN_DURATION, 48L)
        customVoiceConfidenceThreshold = prefs.getFloat(PREF_CONFIDENCE, 0.56f)
        customCalibrationDurationMs = prefs.getLong(PREF_CALIBRATION_TIME, 200L)
        customPreDelayMs = prefs.getLong(PREF_PRE_DELAY, 350L)

        // Cargar modo guardado
        val savedMode = prefs.getString(PREF_CURRENT_MODE, "SENSITIVE") ?: "SENSITIVE"
        currentMode = try {
            SensitivityMode.valueOf(savedMode)
        } catch (e: Exception) {
            SensitivityMode.SENSITIVE
        }

        Timber.i("📥 Settings loaded: Mode=$currentMode, Delta=${customDeltaVoiceThresholdDb}dB")
    }

    /**
     * ✅ Guarda configuración actual en SharedPreferences
     */
    private fun saveCustomSettings() {
        prefs.edit().apply {
            putString(PREF_CURRENT_MODE, currentMode.name)
            putFloat(PREF_DELTA_THRESHOLD, customDeltaVoiceThresholdDb)
            putFloat(PREF_MIN_ENERGY, customMinAbsoluteVoiceEnergyDb)
            putFloat(PREF_MAX_ZCR, customMaxZcrForVoice)
            putFloat(PREF_BASELINE_FACTOR, customDeltaBaselineAdjustmentFactor)
            putLong(PREF_MIN_DURATION, customMinVoiceDurationMs)
            putFloat(PREF_CONFIDENCE, customVoiceConfidenceThreshold)
            putLong(PREF_CALIBRATION_TIME, customCalibrationDurationMs)
            putLong(PREF_PRE_DELAY, customPreDelayMs)
            apply()
        }

        Timber.i("💾 Settings saved: Mode=$currentMode, Delta=${customDeltaVoiceThresholdDb}dB")
    }

    /**
     * ✅ Guarda el estado antes de destruir la actividad (por rotación)
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Guardar configuración actual
        saveCustomSettings()

        // Guardar estado del test
        outState.putBoolean("isTestRunning", isTestRunning)
        outState.putString("currentMode", currentMode.name)

        Timber.d("💾 State saved for rotation")
    }

    /**
     * ✅ Restaura el estado después de recrear la actividad (por rotación)
     */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        // Cargar configuración
        loadCustomSettings()

        // Restaurar modo
        val savedMode = savedInstanceState.getString("currentMode", "SENSITIVE")
        currentMode = try {
            SensitivityMode.valueOf(savedMode)
        } catch (e: Exception) {
            SensitivityMode.SENSITIVE
        }

        updateModeButtons()

        Timber.d("📥 State restored after rotation")
    }

    private fun setupUI() {
        val scrollView = ScrollView(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }

        // ===== TÍTULO =====
        layout.addView(TextView(this).apply {
            text = "🎤 Barge-In Live Monitor"
            textSize = 24f
            setTextColor(Color.parseColor("#1976D2"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        layout.addView(TextView(this).apply {
            text = "@ 44.1kHz • Adaptive Δ Detection • Presets"
            textSize = 14f
            setTextColor(Color.parseColor("#757575"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })

        // ===== MEDIDOR DE NIVEL DE AUDIO =====
        val audioContainer = createCard()

        audioLevelText = TextView(this).apply {
            text = "🔇 Inicializando @ 44.1kHz..."
            textSize = 16f
            setTextColor(Color.parseColor("#212121"))
            setPadding(0, 0, 0, 12)
        }

        audioLevelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                60
            ).apply {
                setMargins(0, 0, 0, 12)
            }
        }

        val metricsText = TextView(this).apply {
            text = "Esperando datos..."
            textSize = 13f
            setTextColor(Color.parseColor("#616161"))
            setPadding(0, 0, 0, 8)
        }

        val infoText = TextView(this).apply {
            text = """
                Análisis @ 44.1kHz:
                • High-Pass: 600Hz (preserva voz)
                • Delta adaptativo según volumen
                • Calibración dinámica
                • Sistema de Presets
            """.trimIndent()
            textSize = 12f
            setTextColor(Color.parseColor("#757575"))
        }

        frequencyInfoText = metricsText

        audioContainer.addView(audioLevelText)
        audioContainer.addView(audioLevelBar)
        audioContainer.addView(metricsText)
        audioContainer.addView(infoText)
        layout.addView(audioContainer)

        // ===== STATUS =====
        statusText = TextView(this).apply {
            text = "Verificando permisos..."
            textSize = 14f
            setTextColor(Color.parseColor("#424242"))
            gravity = Gravity.CENTER
            setPadding(16, 20, 16, 10)
        }
        layout.addView(statusText)

        // ===== SELECTOR DE MODO =====
        val modeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(Color.parseColor("#FFF3E0"))
        }

        modeContainer.addView(TextView(this).apply {
            text = "🎚️ MODO DE SENSIBILIDAD"
            textSize = 14f
            setTextColor(Color.parseColor("#E65100"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })

        // Primera fila de botones - Modos predefinidos
        val modeButtonsRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnModeSuperSensitive = createModeButton("🔴 Super", Color.parseColor("#F44336")) {
            changeSensitivityMode(SensitivityMode.SUPER_SENSITIVE)
        }

        btnModeSensible = createModeButton("🟡 Sensible", Color.parseColor("#FF9800")) {
            changeSensitivityMode(SensitivityMode.SENSITIVE)
        }

        btnModeNormal = createModeButton("🟢 Normal", Color.parseColor("#4CAF50")) {
            changeSensitivityMode(SensitivityMode.NORMAL)
        }

        modeButtonsRow1.addView(btnModeSuperSensitive)
        modeButtonsRow1.addView(btnModeSensible)
        modeButtonsRow1.addView(btnModeNormal)

        // Segunda fila - Custom
        val modeButtonsRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 0)
            }
        }

        btnModeCustom = createModeButton("⚙️ Custom", Color.parseColor("#9C27B0")) {
            changeSensitivityMode(SensitivityMode.CUSTOM)
        }

        btnEditCustom = createModeButton("✏️ Editar", Color.parseColor("#673AB7")) {
            showCustomSettingsDialog()
        }

        modeButtonsRow2.addView(btnModeCustom)
        modeButtonsRow2.addView(btnEditCustom)

        // ✅ Tercera fila - Presets
        val presetButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 0)
            }
        }

        btnSavePreset = createModeButton("💾 Guardar", Color.parseColor("#00897B")) {
            showSavePresetDialog()
        }

        btnLoadPreset = createModeButton("📂 Cargar", Color.parseColor("#00ACC1")) {
            showLoadPresetDialog()
        }

        presetButtonsRow.addView(btnSavePreset)
        presetButtonsRow.addView(btnLoadPreset)

        modeContainer.addView(modeButtonsRow1)
        modeContainer.addView(modeButtonsRow2)
        modeContainer.addView(presetButtonsRow)

        layout.addView(modeContainer)

        // ===== BOTONES DE CONTROL =====
        btnPlayTest = createButton(
            "▶️ INICIAR TEST (Reproduce Audio)",
            Color.parseColor("#4CAF50"),
            false
        ) {
            startBargeInTest()
        }
        layout.addView(btnPlayTest)

        btnStopTest = createButton(
            "⏹️ DETENER TEST",
            Color.parseColor("#F44336"),
            false
        ) {
            stopBargeInTest()
        }
        layout.addView(btnStopTest)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    // ===== GESTIÓN DE PRESETS =====

    /**
     * ✅ Muestra diálogo para guardar preset actual
     */
    private fun showSavePresetDialog() {
        val input = EditText(this).apply {
            hint = "Nombre del preset (ej: Volumen Alto)"
            setPadding(40, 20, 40, 20)
        }

        // Sugerir nombre basado en parámetros
        val suggestedName = generatePresetName()
        input.setText(suggestedName)
        input.selectAll()

        AlertDialog.Builder(this)
            .setTitle("💾 Guardar Preset")
            .setMessage("Guardará la configuración Custom actual")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()

                if (name.isEmpty()) {
                    Toast.makeText(this, "❌ El nombre no puede estar vacío", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (name.length > 30) {
                    Toast.makeText(this, "❌ Nombre demasiado largo (máx 30 caracteres)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Crear preset con configuración actual
                val preset = PresetManager.VadPreset(
                    name = name,
                    deltaVoiceThresholdDb = customDeltaVoiceThresholdDb,
                    minAbsoluteVoiceEnergyDb = customMinAbsoluteVoiceEnergyDb,
                    maxZcrForVoice = customMaxZcrForVoice,
                    deltaBaselineAdjustmentFactor = customDeltaBaselineAdjustmentFactor,
                    minVoiceDurationMs = customMinVoiceDurationMs,
                    voiceConfidenceThreshold = customVoiceConfidenceThreshold,
                    calibrationDurationMs = customCalibrationDurationMs,
                    preDelayMs = customPreDelayMs
                )

                val existed = presetManager.presetExists(name)

                if (presetManager.savePreset(preset)) {
                    val message = if (existed) {
                        "✅ Preset '$name' actualizado"
                    } else {
                        "✅ Preset '$name' guardado"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    Timber.i(message)
                } else {
                    Toast.makeText(this, "❌ Error guardando preset", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * ✅ Genera nombre sugerido basado en parámetros
     */
    private fun generatePresetName(): String {
        val deltaStr = customDeltaVoiceThresholdDb.toInt()
        val factorStr = (customDeltaBaselineAdjustmentFactor * 100).toInt()
        return "Config Δ${deltaStr} F${factorStr}"
    }

    /**
     * ✅ Muestra diálogo para cargar preset guardado
     */
    private fun showLoadPresetDialog() {
        val presets = presetManager.getAllPresets()

        if (presets.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("📂 Cargar Preset")
                .setMessage("No hay presets guardados aún.\n\nPrimero ajusta los parámetros en modo Custom y luego usa '💾 Guardar'.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Crear layout para la lista
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        presets.forEach { preset ->
            val presetCard = createPresetCard(preset)
            layout.addView(presetCard)
        }

        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("📂 Cargar Preset (${presets.size})")
            .setView(scrollView)
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("🗑️ Gestionar") { _, _ ->
                showManagePresetsDialog()
            }
            .show()
    }

    /**
     * ✅ Crea tarjeta visual para un preset
     */
    private fun createPresetCard(preset: PresetManager.VadPreset): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 12)
            }

            // Nombre
            addView(TextView(this@TestActivity).apply {
                text = "📌 ${preset.name}"
                textSize = 16f
                setTextColor(Color.parseColor("#1B5E20"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })

            // Descripción
            addView(TextView(this@TestActivity).apply {
                text = preset.getDescription()
                textSize = 12f
                setTextColor(Color.parseColor("#2E7D32"))
                setPadding(0, 4, 0, 8)
            })

            // Fecha
            addView(TextView(this@TestActivity).apply {
                val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(preset.timestamp))
                text = "🕐 $date"
                textSize = 11f
                setTextColor(Color.parseColor("#558B2F"))
                setPadding(0, 0, 0, 8)
            })

            // Botón cargar
            addView(Button(this@TestActivity).apply {
                text = "✅ Cargar este preset"
                setBackgroundColor(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    loadPresetConfiguration(preset)

                    // Cerrar el diálogo actual
                    (parent?.parent?.parent as? AlertDialog)?.dismiss()
                }
            })
        }
    }

    /**
     * ✅ Carga configuración desde preset
     */
    private fun loadPresetConfiguration(preset: PresetManager.VadPreset) {
        customDeltaVoiceThresholdDb = preset.deltaVoiceThresholdDb
        customMinAbsoluteVoiceEnergyDb = preset.minAbsoluteVoiceEnergyDb
        customMaxZcrForVoice = preset.maxZcrForVoice
        customDeltaBaselineAdjustmentFactor = preset.deltaBaselineAdjustmentFactor
        customMinVoiceDurationMs = preset.minVoiceDurationMs
        customVoiceConfidenceThreshold = preset.voiceConfidenceThreshold
        customCalibrationDurationMs = preset.calibrationDurationMs
        customPreDelayMs = preset.preDelayMs

        // Guardar como configuración actual
        saveCustomSettings()

        // Marcar como último usado
        presetManager.setLastUsedPreset(preset.name)

        // Cambiar a modo Custom
        if (currentMode != SensitivityMode.CUSTOM) {
            changeSensitivityMode(SensitivityMode.CUSTOM)
        } else if (::engine.isInitialized && !isTestRunning) {
            // Si ya estaba en Custom, solo reiniciar engine
            try {
                engine.release()
                initializeEngine()

                statusText.text = """
                    ✅ Preset '${preset.name}' cargado
                    
                    ${preset.getDescription()}
                    
                    🎤 Sistema listo (inactivo)
                    Presiona "INICIAR TEST" para probar
                """.trimIndent()
            } catch (e: Exception) {
                Timber.e(e, "Error reinitializing with preset")
            }
        }

        Toast.makeText(this, "✅ Preset '${preset.name}' cargado", Toast.LENGTH_SHORT).show()
        Timber.i("📂 Preset '${preset.name}' loaded")
    }

    /**
     * ✅ Muestra diálogo para gestionar (eliminar/exportar) presets
     */
    private fun showManagePresetsDialog() {
        val presets = presetManager.getAllPresets()

        if (presets.isEmpty()) {
            Toast.makeText(this, "No hay presets para gestionar", Toast.LENGTH_SHORT).show()
            return
        }

        val presetNames = presets.map { it.name }.toTypedArray()
        val checkedItems = BooleanArray(presets.size) { false }

        AlertDialog.Builder(this)
            .setTitle("🗑️ Gestionar Presets")
            .setMultiChoiceItems(presetNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("🗑️ Eliminar seleccionados") { _, _ ->
                val selectedCount = checkedItems.count { it }

                if (selectedCount == 0) {
                    Toast.makeText(this, "No se seleccionó ningún preset", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Confirmar eliminación
                AlertDialog.Builder(this)
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
                        Toast.makeText(this, "✅ $deletedCount preset(s) eliminado(s)", Toast.LENGTH_SHORT).show()
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

    /**
     * ✅ Exporta presets (copia al portapapeles)
     */
    private fun exportPresets() {
        val json = presetManager.exportPresetsAsJson()

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Barge-In Presets", json)
        clipboard.setPrimaryClip(clip)

        val count = presetManager.getPresetCount()

        AlertDialog.Builder(this)
            .setTitle("📤 Presets Exportados")
            .setMessage("$count preset(s) copiados al portapapeles en formato JSON.\n\nPuedes compartirlos o guardarlos en un archivo.")
            .setPositiveButton("OK", null)
            .setNeutralButton("📥 Importar") { _, _ ->
                showImportPresetsDialog()
            }
            .show()

        Toast.makeText(this, "✅ Presets copiados al portapapeles", Toast.LENGTH_LONG).show()
    }

    /**
     * ✅ Importa presets desde JSON
     */
    private fun showImportPresetsDialog() {
        val input = EditText(this).apply {
            hint = "Pega aquí el JSON de presets"
            setPadding(40, 20, 40, 20)
            minLines = 5
            maxLines = 10
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        AlertDialog.Builder(this)
            .setTitle("📥 Importar Presets")
            .setMessage("Pega el JSON exportado previamente:")
            .setView(input)
            .setPositiveButton("Importar") { _, _ ->
                val json = input.text.toString().trim()

                if (json.isEmpty()) {
                    Toast.makeText(this, "❌ No se pegó ningún contenido", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (presetManager.importPresetsFromJson(json)) {
                    val count = presetManager.getPresetCount()
                    Toast.makeText(this, "✅ Presets importados. Total: $count", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ Error: JSON inválido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ===== DIÁLOGO DE CONFIGURACIÓN CUSTOM =====

    private fun showCustomSettingsDialog() {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // Crear campos editables para cada parámetro
        val params = listOf(
            ParamConfig("Delta Threshold (dB)", customDeltaVoiceThresholdDb, 8f, 20f, "%.1f") { customDeltaVoiceThresholdDb = it },
            ParamConfig("Min Energy (dB)", customMinAbsoluteVoiceEnergyDb, -35f, -20f, "%.1f") { customMinAbsoluteVoiceEnergyDb = it },
            ParamConfig("Max ZCR", customMaxZcrForVoice, 0.10f, 0.30f, "%.2f") { customMaxZcrForVoice = it },
            ParamConfig("Baseline Factor", customDeltaBaselineAdjustmentFactor, 0.5f, 0.9f, "%.2f") { customDeltaBaselineAdjustmentFactor = it },
            ParamConfig("Min Duration (ms)", customMinVoiceDurationMs.toFloat(), 20f, 100f, "%.0f") { customMinVoiceDurationMs = it.toLong() },
            ParamConfig("Confidence Threshold", customVoiceConfidenceThreshold, 0.40f, 0.80f, "%.2f") { customVoiceConfidenceThreshold = it },
            ParamConfig("Calibration Time (ms)", customCalibrationDurationMs.toFloat(), 100f, 500f, "%.0f") { customCalibrationDurationMs = it.toLong() },
            ParamConfig("Pre-Delay (ms)", customPreDelayMs.toFloat(), 200f


                , 500f, "%.0f") { customPreDelayMs = it.toLong() }
        )

        val seekBars = mutableListOf<Pair<TextView, SeekBar>>()

        params.forEach { param ->
            // Label
            val label = TextView(this).apply {
                text = "${param.name}: ${String.format(param.format, param.currentValue)}"
                textSize = 14f
                setTextColor(Color.parseColor("#212121"))
                setPadding(0, 16, 0, 8)
            }
            dialogLayout.addView(label)

            // SeekBar
            val seekBar = SeekBar(this).apply {
                max = 100
                val normalized = ((param.currentValue - param.min) / (param.max - param.min) * 100).toInt()
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

        // Botón de reset
        val btnReset = Button(this).apply {
            text = "🔄 Restaurar Valores por Defecto"
            setOnClickListener {
                // Restaurar a valores de modo SENSIBLE
                customDeltaVoiceThresholdDb = 13f
                customMinAbsoluteVoiceEnergyDb = -26f
                customMaxZcrForVoice = 0.18f
                customDeltaBaselineAdjustmentFactor = 0.72f
                customMinVoiceDurationMs = 48L
                customVoiceConfidenceThreshold = 0.56f
                customCalibrationDurationMs = 200L
                customPreDelayMs = 350L

                // Guardar los valores restaurados
                saveCustomSettings()

                // Actualizar seekbars y labels
                val restoredParams = listOf(
                    customDeltaVoiceThresholdDb,
                    customMinAbsoluteVoiceEnergyDb,
                    customMaxZcrForVoice,
                    customDeltaBaselineAdjustmentFactor,
                    customMinVoiceDurationMs.toFloat(),
                    customVoiceConfidenceThreshold,
                    customCalibrationDurationMs.toFloat(),
                    customPreDelayMs.toFloat()
                )

                params.forEachIndexed { index, param ->
                    val (label, seekBar) = seekBars[index]
                    val value = restoredParams[index]
                    val normalized = ((value - param.min) / (param.max - param.min) * 100).toInt()
                    seekBar.progress = normalized
                    label.text = "${param.name}: ${String.format(param.format, value)}"
                }

                Toast.makeText(this@TestActivity, "✅ Valores restaurados y guardados", Toast.LENGTH_SHORT).show()
            }
        }
        dialogLayout.addView(btnReset)

        // Info adicional
        val infoText = TextView(this).apply {
            text = """
                
                ℹ️ Guía Rápida:
                • Delta ↓ = Más sensible
                • Min Energy ↓ = Acepta voz más débil
                • Max ZCR ↑ = Más permisivo con ruido
                • Factor ↑ = Mejor en volumen alto
                • Duration ↓ = Respuesta más rápida
                • Confidence ↓ = Menos exigente
                • Calibration ↑ = Más preciso
                • Pre-Delay ↑ = Evita problemas de timing
            """.trimIndent()
            textSize = 11f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, 16, 0, 0)
        }
        dialogLayout.addView(infoText)

        val scrollView = ScrollView(this).apply {
            addView(dialogLayout)
        }

        AlertDialog.Builder(this)
            .setTitle("⚙️ Configuración Custom")
            .setView(scrollView)
            .setPositiveButton("💾 Guardar") { _, _ ->
                // Aplicar valores desde seekbars
                params.forEachIndexed { index, param ->
                    val progress = seekBars[index].second.progress
                    val value = param.min + (progress / 100f) * (param.max - param.min)
                    param.onValueChange(value)
                }

                // Guardar en SharedPreferences
                saveCustomSettings()

                Toast.makeText(this, "✅ Configuración guardada", Toast.LENGTH_SHORT).show()

                // Si ya está en modo custom, reiniciar engine
                if (currentMode == SensitivityMode.CUSTOM && ::engine.isInitialized && !isTestRunning) {
                    try {
                        engine.release()
                        initializeEngine()

                        statusText.text = """
                            ✅ Configuración Custom actualizada
                            
                            ⚙️ CUSTOM
                            Δ=${customDeltaVoiceThresholdDb}dB • E=${customMinAbsoluteVoiceEnergyDb}dB
                            ZCR=${customMaxZcrForVoice} • Factor=${customDeltaBaselineAdjustmentFactor}
                            
                            🎤 Sistema listo (inactivo)
                            Presiona "INICIAR TEST" para probar
                        """.trimIndent()
                    } catch (e: Exception) {
                        Timber.e(e, "Error updating custom config")
                        statusText.text = "❌ Error actualizando config:\n${e.message}"
                    }
                }
            }
            .setNegativeButton("❌ Cancelar", null)
            .show()
    }

    data class ParamConfig(
        val name: String,
        val currentValue: Float,
        val min: Float,
        val max: Float,
        val format: String,
        val onValueChange: (Float) -> Unit
    )

    // ===== UI HELPERS =====

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
    }

    private fun createButton(text: String, color: Int, enabled: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 15f
            setBackgroundColor(color)
            setTextColor(Color.WHITE)
            isEnabled = enabled
            setPadding(20, 32, 20, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createModeButton(text: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            setBackgroundColor(color)
            setTextColor(Color.WHITE)
            setPadding(8, 24, 8, 24)
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

    // ===== PERMISOS =====

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        } else {
            onPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onPermissionsGranted()
            } else {
                statusText.text = "❌ Permiso de micrófono denegado\n\nLa aplicación no puede funcionar"
                Timber.e("Permission denied")
            }
        }
    }

    private fun onPermissionsGranted() {
        Timber.i("✅ Permissions granted - Starting initialization @ 44.1kHz")

        statusText.text = "⏳ Inicializando @ 44.1kHz..."

        try {
            copyWavFromAssets()

            updateModeButtons()
            initializeEngine()

            val modeInfo = when (currentMode) {
                SensitivityMode.SUPER_SENSITIVE -> "Modo: 🔴 Super Sensitive"
                SensitivityMode.SENSITIVE -> "Modo: 🟡 Sensitive"
                SensitivityMode.NORMAL -> "Modo: 🟢 Normal"
                SensitivityMode.CUSTOM -> "Modo: ⚙️ Custom"
            }

            val presetCount = presetManager.getPresetCount()
            val presetInfo = if (presetCount > 0) "\n💾 $presetCount preset(s) guardado(s)" else ""

            statusText.text = """
                ✅ Sistema listo @ 44.1kHz
                
                $modeInfo$presetInfo
                🎤 Micrófono: LISTO (inactivo)
                🎚️ High-pass: 600Hz
                🎯 Delta adaptativo activo
                ⏱️ Pre-calibración: ${customPreDelayMs}ms
                
                Presiona "INICIAR TEST" para comenzar
            """.trimIndent()

            btnPlayTest.isEnabled = true

            Timber.i("✅ System ready (idle mode)")

        } catch (e: Exception) {
            Timber.e(e, "Error in initialization")
            statusText.text = """
                ❌ Error al inicializar
                
                ${e.message}
                
                Por favor revisa los logs
            """.trimIndent()
        }
    }

    // ===== ENGINE MANAGEMENT =====

    private fun copyWavFromAssets() {
        try {
            wavFile = File(cacheDir, "test_audio.wav")

            if (wavFile!!.exists()) {
                Timber.i("🗑️ Deleting existing WAV file...")
                wavFile!!.delete()
            }

            var copiedFromAssets = false
            try {
                Timber.i("📂 Attempting to copy test_audio.wav from assets...")

                val assetManager = assets
                val inputStream = assetManager.open("test_audio.wav")
                val outputStream = FileOutputStream(wavFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytes = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                }

                inputStream.close()
                outputStream.flush()
                outputStream.close()

                copiedFromAssets = true
                Timber.i("✅ WAV file copied successfully from assets!")
                Timber.i("   Size: ${totalBytes / 1024}KB")

            } catch (e: java.io.FileNotFoundException) {
                Timber.w("⚠️ test_audio.wav NOT FOUND in assets")
                Timber.w("   Will generate synthetic audio at 44.1kHz")
            }

            if (!copiedFromAssets) {
                Timber.i("🔧 Generating synthetic WAV @ 44.1kHz...")
                WavGenerator.generateTestWav(wavFile!!, durationSeconds = 15)
            }

        } catch (e: Exception) {
            Timber.e(e, "❌ CRITICAL ERROR preparing WAV file")
            wavFile = null
        }
    }

    private fun initializeEngine() {
        try {
            Timber.i("🔧 Initializing BargeInEngine @ 44.1kHz with mode: $currentMode")

            val config = getConfigForMode(currentMode)

            engine = BargeInEngine(
                context = applicationContext,
                config = config,
                listener = this
            )

            engine.initialize()

            Timber.i("✅ Engine initialized successfully @ 44.1kHz with mode: $currentMode")

        } catch (e: Exception) {
            statusText.text = """
                ❌ Error al inicializar motor
                
                ${e.message}
            """.trimIndent()

            Timber.e(e, "Engine initialization failed")
            throw e
        }
    }

    private fun getConfigForMode(mode: SensitivityMode): BargeInConfig {
        return when (mode) {
            SensitivityMode.SUPER_SENSITIVE -> BargeInConfig(
                sampleRate = 44100,
                vadMode = IVoiceActivityDetector.AggressivenessMode.VERY_AGGRESSIVE,
                minVoiceDurationMs = 36,
                voiceConfidenceThreshold = 0.50f,
                // ✅ MÁS SENSIBLE - Detecta rápido, permite voz más baja
                deltaVoiceThresholdDb = 13f,           // Más bajo que Sensible
                minAbsoluteVoiceEnergyDb = -27f,       // Acepta voz más débil
                maxZcrForVoice = 0.19f,                // Más permisivo con ruido
                deltaBaselineAdjustmentFactor = 0.65f  // Ajuste medio-bajo (más estricto que antes)
            )

            SensitivityMode.SENSITIVE -> BargeInConfig(
                sampleRate = 44100,
                vadMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
                minVoiceDurationMs = 48,
                voiceConfidenceThreshold = 0.58f,
                // ✅ EQUILIBRADO - Balance entre detección y precisión
                deltaVoiceThresholdDb = 14f,           // Medio
                minAbsoluteVoiceEnergyDb = -26f,       // Medio
                maxZcrForVoice = 0.18f,                // Medio
                deltaBaselineAdjustmentFactor = 0.70f  // Ajuste medio
            )

            SensitivityMode.NORMAL -> BargeInConfig(
                sampleRate = 44100,
                vadMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
                minVoiceDurationMs = 54,               // Reducido de 60ms
                voiceConfidenceThreshold = 0.62f,      // Reducido de 0.65f
                // ✅ MÁS PERMISIVO - Permite interrumpir durante frases con volumen alto
                deltaVoiceThresholdDb = 15f,           // Aumentado de 16f (más permisivo)
                minAbsoluteVoiceEnergyDb = -25f,       // Aumentado de -24f (más permisivo)
                maxZcrForVoice = 0.17f,                // Aumentado de 0.16f (más permisivo)
                deltaBaselineAdjustmentFactor = 0.75f  // ✅ CLAVE: Aumentado de 0.6f a 0.75f
            )


            SensitivityMode.CUSTOM -> BargeInConfig(
                sampleRate = 44100,
                vadMode = IVoiceActivityDetector.AggressivenessMode.AGGRESSIVE,
                minVoiceDurationMs = customMinVoiceDurationMs,
                voiceConfidenceThreshold = customVoiceConfidenceThreshold,
                deltaVoiceThresholdDb = customDeltaVoiceThresholdDb,
                minAbsoluteVoiceEnergyDb = customMinAbsoluteVoiceEnergyDb,
                maxZcrForVoice = customMaxZcrForVoice,
                deltaBaselineAdjustmentFactor = customDeltaBaselineAdjustmentFactor
            )
        }
    }

    private fun changeSensitivityMode(newMode: SensitivityMode) {
        if (!::engine.isInitialized) {
            Timber.w("Engine not initialized yet")
            return
        }

        if (isTestRunning) {
            statusText.text = "⚠️ Detén el test antes de cambiar el modo"
            return
        }

        currentMode = newMode
        updateModeButtons()

        try {
            Timber.i("🔄 Changing mode to: $newMode @ 44.1kHz")

            engine.release()
            initializeEngine()

            val modeText = when (newMode) {
                SensitivityMode.SUPER_SENSITIVE -> "🔴 SUPER SENSIBLE\nΔ=13dB • E=-27dB • ZCR=0.19 • Factor=0.65"
                SensitivityMode.SENSITIVE -> "🟡 SENSIBLE\nΔ=13dB • E=-26dB • ZCR=0.18 • Factor=0.72"
                SensitivityMode.NORMAL -> "🟢 NORMAL\nΔ=15dB • E=-25dB • ZCR=0.17 • Factor=0.75"
                SensitivityMode.CUSTOM -> "⚙️ CUSTOM\nΔ=${customDeltaVoiceThresholdDb}dB • E=${customMinAbsoluteVoiceEnergyDb}dB • ZCR=${customMaxZcrForVoice} • Factor=${customDeltaBaselineAdjustmentFactor}"
            }

            statusText.text = """
                ✅ Modo cambiado @ 44.1kHz
                
                $modeText
                
                🎤 Sistema listo (inactivo)
                Presiona "INICIAR TEST" para probar
            """.trimIndent()

            // Guardar el modo actual
            saveCustomSettings()

            Timber.i("✅ Mode changed successfully to: $newMode")

        } catch (e: Exception) {
            statusText.text = "❌ Error cambiando modo:\n${e.message}"
            Timber.e(e, "Failed to change mode")
        }
    }

    private fun updateModeButtons() {
        btnModeSuperSensitive.alpha = 0.5f
        btnModeSensible.alpha = 0.5f
        btnModeNormal.alpha = 0.5f
        btnModeCustom.alpha = 0.5f

        when (currentMode) {
            SensitivityMode.SUPER_SENSITIVE -> btnModeSuperSensitive.alpha = 1.0f
            SensitivityMode.SENSITIVE -> btnModeSensible.alpha = 1.0f
            SensitivityMode.NORMAL -> btnModeNormal.alpha = 1.0f
            SensitivityMode.CUSTOM -> btnModeCustom.alpha = 1.0f
        }
    }

    // ===== TEST MANAGEMENT =====

    private fun startUIUpdates() {
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isTestRunning) {
                    updateAudioVisualizer()
                    handler.postDelayed(this, 50)
                }
            }
        }
        handler.post(updateRunnable)
    }

    private fun updateAudioVisualizer() {
        if (!::engine.isInitialized) return

        try {
            val metrics = engine.getMetrics()
            val vadMetrics = metrics.vadMetrics ?: return

            val totalFrames = vadMetrics.framesProcessed.toFloat()
            if (totalFrames == 0f) return

            val voiceRatio = vadMetrics.voiceFrames.toFloat() / totalFrames
            val avgConfidence = vadMetrics.averageConfidence

            val estimatedDb = -60f + (avgConfidence * 60f)
            val barProgress = ((estimatedDb + 60f) * 100f / 60f).toInt().coerceIn(0, 100)

            audioLevelBar.progress = barProgress

            val color = when {
                barProgress > 70 -> Color.parseColor("#4CAF50")
                barProgress > 50 -> Color.parseColor("#8BC34A")
                barProgress > 30 -> Color.parseColor("#FFC107")
                barProgress > 15 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#F44336")
            }
            audioLevelBar.progressTintList = android.content.res.ColorStateList.valueOf(color)

            val icon = when {
                barProgress > 70 -> "🔊"
                barProgress > 50 -> "🔉"
                barProgress > 30 -> "🔉"
                barProgress > 10 -> "🔈"
                else -> "🔇"
            }

            audioLevelText.text = "$icon Audio: ${String.format("%.1f", estimatedDb)} dB | " +
                    "Confianza: ${String.format("%.2f", avgConfidence)}"

            val stateEmoji = when (metrics.state) {
                BargeInState.IDLE -> "💤"
                BargeInState.LISTENING -> "🎤"
                BargeInState.INTERRUPTED -> "🚨"
                BargeInState.STOPPED -> "⏸️"
                BargeInState.ERROR -> "❌"
            }

            val listeningStatus = if (metrics.isListening) "🟢 ACTIVO" else "🔴 INACTIVO"
            val playingStatus = if (metrics.isPlaying) "🟢 SÍ" else "⚪ NO"

            frequencyInfoText.text = """
                📊 Frames: ${vadMetrics.framesProcessed} | Voz: ${vadMetrics.voiceFrames} (${String.format("%.1f", voiceRatio * 100)}%)
                ⏱️ Proc: ${vadMetrics.averageProcessingTimeUs}µs/frame (~11.6ms)
                🎯 Estado: $stateEmoji ${metrics.state} | Mic: $listeningStatus | Audio: $playingStatus
            """.trimIndent()

        } catch (e: Exception) {
            Timber.e(e, "Error updating visualizer")
        }
    }

    @Suppress("MissingPermission")
    private fun startBargeInTest() {
        if (wavFile == null || !wavFile!!.exists()) {
            statusText.text = """
                ❌ No hay archivo de audio
                
                Error generando el WAV
                Revisa los logs
            """.trimIndent()
            return
        }

        if (isTestRunning) {
            Timber.w("Test already running")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "❌ No hay permiso de micrófono"
            return
        }

        try {
            isTestRunning = true

            engine.startListening()

            val modeInfo = when (currentMode) {
                SensitivityMode.SUPER_SENSITIVE -> "Δ=13dB, E>-27dB"
                SensitivityMode.SENSITIVE -> "Δ=13dB, E>-26dB"
                SensitivityMode.NORMAL -> "Δ=15dB, E>-25dB"
                SensitivityMode.CUSTOM -> "Δ=${customDeltaVoiceThresholdDb}dB, E>${customMinAbsoluteVoiceEnergyDb}dB"
            }

            statusText.text = """
                🎵 REPRODUCIENDO AUDIO @ 44.1kHz
                
                ¡Interrumpe hablando FUERTE!
                
                🎤 Micrófono: ACTIVO
                ⏳ Pre-delay: ${customPreDelayMs}ms
                🎯 Calibración: ${customCalibrationDurationMs}ms
                📊 Modo: $modeInfo
            """.trimIndent()

            btnPlayTest.isEnabled = false
            btnStopTest.isEnabled = true

            startUIUpdates()

            handler.postDelayed({
                val inputStream = wavFile!!.inputStream()
                engine.playAudio(inputStream)
                Timber.i("▶️ Barge-in test started @ 44.1kHz")
            }, 100)

        } catch (e: SecurityException) {
            statusText.text = "❌ Error de permisos:\n${e.message}"
            isTestRunning = false
            btnPlayTest.isEnabled = true
            btnStopTest.isEnabled = false
            Timber.e(e, "Permission error")
        } catch (e: Exception) {
            statusText.text = "❌ Error iniciando test:\n${e.message}"
            isTestRunning = false
            btnPlayTest.isEnabled = true
            btnStopTest.isEnabled = false
            Timber.e(e, "Failed to start test")
        }
    }

    private fun stopBargeInTest() {
        if (!isTestRunning) {
            Timber.w("No test running")
            return
        }

        try {
            Timber.i("🛑 User requested to stop test...")

            engine.stopAudioPlayback()
            engine.stopListening()
            handler.removeCallbacksAndMessages(null)

            isTestRunning = false

            statusText.text = """
                ⏸️ Test detenido
                
                🎤 Micrófono: INACTIVO
                📊 Sistema en reposo
                
                Presiona "INICIAR TEST" para otra prueba
            """.trimIndent()

            btnPlayTest.isEnabled = true
            btnStopTest.isEnabled = false

            Timber.i("✅ Test stopped, system idle")

        } catch (e: Exception) {
            statusText.text = "❌ Error deteniendo:\n${e.message}"
            Timber.e(e, "Error stopping test")
        }
    }

    // ===== BargeInListener CALLBACKS =====

    @Suppress("MissingPermission")
    override fun onUserInterruption(event: BargeInEvent) {
        runOnUiThread {
            engine.stopAudioPlayback()
            engine.stopListening()
            handler.removeCallbacksAndMessages(null)

            isTestRunning = false

            val latencyOk = event.latencyMs < 300
            val emoji = if (latencyOk) "✅" else "⚠️"
            val colorIndicator = if (latencyOk) "🟢" else "🟡"

            statusText.text = """
                🎉 ¡BARGE-IN DETECTADO!
                
                $emoji Latencia: ${String.format("%.0f", event.latencyMs)} ms $colorIndicator
                📊 Confianza: ${String.format("%.0f", event.confidence * 100)}%
                📊 Energía: ${String.format("%.1f", event.energyDb)} dB
                
                ${if (latencyOk) "¡Excelente respuesta! <300ms" else "Mejorable (>300ms)"}
                
                🎤 Sistema en reposo
                Presiona "INICIAR TEST" para otra prueba
            """.trimIndent()

            btnPlayTest.isEnabled = true
            btnStopTest.isEnabled = false
        }

        Timber.i("🎉 BARGE-IN! latency=${String.format("%.1f", event.latencyMs)}ms, " +
                "conf=${String.format("%.0f", event.confidence * 100)}%, " +
                "energy=${String.format("%.1f", event.energyDb)}dB")
    }

    override fun onStateChanged(state: BargeInState) {
        Timber.d("📊 State: $state")
    }

    override fun onError(error: BargeInError) {
        runOnUiThread {
            statusText.text = """
                ❌ ERROR
                
                ${error.code}
                ${error.message}
            """.trimIndent()

            isTestRunning = false
            btnPlayTest.isEnabled = true
            btnStopTest.isEnabled = false
        }

        Timber.e("❌ Error: ${error.code} - ${error.message}")
    }

    // ===== LIFECYCLE =====

    override fun onDestroy() {
        super.onDestroy()

        Timber.i("🔧 Destroying TestActivity...")

        handler.removeCallbacksAndMessages(null)

        try {
            if (::engine.isInitialized) {
                engine.release()
                Timber.d("✅ Engine released")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error releasing engine")
        }

        Timber.i("✅ TestActivity destroyed")
    }
}