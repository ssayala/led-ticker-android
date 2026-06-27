package io.github.ssayala.ledticker.ble

import java.util.UUID

/** The GATT characteristics of the LED-Ticker service, keyed by UUID. */
enum class CharKind(val uuidString: String) {
    Tickers("BEB5483E-36E1-4688-B7F5-EA07361B26A8"),
    Mode("BEB5483E-36E1-4688-B7F5-EA07361B26A9"),
    // 26AA was the legacy "Messages" characteristic — tombstoned, do not reuse.
    Command("BEB5483E-36E1-4688-B7F5-EA07361B26AB"),
    Wifi("BEB5483E-36E1-4688-B7F5-EA07361B26AC"),
    ApiKey("BEB5483E-36E1-4688-B7F5-EA07361B26AD"),
    Locations("BEB5483E-36E1-4688-B7F5-EA07361B26AE"),
    Status("BEB5483E-36E1-4688-B7F5-EA07361B26AF"),
    Version("BEB5483E-36E1-4688-B7F5-EA07361B26B0"),
    Power("BEB5483E-36E1-4688-B7F5-EA07361B26B1"),
    // 26B2 (Auth) — written with the 6-digit PIN as the bonding fallback.
    Auth("BEB5483E-36E1-4688-B7F5-EA07361B26B2"),
    Display("BEB5483E-36E1-4688-B7F5-EA07361B26B3"),
    Timezone("BEB5483E-36E1-4688-B7F5-EA07361B26B4");

    val uuid: UUID get() = UUID.fromString(uuidString)

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("4FAFC201-1FB5-459E-8FCC-C5C9C331914B")

        fun from(uuid: UUID): CharKind? = entries.firstOrNull { it.uuid == uuid }

        /**
         * Anything not in this set is optional — discovery still goes Ready
         * without it, so new characteristics don't break older firmware.
         */
        val requiredKinds: Set<CharKind> = setOf(
            Tickers, Mode, Command, Wifi, ApiKey, Locations, Status,
        )
    }
}
