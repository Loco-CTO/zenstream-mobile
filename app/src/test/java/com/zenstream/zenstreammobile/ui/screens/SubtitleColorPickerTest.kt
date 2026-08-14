package com.zenstream.zenstreammobile.ui.screens

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleColorPickerTest {
    @Test
    fun parsesAndFormatsSixDigitHexColors() {
        val color = parseHexColor("#12abEF")

        assertEquals(RgbColor(18, 171, 239), color)
        assertEquals("#12abef", color?.toHex())
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("12abef"))
    }

    @Test
    fun clampsRgbValuesBeforeFormatting() {
        assertEquals("#00ff7f", RgbColor(-10, 300, 127).toHex())
        assertEquals(RgbColor(0, 255, 127), RgbColor(-10, 300, 127).clamped())
    }

    @Test
    fun convertsPrimaryAndNeutralColorsBetweenRgbAndHsv() {
        assertEquals(HsvColor(0f, 1f, 1f), rgbToHsv(RgbColor(255, 0, 0)))
        assertEquals(HsvColor(120f, 1f, 1f), rgbToHsv(RgbColor(0, 255, 0)))
        assertEquals(HsvColor(0f, 0f, 128f / 255f), rgbToHsv(RgbColor(128, 128, 128)))
        assertEquals(RgbColor(255, 0, 255), hsvToRgb(HsvColor(300f, 1f, 1f)))
    }

    @Test
    fun rgbAndHsvRoundTripWithinRoundingTolerance() {
        val original = RgbColor(18, 171, 239)
        val restored = hsvToRgb(rgbToHsv(original))

        assertTrue(abs(original.red - restored.red) <= 1)
        assertTrue(abs(original.green - restored.green) <= 1)
        assertTrue(abs(original.blue - restored.blue) <= 1)
    }
}
