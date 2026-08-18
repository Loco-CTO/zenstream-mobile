package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.BuildConfig
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.InterfaceLocaleMode
import com.zenstream.zenstreammobile.data.PlaybackLanguageOption
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleStyle
import com.zenstream.zenstreammobile.ui.SettingsUiState

@Composable
internal fun MyPageSettingsContent(
    state: SettingsUiState,
    onInterfaceLocaleChange: (InterfaceLocaleMode) -> Unit,
    onMetadataLanguageChange: (String?) -> Unit,
    onPlaybackPreferenceChange: (String?, String?) -> Unit,
    onPlayerEngineChange: (PlayerEngine) -> Unit,
    onShowDebugIconChange: (Boolean) -> Unit,
    onSubtitleChange: (SubtitleStyle.() -> SubtitleStyle) -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SettingsSectionCard(
            title = stringResource(R.string.appearance_group),
            icon = LucideR.drawable.lucide_ic_settings,
        ) {
            InterfaceLanguageSelector(
                selected = state.interfaceLocaleMode,
                enabled = !state.interfaceLocaleSaving,
                onChange = onInterfaceLocaleChange,
            )
            SectionDivider()
            MetadataLanguageSelector(
                languages = state.metadataLanguages,
                selected = state.metadataLanguage,
                effective = state.effectiveMetadataLanguage,
                enabled = !state.metadataSaving,
                onChange = onMetadataLanguageChange,
            )
            if (state.interfaceLocaleSaveError) {
                SettingsErrorText(R.string.interface_language_save_failed)
            }
            if (state.metadataSaveError) {
                SettingsErrorText(R.string.metadata_language_save_failed)
            }
        }

        SettingsSectionCard(
            title = stringResource(R.string.player_group),
            icon = LucideR.drawable.lucide_ic_play,
        ) {
            PlaybackLanguageSelector(
                title = stringResource(R.string.audio_language),
                options = state.playbackPreference.audioLanguages,
                selected = state.playbackPreference.audioLanguage,
                offAllowed = false,
                enabled = !state.playbackSaving,
                onChange = { audio ->
                    onPlaybackPreferenceChange(
                        audio,
                        state.playbackPreference.subtitleLanguage,
                    )
                },
            )
            SectionDivider()
            PlaybackLanguageSelector(
                title = stringResource(R.string.subtitle_language),
                options = state.playbackPreference.subtitleLanguages,
                selected = state.playbackPreference.subtitleLanguage,
                offAllowed = true,
                enabled = !state.playbackSaving,
                onChange = { subtitle ->
                    onPlaybackPreferenceChange(
                        state.playbackPreference.audioLanguage,
                        subtitle,
                    )
                },
            )
            if (state.playbackSaveError) {
                SettingsErrorText(R.string.playback_language_save_failed)
            }
            SectionDivider()
            EngineSelector(state.playerEngine, onPlayerEngineChange)
            SectionDivider()
            SettingSwitchRow(
                title = stringResource(R.string.player_show_debug_icon),
                supporting = stringResource(R.string.player_show_debug_icon_description),
                checked = state.showDebugIcon,
                onCheckedChange = onShowDebugIconChange,
            )
        }

        SettingsSectionCard(
            title = stringResource(R.string.subtitles_group),
            icon = LucideR.drawable.lucide_ic_captions,
        ) {
            SubtitleSettings(style = state.subtitleStyle, onChange = onSubtitleChange)
            if (state.subtitleSaveError) {
                SettingsErrorText(R.string.subtitle_save_failed)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
            ) {
                Text(stringResource(R.string.logout))
            }
            Text(
                text =
                    stringResource(R.string.settings_version_value, BuildConfig.ZENSTREAM_VERSION),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = .55f),
    )
}

@Composable
private fun SettingsErrorText(message: Int) {
    Text(
        text = stringResource(message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
internal fun InterfaceLanguageSelector(
    selected: InterfaceLocaleMode,
    enabled: Boolean,
    onChange: (InterfaceLocaleMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel =
        when (selected) {
            InterfaceLocaleMode.Automatic -> stringResource(R.string.interface_language_automatic)
            InterfaceLocaleMode.English -> stringResource(R.string.language_english)
            InterfaceLocaleMode.Japanese -> stringResource(R.string.language_japanese)
        }
    SettingChoiceRow(
        title = stringResource(R.string.interface_language),
        supporting = selectedLabel,
        enabled = enabled,
        onClick = { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        listOf(
                InterfaceLocaleMode.Automatic to R.string.interface_language_automatic,
                InterfaceLocaleMode.English to R.string.language_english,
                InterfaceLocaleMode.Japanese to R.string.language_japanese,
            )
            .forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        onChange(mode)
                        expanded = false
                    },
                )
            }
    }
}

@Composable
internal fun MetadataLanguageSelector(
    languages: List<String>,
    selected: String?,
    effective: String,
    enabled: Boolean,
    onChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingChoiceRow(
        title = stringResource(R.string.preferred_metadata_language),
        supporting = selected ?: stringResource(R.string.metadata_language_automatic, effective),
        enabled = enabled,
        onClick = { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.metadata_language_automatic, effective)) },
            onClick = {
                onChange(null)
                expanded = false
            },
        )
        languages.forEach { language ->
            DropdownMenuItem(
                text = { Text(language) },
                onClick = {
                    onChange(language)
                    expanded = false
                },
            )
        }
    }
}

