package io.github.ssayala.ledticker.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Static fallback palette (pre-Material-You, or if dynamic color is off).
// Seeded around an LED-amber/green so the brand still reads through.
private val BrandGreen = Color(0xFF2E7D32)
private val BrandAmber = Color(0xFFF59E0B)

private val LightColors = lightColorScheme(
    primary = BrandGreen,
    secondary = BrandAmber,
    tertiary = Color(0xFF1565C0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BD88F),
    secondary = Color(0xFFFFCB6B),
    tertiary = Color(0xFF90CAF9),
)

/**
 * App theme. On Android 12+ (our minimum) it adopts the user's Material You
 * wallpaper colors via dynamic color; otherwise it falls back to the brand
 * palette. Either way it tracks the system light/dark setting.
 */
@Composable
fun LedTickerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
