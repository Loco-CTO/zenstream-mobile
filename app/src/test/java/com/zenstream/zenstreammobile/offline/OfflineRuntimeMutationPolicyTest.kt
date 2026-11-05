package com.zenstream.zenstreammobile.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRuntimeMutationPolicyTest {
    @Test
    fun immediatelySyncsExplicitWatchChangesOnlyWhenOnlineAndAuthenticated() {
        assertTrue(
            shouldSynchronizeAccountMutationImmediately(
                kind = "watched",
                online = true,
                hasNewOperation = true,
                requiresSignIn = false,
            )
        )
        assertTrue(
            shouldSynchronizeAccountMutationImmediately(
                kind = "complete",
                online = true,
                hasNewOperation = true,
                requiresSignIn = false,
            )
        )
        assertFalse(
            shouldSynchronizeAccountMutationImmediately(
                kind = "watched",
                online = false,
                hasNewOperation = true,
                requiresSignIn = false,
            )
        )
        assertFalse(
            shouldSynchronizeAccountMutationImmediately(
                kind = "checkpoint",
                online = true,
                hasNewOperation = true,
                requiresSignIn = false,
            )
        )
        assertFalse(
            shouldSynchronizeAccountMutationImmediately(
                kind = "watched",
                online = true,
                hasNewOperation = true,
                requiresSignIn = true,
            )
        )
    }

    @Test
    fun fallsBackToCatalogStatePatchWhenSyncRejectsExplicitWatchState() {
        assertTrue(shouldFallbackToCatalogStateMutation("watched", "rejected"))
        assertTrue(shouldFallbackToCatalogStateMutation("unwatched", "rejected"))
        assertFalse(shouldFallbackToCatalogStateMutation("complete", "rejected"))
        assertFalse(shouldFallbackToCatalogStateMutation("watched", "applied"))
        assertFalse(shouldFallbackToCatalogStateMutation("watched", null))
    }
}
