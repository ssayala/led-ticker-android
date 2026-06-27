package io.github.ssayala.ledticker.model

/**
 * A weather location as the device stores it: coordinates plus the short
 * label shown on the matrix. The app geocodes place names to coordinates
 * (see LocationSearch); the firmware never resolves names itself. Wire form
 * is `lat,lon,label`.
 */
data class WeatherLocation(
    val latitude: Double,
    val longitude: Double,
    val label: String,
) {
    /** Stable identity for lists; the wire string is unique per entry. */
    val id: String get() = wire

    /** `lat,lon,label` — exactly what the firmware parses. */
    val wire: String get() = "${fmt(latitude)},${fmt(longitude)},$label"

    companion object {
        operator fun invoke(latitude: Double, longitude: Double, rawLabel: String): WeatherLocation {
            // '|' separates entries and the first two commas are field
            // delimiters, so a '|' in the label would corrupt the payload.
            return WeatherLocation(latitude, longitude, rawLabel.replace("|", ""))
        }

        /** Parse one `lat,lon,label` entry; null on malformed/out-of-range. */
        fun fromWire(entry: String): WeatherLocation? {
            // limit 3 mirrors the firmware: only the first two commas are
            // delimiters (the label itself may contain commas).
            val parts = entry.split(",", limit = 3)
            if (parts.size != 3) return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lon = parts[1].trim().toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val label = parts[2].trim()
            if (label.isEmpty()) return null
            return WeatherLocation(lat, lon, label)
        }

        /** Up to 4 decimals (~11 m), trailing zeros trimmed. */
        private fun fmt(value: Double): String {
            // Force US locale so the decimal separator is always '.' — a
            // comma separator (some locales) would break the wire format.
            var s = "%.4f".format(java.util.Locale.US, value)
            if (s.contains(".")) {
                s = s.trimEnd('0').trimEnd('.')
            }
            return s
        }
    }
}
