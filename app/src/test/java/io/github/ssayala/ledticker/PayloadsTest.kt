package io.github.ssayala.ledticker

import io.github.ssayala.ledticker.model.Categories
import io.github.ssayala.ledticker.model.DeviceMode
import io.github.ssayala.ledticker.model.DisplaySettings
import io.github.ssayala.ledticker.model.Payloads
import io.github.ssayala.ledticker.model.PayloadException
import io.github.ssayala.ledticker.model.PowerState
import io.github.ssayala.ledticker.model.WeatherLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the iOS PayloadsTests — the wire format must match `tools/led.py`. */
class PayloadsTest {
    private fun str(b: ByteArray) = String(b, Charsets.UTF_8)

    // MARK: Tickers
    @Test fun tickersUppercaseTrimDedupe() {
        val out = str(Payloads.tickers(listOf(" aapl ", "msft", "AAPL")))
        assertEquals("AAPL,MSFT", out)
    }

    @Test fun tickersCapAtTen() {
        val list = (1..15).map { "T$it" }
        val out = str(Payloads.tickers(list))
        assertEquals(10, out.split(",").size)
    }

    @Test(expected = PayloadException.Empty::class)
    fun tickersEmptyThrows() {
        Payloads.tickers(listOf("  ", ""))
    }

    @Test fun parseTickers() {
        assertEquals(listOf("AAPL", "MSFT"), Payloads.parseTickers("aapl,msft".toByteArray()))
        assertEquals(emptyList<String>(), Payloads.parseTickers(ByteArray(0)))
    }

    // MARK: WiFi
    @Test fun wifiJoinsWithPipe() {
        assertEquals("Net|pw|with|pipes", str(Payloads.wifi("Net", "pw|with|pipes")))
    }

    @Test(expected = PayloadException.InvalidSSID::class)
    fun wifiSsidWithPipeThrows() {
        Payloads.wifi("Bad|SSID", "pw")
    }

    // MARK: Mode
    @Test fun modeEncoding() {
        assertEquals("none", str(Payloads.mode(Categories.none)))
        assertEquals("all", str(Payloads.mode(Categories.all)))
        assertEquals("stocks,clock", str(Payloads.mode(Categories.stocks + Categories.clock)))
    }

    @Test fun parseMode() {
        assertEquals(DeviceMode.Content(Categories.all), Payloads.parseMode("all".toByteArray()))
        assertEquals(DeviceMode.None, Payloads.parseMode("none".toByteArray()))
        assertEquals(DeviceMode.Setup, Payloads.parseMode("setup".toByteArray()))
        assertEquals(DeviceMode.Unknown, Payloads.parseMode("bogus".toByteArray()))
        assertEquals(
            DeviceMode.Content(Categories.stocks + Categories.weather),
            Payloads.parseMode("stocks,weather".toByteArray()),
        )
        // Trailing comma accepted (mirrors strtok).
        assertEquals(DeviceMode.Content(Categories.stocks), Payloads.parseMode("stocks,".toByteArray()))
    }

    // MARK: Status
    @Test fun statusEncoding() {
        assertEquals("BUSY|0", str(Payloads.status("BUSY", 0)))
        assertEquals("Lunch|300", str(Payloads.status(" Lunch ", 300)))
    }

    @Test(expected = PayloadException.InvalidStatusText::class)
    fun statusPipeThrows() {
        Payloads.status("a|b", 0)
    }

    @Test fun parseStatusIndefiniteAndTimed() {
        assertNull(Payloads.parseStatus(ByteArray(0)))
        val a = Payloads.parseStatus("HELLO|0".toByteArray())!!
        assertEquals("HELLO", a.text); assertNull(a.expiresAtMillis)
        val b = Payloads.parseStatus("HI|60".toByteArray(), nowMillis = 1_000_000L)!!
        assertEquals(1_060_000L, b.expiresAtMillis)
    }

    // MARK: Locations
    @Test fun locationWireRoundTrip() {
        val loc = WeatherLocation(47.6097, -122.3331, rawLabel = "Seattle")
        assertEquals("47.6097,-122.3331,Seattle", loc.wire)
        val parsed = WeatherLocation.fromWire(loc.wire)!!
        assertEquals("Seattle", parsed.label)
    }

    @Test fun locationTrailingZerosTrimmed() {
        assertEquals("47.61,-122.33,X", WeatherLocation(47.61, -122.33, rawLabel = "X").wire)
        assertEquals("40,-100,Y", WeatherLocation(40.0, -100.0, rawLabel = "Y").wire)
    }

    @Test fun locationLabelCommasPreserved() {
        val parsed = WeatherLocation.fromWire("47.61,-122.33,Seattle, WA")!!
        assertEquals("Seattle, WA", parsed.label)
    }

    @Test fun locationOutOfRangeRejected() {
        assertNull(WeatherLocation.fromWire("200,0,X"))
        assertNull(WeatherLocation.fromWire("0,500,X"))
        assertNull(WeatherLocation.fromWire("bad"))
    }

    @Test fun locationsPipeJoin() {
        val out = str(Payloads.locations(listOf(
            WeatherLocation(47.61, -122.33, rawLabel = "Seattle"),
            WeatherLocation(30.27, -97.74, rawLabel = "Austin"),
        )))
        assertEquals("47.61,-122.33,Seattle|30.27,-97.74,Austin", out)
    }

    // MARK: Power / Display / Timezone
    @Test fun power() {
        assertEquals("on", str(Payloads.power(PowerState.On)))
        assertEquals(PowerState.Off, Payloads.parsePower("OFF".toByteArray()))
        assertNull(Payloads.parsePower(ByteArray(0)))
    }

    @Test fun displaySettingsClampAndParse() {
        assertEquals("15|20", str(Payloads.displaySettings(DisplaySettings(99, 5))))
        assertEquals("0|500", str(Payloads.displaySettings(DisplaySettings(-3, 9000))))
        val parsed = Payloads.parseDisplaySettings("4|70".toByteArray())!!
        assertEquals(4, parsed.brightness); assertEquals(70, parsed.scrollMs)
        assertNull(Payloads.parseDisplaySettings("garbage".toByteArray()))
    }

    @Test fun timezone() {
        assertEquals("IST-5:30", str(Payloads.timezone("IST-5:30")))
        assertEquals("US Eastern", io.github.ssayala.ledticker.model.Timezones.label("EST5EDT,M3.2.0,M11.1.0"))
    }

    @Test(expected = PayloadException.Empty::class)
    fun timezoneMustStartWithLetter() {
        Payloads.timezone("5:30")
    }

    // MARK: Timer / countdown
    @Test fun timerClampAndCancel() {
        assertEquals("timer 1", str(Payloads.timer(0)))
        assertEquals("timer 99", str(Payloads.timer(500)))
        assertEquals("timer cancel", str(Payloads.timerCancel()))
    }

    @Test fun countdownLabelCeil() {
        assertEquals("5:00", Payloads.countdownLabel(300.0))
        assertEquals("1:01", Payloads.countdownLabel(60.2))
        assertEquals("0:00", Payloads.countdownLabel(-3.0))
    }

    // MARK: parseString trims trailing NULs
    @Test fun parseStringTrimsNuls() {
        val raw = byteArrayOf('H'.code.toByte(), 'i'.code.toByte(), 0, 0)
        assertEquals("Hi", Payloads.parseString(raw))
    }

    @Test fun categoriesSetSemantics() {
        val c = Categories.stocks + Categories.clock
        assertTrue(c contains Categories.stocks)
        assertTrue((c - Categories.stocks) == Categories.clock)
        assertTrue(Categories.none.isEmpty)
    }
}
