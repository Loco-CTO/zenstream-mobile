package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.SubtitleCue
import com.zenstream.zenstreammobile.model.SubtitleStyle

fun parseWebVttCues(input: String): List<SubtitleCue> = buildList {
    val lines = input.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    var index = 0
    while (index < lines.size) {
        val line = lines[index].trim()
        if (!line.contains(" --> ")) {
            index++
            continue
        }
        val timing = line.split(" --> ", limit = 2)
        val start = parseVttTimestamp(timing[0])
        val end = parseVttTimestamp(timing[1].substringBefore(' '))
        index++
        val text = buildList {
            while (index < lines.size && lines[index].isNotBlank()) {
                add(lines[index].replace(Regex("<[^>]+>"), ""))
                index++
            }
        }.joinToString("\n").trim()
        if (start != null && end != null && end > start && text.isNotBlank()) {
            add(SubtitleCue(start, end, text))
        }
    }
}

fun activeSubtitleCues(
    cues: List<SubtitleCue>,
    positionSeconds: Double,
    offsetSeconds: Double = 0.0,
): List<SubtitleCue> {
    val time = positionSeconds + offsetSeconds
    return cues.filter { cue -> time >= cue.startSeconds && time < cue.endSeconds }
}

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
