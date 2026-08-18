package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.SubtitleStyle
import org.json.JSONObject

val DEFAULT_SUBTITLE_STYLE = SubtitleStyle()

fun subtitleStyleToJson(style: SubtitleStyle): String =
    JSONObject()
        .put("fontFamily", style.fontFamily)
        .put("bold", style.bold)
        .put("textScale", style.textScale)
        .put("bottomSpacing", style.bottomSpacing)
        .put("fontColor", style.fontColor)
        .put("borderSize", style.borderSize)
        .put("borderColor", style.borderColor)
        .put("backgroundColor", style.backgroundColor)
        .put("backgroundOpacity", style.backgroundOpacity)
        .toString()

fun subtitleStyleFromJson(body: String): SubtitleStyle {
    val json = JSONObject(body)
    return normalizeSubtitleStyle(
        SubtitleStyle(
            fontFamily = json.optString("fontFamily", "sans"),
            bold = json.optBoolean("bold", false),
            textScale = json.optDouble("textScale", 100.0).toFloat(),
            bottomSpacing =
                json
                    .optDouble("bottomSpacing", DEFAULT_SUBTITLE_STYLE.bottomSpacing.toDouble())
                    .toFloat(),
            fontColor = json.optString("fontColor", "#ffffff"),
            borderSize =
                json
                    .optDouble("borderSize", DEFAULT_SUBTITLE_STYLE.borderSize.toDouble())
                    .toFloat(),
            borderColor = json.optString("borderColor", "#000000"),
            backgroundColor = json.optString("backgroundColor", "#000000"),
            backgroundOpacity = json.optDouble("backgroundOpacity", 0.0).toFloat(),
        )
    )
}
