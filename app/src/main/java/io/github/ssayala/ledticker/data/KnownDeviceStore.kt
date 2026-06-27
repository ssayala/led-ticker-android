package io.github.ssayala.ledticker.data

import android.content.Context
import io.github.ssayala.ledticker.model.KnownDevice
import kotlinx.serialization.json.Json

/**
 * Persists the known-devices list as JSON in SharedPreferences. Mirrors the
 * iOS KnownDevice.load/save: always returns the list sorted MRU-first.
 */
class KnownDeviceStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("LEDTicker.known", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<KnownDevice> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<KnownDevice>>(raw) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.lastConnectedMillis }
    }

    fun save(devices: List<KnownDevice>) {
        val sorted = devices.sortedByDescending { it.lastConnectedMillis }
        prefs.edit().putString(KEY, json.encodeToString(sorted)).apply()
    }

    private companion object {
        const val KEY = "knownDevices"
    }
}
