package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogEventsClientTest {
    @Test
    fun websocketUrlKeepsHttpSchemeForOkHttpUpgrade() {
        assertEquals(
            "http://orchestrator.example/api/ws/catalog?ticket=socket-ticket",
            catalogEventsUrl("http://orchestrator.example", "socket-ticket").toString(),
        )
        assertEquals(
            "https://orchestrator.example/api/ws/catalog?ticket=socket-ticket",
            catalogEventsUrl("https://orchestrator.example", "socket-ticket").toString(),
        )
    }

    @Test
    fun changedEventReturnsGeneration() {
        assertEquals(7L, catalogEventGeneration("""{"type":"catalog.changed","generation":7}"""))
    }

    @Test
    fun statusAndInvalidMessagesAreIgnored() {
        assertNull(catalogEventGeneration("""{"type":"catalog.status","generation":7}"""))
        assertNull(catalogEventGeneration("not-json"))
    }

    @Test
    fun reconnectStatusProducesLibraryWideInvalidations() {
        assertEquals(
            listOf(CatalogChange(9L, "tv", null)),
            catalogEvents(
                """{"type":"catalog.status","libraries":[{"id":"tv","catalogGeneration":9}]}"""
            ),
        )
    }
}
