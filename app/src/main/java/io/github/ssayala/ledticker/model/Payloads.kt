package io.github.ssayala.ledticker.model

import kotlin.math.abs
import kotlin.math.ceil

/** UTF-8 byte length, matching Swift's `String.utf8.count`. */
internal val String.utf8Size: Int get() = toByteArray(Charsets.UTF_8).size

/**
 * Category bitmask — 1:1 with the firmware's BIT_STOCKS/WEATHER/CLOCK.
 * Encoded on the wire as "all", a single token, or a comma-joined subset.
 * Bit 1 (the old "messages" bit) is intentionally skipped so any persisted
 * values from an earlier app version still decode unchanged.
 */
@JvmInline
value class Categories(val rawValue: Int) {
    infix fun contains(other: Categories) = (rawValue and other.rawValue) == other.rawValue
    operator fun plus(other: Categories) = Categories(rawValue or other.rawValue)
    operator fun minus(other: Categories) = Categories(rawValue and other.rawValue.inv())
    val isEmpty: Boolean get() = rawValue == 0

    companion object {
        val none = Categories(0)
        val stocks = Categories(1 shl 0)
        val weather = Categories(1 shl 2)
        val clock = Categories(1 shl 3)
        val all = Categories(stocks.rawValue or weather.rawValue or clock.rawValue)
    }
}

/**
 * What the device reports for the Mode characteristic.
 * - [Content] while scrolling categories
 * - [None] for sign-only mode — no ambient rotation, device sits on the
 *   bouncing-pixel idle state between signs (firmware's MODE_IDLE)
 * - [Setup] while in MODE_SETUP (firmware shows a configuration hint)
 * - [Unknown] for empty / NUL / unparseable payloads
 */
sealed interface DeviceMode {
    data class Content(val categories: Categories) : DeviceMode
    data object None : DeviceMode
    data object Setup : DeviceMode
    data object Unknown : DeviceMode
}

/** On/off state of the device's display (the BLE Power characteristic). */
enum class PowerState(val wire: String) {
    On("on"), Off("off");

    companion object {
        fun fromWire(s: String): PowerState? = entries.firstOrNull { it.wire == s }
    }
}

/** Display-characteristic settings. brightness 0–15, scrollMs 20–500. */
data class DisplaySettings(val brightness: Int, val scrollMs: Int)

/**
 * Active sign read back from the device. Stores the absolute deadline (epoch
 * millis) so the UI can render a live countdown without re-reads.
 * [expiresAtMillis] == null means indefinite.
 */
data class ActiveStatus(val text: String, val expiresAtMillis: Long?)

/** Local view of a running countdown; [endsAtMillis] is the 0:00 instant. */
data class ActiveTimer(val endsAtMillis: Long)

/** Picker entry mapping a human label to the POSIX TZ string. */
data class TimezonePreset(val label: String, val posix: String)

object Timezones {
    val presets: List<TimezonePreset> = listOf(
        TimezonePreset("US Pacific", "PST8PDT,M3.2.0,M11.1.0"),
        TimezonePreset("US Mountain", "MST7MDT,M3.2.0,M11.1.0"),
        TimezonePreset("Arizona", "MST7"),
        TimezonePreset("US Central", "CST6CDT,M3.2.0,M11.1.0"),
        TimezonePreset("US Eastern", "EST5EDT,M3.2.0,M11.1.0"),
        TimezonePreset("Alaska", "AKST9AKDT,M3.2.0,M11.1.0"),
        TimezonePreset("Hawaii", "HST10"),
        TimezonePreset("UTC", "UTC0"),
        TimezonePreset("United Kingdom", "GMT0BST,M3.5.0/1,M10.5.0"),
        TimezonePreset("Central Europe", "CET-1CEST,M3.5.0,M10.5.0/3"),
        TimezonePreset("Eastern Europe", "EET-2EEST,M3.5.0/3,M10.5.0/4"),
        TimezonePreset("India", "IST-5:30"),
        TimezonePreset("China", "CST-8"),
        TimezonePreset("Japan", "JST-9"),
        TimezonePreset("South Korea", "KST-9"),
        TimezonePreset("Singapore", "SGT-8"),
        TimezonePreset("Australia East", "AEST-10AEDT,M10.1.0,M4.1.0/3"),
        TimezonePreset("Australia West", "AWST-8"),
        TimezonePreset("New Zealand", "NZST-12NZDT,M9.5.0,M4.1.0/3"),
        TimezonePreset("Brazil (São Paulo)", "BRT3"),
    )

    fun label(posix: String): String? =
        presets.firstOrNull { it.posix == posix }?.label
}

/** Validation failures from the encoders, mirroring Swift's PayloadError. */
sealed class PayloadException(message: String) : Exception(message) {
    class Empty(field: String) : PayloadException("$field cannot be empty")
    class TooLong(field: String, limit: Int, actual: Int) :
        PayloadException("$field too long: $actual > $limit bytes")
    class InvalidSSID : PayloadException("SSID is empty, too long, or contains '|'")
    class InvalidLocation(l: String) :
        PayloadException("Location '$l' is empty, too long, or contains '|'")
    class InvalidStatusText(t: String) :
        PayloadException("Status text '$t' cannot contain '|'")
}

