package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.MediaSource
import com.zenstream.zenstreammobile.model.SubtitleCue
import com.zenstream.zenstreammobile.model.SubtitleStyle
import com.zenstream.zenstreammobile.model.TrickplayPreview
import kotlin.math.floor

fun parseWebVttCues(input: String): List<SubtitleCue> = buildList {
    val lines = input.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
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
        val end =
            parseVttTimestamp(
                timingLine.substring(arrowIndex + 3).trim().substringBefore(' ').trim()
            )
        index++
        val text =
            buildList {
                    while (index < lines.size && lines[index].isNotBlank()) {
                        add(lines[index])
                        index++
                    }
                }
                .joinToString("\n")
                .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("""<[^>]+>"""), "")
                .let(::stripAssOverrideTags)
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

private fun stripAssOverrideTags(input: String): String =
    buildString(input.length) {
        var index = 0
        while (index < input.length) {
            if (input[index] == '{' && input.getOrNull(index + 1) == '\\') {
                val end = input.indexOf('}', startIndex = index + 2)
                if (end >= 0) {
                    index = end + 1
                    continue
                }
            }
            append(input[index])
            index++
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
): Boolean =
    requestGeneration == currentGeneration &&
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

fun normalizeSubtitleStyle(style: SubtitleStyle): SubtitleStyle =
    style.copy(
        fontFamily = style.fontFamily.takeIf { it in setOf("sans", "serif", "mono") } ?: "sans",
        textScale = style.textScale.coerceIn(50f, 200f),
        bottomSpacing = style.bottomSpacing.coerceIn(0f, 300f),
        borderSize = style.borderSize.coerceIn(0f, 8f),
        backgroundOpacity = style.backgroundOpacity.coerceIn(0f, 100f),
    )

fun trickplayPreview(
    source: MediaSource?,
    timeSeconds: Double,
): TrickplayPreview? {
    if (!timeSeconds.isFinite() || timeSeconds < 0.0) return null
    val manifest = source?.trickplay ?: return null
    if (
        manifest.state != "ready" ||
            manifest.frameWidth <= 0 ||
            manifest.frameHeight <= 0 ||
            !manifest.intervalSeconds.isFinite() ||
            manifest.intervalSeconds <= 0.0 ||
            manifest.columns <= 0 ||
            manifest.rows <= 0 ||
            manifest.frameCount <= 0
    )
        return null

    val thumbnail =
        floor(timeSeconds / manifest.intervalSeconds).toInt().coerceIn(0, manifest.frameCount - 1)
    val columns = manifest.columns
    val rows = manifest.rows
    val tileSize = columns * rows
    val tileIndex = thumbnail / tileSize
    val sheet = manifest.sheets.firstOrNull { it.index == tileIndex } ?: return null
    val tileOffset = thumbnail % tileSize
    if (tileOffset >= sheet.frameCount) return null
    return TrickplayPreview(
        url = sheet.url,
        width = manifest.frameWidth,
        height = manifest.frameHeight,
        tileIndex = tileIndex,
        cellX = tileOffset % columns,
        cellY = tileOffset / columns,
        columns = columns,
        rows = rows,
    )
}
