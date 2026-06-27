package io.github.ssayala.ledticker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.ssayala.ledticker.ble.BleManager
import io.github.ssayala.ledticker.ble.CharKind
import io.github.ssayala.ledticker.data.AppState
import io.github.ssayala.ledticker.model.Categories
import io.github.ssayala.ledticker.model.DeviceMode
import io.github.ssayala.ledticker.model.DisplaySettings
import io.github.ssayala.ledticker.model.Payloads
import io.github.ssayala.ledticker.model.PowerState
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPEED_PRESETS = intArrayOf(150, 70, 40, 30) // Slow · Normal · Fast · Very fast

@Composable
fun DisplayScreen(app: AppState, ble: BleManager) {
    // Local category state, applied through a 1s debounce so one gesture =
    // one Mode write (each write is a full mode transition on the device).
    var pending by remember { mutableStateOf(app.baselineCategories) }
    LaunchedEffect(app.baselineCategories) { pending = app.baselineCategories }
    LaunchedEffect(pending) {
        if (pending == app.baselineCategories) return@LaunchedEffect
        kotlinx.coroutines.delay(1000)
        if (pending != app.baselineCategories) {
            app.send(ble, CharKind.Mode, Payloads.mode(pending), "Display")
            app.baselineCategories = pending
            app.deviceMode = if (pending.isEmpty) DeviceMode.None else DeviceMode.Content(pending)
        }
    }

    Scaffold(topBar = { LedTopBar("Display", deviceSubtitle(ble)) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { StatusSection(app, ble) }
            item {
                CategoriesSection(app, pending, onToggle = { cat, on ->
                    app.haptics.tap()
                    pending = if (on) pending + cat else pending - cat
                })
            }
            if (app.displaySettings != null) {
                item { AppearanceSection(app, ble) }
            }
        }
    }
}

@Composable
private fun StatusSection(app: AppState, ble: BleManager) {
    val off = (app.displayPower ?: PowerState.On) == PowerState.Off
    SectionCard(footer = { FooterText("Turns back on after a power cycle.") }) {
        StatusRow(statusIcon(app, off), statusTint(app, off), statusTitle(off), statusBody(app, off))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Screen", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = !off, onCheckedChange = { on ->
                val target = if (on) PowerState.On else PowerState.Off
                app.displayPower = target  // optimistic
                app.send(ble, CharKind.Power, Payloads.power(target), "Display")
            })
        }
    }
}

@Composable
private fun CategoriesSection(
    app: AppState,
    pending: Categories,
    onToggle: (Categories, Boolean) -> Unit,
) {
    SectionCard(
        header = "Categories",
        footer = { FooterText("Turn all off to show signs only.") },
    ) {
        CategoryRow("Stocks", Categories.stocks, pending, prereq(app, Categories.stocks), onToggle)
        CategoryRow("Weather", Categories.weather, pending, prereq(app, Categories.weather), onToggle)
        CategoryRow("Clock", Categories.clock, pending, prereq(app, Categories.clock), onToggle)
    }
}