/**
 * Pure payload-formatting layer that mirrors `tools/led.py` and the iOS
 * `Payloads` enum. Free of any Android dependency so it can be unit tested
 * on the JVM.
 */
object Payloads {
    const val WIFI_SSID_MAX_BYTES = 63
    const val API_KEY_MAX_BYTES = 63
    const val TICKER_MAX_COUNT = 10
    const val TICKER_MAX_LEN = 15
    const val LOCATION_MAX_COUNT = 5
    const val LOCATION_MAX_LEN = 47
    const val LOCATIONS_MAX_BYTES = 244
    const val LOCATION_LABEL_MAX_BYTES = 23
    const val STATUS_TEXT_MAX_BYTES = 95
    const val STATUS_STATIC_MAX_BYTES = 5
    const val TIMER_MAX_MINUTES = 99L

    val brightnessRange = 0..15
    val scrollMsRange = 20..500
    const val TIMEZONE_MAX_BYTES = 63

    // MARK: - Encoders

    @Throws(PayloadException::class)
    fun wifi(ssid: String, password: String): ByteArray {
        val s = ssid.trim()
        if (s.isEmpty() || s.contains("|") || s.utf8Size > WIFI_SSID_MAX_BYTES) {
            throw PayloadException.InvalidSSID()
        }
        return "$s|$password".toByteArray(Charsets.UTF_8)
    }

    @Throws(PayloadException::class)
    fun apiKey(key: String): ByteArray {
        val k = key.trim()
        if (k.isEmpty()) throw PayloadException.Empty("API key")
        if (k.utf8Size > API_KEY_MAX_BYTES) {
            throw PayloadException.TooLong("API key", API_KEY_MAX_BYTES, k.utf8Size)
        }
        return k.toByteArray(Charsets.UTF_8)
    }

    @Throws(PayloadException::class)
    fun tickersFromCsv(csv: String): ByteArray =
        tickers(csv.split(",").map { it })

    /** Uppercases, trims, dedupes order-preserving, caps at the limits. */
    @Throws(PayloadException::class)
    fun tickers(list: List<String>): ByteArray {
        val seen = LinkedHashSet<String>()
        for (raw in list) {
            val t = raw.trim().uppercase()
            if (t.isEmpty() || t.utf8Size > TICKER_MAX_LEN) continue
            if (!seen.add(t)) continue
            if (seen.size == TICKER_MAX_COUNT) break
        }
        if (seen.isEmpty()) throw PayloadException.Empty("Tickers")
        return seen.joinToString(",").toByteArray(Charsets.UTF_8)
    }

    @Throws(PayloadException::class)
    fun locations(list: List<WeatherLocation>): ByteArray {
        if (list.isEmpty()) throw PayloadException.Empty("Locations")
        val entries = list.take(LOCATION_MAX_COUNT).map { it.wire }
        for (entry in entries) {
            if (entry.contains("|") || entry.utf8Size > LOCATION_MAX_LEN) {
                throw PayloadException.InvalidLocation(entry)
            }
        }
        val joined = entries.joinToString("|")
        val bytes = joined.utf8Size
        if (bytes > LOCATIONS_MAX_BYTES) {
            throw PayloadException.TooLong("Locations", LOCATIONS_MAX_BYTES, bytes)
        }
        return joined.toByteArray(Charsets.UTF_8)
    }

    fun parseLocations(data: ByteArray): List<WeatherLocation> =
        parseString(data)
            .split("|")
            .filter { it.isNotEmpty() }
            .mapNotNull { WeatherLocation.fromWire(it) }

    /** Empty encodes "none", full set "all", else canonical comma-joined subset. */
    fun mode(c: Categories): ByteArray {
        if (c.isEmpty) return "none".toByteArray(Charsets.UTF_8)
        if (c == Categories.all) return "all".toByteArray(Charsets.UTF_8)
        val tokens = buildList {
            if (c contains Categories.stocks) add("stocks")
            if (c contains Categories.weather) add("weather")
            if (c contains Categories.clock) add("clock")
        }
        return tokens.joinToString(",").toByteArray(Charsets.UTF_8)
    }

    fun command(cmd: String): ByteArray = cmd.toByteArray(Charsets.UTF_8)

    /** Countdown-timer start, minutes clamped 1..99. */
    fun timer(minutes: Long): ByteArray {
        val m = minutes.coerceIn(1, TIMER_MAX_MINUTES)
        return "timer $m".toByteArray(Charsets.UTF_8)
    }

    fun timerCancel(): ByteArray = "timer cancel".toByteArray(Charsets.UTF_8)

