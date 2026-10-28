package com.zenstream.zenstreammobile.offline

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineStorageTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun pathsRejectTraversalUrlsAndWrongBuckets() {
        val paths = OfflinePaths(directory.root)
        val profile = "a".repeat(64)
        val asset = "b".repeat(64)
        val relative = paths.relative(profile, asset)
        assertEquals("$profile/bb/$asset.media", relative)
        assertTrue(paths.resolve(relative).path.startsWith(directory.root.path))
        for (bad in
            listOf(
                "../private",
                "file:///private",
                "$profile/aa/$asset.media",
                "$profile/bb/../$asset.media",
                "$profile/bb/$asset..media",
            )) {
            assertThrows(IllegalArgumentException::class.java) { paths.resolve(bad) }
        }
    }

    @Test
    fun simultaneousReservationsPreserveFreeReserveAndUserLimit() {
        val gib = OFFLINE_FREE_RESERVE
        assertTrue(fitsOfflineBudget(4 * gib, gib, 2 * gib, 0, null))
        assertFalse(fitsOfflineBudget(4 * gib, gib, 2 * gib + 1, 0, null))
        assertFalse(fitsOfflineBudget(10 * gib, gib, gib, 2 * gib, 3 * gib))
        assertTrue(fitsOfflineBudget(10 * gib, 0, gib, 2 * gib, 3 * gib))
        assertFalse(fitsOfflineBudget(Long.MAX_VALUE, Long.MAX_VALUE, 1, 0, null))
    }
}
