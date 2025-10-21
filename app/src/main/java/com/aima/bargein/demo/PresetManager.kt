package com.aima.bargein.demo

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gestiona presets personalizados de configuración VAD
 */
class PresetManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "BargeInPresets",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_PRESETS = "saved_presets"
        private const val KEY_LAST_USED = "last_used_preset"
    }

    data class VadPreset(
        val name: String,
        val deltaVoiceThresholdDb: Float,
        val minAbsoluteVoiceEnergyDb: Float,
        val maxZcrForVoice: Float,
        val deltaBaselineAdjustmentFactor: Float,
        val minVoiceDurationMs: Long,
        val voiceConfidenceThreshold: Float,
        val calibrationDurationMs: Long,
        val preDelayMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("name", name)
                put("deltaVoiceThresholdDb", deltaVoiceThresholdDb.toDouble())
                put("minAbsoluteVoiceEnergyDb", minAbsoluteVoiceEnergyDb.toDouble())
                put("maxZcrForVoice", maxZcrForVoice.toDouble())
                put("deltaBaselineAdjustmentFactor", deltaBaselineAdjustmentFactor.toDouble())
                put("minVoiceDurationMs", minVoiceDurationMs)
                put("voiceConfidenceThreshold", voiceConfidenceThreshold.toDouble())
                put("calibrationDurationMs", calibrationDurationMs)
                put("preDelayMs", preDelayMs)
                put("timestamp", timestamp)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): VadPreset {
                return VadPreset(
                    name = json.getString("name"),
                    deltaVoiceThresholdDb = json.getDouble("deltaVoiceThresholdDb").toFloat(),
                    minAbsoluteVoiceEnergyDb = json.getDouble("minAbsoluteVoiceEnergyDb").toFloat(),
                    maxZcrForVoice = json.getDouble("maxZcrForVoice").toFloat(),
                    deltaBaselineAdjustmentFactor = json.getDouble("deltaBaselineAdjustmentFactor").toFloat(),
                    minVoiceDurationMs = json.getLong("minVoiceDurationMs"),
                    voiceConfidenceThreshold = json.getDouble("voiceConfidenceThreshold").toFloat(),
                    calibrationDurationMs = json.getLong("calibrationDurationMs"),
                    preDelayMs = json.getLong("preDelayMs"),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
            }
        }

        /**
         * Genera descripción legible del preset
         */
        fun getDescription(): String {
            return "Δ=${deltaVoiceThresholdDb}dB • E=${minAbsoluteVoiceEnergyDb}dB • " +
                    "ZCR=${maxZcrForVoice} • Factor=${deltaBaselineAdjustmentFactor}"
        }
    }

    /**
     * Guarda un nuevo preset
     */
    fun savePreset(preset: VadPreset): Boolean {
        return try {
            val presets = getAllPresets().toMutableList()

            // Si ya existe un preset con ese nombre, reemplazarlo
            val existingIndex = presets.indexOfFirst { it.name == preset.name }
            if (existingIndex >= 0) {
                presets[existingIndex] = preset
                Timber.i("📝 Preset '${preset.name}' actualizado")
            } else {
                presets.add(preset)
                Timber.i("💾 Nuevo preset '${preset.name}' guardado")
            }

            saveAllPresets(presets)
            true
        } catch (e: Exception) {
            Timber.e(e, "Error guardando preset")
            false
        }
    }

    /**
     * Carga un preset por nombre
     */
    fun loadPreset(name: String): VadPreset? {
        return getAllPresets().firstOrNull { it.name == name }
    }

    /**
     * Obtiene todos los presets guardados
     */
    fun getAllPresets(): List<VadPreset> {
        return try {
            val json = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
            val jsonArray = JSONArray(json)

            List(jsonArray.length()) { i ->
                VadPreset.fromJson(jsonArray.getJSONObject(i))
            }.sortedByDescending { it.timestamp } // Más recientes primero

        } catch (e: Exception) {
            Timber.e(e, "Error cargando presets")
            emptyList()
        }
    }

    /**
     * Elimina un preset
     */
    fun deletePreset(name: String): Boolean {
        return try {
            val presets = getAllPresets().filter { it.name != name }
            saveAllPresets(presets)
            Timber.i("🗑️ Preset '$name' eliminado")
            true
        } catch (e: Exception) {
            Timber.e(e, "Error eliminando preset")
            false
        }
    }

    /**
     * Guarda el nombre del último preset usado
     */
    fun setLastUsedPreset(name: String) {
        prefs.edit().putString(KEY_LAST_USED, name).apply()
    }

    /**
     * Obtiene el nombre del último preset usado
     */
    fun getLastUsedPreset(): String? {
        return prefs.getString(KEY_LAST_USED, null)
    }

    /**
     * Verifica si existe un preset con ese nombre
     */
    fun presetExists(name: String): Boolean {
        return getAllPresets().any { it.name == name }
    }

    /**
     * Exporta todos los presets como JSON string
     */
    fun exportPresetsAsJson(): String {
        return try {
            val presets = getAllPresets()
            val jsonArray = JSONArray()
            presets.forEach { jsonArray.put(it.toJson()) }
            jsonArray.toString(2) // Pretty print
        } catch (e: Exception) {
            Timber.e(e, "Error exportando presets")
            "[]"
        }
    }

    /**
     * Importa presets desde JSON string
     */
    fun importPresetsFromJson(jsonString: String): Boolean {
        return try {
            val jsonArray = JSONArray(jsonString)
            val importedPresets = List(jsonArray.length()) { i ->
                VadPreset.fromJson(jsonArray.getJSONObject(i))
            }

            val existingPresets = getAllPresets().toMutableList()

            // Añadir presets importados (sin duplicar por nombre)
            importedPresets.forEach { imported ->
                val existingIndex = existingPresets.indexOfFirst { it.name == imported.name }
                if (existingIndex >= 0) {
                    existingPresets[existingIndex] = imported
                } else {
                    existingPresets.add(imported)
                }
            }

            saveAllPresets(existingPresets)
            Timber.i("📥 ${importedPresets.size} presets importados")
            true
        } catch (e: Exception) {
            Timber.e(e, "Error importando presets")
            false
        }
    }

    /**
     * Obtiene estadísticas de uso
     */
    fun getPresetCount(): Int = getAllPresets().size

    // ===== MÉTODOS PRIVADOS =====

    private fun saveAllPresets(presets: List<VadPreset>) {
        val jsonArray = JSONArray()
        presets.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString(KEY_PRESETS, jsonArray.toString()).apply()
    }
}