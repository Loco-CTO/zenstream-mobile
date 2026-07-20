package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImagesTest {
    private val item = MediaItem(
        id = "movie-1",
        name = "Movie",
        imageTags = mapOf("Primary" to "primary-tag", "Thumb" to "thumb-tag"),
        backdropImageTags = listOf("backdrop-tag"),
    )

    @Test
    fun landscapePrefersThumbThenBackdropThenPrimary() {
        assertEquals("Thumb", landscapeImageType(item))
        assertEquals(
            "https://server/api/assets/items/movie-1/images/Thumb?fillWidth=448&fillHeight=252&quality=90&tag=thumb-tag",
            imageUrl("https://server", item, "Thumb", 448, 252)
        )
        assertEquals(
            "Backdrop",
            landscapeImageType(item.copy(imageTags = mapOf("Primary" to "primary-tag")))
        )
        assertEquals(
            "Primary",
            landscapeImageType(
                item.copy(
                    imageTags = mapOf("Primary" to "primary-tag"),
                    backdropImageTags = emptyList()
                )
            )
        )
    }

    @Test
    fun heroDoesNotFallBackToPoster() {
        val noBackdrop = item.copy(backdropImageTags = emptyList())
        assertNull(imageUrl("https://server", noBackdrop, "Backdrop", 1280, 720))
    }
}
