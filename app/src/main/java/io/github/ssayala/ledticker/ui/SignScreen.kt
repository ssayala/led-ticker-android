package io.github.ssayala.ledticker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ssayala.ledticker.ble.BleManager
import io.github.ssayala.ledticker.ble.CharKind
import io.github.ssayala.ledticker.ble.ConnectionState
import io.github.ssayala.ledticker.data.AppState
import io.github.ssayala.ledticker.model.ActiveStatus
import io.github.ssayala.ledticker.model.ActiveTimer
import io.github.ssayala.ledticker.model.Payloads
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class ComposeMode { Sign, Timer }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignScreen(app: AppState, ble: BleManager) {
    val ready = ble.state.collectAsStateValue() is ConnectionState.Ready

    var composeMode by remember { mutableStateOf(ComposeMode.Sign) }
    var customText by remember { mutableStateOf("") }
    var indefinite by remember { mutableStateOf(app.lastDurationSeconds == 0) }
    var signMinutes by remember {
        mutableFloatStateOf(
            when (val s = app.lastDurationSeconds) {
                -1, 0 -> 30f
                else -> (s / 60).coerceIn(1, 99).toFloat()
            }
        )
    }
    var timerMinutes by remember { mutableFloatStateOf(5f) }
    var showEditPresets by remember { mutableStateOf(false) }

    // Auto-clear local state past the deadline (the device's own clear is
    // authoritative; this keeps the card honest until the next refresh).
    LaunchedEffect(app.activeStatus) {
        val expires = app.activeStatus?.expiresAtMillis ?: return@LaunchedEffect
        val delayMs = expires - System.currentTimeMillis()
        if (delayMs > 0) delay(delayMs)
        if (app.activeStatus?.expiresAtMillis == expires) app.activeStatus = null
    }
    LaunchedEffect(app.activeTimer) {
        val ends = app.activeTimer?.endsAtMillis ?: return@LaunchedEffect
        val delayMs = ends - System.currentTimeMillis() + 2500
        if (delayMs > 0) delay(delayMs)
        if (app.activeTimer?.endsAtMillis == ends) app.activeTimer = null
    }

    val trimmed = customText.trim()
    val canWrite = ready
    val canSendCustom = canWrite && trimmed.isNotEmpty() && !trimmed.contains("|") &&
        trimmed.toByteArray(Charsets.UTF_8).size <= Payloads.STATUS_TEXT_MAX_BYTES

    fun sendSign() {
        if (!canSendCustom) return
        app.haptics.tap()
        val seconds = if (indefinite) 0L else signMinutes.roundToInt().toLong() * 60
        app.lastDurationSeconds = seconds.toInt()
        try {
            val data = Payloads.status(trimmed, seconds)
            app.send(ble, CharKind.Status, data, "Sign", confirmHaptic = false)
            val expiresAt = if (seconds == 0L) null else System.currentTimeMillis() + seconds * 1000
            app.activeStatus = ActiveStatus(trimmed, expiresAt)
            customText = ""
        } catch (e: Exception) {
            app.show("Sign: ${e.message}", isError = true)
        }
    }

    fun startTimer() {
        app.haptics.tap()
        val m = timerMinutes.roundToInt().coerceIn(1, 99)
        app.send(ble, CharKind.Command, Payloads.timer(m.toLong()), "Timer", confirmHaptic = false) {
            app.activeTimer = ActiveTimer(System.currentTimeMillis() + m * 60_000L)
            app.activeStatus = null
        }
    }

    fun clearSign() {
        app.haptics.warning()
        app.send(ble, CharKind.Status, Payloads.statusClear(), "Clear sign", confirmHaptic = false)
        app.activeStatus = null
    }

    fun cancelTimer() {
        app.haptics.warning()
        app.send(ble, CharKind.Command, Payloads.timerCancel(), "Cancel timer", confirmHaptic = false)
        app.activeTimer = null
    }

    Scaffold(
        topBar = {
            LedTopBar("Sign", deviceSubtitle(ble)) {
                IconButton(onClick = { showEditPresets = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Edit presets")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { NowShowingSection(app, canWrite, onClearSign = { clearSign() }, onCancelTimer = { cancelTimer() }) }
            item {
                SectionCard(footer = { if (composeMode == ComposeMode.Sign) SignFooter(trimmed) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = composeMode == ComposeMode.Sign,
                                onClick = { composeMode = ComposeMode.Sign },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                            ) { Text("Sign") }
                            SegmentedButton(
                                selected = composeMode == ComposeMode.Timer,
                                onClick = { composeMode = ComposeMode.Timer },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                            ) { Text("Timer") }
                        }
                        when (composeMode) {
                            ComposeMode.Sign -> SignComposer(
                                app = app,
                                customText = customText,
                                onTextChange = { customText = it },
                                onPreset = { app.haptics.tap(); customText = it },
                                indefinite = indefinite,
                                onIndefinite = { indefinite = it },
                                signMinutes = signMinutes,
                                onSignMinutes = { signMinutes = it },
                                canSend = canSendCustom,
                                onSend = { sendSign() },
                            )
                            ComposeMode.Timer -> TimerComposer(
                                timerMinutes = timerMinutes,
                                onTimerMinutes = { timerMinutes = it },
                                enabled = canWrite && app.activeTimer == null,
                                onStart = { startTimer() },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditPresets) {
        EditPresetsDialog(app, onDismiss = { showEditPresets = false })
    }
}

@Composable
private fun NowShowingSection(
    app: AppState,
    canWrite: Boolean,
    onClearSign: () -> Unit,
    onCancelTimer: () -> Unit,
) {
    SectionCard(header = "Now showing") {
        AnimatedContent(targetState = Triple(app.activeTimer, app.activeStatus, Unit), label = "nowShowing") { (timer, status, _) ->
            when {
                timer != null -> TimerCard(timer, canWrite, onCancelTimer)
                status != null -> SignCard(status, canWrite, onClearSign)
                else -> IdleCard()
            }
        }
    }
}

@Composable
private fun SignCard(status: ActiveStatus, canWrite: Boolean, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TintedIcon(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(
                status.text.uppercase(),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                expiryText(status), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClear, enabled = canWrite) {
            Icon(Icons.Filled.Close, contentDescription = "Clear sign")
        }
    }
}

@Composable
private fun TimerCard(timer: ActiveTimer, canWrite: Boolean, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= timer.endsAtMillis) break
            delay(250)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TintedIcon(Icons.Filled.Timer, MaterialTheme.colorScheme.tertiary)
        Column(Modifier.weight(1f)) {
            Text(
                Payloads.countdownLabel((timer.endsAtMillis - now) / 1000.0),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
            Text(
                "Countdown timer running", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onCancel, enabled = canWrite) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel timer")
        }
    }
}

@Composable
private fun IdleCard() {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TintedIcon(Icons.Filled.AutoAwesome, MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text("Ambient display", style = MaterialTheme.typography.bodyLarge)
            Text(
                "No sign set — your device is on its ambient rotation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignComposer(
    app: AppState,
    customText: String,
    onTextChange: (String) -> Unit,
    onPreset: (String) -> Unit,
    indefinite: Boolean,
    onIndefinite: (Boolean) -> Unit,
    signMinutes: Float,
    onSignMinutes: (Float) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
) {
    Text("Presets", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (app.presetTexts.isEmpty()) {
        Text(
            "No presets yet — tap the ⚙ icon above to add some.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            app.presetTexts.forEach { preset ->
                AssistChip(onClick = { onPreset(preset) }, label = { Text(preset.uppercase()) })
            }
        }
    }
    OutlinedTextField(
        value = customText, onValueChange = onTextChange,
        label = { Text("Type a message…") }, singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Until I clear it", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = indefinite, onCheckedChange = onIndefinite)
    }
    if (!indefinite) {
        Row {
            Text("Show for", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${signMinutes.roundToInt()} min", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
        Slider(value = signMinutes, onValueChange = onSignMinutes, valueRange = 1f..99f, steps = 97)
    }
    Button(onClick = onSend, enabled = canSend, modifier = Modifier.fillMaxWidth()) {
        Text("Set sign")
    }
}

@Composable
private fun TimerComposer(
    timerMinutes: Float,
    onTimerMinutes: (Float) -> Unit,
    enabled: Boolean,
    onStart: () -> Unit,
) {
    Row {
        Text("Length", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("${timerMinutes.roundToInt()} min", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
    }
    Slider(value = timerMinutes, onValueChange = onTimerMinutes, valueRange = 1f..99f, steps = 97)
    Button(onClick = onStart, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("Start timer")
    }
}

@Composable
private fun SignFooter(trimmed: String) {
    val bytes = trimmed.toByteArray(Charsets.UTF_8).size
    if (bytes == 0) return
    val over = bytes > Payloads.STATUS_TEXT_MAX_BYTES
    val hasPipe = trimmed.contains("|")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        FooterText(
            when {
                hasPipe -> "Text cannot contain ‘|’."
                over -> "Too long."
                bytes > Payloads.STATUS_STATIC_MAX_BYTES -> "Scrolls on your sign."
                else -> "Shown steady, centered."
            },
        )
        FooterText(
            "$bytes / ${Payloads.STATUS_TEXT_MAX_BYTES}",
            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditPresetsDialog(app: AppState, onDismiss: () -> Unit) {
    var newPreset by remember { mutableStateOf("") }
    val trimmed = newPreset.trim()
    val isValid = trimmed.isNotEmpty() && !trimmed.contains("|") &&
        trimmed.toByteArray(Charsets.UTF_8).size <= Payloads.STATUS_TEXT_MAX_BYTES &&
        trimmed !in app.presetTexts

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Presets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                app.presetTexts.forEach { preset ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(preset, Modifier.weight(1f))
                        IconButton(onClick = { app.setPresets(app.presetTexts - preset) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Delete $preset")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newPreset, onValueChange = { newPreset = it },
                        label = { Text("New preset") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { app.setPresets(app.presetTexts + trimmed); newPreset = "" },
                        enabled = isValid,
                    ) { Text("Add") }
                }
                FooterText("Local to this app — never sent to the device.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun expiryText(status: ActiveStatus): String {
    val expires = status.expiresAtMillis ?: return "Indefinite"
    val secs = ((expires - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    val mins = secs / 60
    return when {
        mins <= 0 -> "Expires soon"
        mins == 1L -> "Expires in 1 minute"
        mins < 60 -> "Expires in $mins minutes"
        else -> "Expires in ${mins / 60}h ${mins % 60}m"
    }
}
