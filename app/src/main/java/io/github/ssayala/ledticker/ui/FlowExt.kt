package io.github.ssayala.ledticker.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Sugar: collect a StateFlow and return just its current value. */
@Composable
fun <T> StateFlow<T>.collectAsStateValue(): T = collectAsStateWithLifecycle().value