@Composable
private fun CategoryRow(
    label: String,
    category: Categories,
    pending: Categories,
    prereq: Pair<Boolean, String?>,
    onToggle: (Categories, Boolean) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = pending contains category,
                onCheckedChange = { onToggle(category, it) },
                enabled = prereq.first,
            )
        }
        prereq.second?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppearanceSection(app: AppState, ble: BleManager) {
    var brightness by remember { mutableFloatStateOf((app.displaySettings?.brightness ?: 2).toFloat()) }
    var speedIdx by remember {
        mutableFloatStateOf(
            Payloads.nearestSpeedPresetIndex(SPEED_PRESETS, app.displaySettings?.scrollMs ?: 70).toFloat()
        )
    }
    LaunchedEffect(app.displaySettings) {
        app.displaySettings?.let { s ->
            brightness = s.brightness.toFloat()
            speedIdx = Payloads.nearestSpeedPresetIndex(SPEED_PRESETS, s.scrollMs).toFloat()
        }
    }

    fun writeSettings() {
        val s = DisplaySettings(brightness.roundToInt(), SPEED_PRESETS[speedIdx.roundToInt()])
        if (s == app.displaySettings) return
        app.displaySettings = s
        app.send(ble, CharKind.Display, Payloads.displaySettings(s), "Appearance")
    }

    SectionCard(header = "Appearance") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row {
                Text("Brightness", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("${brightness.roundToInt()} / 15", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = brightness, onValueChange = { brightness = it },
                valueRange = 0f..15f, onValueChangeFinished = { writeSettings() },
            )
            Row {
                Text("Scroll speed", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(speedLabel(SPEED_PRESETS[speedIdx.roundToInt()]), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = speedIdx, onValueChange = { speedIdx = it },
                valueRange = 0f..(SPEED_PRESETS.size - 1).toFloat(),
                steps = SPEED_PRESETS.size - 2,
                onValueChangeFinished = { writeSettings() },
            )
        }
    }
}

// MARK: - Helpers

private fun prereq(app: AppState, category: Categories): Pair<Boolean, String?> {
    val wifi = app.ssid.isNotEmpty()
    val apiKey = app.apikey.isNotEmpty()
    return when (category) {
        Categories.stocks ->
            (wifi && apiKey) to (if (wifi && apiKey) null else "Requires Wi-Fi & Finnhub API key")
        else -> wifi to (if (wifi) null else "Requires Wi-Fi")
    }
}

private fun speedLabel(ms: Int): String = when {
    ms < 35 -> "Very fast"
    ms < 55 -> "Fast"
    ms < 110 -> "Normal"
    else -> "Slow"
}

private fun statusIcon(app: AppState, off: Boolean): ImageVector = when {
    off -> Icons.Filled.VisibilityOff
    app.activeTimer != null -> Icons.Filled.Timer
    app.activeStatus != null -> Icons.Filled.CheckCircle
    else -> when (app.deviceMode) {
        is DeviceMode.Content -> Icons.Filled.CheckCircle
        DeviceMode.None -> Icons.Filled.Bedtime
        DeviceMode.Setup -> Icons.Filled.Warning
        DeviceMode.Unknown -> Icons.AutoMirrored.Filled.HelpOutline
    }
}

@Composable
private fun statusTint(app: AppState, off: Boolean): Color = when {
    off -> MaterialTheme.colorScheme.outline
    app.activeTimer != null -> MaterialTheme.colorScheme.tertiary
    app.activeStatus != null -> MaterialTheme.colorScheme.primary
    else -> when (app.deviceMode) {
        is DeviceMode.Content -> MaterialTheme.colorScheme.primary
        DeviceMode.Setup -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
}

private fun statusTitle(off: Boolean): String = if (off) "Display off" else "Currently showing"

private fun statusBody(app: AppState, off: Boolean): String {
    if (off) return "Screen is blank — turn Display on to resume."
    if (app.activeTimer != null) return "Timer running"
    if (app.activeStatus != null) return "Showing your sign"
    return when (val m = app.deviceMode) {
        is DeviceMode.Content -> humanReadable(m.categories)
        DeviceMode.None -> "Sign only — no ambient rotation"
        DeviceMode.Setup -> when {
            app.ssid.isEmpty() -> "Setup needed — configure Wi-Fi"
            app.apikey.isEmpty() -> "Setup needed — configure Finnhub key"
            else -> "Setup needed"
        }
        DeviceMode.Unknown -> "—"
    }
}

private fun humanReadable(c: Categories): String {
    if (c == Categories.all) return "All categories"
    val parts = buildList {
        if (c contains Categories.stocks) add("Stocks")
        if (c contains Categories.weather) add("Weather")
        if (c contains Categories.clock) add("Clock")
    }
    return parts.joinToString(", ")
}