@Composable
internal fun PlaybackLanguageSelector(
    title: String,
    options: List<PlaybackLanguageOption>,
    selected: String?,
    offAllowed: Boolean,
    enabled: Boolean,
    onChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel =
        when {
            selected == null -> stringResource(R.string.language_automatic)
            selected == "off" -> stringResource(R.string.subtitles_off)
            else ->
                options.firstOrNull { it.value == selected }?.label
                    ?: stringResource(R.string.language_automatic)
        }
    SettingChoiceRow(
        title = title,
        supporting = selectedLabel,
        enabled = enabled,
        onClick = { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.language_automatic)) },
            onClick = {
                onChange(null)
                expanded = false
            },
        )
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                onClick = {
                    onChange(option.value)
                    expanded = false
                },
            )
        }
        if (offAllowed) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.subtitles_off)) },
                onClick = {
                    onChange("off")
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun EngineSelector(selected: PlayerEngine, onChange: (PlayerEngine) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SettingChoiceRow(
        title = stringResource(R.string.player_engine),
        supporting =
            if (selected == PlayerEngine.MEDIA3) {
                stringResource(R.string.player_engine_media3)
            } else {
                stringResource(R.string.player_engine_mpv)
            },
        onClick = { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.player_engine_media3)) },
            onClick = {
                onChange(PlayerEngine.MEDIA3)
                expanded = false
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.player_engine_mpv)) },
            onClick = {
                onChange(PlayerEngine.MPV)
                expanded = false
            },
        )
    }
}

@Composable
private fun SettingChoiceRow(
    title: String,
    supporting: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = supporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_chevron_down),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = supporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun SubtitleSettings(
    style: SubtitleStyle,
    onChange: (SubtitleStyle.() -> SubtitleStyle) -> Unit,
) {
    var fontExpanded by remember { mutableStateOf(false) }
    SettingChoiceRow(
        title = stringResource(R.string.subtitle_font),
        supporting = subtitleFontLabel(style.fontFamily),
        onClick = { fontExpanded = true },
    )
    DropdownMenu(expanded = fontExpanded, onDismissRequest = { fontExpanded = false }) {
        listOf(
                "sans" to R.string.subtitle_font_sans,
                "serif" to R.string.subtitle_font_serif,
                "mono" to R.string.subtitle_font_mono,
            )
            .forEach { (family, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        onChange { copy(fontFamily = family) }
                        fontExpanded = false
                    },
                )
            }
    }
    SectionDivider()
    SettingSwitchRow(
        title = stringResource(R.string.subtitle_bold),
        supporting = stringResource(R.string.subtitle_bold_description),
        checked = style.bold,
        onCheckedChange = { onChange { copy(bold = it) } },
    )
    SectionDivider()
    Text(
        text = stringResource(R.string.subtitle_typography_group),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
    SliderRow(stringResource(R.string.subtitle_text_size), style.textScale, 50f..200f, "%d%%") {
        onChange { copy(textScale = it) }
    }
    SectionDivider()
    Text(
        text = stringResource(R.string.subtitle_position_group),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
    SliderRow(
        stringResource(R.string.subtitle_bottom_spacing),
        style.bottomSpacing,
        0f..300f,
        "%d dp",
        steps = 299,
    ) {
        onChange { copy(bottomSpacing = it) }
    }
    SectionDivider()
    Text(
        text = stringResource(R.string.subtitle_colors_group),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
    SubtitleColorField(stringResource(R.string.subtitle_font_color), style.fontColor) {
        onChange { copy(fontColor = it) }
    }
    SubtitleColorField(stringResource(R.string.subtitle_border_color), style.borderColor) {
        onChange { copy(borderColor = it) }
    }
    SliderRow(stringResource(R.string.subtitle_border_size), style.borderSize, 0f..8f, "%.0f") {
        onChange { copy(borderSize = it) }
    }
    SubtitleColorField(stringResource(R.string.subtitle_background_color), style.backgroundColor) {
        onChange { copy(backgroundColor = it) }
    }
    SliderRow(
        stringResource(R.string.subtitle_background_opacity),
        style.backgroundOpacity,
        0f..100f,
        "%d%%",
    ) {
        onChange { copy(backgroundOpacity = it) }
    }
    SectionDivider()
    Text(
        text = stringResource(R.string.subtitle_preview),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
    Text(
        text = stringResource(R.string.subtitle_preview_sample),
        color =
            runCatching { Color(android.graphics.Color.parseColor(style.fontColor)) }
                .getOrDefault(Color.White),
        fontFamily =
            when (style.fontFamily) {
                "serif" -> FontFamily.Serif
                "mono" -> FontFamily.Monospace
                else -> FontFamily.SansSerif
            },
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
        fontSize = (20f * style.textScale / 100f).sp,
        modifier =
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp),
    )
}

@Composable
private fun subtitleFontLabel(family: String): String =
    stringResource(
        when (family) {
            "serif" -> R.string.subtitle_font_serif
            "mono" -> R.string.subtitle_font_mono
            else -> R.string.subtitle_font_sans
        }
    )

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (format.contains("%d")) format.format(value.toInt()) else format.format(value),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
