package io.github.ssayala.ledticker

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import io.github.ssayala.ledticker.data.AppState
import io.github.ssayala.ledticker.ui.RootScreen
import io.github.ssayala.ledticker.ui.theme.LedTickerTheme

class MainActivity : ComponentActivity() {

    private val appState: AppState by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        (application as LedTickerApp).ble.onPermissionsResolved()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ble = (application as LedTickerApp).ble
        requestBlePermissionsIfNeeded()

        setContent {
            LedTickerTheme {
                RootScreen(app = appState, ble = ble)
            }
        }
    }

    private fun requestBlePermissionsIfNeeded() {
        val needed = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
