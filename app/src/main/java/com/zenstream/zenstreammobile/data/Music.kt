package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.ArtistCredit
import com.zenstream.zenstreammobile.model.MediaItem
import java.util.Locale

/**
 * The mobile equivalent of the web music credit normalizer. Credits remain
 * atomic and ordered: a joined display artist is never invented when the
 * server supplied distinct credit boundaries.
 */
fun normalizeArtistCredits(vararg sources: List<ArtistCredit>?): List<ArtistCredit> {
    val credits = mutableListOf<ArtistCredit>()
    val byName = mutableMapOf<String, Int>()
    val byId = mutableMapOf<String, Int>()

    for (source in sources) {
        for (value in source.orEmpty()) {
            val name = value.name.trim()
            if (name.isEmpty()) continue
            val id = value.id?.trim()?.takeIf(String::isNotEmpty)
            val nameKey = name.lowercase(Locale.ROOT)
            val idIndex = id?.let(byId::get)
            val nameIndex = byName[nameKey]
            val nameMatchesDifferentId =
                nameIndex != null &&
                    id != null &&
                    credits[nameIndex]?.id != null &&
                    credits[nameIndex]?.id != id
            val existingIndex = idIndex ?: nameIndex?.takeUnless { nameMatchesDifferentId }

            if (existingIndex != null) {
                val existing = credits[existingIndex]
                val completed =
                    existing.copy(
                        id = existing.id ?: id,
                        joinPhrase = existing.joinPhrase ?: value.joinPhrase,
                    )
                credits[existingIndex] = completed
                if (completed.id != null) byId[completed.id] = existingIndex
                continue
            }

            val credit = value.copy(name = name, id = id)
            val index = credits.size
            credits += credit
            byName[nameKey] = index
            if (id != null) byId[id] = index
        }
    }
    return credits
}

fun artistCreditSeparator(credits: List<ArtistCredit>, index: Int): String {
    val explicit = credits.getOrNull(index)?.joinPhrase
    return explicit ?: if (index < credits.lastIndex) ", " else ""
}

fun formatArtistCredits(credits: List<ArtistCredit>): String =
    credits.mapIndexed { index, credit ->
        "${credit.name}${artistCreditSeparator(credits, index)}"
    }.joinToString("")

fun artistCreditsForTrack(track: MediaItem): List<ArtistCredit> {
    val sourceCredits =
        if (track.artistCredits.isNotEmpty()) {
            track.artistCredits
        } else {
            track.artists.map { ArtistCredit(name = it) } +
                track.contributingArtists.map { ArtistCredit(name = it) }
        }
    val credits = normalizeArtistCredits(sourceCredits).toMutableList()
    if (credits.isEmpty()) {
        track.albumArtist?.trim()?.takeIf(String::isNotEmpty)?.let {
            credits += ArtistCredit(name = it)
        }
    }
    val albumArtist = track.albumArtist?.trim()?.lowercase(Locale.ROOT)
    return credits.mapIndexed { index, credit ->
        if (credit.id != null || track.artistId == null) {
            credit
        } else {
            val primary =
                if (albumArtist != null) credit.name.trim().lowercase(Locale.ROOT) == albumArtist
                else index == 0
            if (primary) credit.copy(id = track.artistId) else credit
        }
    }
}

fun artistCreditsForAlbum(
    album: MediaItem,
    primaryArtist: MediaItem? = null,
): List<ArtistCredit> {
    val credits =
        if (album.artistCredits.isNotEmpty()) {
            normalizeArtistCredits(album.artistCredits)
        } else {
            normalizeArtistCredits(
                album.artists.map { ArtistCredit(name = it) },
                album.contributingArtists.map { ArtistCredit(name = it) },
            )
        }.toMutableList()
    val primaryName = (primaryArtist?.name ?: album.albumArtist)?.trim()?.lowercase(Locale.ROOT)
    val primaryId = primaryArtist?.id ?: album.artistId
    val primaryIndex =
        primaryName?.let { name ->
            credits.indexOfFirst { it.name.trim().lowercase(Locale.ROOT) == name }
        } ?: -1
    if (credits.isEmpty()) {
        val fallbackName = primaryArtist?.name?.trim()?.takeIf(String::isNotEmpty)
            ?: album.albumArtist?.trim()?.takeIf(String::isNotEmpty)
        return fallbackName?.let { listOf(ArtistCredit(primaryId, it)) }.orEmpty()
    }
    return credits.mapIndexed { index, credit ->
        if (credit.id != null || primaryId == null) credit
        else if ((primaryIndex >= 0 && index == primaryIndex) || (primaryIndex < 0 && index == 0)) {
            credit.copy(id = primaryId)
        } else credit
    }
}

fun formatTrackArtists(track: MediaItem?): String =
    track?.let { formatArtistCredits(artistCreditsForTrack(it)) }.orEmpty()

fun MediaItem.isMusicArtist(): Boolean = type == "MusicArtist"

fun MediaItem.isMusicAlbum(): Boolean = type == "MusicAlbum"

fun MediaItem.isAudio(): Boolean = type == "Audio"
