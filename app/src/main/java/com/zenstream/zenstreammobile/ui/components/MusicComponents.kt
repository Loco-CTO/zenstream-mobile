package com.zenstream.zenstreammobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.data.artistCreditSeparator
import com.zenstream.zenstreammobile.data.artistCreditsForAlbum
import com.zenstream.zenstreammobile.data.artistCreditsForTrack
import com.zenstream.zenstreammobile.data.imageBlurHash
import com.zenstream.zenstreammobile.data.imageUrl
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem

@Composable
fun AudioCard(
    item: MediaItem,
    session: AuthSession,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 148.dp,
) {
    val cardModifier =
        if (width == null) modifier.fillMaxWidth()
        else modifier.width(width)
    Column(
        modifier =
            cardModifier
                .semantics {
                    role = Role.Button
                    contentDescription = "Open ${item.name}"
                }
                .clickable { onClick(item) }
    ) {
        MusicArtwork(
            item = item,
            session = session,
            requestedSize = 360,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Text(
            text = item.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = .86f),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = musicSubtitle(item),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun MusicArtwork(
    item: MediaItem,
    session: AuthSession,
    fallbackItem: MediaItem? = null,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    requestedSize: Int = 640,
    shape: Shape = RoundedCornerShape(12.dp),
    fallbackGlyph: String = "♪",
) {
    val safeSize = requestedSize.coerceIn(160, 1_024)
    val sourceItem =
        if (item.imageTags["Primary"].isNullOrBlank()) fallbackItem ?: item else item
    val url = imageUrl(session.serverUrl, sourceItem, "Primary", safeSize, safeSize)
    val request = url?.let { authenticatedImageRequest(LocalContext.current, it, session) }
    val blurHash = imageBlurHash(sourceItem, "Primary")
    var imageFailed by remember(url) { mutableStateOf(url == null) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        BlurHashAsyncImage(
            model = request,
            imageKey = url,
            blurHash = blurHash,
            contentDescription = contentDescription ?: item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onError = { imageFailed = true },
        )
        if (imageFailed && blurHash.isNullOrBlank()) {
            Text(
                text = fallbackGlyph,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f),
            )
        }
    }
}

@Composable
fun MusicArtistCard(
    item: MediaItem,
    session: AuthSession,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 112.dp,
) {
    Column(
        modifier = modifier
            .width(size)
            .semantics {
                role = Role.Button
                contentDescription = "Open ${item.name}"
            }
            .clickable { onClick(item) },
        horizontalAlignment = Alignment.Start,
    ) {
        MusicArtwork(
            item = item,
            session = session,
            requestedSize = 320,
            fallbackGlyph = "★",
            shape = CircleShape,
            modifier = Modifier.size(size),
            contentDescription = item.name,
        )
        Text(
            text = item.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = .9f),
            modifier = Modifier.padding(top = 9.dp),
        )
        Text(
            text = "Artist",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun AudioTrackRow(
    item: MediaItem,
    session: AuthSession,
    position: Int,
    isCurrent: Boolean = false,
    onClick: (MediaItem) -> Unit,
    onFavorite: ((MediaItem) -> Unit)? = null,
    onArtistClick: (String) -> Unit = {},
    artworkItem: MediaItem? = null,
    modifier: Modifier = Modifier,
) {
    val credits = artistCreditsForTrack(item)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                    else Color.Transparent
                )
                .clickable { onClick(item) }
                .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isCurrent) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_play),
                    contentDescription = "Currently playing",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = position.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        MusicArtwork(
            item = item,
            session = session,
            fallbackItem = artworkItem,
            requestedSize = 256,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(44.dp),
            contentDescription = "${item.name} artwork",
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                color =
                    if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (credits.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    credits.forEachIndexed { index, credit ->
                        Text(
                            text = credit.name,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                if (credit.id != null)
                                    Modifier.clickable { onArtistClick(credit.id) }
                                else Modifier,
                        )
                        artistCreditSeparator(credits, index).takeIf(String::isNotEmpty)?.let {
                            separator ->
                            Text(
                                separator,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
        item.playCount
            ?.takeIf { it > 0 }
            ?.let {
                Text(
                    text = it.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        Text(
            text = formatDurationSeconds(item.durationSeconds),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        if (onFavorite != null) {
            IconButton(
                onClick = { onFavorite(item) },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_heart),
                    contentDescription = if (item.favorite) "Remove favorite" else "Add favorite",
                    tint =
                        if (item.favorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun MusicCreditLine(
    item: MediaItem,
    primaryArtist: MediaItem? = null,
    onArtistClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    accentColor: Color? = null,
) {
    val credits = artistCreditsForAlbum(item, primaryArtist)
    val linkColor = accentColor ?: MaterialTheme.colorScheme.primary
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        credits.forEachIndexed { index, credit ->
            Text(
                text = credit.name,
                color = linkColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    if (credit.id != null) Modifier.clickable { onArtistClick(credit.id) }
                    else Modifier,
            )
            artistCreditSeparator(credits, index).takeIf(String::isNotEmpty)?.let { separator ->
                Text(
                    separator,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

fun musicSubtitle(item: MediaItem): String =
    when {
        item.type == "MusicArtist" -> item.albumArtist.orEmpty().ifBlank { "Artist" }
        item.type == "Audio" -> item.albumArtist.orEmpty().ifBlank { item.album.orEmpty() }
        else -> listOfNotNull(item.productionYear?.toString(), item.albumType).joinToString(" · ")
    }

fun formatDurationSeconds(value: Double?): String {
    if (value == null || !value.isFinite() || value < 0) return "—"
    val total = value.toLong()
    val minutes = total / 60
    val seconds = total % 60
    return if (minutes >= 60) {
        "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
