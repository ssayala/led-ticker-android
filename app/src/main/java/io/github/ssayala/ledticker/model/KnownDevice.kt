package io.github.ssayala.ledticker.model

import kotlinx.serialization.Serializable

/**
 * An LED-Ticker the user has previously connected to. Identity is the BLE
 * MAC address (stable for a bonded ESP32). Friendly name defaults to the
 * advertised name on first connect and is freely editable.
 */
@Serializable
data class KnownDevice(
    val id: String,                 // BLE MAC address
    val friendlyName: String,       // editable
    val advertisedName: String,     // refined once from a placeholder; else stable
    val lastConnectedMillis: Long,  // moves on every successful (re)connect
) {
    companion object {
        /** Placeholder used when the OS doesn't surface the advertised name. */
        const val PLACEHOLDER_NAME = "LED-Ticker"
    }
}
