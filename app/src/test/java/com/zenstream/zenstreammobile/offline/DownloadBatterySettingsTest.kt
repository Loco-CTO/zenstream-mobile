package com.zenstream.zenstreammobile.offline

import com.zenstream.zenstreammobile.R
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadBatterySettingsTest {
    @Test
    fun manufacturerAndBrandSelectVendorInstructions() {
        for ((manufacturer, brand) in
            listOf("Xiaomi" to "Redmi", "Xiaomi" to "POCO", "unknown" to " Redmi ")) {
            assertEquals(
                R.string.download_battery_xiaomi,
                downloadBatteryInstructions(manufacturer, brand),
            )
        }
        assertEquals(
            R.string.download_battery_samsung,
            downloadBatteryInstructions("Samsung", "samsung"),
        )
    }

    @Test
    fun otherManufacturersHaveAndroidInstructions() {
        for (vendor in
            listOf("Google", "Lenovo", "Motorola", "OnePlus", "OPPO", "vivo", "unknown")) {
            assertEquals(
                R.string.download_battery_android,
                downloadBatteryInstructions(vendor, vendor),
            )
        }
    }
}