    /** Match the firmware's rendering exactly (ceil to whole seconds, "M:SS"). */
    fun countdownLabel(remainingSeconds: Double): String {
        val secs = ceil(remainingSeconds).toInt().coerceAtLeast(0)
        return "%d:%02d".format(java.util.Locale.US, secs / 60, secs % 60)
    }

    fun power(p: PowerState): ByteArray = p.wire.toByteArray(Charsets.UTF_8)

    /** Encode "brightness|scroll_ms", clamped to the firmware ranges. */
    fun displaySettings(s: DisplaySettings): ByteArray {
        val b = s.brightness.coerceIn(brightnessRange.first, brightnessRange.last)
        val m = s.scrollMs.coerceIn(scrollMsRange.first, scrollMsRange.last)
        return "$b|$m".toByteArray(Charsets.UTF_8)
    }

    fun parseDisplaySettings(data: ByteArray): DisplaySettings? {
        val parts = parseString(data).split("|")
        if (parts.size != 2) return null
        val b = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return DisplaySettings(
            brightness = b.coerceIn(brightnessRange.first, brightnessRange.last),
            scrollMs = m.coerceIn(scrollMsRange.first, scrollMsRange.last),
        )
    }

    @Throws(PayloadException::class)
    fun timezone(posix: String): ByteArray {
        val t = posix.trim()
        val first = t.firstOrNull()
        if (first == null || !first.isLetter()) throw PayloadException.Empty("Timezone")
        if (t.utf8Size > TIMEZONE_MAX_BYTES) {
            throw PayloadException.TooLong("Timezone", TIMEZONE_MAX_BYTES, t.utf8Size)
        }
        return t.toByteArray(Charsets.UTF_8)
    }

    fun parseTimezone(data: ByteArray): String? {
        val s = parseString(data).trim()
        return s.ifEmpty { null }
    }

    // MARK: - Status (active sign)

    @Throws(PayloadException::class)
    fun status(text: String, durationSeconds: Long): ByteArray {
        val t = text.trim()
        if (t.isEmpty()) throw PayloadException.Empty("Status")
        if (t.contains("|")) throw PayloadException.InvalidStatusText(t)
        if (t.utf8Size > STATUS_TEXT_MAX_BYTES) {
            throw PayloadException.TooLong("Status", STATUS_TEXT_MAX_BYTES, t.utf8Size)
        }
        return "$t|$durationSeconds".toByteArray(Charsets.UTF_8)
    }

    /** Empty write clears any active status on the device. */
    fun statusClear(): ByteArray = ByteArray(0)

    // MARK: - Parsers for values read back from the device

    /** Decode as UTF-8, trimming any trailing NULs from fixed-size buffers. */
    fun parseString(data: ByteArray): String {
        val end = data.indexOfFirst { it == 0.toByte() }.let { if (it < 0) data.size else it }
        return String(data, 0, end, Charsets.UTF_8)
    }

    fun parseTickers(data: ByteArray): List<String> {
        val raw = parseString(data)
        if (raw.isEmpty()) return emptyList()
        return raw.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
    }

    /** Mirrors the firmware's parseModePayload(): unknown tokens reject all. */
    fun parseMode(data: ByteArray): DeviceMode {
        val raw = parseString(data)
        if (raw.isEmpty()) return DeviceMode.Unknown
        if (raw == "all") return DeviceMode.Content(Categories.all)
        if (raw == "none") return DeviceMode.None
        if (raw == "setup") return DeviceMode.Setup
        var c = Categories.none
        for (piece in raw.split(",").filter { it.isNotEmpty() }) {
            c = when (piece.trim()) {
                "stocks" -> c + Categories.stocks
                "weather" -> c + Categories.weather
                "clock" -> c + Categories.clock
                else -> return DeviceMode.Unknown
            }
        }
        return if (c.isEmpty) DeviceMode.Unknown else DeviceMode.Content(c)
    }

    fun parsePower(data: ByteArray): PowerState? {
        val s = parseString(data).trim().lowercase()
        return if (s.isEmpty()) null else PowerState.fromWire(s)
    }

    /**
     * Returns null when no sign is active. Converts the firmware's
     * "seconds remaining" to an absolute deadline; [nowMillis] is injectable.
     */
    fun parseStatus(data: ByteArray, nowMillis: Long = System.currentTimeMillis()): ActiveStatus? {
        val s = parseString(data)
        if (s.isEmpty()) return null
        val pipe = s.lastIndexOf('|')
        if (pipe < 0) return ActiveStatus(s, null)
        val text = s.substring(0, pipe)
        val secs = s.substring(pipe + 1).toLongOrNull() ?: 0L
        val expiresAt = if (secs == 0L) null else nowMillis + secs * 1000L
        return ActiveStatus(text, expiresAt)
    }

    /** Snap an arbitrary ms to the nearest scroll-speed preset index. */
    fun nearestSpeedPresetIndex(presets: IntArray, ms: Int): Int =
        presets.indices.minByOrNull { abs(presets[it] - ms) } ?: 0
}
