package io.github.ssayala.ledticker

import android.app.Application
import io.github.ssayala.ledticker.ble.BleManager

/**
 * Holds the process-lifetime [BleManager] so the active GATT connection and
 * registered receivers survive Activity config changes (rotation, etc.).
 */
class LedTickerApp : Application() {
    val ble: BleManager by lazy { BleManager(applicationContext) }
}
