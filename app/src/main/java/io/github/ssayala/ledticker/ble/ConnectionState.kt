package io.github.ssayala.ledticker.ble

/** Mirrors the iOS app's ConnectionState. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object PoweredOff : ConnectionState
    data object Unauthorized : ConnectionState
    data object Scanning : ConnectionState
    data object Connecting : ConnectionState
    data object Discovering : ConnectionState
    data object Ready : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}
