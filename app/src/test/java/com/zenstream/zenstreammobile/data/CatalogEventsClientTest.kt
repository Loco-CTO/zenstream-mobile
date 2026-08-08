package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogEventsClientTest {
    @Test
    fun changedEventReturnsGeneration() {
        assertEquals(7L, catalogEventGeneration("""{"type":"catalog.changed","generation":7}"""))
    }

    @Test
    fun statusAndInvalidMessagesAreIgnored() {
        assertNull(catalogEventGeneration("""{"type":"catalog.status","generation":7}"""))
        assertNull(catalogEventGeneration("not-json"))
    }
}
