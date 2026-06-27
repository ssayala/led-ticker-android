package io.github.ssayala.ledticker.data

import android.content.Context

/**
 * Small typed wrapper over SharedPreferences for the app-local values that
 * survive launches: last-used Wi-Fi SSID, Finnhub key, sign preset chips,
 * and the last-used sign duration. The Wi-Fi password is never persisted.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("LEDTicker.state", Context.MODE_PRIVATE)

    var ssid: String
        get() = sp.getString(KEY_SSID, "") ?: ""
        set(v) = sp.edit().putString(KEY_SSID, v).apply()

    var apiKey: String
        get() = sp.getString(KEY_APIKEY, "") ?: ""
        set(v) = sp.edit().putString(KEY_APIKEY, v).apply()

    /** null when never set (first launch) → callers seed defaults. */
    var presets: List<String>?
        get() = if (sp.contains(KEY_PRESETS)) {
            sp.getString(KEY_PRESETS, "")!!.split(SEP).filter { it.isNotEmpty() }
        } else null
        set(v) {
            sp.edit().putString(KEY_PRESETS, v.orEmpty().joinToString(SEP)).apply()
        }

    /** Last-used sign duration in seconds (0 = indefinite); -1 = unset. */
    var lastDurationSeconds: Int
        get() = sp.getInt(KEY_DURATION, -1)
        set(v) = sp.edit().putInt(KEY_DURATION, v).apply()

    private companion object {
        // Chips can't contain '|' (it breaks the sign payload), so it's a
        // safe in-prefs delimiter for the preset list.
        const val SEP = "|"
        const val KEY_SSID = "state.ssid"
        const val KEY_APIKEY = "state.apikey"
        const val KEY_PRESETS = "presetTexts.v1"
        const val KEY_DURATION = "presetDuration.v1"
    }
}
