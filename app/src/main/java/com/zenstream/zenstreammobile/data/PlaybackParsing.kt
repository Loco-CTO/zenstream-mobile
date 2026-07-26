package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaSource
import com.zenstream.zenstreammobile.model.SubtitleCue
import com.zenstream.zenstreammobile.model.SubtitleStyle
import com.zenstream.zenstreammobile.model.TrickplayPreview
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.math.floor
import kotlin.math.max

fun parseWebVttCues(input: String): List<SubtitleCue> = buildList {
    val lines = input.removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
    var index = 0
    while (index < lines.size) {
        // Do not use a timing-line regex here. Android's regex implementation
        // rejects some patterns that pass on the JVM, and a malformed cue must
        // never be able to crash playback.
        val timingLine = lines[index]
        val arrowIndex = timingLine.indexOf("-->")
        if (arrowIndex < 0) {
            index++
            continue
        }
        val start = parseVttTimestamp(timingLine.substring(0, arrowIndex).trim())
        val end = parseVttTimestamp(
            timingLine.substring(arrowIndex + 3).trim().substringBefore(' ').trim(),
        )
        index++
        val text = buildList {
            while (index < lines.size && lines[index].isNotBlank()) {
                add(lines[index])
                index++
            }
        }.joinToString("\n")
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("""\{\\[^}]*}"""), "")
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&nbsp;", " ", ignoreCase = true)
            .trim()
        if (start != null && end != null && end > start && text.isNotBlank()) {
            add(SubtitleCue(start, end, text))
        }
    }
}

fun activeSubtitleCues(
    cues: List<SubtitleCue>,
    positionSeconds: Double,
    offsetSeconds: Double = 0.0,
    timelineOriginSeconds: Double = 0.0,
): List<SubtitleCue> {
    val time = positionSeconds + timelineOriginSeconds + offsetSeconds
    return cues.filter { cue -> time >= cue.startSeconds && time < cue.endSeconds }
}

internal fun isCurrentSubtitleRequest(
    requestGeneration: Long,
    currentGeneration: Long,
    requestedTrack: Int,
    currentTrack: Int?,
    requestedSourceId: String?,
    currentSourceId: String?,
): Boolean = requestGeneration == currentGeneration &&
        requestedTrack == currentTrack &&
        requestedSourceId == currentSourceId

private fun parseVttTimestamp(value: String): Double? {
    val parts = value.trim().split(':')
    if (parts.size !in 2..3) return null
    val secondsPart = parts.last().replace(',', '.').toDoubleOrNull() ?: return null
    val minutes = parts[parts.lastIndex - 1].toDoubleOrNull() ?: return null
    val hours = if (parts.size == 3) parts[0].toDoubleOrNull() ?: return null else 0.0
    return hours * 3600 + minutes * 60 + secondsPart
}

fun normalizeSubtitleStyle(style: SubtitleStyle): SubtitleStyle = style.copy(
    fontFamily = style.fontFamily.takeIf { it in setOf("sans", "serif", "mono") } ?: "sans",
    textScale = style.textScale.coerceIn(50f, 200f),
    borderSize = style.borderSize.coerceIn(0f, 8f),
    backgroundOpacity = style.backgroundOpacity.coerceIn(0f, 100f),
)

fun trickplayPreview(
    session: AuthSession,
    itemId: String,
    source: MediaSource?,
    timeSeconds: Double,
): TrickplayPreview? {
    if (!timeSeconds.isFinite() || timeSeconds < 0.0) return null
    val entry = source?.trickplay
        ?.entries
        ?.sortedByDescending { it.key.toIntOrNull() ?: Int.MIN_VALUE }
        ?.firstOrNull { it.key.toIntOrNull() != null }
        ?: return null
    val widthKey = entry.key
    val info = entry.value
    val thumbnailWidth = info.width ?: widthKey.toIntOrNull() ?: return null
    val thumbnailHeight = info.height ?: ((thumbnailWidth * 9) / 16)
    val columns = info.tileWidth ?: 10
    val rows = info.tileHeight ?: 10
    val intervalSeconds = max(1.0, (info.intervalMillis ?: 10_000L) / 1_000.0)
    if (thumbnailWidth <= 0 || thumbnailHeight <= 0 || columns <= 0 || rows <= 0) return null

    val thumbnail = floor(timeSeconds / intervalSeconds).toInt().coerceAtLeast(0)
    val tileSize = columns * rows
    val tileIndex = thumbnail / tileSize
    val tileOffset = thumbnail % tileSize
    val url = session.serverUrl.toHttpUrl().newBuilder()
        .addPathSegment("api")
        .addPathSegment("video")
        .addPathSegment(itemId)
        .addPathSegment("trickplay")
        .addPathSegment(widthKey)
        .addPathSegment(tileIndex.toString())
        .addQueryParameter("MediaSourceId", source.id ?: itemId)
        .build()
        .toString()
    return TrickplayPreview(
        url = url,
        width = thumbnailWidth,
        height = thumbnailHeight,
        tileIndex = tileIndex,
        cellX = tileOffset % columns,
        cellY = tileOffset / columns,
        columns = columns,
        rows = rows,
    )
}
