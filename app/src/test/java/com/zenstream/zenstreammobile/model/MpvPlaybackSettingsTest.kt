package com.zenstream.zenstreammobile.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvPlaybackSettingsTest {
    @Test
    fun missingAndUnknownStoredValuesUseSafeDefaults() {
        assertEquals(MpvVideoOutput.GPU, MpvVideoOutput.fromStorageValue(null))
        assertEquals(MpvVideoOutput.GPU, MpvVideoOutput.fromStorageValue("unknown"))
        assertEquals(MpvVideoProfile.FAST, MpvVideoProfile.fromStorageValue(null))
        assertEquals(MpvVideoProfile.FAST, MpvVideoProfile.fromStorageValue("unknown"))
        assertEquals(MpvVideoScaler.BILINEAR, MpvVideoScaler.fromStorageValue(null))
        assertEquals(MpvVideoScaler.BILINEAR, MpvVideoScaler.fromStorageValue("unknown"))
    }

    @Test
    fun storageValuesRoundTripToTheAdvancedChoices() {
        assertEquals(MpvVideoOutput.GPU_NEXT, MpvVideoOutput.fromStorageValue("gpu-next"))
        assertEquals(MpvVideoProfile.GPU_HQ, MpvVideoProfile.fromStorageValue("gpu-hq"))
        assertEquals(MpvVideoScaler.LANCZOS, MpvVideoScaler.fromStorageValue("lanczos"))
    }
}
