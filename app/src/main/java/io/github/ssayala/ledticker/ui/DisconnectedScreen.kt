package io.github.ssayala.ledticker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ssayala.ledticker.ble.ConnectionState

/**
 * Empty-state panel shown in place of a gated tab's content while not
 * connected. Copy adapts to the BLE state, mirroring the iOS DisconnectedView.
 */
@Composable
fun DisconnectedScreen(
    tabName: String,
    tabIcon: ImageVector,
    state: ConnectionState,
    onOpenDevice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            tabIcon, contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            title(state), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            message(state, tabName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (showButton(state)) {
            Button(
                onClick = { if (state is ConnectionState.Unauthorized) onOpenSettings() else onOpenDevice() },
                modifier = Modifier.padding(top = 20.dp),
            ) {
                val unauthorized = state is ConnectionState.Unauthorized
                Icon(
                    if (unauthorized) Icons.Outlined.Settings else Icons.Filled.SettingsRemote,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    if (unauthorized) "Open Settings" else "Open Device tab",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private fun title(state: ConnectionState): String = when (state) {
    ConnectionState.PoweredOff -> "Bluetooth is off"
    ConnectionState.Unauthorized -> "Bluetooth not allowed"
    ConnectionState.Connecting, ConnectionState.Discovering -> "Connecting…"
    is ConnectionState.Failed -> "Couldn't connect"
    else -> "Not connected"
}

private fun message(state: ConnectionState, tab: String): String = when (state) {
    ConnectionState.PoweredOff -> "Turn on Bluetooth to use $tab."
    ConnectionState.Unauthorized -> "Allow nearby-devices permission in Settings to use $tab."
    ConnectionState.Connecting, ConnectionState.Discovering ->
        "Hang tight while we link up with your LED Ticker."
    is ConnectionState.Failed -> "Try connecting again from the Device tab."
    else -> "Connect to a device on the Device tab to use $tab."
}

private fun showButton(state: ConnectionState): Boolean = when (state) {
    ConnectionState.Connecting, ConnectionState.Discovering -> false
    else -> true
}
