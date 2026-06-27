package io.github.ssayala.ledticker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import io.github.ssayala.ledticker.ble.BleManager
import io.github.ssayala.ledticker.ble.ConnectionState

/** Title + optional device-name subtitle, the Android take on the iOS nav subtitle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedTopBar(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(),
    )
}

/** Device friendly-name subtitle for the gated tabs. */
@Composable
fun deviceSubtitle(ble: BleManager): String {
    val state = ble.state.collectAsStateValue()
    val device = ble.activeDevice.collectAsStateValue()
    return when (state) {
        ConnectionState.Ready -> device?.friendlyName ?: "Connected"
        ConnectionState.Connecting, ConnectionState.Discovering -> "Connecting…"
        is ConnectionState.Failed -> "Disconnected"
        else -> "Not connected"
    }
}
