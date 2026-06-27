package io.github.ssayala.ledticker.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ssayala.ledticker.ble.BleManager
import io.github.ssayala.ledticker.ble.ConnectionState
import io.github.ssayala.ledticker.data.AppState
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    Device("Device", Icons.Filled.SettingsRemote),
    Display("Display", Icons.Filled.DisplaySettings),
    Stocks("Stocks", Icons.AutoMirrored.Filled.ShowChart),
    Weather("Weather", Icons.Filled.Cloud),
    Sign("Sign", Icons.Filled.Campaign),
}

@Composable
fun RootScreen(app: AppState, ble: BleManager) {
    val state by ble.state.collectAsStateWithLifecycle()
    val authed by ble.authed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(Tab.Device) }

    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showPin by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf(false) }

    // Ready transitions: buzz, refresh from device, or wipe stale state.
    LaunchedEffect(state) {
        when (state) {
            ConnectionState.Ready -> {
                app.haptics.success()
                val resetId = app.recentlyResetDevice?.id
                if (resetId != null && resetId == ble.activeDevice.value?.id) {
                    app.recentlyResetDevice = null
                }
                app.refreshFromDevice(ble)
            }
            else -> app.clearDeviceState()
        }
    }

    // Toasts → snackbar.
    LaunchedEffect(app.toast?.id) {
        app.toast?.let { snackbarHost.showSnackbar(it.text) }
    }

    // PIN fallback wiring.
    LaunchedEffect(Unit) {
        ble.authRequired.collect {
            pinError = false
            showPin = true
        }
    }
    LaunchedEffect(Unit) {
        ble.pinRejected.collect { pinError = true }
    }
    LaunchedEffect(authed) {
        if (authed) {
            showPin = false
            pinError = false
        }
    }

    if (showPin) {
        PinDialog(
            error = pinError,
            onSubmit = { pin -> pinError = false; ble.submitPin(pin) },
            onDismiss = { showPin = false },
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            val ready = state is ConnectionState.Ready
            when (selectedTab) {
                Tab.Device -> DeviceScreen(app, ble)
                Tab.Display -> Gated(ready, Tab.Display, state, { selectedTab = Tab.Device }, context) {
                    DisplayScreen(app, ble)
                }
                Tab.Stocks -> Gated(ready, Tab.Stocks, state, { selectedTab = Tab.Device }, context) {
                    StocksScreen(app, ble)
                }
                Tab.Weather -> Gated(ready, Tab.Weather, state, { selectedTab = Tab.Device }, context) {
                    WeatherScreen(app, ble)
                }
                Tab.Sign -> Gated(ready, Tab.Sign, state, { selectedTab = Tab.Device }, context) {
                    SignScreen(app, ble)
                }
            }
        }
    }
}

@Composable
private fun Gated(
    ready: Boolean,
    tab: Tab,
    state: ConnectionState,
    onOpenDevice: () -> Unit,
    context: android.content.Context,
    content: @Composable () -> Unit,
) {
    if (ready) {
        content()
    } else {
        DisconnectedScreen(
            tabName = tab.label,
            tabIcon = tab.icon,
            state = state,
            onOpenDevice = onOpenDevice,
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            },
        )
    }
}
