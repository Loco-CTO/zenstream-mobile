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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.imageBlurHash
import com.zenstream.zenstreammobile.data.imageUrl
import com.zenstream.zenstreammobile.data.landscapeImageType
import com.zenstream.zenstreammobile.data.posterImageType
import com.zenstream.zenstreammobile.data.seriesPosterImageType
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaRow
import com.zenstream.zenstreammobile.model.RowTitle
import java.time.Instant
import kotlin.math.roundToInt

internal val POSTER_CARD_MIN_WIDTH = 140.dp
internal val POSTER_CARD_MAX_WIDTH = 180.dp

@Composable
fun MediaRowView(
    row: MediaRow,
    session: AuthSession,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (row.items.isEmpty()) return
    val title =
        when (row.title) {
            RowTitle.ContinueWatching -> stringResource(R.string.continue_watching)
            RowTitle.NextUp -> stringResource(R.string.next_up)
            RowTitle.MyList -> stringResource(R.string.my_list)
            RowTitle.RecentlyPlayed -> stringResource(R.string.recently_played)
            RowTitle.Genre -> row.label.orEmpty()
            RowTitle.NewlyAdded ->
                row.libraryName?.let {
                    stringResource(R.string.newly_added_on, it)
                } ?: stringResource(R.string.new_releases)

            RowTitle.TopRated -> stringResource(R.string.top_rated)
            RowTitle.NewReleases -> stringResource(R.string.new_releases)
        }
    val resolvedTitle =
        if (row.title == RowTitle.NewlyAdded || row.libraryName.isNullOrBlank()) {
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
            if (row.stackEpisodes) {
                items(stackNewlyAdded(row.items), key = { it.items.first().id }) { stack ->
                    if (stack.items.size > 1) {
                        StackedEpisodeCard(stack, session, onItemClick)
                    } else {
                        MediaCard(
                            stack.items.first(),
                            session,
                            row.wide,
                            onItemClick,
                            useSeriesPoster = true,
                        )
                    }
                }
            } else {
                items(row.items, key = { it.id }) { item ->
                    MediaCard(item, session, row.wide, onItemClick)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

internal data class EpisodeStack(val items: List<MediaItem>)

internal fun stackNewlyAdded(items: List<MediaItem>): List<EpisodeStack> {
    val stacks = mutableListOf<EpisodeStack>()
    for (item in items) {
        val previous = stacks.lastOrNull()?.items?.lastOrNull()
        if (previous != null && areBatchEpisodes(previous, item)) {
            stacks[stacks.lastIndex] = stacks.last().copy(items = stacks.last().items + item)
        } else {
            stacks += EpisodeStack(listOf(item))
        }
    }
    return stacks
}

private fun areBatchEpisodes(a: MediaItem, b: MediaItem): Boolean =
    a.type == "Episode" &&
        b.type == "Episode" &&
        !a.seriesId.isNullOrBlank() &&
        a.seriesId == b.seriesId &&
        !a.seasonId.isNullOrBlank() &&
        a.seasonId == b.seasonId &&
        a.parentIndexNumber == b.parentIndexNumber &&
        a.indexNumber != null &&
        b.indexNumber != null &&
        kotlin.math.abs(a.indexNumber - b.indexNumber) == 1 &&
        addedWithinOneHour(a.lastAddedAt, b.lastAddedAt)

private fun addedWithinOneHour(a: String?, b: String?): Boolean {
    val aTime = a?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val bTime = b?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    return aTime != null && bTime != null && kotlin.math.abs(aTime - bTime) <= 3_600_000
}

@Composable
private fun StackedEpisodeCard(
    stack: EpisodeStack,
    session: AuthSession,
    onClick: (MediaItem) -> Unit,
) {
    val item = stack.items.first()
    val playDescription = stringResource(R.string.play_description, episodeCardTitle(item))
    Column(
        modifier =
            Modifier.width(POSTER_CARD_MIN_WIDTH)
                .semantics {
                    role = Role.Button
                    contentDescription = playDescription
                }
                .clickable { onClick(item) }
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(start = 10.dp, top = 10.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = .10f))
            )
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(start = 5.dp, top = 5.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = .16f))
            )
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(end = 10.dp, bottom = 10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                MediaImage(item, session, wide = false, useSeriesPoster = true)
                Surface(
                    color = Color.Black.copy(alpha = .65f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                ) {
                    Text(
                        text = stack.items.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Text(
            episodeCardTitle(item),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = .82f),
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(
            episodeCardSubtitle(item),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = .42f),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun MediaCard(
    item: MediaItem,
    session: AuthSession,
    wide: Boolean,
    onClick: (MediaItem) -> Unit,
    showRating: Boolean = false,
    gridCard: Boolean = false,
    useSeriesPoster: Boolean = !wide,
) {
    val locale = LocalLocale.current.platformLocale
    val cardWidthModifier =
        if (gridCard && !wide) {
            Modifier.fillMaxWidth()
                .widthIn(min = POSTER_CARD_MIN_WIDTH, max = POSTER_CARD_MAX_WIDTH)
        } else {
            Modifier.width(if (wide) 224.dp else POSTER_CARD_MIN_WIDTH)
        }
    val playDescription = stringResource(R.string.play_description, item.name)
    Column(
        modifier =
            cardWidthModifier
                .semantics {
                    role = Role.Button
                    contentDescription = playDescription
                }
                .clickable { onClick(item) }
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(if (wide) 16f / 9f else 2f / 3f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            MediaImage(item, session, wide, useSeriesPoster = useSeriesPoster)
            if (item.played || item.unplayedItemCount != null) {
                Surface(
                    color = Color.Black.copy(alpha = .65f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    ) {
                        if (item.played)
                            Icon(
                                painter = painterResource(LucideR.drawable.lucide_ic_check),
                                contentDescription = null,
                                tint = Color(0xFFBBF7D0),
                                modifier = Modifier.width(14.dp),
                            )
                        else
                            Text(
                                stringResource(
                                    R.string.episodes_unwatched,
                                    item.unplayedItemCount ?: 0,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                    }
                }
            }
            if (showRating && item.communityRating != null) {
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).padding(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_star),
                        contentDescription = stringResource(R.string.rating_description, item.name),
                        tint = Color(0xFFFACC15),
                        modifier = Modifier.width(12.dp),
                    )
                    Text(
                        text = String.format(locale, "%.1f", item.communityRating),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = .85f),
                    )
                }
            }
            progressPercent(item)?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = .18f),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                )
            }
        }
        Text(
            episodeCardTitle(item),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = .82f),
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(
            episodeCardSubtitle(item),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = .42f),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun MediaImage(
    item: MediaItem,
    session: AuthSession,
    wide: Boolean,
    useSeriesPoster: Boolean = false,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val type =
        when {
            wide -> landscapeImageType(item)
            useSeriesPoster -> seriesPosterImageType(item)
            else -> posterImageType(item)
        }
    val url = type?.let {
        imageUrl(
            session.serverUrl,
            item,
            it,
            if (wide) 448 else 280,
            if (wide) 252 else 420,
        )
    }
    val request = url?.let { authenticatedImageRequest(LocalContext.current, it, session) }
    BlurHashAsyncImage(
        model = request,
        imageKey = url,
        blurHash = type?.let { imageBlurHash(item, it) },
        contentDescription =
            contentDescription
                ?: stringResource(
                    R.string.poster_description,
                    item.name,
                ),
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
    )
}

fun progressPercent(item: MediaItem): Int? {
    val direct = item.playedPercentage
    if (direct != null) {
        return direct.coerceIn(0.0, 100.0).roundToInt().takeIf { it > 0 }
    }
    if (item.runtimeTicks != null && item.playbackPositionTicks != null && item.runtimeTicks > 0) {
        return ((item.playbackPositionTicks.toDouble() / item.runtimeTicks) * 100)
            .coerceIn(
                0.0,
                100.0,
            )
            .roundToInt()
            .takeIf { it > 0 }
    }
    return null
}

internal fun episodeCardTitle(item: MediaItem): String =
    if (item.type == "Episode" && !item.seriesId.isNullOrBlank()) {
        item.seriesName ?: "Series"
    } else {
        item.name
    }

internal fun episodeCardSubtitle(item: MediaItem): String =
    if (item.type == "Episode" && !item.seriesId.isNullOrBlank()) {
        val season = item.parentIndexNumber?.toString()?.padStart(2, '0') ?: "??"
        val episode = item.indexNumber?.toString()?.padStart(2, '0') ?: "??"
        "S${season}E${episode} · ${item.name}"
    } else {
        itemSubtitle(item)
    }

fun itemSubtitle(item: MediaItem): String =
    listOfNotNull(
            item.productionYear?.toString(),
            if (
                item.type == "Episode" && item.parentIndexNumber != null && item.indexNumber != null
            )
                "S${item.parentIndexNumber}:E${item.indexNumber}"
            else null,
            item.officialRating,
        )
        .joinToString(" · ")
        .ifBlank { item.type.orEmpty() }
