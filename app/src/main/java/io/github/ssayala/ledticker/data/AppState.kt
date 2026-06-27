package io.github.ssayala.ledticker.data

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.github.ssayala.ledticker.ble.BleManager
import io.github.ssayala.ledticker.ble.CharKind
import io.github.ssayala.ledticker.model.ActiveStatus
import io.github.ssayala.ledticker.model.ActiveTimer
import io.github.ssayala.ledticker.model.Categories
import io.github.ssayala.ledticker.model.DeviceMode
import io.github.ssayala.ledticker.model.DisplaySettings
import io.github.ssayala.ledticker.model.KnownDevice
import io.github.ssayala.ledticker.model.Payloads
import io.github.ssayala.ledticker.model.PowerState
import io.github.ssayala.ledticker.model.WeatherLocation

/** A transient banner message. [id] makes repeats re-trigger the UI effect. */
data class Toast(val text: String, val isError: Boolean, val id: Long)

/**
 * Shared observable state for all screens, the Android counterpart of the
 * iOS AppState. The device is the source of truth for device-side fields
 * (populated on connect, never faked). SSID, API key and preset chips persist
 * locally; the Wi-Fi password is never persisted or exposed over BLE.
 *
 * Uses Compose snapshot state so screens observe fields directly.
 */
class AppState(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    val haptics = Haptics(app)

    // Editable, persisted fields (custom setters persist on each edit).
    var ssid: String by mutableStateOf(prefs.ssid)
        private set
    var password: String by mutableStateOf("")
        private set
    var apikey: String by mutableStateOf(prefs.apiKey)
        private set

    fun updateSsid(v: String) { ssid = v; prefs.ssid = v }
    fun updatePassword(v: String) { password = v }
    fun updateApiKey(v: String) { apikey = v; prefs.apiKey = v }

    var tickers: List<String> by mutableStateOf(emptyList())
    var locations: List<WeatherLocation> by mutableStateOf(emptyList())

    var activeStatus: ActiveStatus? by mutableStateOf(null)
    var activeTimer: ActiveTimer? by mutableStateOf(null)

    var presetTexts: List<String> by mutableStateOf(prefs.presets ?: DEFAULT_PRESETS)
        private set

    fun setPresets(v: List<String>) { presetTexts = v; prefs.presets = v }

    var deviceMode: DeviceMode by mutableStateOf(DeviceMode.Unknown)
    var firmwareVersion: String by mutableStateOf("")
    var displayPower: PowerState? by mutableStateOf(null)
    var displaySettings: DisplaySettings? by mutableStateOf(null)
    var deviceTimezone: String? by mutableStateOf(null)
    var recentlyResetDevice: KnownDevice? by mutableStateOf(null)
    var baselineCategories: Categories by mutableStateOf(Categories.none)

    var toast: Toast? by mutableStateOf(null)
        private set
    private var toastSeq = 0L

    // Baselines for dirty tracking.
    var baselineSsid: String by mutableStateOf("")
    var baselinePassword: String by mutableStateOf("")
    var baselineApiKey: String by mutableStateOf("")
    var baselineTickers: List<String> by mutableStateOf(emptyList())
    var baselineLocations: List<WeatherLocation> by mutableStateOf(emptyList())

    var lastDurationSeconds: Int
        get() = prefs.lastDurationSeconds
        set(v) { prefs.lastDurationSeconds = v }

    /** Wipe device-sourced fields when the connection drops. Presets are kept. */
    fun clearDeviceState() {
        tickers = emptyList(); baselineTickers = emptyList()
        locations = emptyList(); baselineLocations = emptyList()
        activeStatus = null
        deviceMode = DeviceMode.Unknown
        baselineCategories = Categories.none
        firmwareVersion = ""
        displayPower = null
        displaySettings = null
        deviceTimezone = null
    }

    fun show(text: String, isError: Boolean = false) {
        toast = Toast(text, isError, ++toastSeq)
    }

    /**
     * Write with haptic feedback + failure toast. [onSuccess] runs only on
     * the device ACK. Success is silent unless [confirmSuccess]; [confirmHaptic]
     * defaults true (passive controls' sole confirmation) — pass false for
     * explicit button presses that already buzzed on tap.
     */
    fun send(
        ble: BleManager,
        kind: CharKind,
        data: ByteArray,
        label: String,
        confirmSuccess: Boolean = false,
        confirmHaptic: Boolean = true,
        onSuccess: (() -> Unit)? = null,
    ) {
        if (confirmSuccess) show("Sending $label…")
        ble.write(kind, data) { err ->
            if (err != null) {
                show("$label failed: ${err.message}", isError = true)
                haptics.error()
            } else {
                if (confirmSuccess) show("$label sent")
                if (confirmHaptic) haptics.success()
                onSuccess?.invoke()
            }
        }
    }

    /** Read device config and overwrite local fields unconditionally. */
    fun refreshFromDevice(ble: BleManager) {
        ble.readAll(
            listOf(
                CharKind.Wifi, CharKind.ApiKey, CharKind.Tickers, CharKind.Status,
                CharKind.Locations, CharKind.Mode, CharKind.Version, CharKind.Power,
                CharKind.Display, CharKind.Timezone,
            )
        ) { results ->
            val ssidStr = results[CharKind.Wifi]?.let { Payloads.parseString(it) } ?: ""
            val apiKeyStr = results[CharKind.ApiKey]?.let { Payloads.parseString(it) } ?: ""
            ssid = ssidStr; prefs.ssid = ssidStr
            password = ""
            baselineSsid = ssidStr
            baselinePassword = ""
            apikey = apiKeyStr; prefs.apiKey = apiKeyStr
            baselineApiKey = apiKeyStr
            tickers = results[CharKind.Tickers]?.let { Payloads.parseTickers(it) } ?: emptyList()
            baselineTickers = tickers
            activeStatus = results[CharKind.Status]?.let { Payloads.parseStatus(it) }
            locations = results[CharKind.Locations]?.let { Payloads.parseLocations(it) } ?: emptyList()
            baselineLocations = locations

            val mode = results[CharKind.Mode]?.let { Payloads.parseMode(it) } ?: DeviceMode.Unknown
            deviceMode = mode
            baselineCategories = when (mode) {
                is DeviceMode.Content -> mode.categories
                else -> Categories.none
            }

            firmwareVersion = results[CharKind.Version]?.let { Payloads.parseString(it) } ?: ""
            displayPower = results[CharKind.Power]?.let { Payloads.parsePower(it) }
            displaySettings = results[CharKind.Display]?.let { Payloads.parseDisplaySettings(it) }
            deviceTimezone = results[CharKind.Timezone]?.let { Payloads.parseTimezone(it) }
        }
    }

    companion object {
        val DEFAULT_PRESETS = listOf("BUSY", "FOCUS", "CALL", "BRB", "LUNCH", "DND")
    }
}
