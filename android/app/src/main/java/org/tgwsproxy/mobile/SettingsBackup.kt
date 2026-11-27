package org.tgwsproxy.mobile

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Копия всех настроек одним текстом (JSON): перенос на другой телефон и основа для
 * синхронизации через облако. При вставке каждое значение проверяется, поэтому
 * испорченная или чужая копия ничего не ломает.
 */
object SettingsBackup {
    private const val FORMAT = "tgws-mobile-settings"
    private const val VERSION = 1

    private val STRING_KEYS = listOf(
        "secret", "worker", "theme", "palette", "screen_reader",
        "narrator_source", "narrator_voice", "custom_ips", "profile_name",
    )
    private val FLOAT_KEYS = listOf("narrator_rate", "narrator_pitch")

    fun export(prefs: SharedPreferences): String {
        val data = JSONObject()
        for (key in STRING_KEYS) prefs.getString(key, null)?.let { data.put(key, it) }
        if (prefs.contains("port")) data.put("port", prefs.getInt("port", 1443))
        for (key in FLOAT_KEYS) {
            if (prefs.contains(key)) data.put(key, prefs.getFloat(key, 1.0f).toDouble())
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("data", data)
            .toString(2)
    }

    /** Возвращает число применённых настроек или бросает IllegalArgumentException с понятным текстом. */
    fun import(prefs: SharedPreferences, text: String): Int {
        val root = try {
            JSONObject(text.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException("В буфере обмена нет копии настроек.")
        }
        if (root.optString("format") != FORMAT) {
            throw IllegalArgumentException("Это не копия настроек TG WS Proxy.")
        }
        if (root.optInt("version", 0) > VERSION) {
            throw IllegalArgumentException("Копия сделана более новой версией приложения. Обновите приложение.")
        }
        val data = root.optJSONObject("data")
            ?: throw IllegalArgumentException("В копии нет данных.")

        val editor = prefs.edit()
        var applied = 0
        for (key in STRING_KEYS) {
            if (!data.has(key)) continue
            val value = data.optString(key)
            val clean = when (key) {
                "secret" -> value.takeIf { it.length in 2..128 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
                "profile_name" -> value.take(30)
                "custom_ips" -> value.takeIf { it.length <= 4000 }
                else -> value.takeIf { it.length <= 500 && it.none { c -> c.isISOControl() && c != '\n' } }
            }
            if (clean != null) {
                editor.putString(key, clean)
                applied++
            }
        }
        data.optInt("port", -1).takeIf { it in 1024..65535 }?.let {
            editor.putInt("port", it)
            applied++
        }
        for (key in FLOAT_KEYS) {
            if (!data.has(key)) continue
            val value = data.optDouble(key, Double.NaN)
            if (!value.isNaN()) {
                editor.putFloat(key, value.toFloat().coerceIn(0.5f, 2.0f))
                applied++
            }
        }
        editor.apply()
        return applied
    }
}
