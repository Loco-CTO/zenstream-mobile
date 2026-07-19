package com.zenstream.zenstreammobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.JellyfinApi
import com.zenstream.zenstreammobile.data.imageUrl
import com.zenstream.zenstreammobile.data.landscapeImageType
import com.zenstream.zenstreammobile.data.posterImageType
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaRow
import com.zenstream.zenstreammobile.model.RowTitle
import kotlin.math.roundToInt

@Composable
fun MediaRowView(
    row: MediaRow,
    session: AuthSession,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (row.items.isEmpty()) return
    val title = when (row.title) {
        RowTitle.ContinueWatching -> stringResource(R.string.continue_watching)
        RowTitle.NextUp -> stringResource(R.string.next_up)
        RowTitle.NewlyAdded -> row.libraryName?.let {
            stringResource(R.string.newly_added_on, it)
        } ?: stringResource(R.string.new_releases)

        RowTitle.TopRated -> stringResource(R.string.top_rated)
        RowTitle.NewReleases -> stringResource(R.string.new_releases)
    }
    val resolvedTitle = if (row.title == RowTitle.NewlyAdded || row.libraryName.isNullOrBlank()) {
        title
    } else {
        "${row.libraryName} $title"
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = resolvedTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(row.items, key = { it.id }) { item ->
                MediaCard(item, session, row.wide, onItemClick)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun MediaCard(item: MediaItem, session: AuthSession, wide: Boolean, onClick: (MediaItem) -> Unit) {
    val width = if (wide) 224.dp else 140.dp
    val playDescription = stringResource(R.string.play_description, item.name)
    Column(
        modifier = Modifier
            .width(width)
            .semantics {
                role = Role.Button
                contentDescription = playDescription
            }
            .clickable { onClick(item) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (wide) 16f / 9f else 2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            MediaImage(item, session, wide)
            if (item.played || item.unplayedItemCount != null) {
                Surface(
                    color = Color.Black.copy(alpha = .65f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        if (item.played) Icon(
                            painter = painterResource(R.drawable.lucide_ic_check),
                            contentDescription = null,
                            tint = Color(0xFFBBF7D0),
                            modifier = Modifier.width(14.dp)
                        )
                        else Text(
                            stringResource(
                                R.string.episodes_unwatched,
                                item.unplayedItemCount ?: 0
                            ), style = MaterialTheme.typography.labelSmall, color = Color.White
                        )
                    }
                }
            }
            progressPercent(item)?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = .18f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
        Text(
            item.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = .82f),
            modifier = Modifier.padding(top = 7.dp)
        )
        Text(
            itemSubtitle(item),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = .42f),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
fun MediaImage(
    item: MediaItem,
    session: AuthSession,
    wide: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val type = if (wide) landscapeImageType(item) else posterImageType(item)
    val url = type?.let {
        imageUrl(
            session.serverUrl,
            item,
            it,
            if (wide) 448 else 280,
            if (wide) 252 else 420
        )
    }
    val request = url?.let {
        ImageRequest.Builder(LocalContext.current)
            .data(it)
            .httpHeaders(
                NetworkHeaders.Builder()
                    .set("Authorization", JellyfinApi.authorizationHeader(session.token)).build()
            )
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription ?: stringResource(
            R.string.poster_description,
            item.name
        ),
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp)),
    )
}

fun progressPercent(item: MediaItem): Int? {
    val direct = item.playedPercentage
    if (direct != null) {
        return direct.coerceIn(0.0, 100.0).roundToInt().takeIf { it > 0 }
    }
    if (item.runtimeTicks != null && item.playbackPositionTicks != null && item.runtimeTicks > 0) {
        return ((item.playbackPositionTicks.toDouble() / item.runtimeTicks) * 100).coerceIn(
            0.0,
            100.0
        ).roundToInt().takeIf { it > 0 }
    }
    return null
}

fun itemSubtitle(item: MediaItem): String = listOfNotNull(
    item.productionYear?.toString(),
    if (item.type == "Episode" && item.parentIndexNumber != null && item.indexNumber != null) "S${item.parentIndexNumber}:E${item.indexNumber}" else null,
    item.officialRating,
).joinToString(" · ").ifBlank { item.type.orEmpty() }
