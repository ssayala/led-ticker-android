package io.github.ssayala.ledticker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import io.github.ssayala.ledticker.ble.BleManager
import io.github.ssayala.ledticker.ble.CharKind
import io.github.ssayala.ledticker.ble.ConnectionState
import io.github.ssayala.ledticker.data.AppState
import io.github.ssayala.ledticker.model.KnownDevice
import io.github.ssayala.ledticker.model.Payloads
import io.github.ssayala.ledticker.model.Timezones
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DeviceScreen(app: AppState, ble: BleManager) {
    val state = ble.state.collectAsStateValue()
    if (state is ConnectionState.Ready) DeviceSettings(app, ble) else DevicePicker(app, ble)
}

// MARK: - Picker

private enum class RowState { Connected, Connecting, Failed, InRange, OutOfRange }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePicker(app: AppState, ble: BleManager) {
    val known = ble.knownDevices.collectAsStateValue()
    val inRange = ble.inRange.collectAsStateValue()
    val advertised = ble.advertisedNames.collectAsStateValue()
    val active = ble.activeDevice.collectAsStateValue()
    val connectingTo = ble.connectingTo.collectAsStateValue()
    val state = ble.state.collectAsStateValue()
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        ble.startAmbientScan()
        onDispose { ble.stopAmbientScan() }
    }

    var renaming by remember { mutableStateOf<KnownDevice?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<KnownDevice?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    fun rowState(device: KnownDevice): RowState {
        if (active?.id == device.id) {
            when (state) {
                ConnectionState.Ready -> return RowState.Connected
                ConnectionState.Connecting, ConnectionState.Discovering, ConnectionState.Scanning ->
                    return RowState.Connecting
                is ConnectionState.Failed -> return RowState.Failed
                else -> {}
            }
        }
        if (connectingTo == device.id) return RowState.Connecting
        return if (device.id in inRange) RowState.InRange else RowState.OutOfRange
    }

    val nearbyUnknown = inRange.filter { id -> known.none { it.id == id } }
        .sorted()
        .map { id -> id to (advertised[id] ?: KnownDevice.PLACEHOLDER_NAME) }

    Scaffold(topBar = { LedTopBar("Devices") }) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                ble.restartAmbientScan()
                scope.launch { delay(900); refreshing = false }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                app.recentlyResetDevice?.let { reset ->
                    item { ResetHintCard(reset.friendlyName) { app.recentlyResetDevice = null } }
                }
                item {
                    SectionCard(
                        header = "Known Devices",
                        footer = { FooterText(footerHint(state, known.isEmpty())) },
                    ) {
                        if (known.isEmpty() && nearbyUnknown.isEmpty()) {
                            Text(
                                "No devices yet. Move closer to your LED Ticker and pull to scan.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        } else {
                            known.forEachIndexed { i, device ->
                                if (i > 0) HorizontalDivider()
                                KnownDeviceRow(
                                    device = device,
                                    rowState = rowState(device),
                                    firmwareVersion = app.firmwareVersion,
                                    onTap = {
                                        if (!(active?.id == device.id && state is ConnectionState.Ready))
                                            ble.connect(device)
                                    },
                                    onDisconnect = { app.haptics.tap(); ble.disconnect() },
                                    onRename = { renaming = device; renameDraft = device.friendlyName },
                                    onForget = { forgetting = device },
                                )
                            }
                            nearbyUnknown.forEach { (id, name) ->
                                HorizontalDivider()
                                NearbyRow(
                                    name = name,
                                    connecting = connectingTo == id ||
                                        (active?.id == id && state is ConnectionState.Connecting),
                                    onTap = { ble.connectToAddress(id, name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { device ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { ble.rename(device, renameDraft); renaming = null },
                    enabled = renameDraft.trim().isNotEmpty(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    forgetting?.let { device ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget '${device.friendlyName}'?") },
            text = {
                Text(
                    "This removes it from the app. To fully unpair, also forget it in " +
                        "Android Settings → Connected devices. You'll need to scan to re-add it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.haptics.warning(); ble.forget(device); forgetting = null
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ResetHintCard(name: String, onDismiss: () -> Unit) {
    SectionCard {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Filled.Warning, contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Column(Modifier.weight(1f)) {
                Text("$name was reset", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "A new PIN is scrolling on its display. If reconnecting fails, forget the " +
                        "device in Android Settings → Bluetooth, then pair again with the new PIN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun KnownDeviceRow(
    device: KnownDevice,
    rowState: RowState,
    firmwareVersion: String,
    onTap: () -> Unit,
    onDisconnect: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { StateGlyph(rowState) }
        Column(Modifier.weight(1f)) {
            Text(device.friendlyName, style = MaterialTheme.typography.bodyLarge)
            Text(
                statusText(rowState, firmwareVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (rowState == RowState.Connected) {
                    DropdownMenuItem(
                        text = { Text("Disconnect") },
                        onClick = { menu = false; onDisconnect() },
                    )
                }
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Forget") }, onClick = { menu = false; onForget() })
            }
        }
    }
}

@Composable
private fun NearbyRow(name: String, connecting: Boolean, onTap: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (connecting) "connecting…" else "Tap to add",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StateGlyph(s: RowState) {
    when (s) {
        RowState.Connected -> Icon(
            Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary
        )
        RowState.Connecting -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        RowState.Failed -> Icon(
            Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.tertiary
        )
        RowState.InRange -> Icon(
            Icons.Filled.Circle, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp),
        )
        RowState.OutOfRange -> Icon(
            Icons.Outlined.Circle, null, tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(12.dp),
        )
    }
}

private fun statusText(s: RowState, fw: String): String = when (s) {
    RowState.Connected -> if (fw.isEmpty()) "connected" else "connected · v$fw"
    RowState.Connecting -> "connecting…"
    RowState.Failed -> "couldn't connect"
    RowState.InRange -> "in range"
    RowState.OutOfRange -> "not in range"
}

private fun footerHint(state: ConnectionState, knownEmpty: Boolean): String = when {
    state is ConnectionState.PoweredOff -> "Bluetooth is off."
    state is ConnectionState.Unauthorized -> "Nearby-devices permission denied."
    knownEmpty -> "Pull down to scan for LED Tickers nearby."
    else -> "Tap ⋮ on a row to rename or forget."
}

// MARK: - Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSettings(app: AppState, ble: BleManager) {
    val active = ble.activeDevice.collectAsStateValue()
    var revealKey by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }

    val wifiDirty = app.ssid != app.baselineSsid || app.password != app.baselinePassword
    val apiKeyDirty = app.apikey != app.baselineApiKey
    val anyDirty = wifiDirty || apiKeyDirty

    fun saveDirty() {
        if (wifiDirty) {
            try {
                val data = Payloads.wifi(app.ssid, app.password)
                app.send(ble, CharKind.Wifi, data, "Wi-Fi", confirmSuccess = true)
                app.baselineSsid = app.ssid; app.baselinePassword = app.password
            } catch (e: Exception) {
                app.show("Wi-Fi: ${e.message}", isError = true); return
            }
        }
        if (apiKeyDirty) {
            try {
                val data = Payloads.apiKey(app.apikey)
                app.send(ble, CharKind.ApiKey, data, "API Key", confirmSuccess = true)
                app.baselineApiKey = app.apikey
            } catch (e: Exception) {
                app.show("API Key: ${e.message}", isError = true)
            }
        }
    }

    Scaffold(
        topBar = {
            LedTopBar(active?.friendlyName ?: "Device") {
                TextButton(onClick = { saveDirty() }, enabled = anyDirty) { Text("Save") }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionCard {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(active?.friendlyName ?: "Device", style = MaterialTheme.typography.bodyLarge)
                            if (app.firmwareVersion.isNotEmpty()) {
                                Text(
                                    "Firmware v${app.firmwareVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = { app.haptics.tap(); ble.disconnect() }) { Text("Disconnect") }
                    }
                }
            }

            item {
                SectionCard(header = "Wi-Fi") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = app.ssid, onValueChange = { app.updateSsid(it) },
                            label = { Text("SSID") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = app.password, onValueChange = { app.updatePassword(it) },
                            label = {
                                Text(if (app.baselineSsid.isEmpty()) "Password" else "Enter password to change")
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item {
                SectionCard(
                    header = "Finnhub API Key",
                    footer = { FooterText("Get a free key at finnhub.io. The key is yours and governed by Finnhub's terms.") },
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = app.apikey, onValueChange = { app.updateApiKey(it) },
                            label = { Text("API Key") }, singleLine = true,
                            visualTransformation = if (revealKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { revealKey = !revealKey }) {
                                    Icon(
                                        if (revealKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (revealKey) "Hide key" else "Show key",
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (app.deviceTimezone != null) {
                item { TimezoneSection(app, ble) }
            }

            item {
                SectionCard {
                    TextButton(
                        onClick = { showReset = true },
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text("Reset Device", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item { Spacer(Modifier.size(8.dp)) }
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Reset everything on the device?") },
            text = {
                Text(
                    "Wipes Wi-Fi, API key, tickers, weather locations, active sign, and cached " +
                        "data from the device. (Your local preset chips are not affected.)",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showReset = false
                    app.haptics.warning()
                    val device = ble.activeDevice.value
                    app.send(ble, CharKind.Command, Payloads.command("reset"), "Reset") {
                        app.recentlyResetDevice = device
                        ble.disconnect()
                    }
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimezoneSection(app: AppState, ble: BleManager) {
    var expanded by remember { mutableStateOf(false) }
    val current = app.deviceTimezone ?: ""
    val currentLabel = Timezones.label(current) ?: if (current.isEmpty()) "Not set" else "Custom ($current)"

    SectionCard(header = "Clock") {
        Box(Modifier.padding(16.dp)) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = currentLabel, onValueChange = {}, readOnly = true,
                    label = { Text("Timezone") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    Timezones.presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.label) },
                            onClick = {
                                expanded = false
                                if (preset.posix != app.deviceTimezone) {
                                    runCatching { Payloads.timezone(preset.posix) }.getOrNull()?.let { data ->
                                        app.deviceTimezone = preset.posix
                                        app.send(ble, CharKind.Timezone, data, "Timezone", confirmSuccess = true)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
