package io.github.ssayala.ledticker.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import io.github.ssayala.ledticker.model.WeatherLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** One geocoded candidate the user can pick from the Weather tab. */
data class GeoSuggestion(
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Resolves a typed city/ZIP to coordinate candidates via Android's built-in
 * Geocoder — the Android counterpart of the iOS MKLocalSearch flow. The
 * device still receives only `lat,lon,label`; no third-party key is needed.
 */
class LocationSearch(context: Context) {
    private val geocoder = Geocoder(context.applicationContext, Locale.getDefault())

    val isAvailable: Boolean get() = Geocoder.isPresent()

    /** Up to [max] candidates for [query]; empty when nothing matches. */
    suspend fun search(query: String, max: Int = 8): List<GeoSuggestion> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val addresses = geocode(q, max)
        return addresses.mapNotNull { it.toSuggestion() }
    }

    /** Clip a label to at most [maxBytes] UTF-8 bytes without splitting a char. */
    fun clipLabel(name: String, maxBytes: Int): String {
        var out = name
        while (out.toByteArray(Charsets.UTF_8).size > maxBytes) out = out.dropLast(1)
        return out
    }

    fun toLocation(s: GeoSuggestion, maxLabelBytes: Int): WeatherLocation =
        WeatherLocation(s.latitude, s.longitude, rawLabel = clipLabel(s.title, maxLabelBytes))

    private suspend fun geocode(query: String, max: Int): List<Address> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(query, max, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                })
            }
        }
        // API 31/32: the blocking overload, off the main thread.
        return withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            runCatching { geocoder.getFromLocationName(query, max) }.getOrNull().orEmpty()
        }
    }

    private fun Address.toSuggestion(): GeoSuggestion? {
        // Prefer a town name so a ZIP shows "Redmond", not "98052".
        val title = locality ?: subAdminArea ?: featureName ?: adminArea ?: return null
        val subtitleParts = listOfNotNull(
            adminArea?.takeIf { it != title },
            countryName,
        )
        return GeoSuggestion(
            title = title,
            subtitle = subtitleParts.joinToString(", "),
            latitude = latitude,
            longitude = longitude,
        )
    }
}
