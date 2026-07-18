package com.zenstream.zenstreammobile.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ParserTest {
    @Test
    fun parsesHomeMediaItems() {
        val json =
            JSONObject("""{"Items":[{"Id":"1","Name":"Example","Type":"Movie","ImageTags":{"Primary":"primary-tag"},"BackdropImageTags":["backdrop-tag"],"UserData":{"PlayedPercentage":25.0}}]}""")
        val item = parseMediaItems(json).single()
        assertEquals("1", item.id)
        assertEquals("Example", item.name)
        assertEquals("primary-tag", item.imageTags["Primary"])
        assertEquals(listOf("backdrop-tag"), item.backdropImageTags)
        assertEquals(25.0, item.playedPercentage!!, 0.0)
    }
}
