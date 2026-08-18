package com.zenstream.zenstreammobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.userAvatarUrl
import com.zenstream.zenstreammobile.model.AuthSession

@Composable
fun UserAvatar(
    session: AuthSession,
    userId: String,
    username: String,
    modifier: Modifier = Modifier,
    avatarVersion: String? = if (userId == session.userId) session.avatarVersion else null,
    allowUnversionedLookup: Boolean = userId != session.userId,
    contentDescription: String? = stringResource(R.string.avatar_description, username),
) {
    val context = LocalContext.current
    val requestUrl =
        remember(session.serverUrl, userId, avatarVersion, allowUnversionedLookup) {
            when {
                avatarVersion != null -> userAvatarUrl(session.serverUrl, userId, avatarVersion)
                allowUnversionedLookup -> userAvatarUrl(session.serverUrl, userId)
                else -> null
            }
        }
    var failedUrl by remember(requestUrl) { mutableStateOf<String?>(null) }
    val showImage = requestUrl != null && failedUrl != requestUrl
    val initial = username.trim().firstOrNull()?.uppercase() ?: "?"

    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (showImage) {
            val request =
                remember(requestUrl, session.token) {
                    ImageRequest.Builder(context)
                        .data(
                            authenticatedImageUrl(
                                resolveImageUrl(session.serverUrl, requestUrl.orEmpty()),
                                session.resourceTicket,
                            )
                        )
                        .httpHeaders(
                            coil3.network.NetworkHeaders.Builder()
                                .set("Authorization", "Bearer ${session.token}")
                                .build()
                        )
                        .apply {
                            if (avatarVersion == null && allowUnversionedLookup) {
                                memoryCachePolicy(CachePolicy.DISABLED)
                            }
                        }
                        .build()
                }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { failedUrl = requestUrl },
            )
        }
    }
}
