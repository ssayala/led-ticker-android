package io.github.ssayala.ledticker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.ssayala.ledticker.ble.BleManager
import io.github.ssayala.ledticker.ble.CharKind
import io.github.ssayala.ledticker.ble.ConnectionState
import io.github.ssayala.ledticker.data.AppState
import io.github.ssayala.ledticker.model.Payloads
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StocksScreen(app: AppState, ble: BleManager) {
    val ready = ble.state.collectAsStateValue() is ConnectionState.Ready
    var newTicker by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val trimmed = newTicker.trim().uppercase()
    val isValidNew = trimmed.isNotEmpty() &&
        trimmed.toByteArray(Charsets.UTF_8).size <= Payloads.TICKER_MAX_LEN &&
        trimmed !in app.tickers &&
        app.tickers.size < Payloads.TICKER_MAX_COUNT
    val isDirty = app.tickers != app.baselineTickers
    val canSave = ready && app.tickers.isNotEmpty() && isDirty

    fun addTicker() {
        if (!isValidNew) {
            invalidReason(app, trimmed)?.let { app.show(it, isError = true) }
            return
        }
        app.haptics.tap()
        app.tickers = app.tickers + trimmed
        newTicker = ""
    }

    fun save() {
        try {
            app.send(ble, CharKind.Tickers, Payloads.tickers(app.tickers), "Symbols")
            app.baselineTickers = app.tickers
        } catch (e: Exception) {
            app.show("Symbols: ${e.message}", isError = true)
        }
    }

    Scaffold(
        topBar = {
            LedTopBar("Stocks", deviceSubtitle(ble)) {
                TextButton(onClick = { save() }, enabled = canSave) { Text("Save") }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                if (!ready) return@PullToRefreshBox
                refreshing = true
                app.send(ble, CharKind.Command, Payloads.command("reload"), "Reload", confirmSuccess = true)
                scope.launch { delay(1200); refreshing = false }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                item {
                    SectionCard(
                        header = "Symbols",
                        footer = { StocksFooter(app, trimmed) },
                    ) {
                        app.tickers.forEachIndexed { i, t ->
                            if (i > 0) HorizontalDivider()
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    t, Modifier.weight(1f),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                IconButton(onClick = { app.tickers = app.tickers.filterIndexed { j, _ -> j != i } }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove $t")
                                }
                            }
                        }
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = newTicker,
                                onValueChange = { newTicker = it },
                                label = { Text("Add symbol (e.g. NVDA)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    imeAction = ImeAction.Done,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { addTicker() }, enabled = isValidNew) { Text("Add") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StocksFooter(app: AppState, trimmedNew: String) {
    val reason = invalidReason(app, trimmedNew)
    when {
        reason != null -> FooterText(reason, color = MaterialTheme.colorScheme.error)
        app.tickers != app.baselineTickers -> {
            val diff = changeCount(app)
            FooterText(
                "$diff unsaved change${if (diff == 1) "" else "s"} — tap Save to push to device.",
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        else -> FooterText("${app.tickers.size} of ${Payloads.TICKER_MAX_COUNT}. Pull down to refresh quotes.")
    }
}

private fun invalidReason(app: AppState, trimmedNew: String): String? {
    if (trimmedNew.isEmpty()) return null
    if (trimmedNew in app.tickers) return "Symbol already added."
    if (trimmedNew.toByteArray(Charsets.UTF_8).size > Payloads.TICKER_MAX_LEN)
        return "Too long (max ${Payloads.TICKER_MAX_LEN} chars)."
    if (app.tickers.size >= Payloads.TICKER_MAX_COUNT)
        return "Symbol list is full (max ${Payloads.TICKER_MAX_COUNT})."
    return null
}

private fun changeCount(app: AppState): Int {
    val baseline = app.baselineTickers.toSet()
    val current = app.tickers.toSet()
    val added = (current - baseline).size
    val removed = (baseline - current).size
    return maxOf(added + removed, 1)
}
