package com.zenstream.zenstreammobile.offline

import org.junit.Assert.*
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun introductionRequiresAnEligibleFirstStartup() {
        assertTrue(shouldIntroduceNotificationPermission(true, 33, false, false))
        assertFalse(shouldIntroduceNotificationPermission(false, 33, false, false))
        assertFalse(shouldIntroduceNotificationPermission(true, 32, false, false))
        assertFalse(shouldIntroduceNotificationPermission(true, 33, true, false))
        assertFalse(shouldIntroduceNotificationPermission(true, 33, false, true))
    }

    @Test
    fun firstRequestDoesNotRequireAnAndroidRationale() {
        assertTrue(canRequestNotificationPermission(33, false, false, false))
        assertTrue(canRequestNotificationPermission(37, false, false, false))
    }

    @Test
    fun denialCanOnlyBeRequestedAgainWhenAndroidOffersARationale() {
        assertTrue(canRequestNotificationPermission(33, false, true, true))
        assertFalse(canRequestNotificationPermission(33, false, true, false))
    }

    @Test
    fun grantedPermissionOrAnOlderRuntimeUsesSettingsForAnyRemainingChannelBlock() {
        assertFalse(canRequestNotificationPermission(32, false, false, false))
        assertFalse(canRequestNotificationPermission(33, true, false, false))
        assertFalse(canRequestNotificationPermission(37, true, true, true))
    }
}
