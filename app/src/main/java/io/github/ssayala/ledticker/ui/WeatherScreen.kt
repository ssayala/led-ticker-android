package io.github.ssayala.ledticker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ssayala.ledticker.ble.BleManager
import io.github.ssayala.ledticker.ble.CharKind
import io.github.ssayala.ledticker.ble.ConnectionState
import io.github.ssayala.ledticker.data.AppState
import io.github.ssayala.ledticker.data.GeoSuggestion
import io.github.ssayala.ledticker.data.LocationSearch
import io.github.ssayala.ledticker.model.Payloads
import kotlinx.coroutines.launch

@Composable
fun WeatherScreen(app: AppState, ble: BleManager) {
    val ready = ble.state.collectAsStateValue() is ConnectionState.Ready
    val context = LocalContext.current
    val search = remember { LocationSearch(context) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoSuggestion>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    val canAddMore = app.locations.size < Payloads.LOCATION_MAX_COUNT
    val isDirty = app.locations != app.baselineLocations
    val canSave = ready && app.locations.isNotEmpty() && isDirty

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        scope.launch {
            results = runCatching { search.search(q) }.getOrDefault(emptyList())
            searching = false
        }
    }

    fun add(s: GeoSuggestion) {
        if (!canAddMore) {
            app.show("Location list is full.", isError = true); return
        }
        val loc = search.toLocation(s, Payloads.LOCATION_LABEL_MAX_BYTES)
        if (app.locations.contains(loc)) {
            app.show("“${loc.label}” is already added.", isError = true); return
        }
        app.haptics.tap()
        app.locations = app.locations + loc
        query = ""; results = emptyList()
    }

    fun save() {
        try {
            app.send(ble, CharKind.Locations, Payloads.locations(app.locations), "Locations")
            app.baselineLocations = app.locations
        } catch (e: Exception) {
            app.show("Locations: ${e.message}", isError = true)
        }
    }

    Scaffold(
        topBar = {
            LedTopBar("Weather", deviceSubtitle(ble)) {
                TextButton(onClick = { save() }, enabled = canSave) { Text("Save") }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionCard(
                    header = "Locations",
                    footer = { WeatherFooter(app, canAddMore) },
                ) {
                    if (app.locations.isEmpty()) {
                        Text(
                            "No locations yet — search a city or ZIP below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    app.locations.forEachIndexed { i, loc ->
                        if (i > 0) HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(loc.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { app.locations = app.locations.filterIndexed { j, _ -> j != i } }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${loc.label}")
                            }
                        }
                    }
                }
            }

            if (canAddMore) {
                item {
                    SectionCard(header = "Add") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("Add a city or ZIP") },
                                singleLine = true,
                                trailingIcon = {
                                    if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    else IconButton(onClick = { runSearch() }) {
                                        Icon(Icons.Filled.Search, contentDescription = "Search")
                                    }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            results.forEach { s ->
                                Column(
                                    Modifier.fillMaxWidth().clickable { add(s) }.padding(vertical = 6.dp),
                                ) {
                                    Text(s.title, style = MaterialTheme.typography.bodyLarge)
                                    if (s.subtitle.isNotEmpty()) {
                                        Text(
                                            s.subtitle, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (query.isNotEmpty() && results.isEmpty() && !searching) {
                                Text(
                                    "No matches — tap search to look up “${query.trim()}”.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherFooter(app: AppState, canAddMore: Boolean) {
    Column {
        val diff = changeCount(app)
        when {
            !canAddMore -> FooterText("Location list is full (max ${Payloads.LOCATION_MAX_COUNT}). Remove one to add another.")
            diff > 0 -> FooterText(
                "$diff unsaved change${if (diff == 1) "" else "s"} — tap Save to push to device.",
                color = MaterialTheme.colorScheme.tertiary,
            )
            else -> FooterText("${app.locations.size} of ${Payloads.LOCATION_MAX_COUNT}. Search a city or ZIP to add.")
        }
        Text(
            "Weather data from MET Norway · CC BY 4.0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun changeCount(app: AppState): Int {
    if (app.locations == app.baselineLocations) return 0
    val baseline = app.baselineLocations.toSet()
    val current = app.locations.toSet()
    val added = (current - baseline).size
    val removed = (baseline - current).size
    return maxOf(added + removed, 1)
}
