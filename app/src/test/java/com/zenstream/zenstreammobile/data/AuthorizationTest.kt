package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthorizationTest {
    @Test
    fun authenticatedHeaderContainsToken() {
        assertEquals("Bearer secret", CatalogApi.authorizationHeader("secret"))
    }
}
