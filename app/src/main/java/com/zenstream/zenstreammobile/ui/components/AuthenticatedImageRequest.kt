package com.zenstream.zenstreammobile.ui.components

import android.content.Context
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.zenstream.zenstreammobile.data.CatalogApi
import com.zenstream.zenstreammobile.model.AuthSession
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Builds the same authenticated image request for artwork and person portraits. */
fun authenticatedImageRequest(
    context: Context,
    url: String,
    session: AuthSession,
): ImageRequest {
    val requestUrl = authenticatedImageUrl(url, session.resourceTicket)
    return ImageRequest.Builder(context)
        .data(requestUrl)
        .httpHeaders(
            NetworkHeaders.Builder()
                .set("Authorization", CatalogApi.authorizationHeader(session.token))
                .build()
        )
        .crossfade(true)
        .build()
}

internal fun authenticatedImageUrl(url: String, resourceTicket: String?): String =
    resourceTicket?.takeIf(String::isNotBlank)?.let { ticket ->
        url.toHttpUrl().newBuilder().addQueryParameter("access", ticket).build().toString()
    } ?: url
