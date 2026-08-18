package com.zenstream.zenstreammobile.data

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zenstream.zenstreammobile.model.SubtitleStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleStyleStorageTest {
    @Test
    fun defaultStyleUsesTheRequestedAppearance() {
        assertEquals("sans", DEFAULT_SUBTITLE_STYLE.fontFamily)
        assertEquals("#ffffff", DEFAULT_SUBTITLE_STYLE.fontColor)
        assertEquals(2f, DEFAULT_SUBTITLE_STYLE.borderSize)
        assertEquals("#000000", DEFAULT_SUBTITLE_STYLE.borderColor)
        assertEquals(0f, DEFAULT_SUBTITLE_STYLE.backgroundOpacity)
    }

    @Test
    fun missingBorderSizeUsesTheNewDefaultButExplicitZeroIsPreserved() {
        assertEquals(2f, subtitleStyleFromJson("{\"fontFamily\":\"sans\"}").borderSize)
        assertEquals(
            0f,
            subtitleStyleFromJson(subtitleStyleToJson(SubtitleStyle(borderSize = 0f))).borderSize,
        )
    }

    @Test
    fun serializesAndRestoresTheCompleteStyle() {
        val style =
            SubtitleStyle(
                fontFamily = "mono",
                bold = true,
                textScale = 135f,
                fontColor = "#12abef",
                borderSize = 4f,
                borderColor = "#010203",
                backgroundColor = "#405060",
                backgroundOpacity = 65f,
            )

        assertEquals(style, subtitleStyleFromJson(subtitleStyleToJson(style)))
    }

    @Test
    fun migratesTheFirstLegacyUserStyleIntoTheDeviceLocalFormat() {
        val style = SubtitleStyle(fontFamily = "serif", textScale = 120f)
        val preferences =
            preferencesOf(
                stringPreferencesKey("subtitle_style_user-1") to subtitleStyleToJson(style)
            )

        assertEquals(style, legacySubtitleStyleFrom(preferences))
    }
}
