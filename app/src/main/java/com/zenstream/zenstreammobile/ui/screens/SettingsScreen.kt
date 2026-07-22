package com.zenstream.zenstreammobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zenstream.zenstreammobile.BuildConfig
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.ui.SettingsViewModel
import com.composables.icons.lucide.R as LucideR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: CatalogRepository,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(
        key = "settings",
        factory = SettingsViewModel.Factory(repository),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(SettingsSection.Root) }
    BackHandler(enabled = section != SettingsSection.Root) {
        section = SettingsSection.Root
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (section) {
                            SettingsSection.Root -> stringResource(R.string.settings)
							SettingsSection.Appearance -> stringResource(R.string.appearance_group)
                            SettingsSection.Player -> stringResource(R.string.player_group)
                            SettingsSection.Subtitles -> stringResource(R.string.subtitles_group)
                            SettingsSection.Version -> stringResource(R.string.settings_version)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (section == SettingsSection.Root) onBack() else section =
                            SettingsSection.Root
                    }) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_arrow_left),
                            stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        PullToRefreshLayout(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                when (section) {
                    SettingsSection.Root -> item {
                        SettingsRootContent(
                            onOpenSection = { section = it },
                            onLogout = onLogout,
                        )
                    }

                    SettingsSection.Player -> item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                        ) {
                            EngineSelector(state.playerEngine, vm::setPlayerEngine)
                        }
                    }

					SettingsSection.Appearance -> item {
						Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111111))) {
							MetadataLanguageSelector(
								languages = state.metadataLanguages,
								selected = state.metadataLanguage,
								effective = state.effectiveMetadataLanguage,
								onChange = vm::setMetadataLanguage,
							)
							if (state.metadataSaveError) Text(stringResource(R.string.metadata_language_save_failed), color = MaterialThemeError, modifier = Modifier.padding(16.dp))
						}
					}

                    SettingsSection.Subtitles -> item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                        ) {
                            SubtitleSettings(
                                style = state.subtitleStyle,
                                onChange = vm::updateSubtitle
                            )
                            if (state.subtitleSaveError) {
                                Text(
                                    stringResource(R.string.subtitle_save_failed),
                                    color = MaterialThemeError,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }

                    SettingsSection.Version -> item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                        ) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.settings_version)) },
                                supportingContent = { Text(BuildConfig.ZENSTREAM_VERSION) },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal enum class SettingsSection { Root, Appearance, Player, Subtitles, Version }

@Composable
internal fun SettingsRootContent(
    onOpenSection: (SettingsSection) -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
		SettingsMenuItem(stringResource(R.string.appearance_group)) { onOpenSection(SettingsSection.Appearance) }
        SettingsMenuItem(stringResource(R.string.player_group)) { onOpenSection(SettingsSection.Player) }
        SettingsMenuItem(stringResource(R.string.subtitles_group)) { onOpenSection(SettingsSection.Subtitles) }
        SettingsMenuItem(stringResource(R.string.settings_version)) { onOpenSection(SettingsSection.Version) }
        androidx.compose.material3.Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.logout)) }
        Text(
            stringResource(R.string.settings_version_value, BuildConfig.ZENSTREAM_VERSION),
            color = Color.White.copy(alpha = 0.6f),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MetadataLanguageSelector(
	languages: List<String>,
	selected: String?,
	effective: String,
	onChange: (String?) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	ListItem(
		headlineContent = { Text(stringResource(R.string.preferred_metadata_language)) },
		supportingContent = { Text(selected ?: stringResource(R.string.metadata_language_automatic, effective)) },
		modifier = Modifier.fillMaxWidth().clickable { expanded = true },
	)
	DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
		DropdownMenuItem(text = { Text(stringResource(R.string.metadata_language_automatic, effective)) }, onClick = { onChange(null); expanded = false })
		languages.forEach { language ->
			DropdownMenuItem(text = { Text(language) }, onClick = { onChange(language); expanded = false })
		}
	}
}

@Composable
private fun SettingsMenuItem(label: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        ListItem(
            headlineContent = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
            content = content,
        )
    }
}

@Composable
private fun EngineSelector(selected: PlayerEngine, onChange: (PlayerEngine) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.player_engine)) },
        supportingContent = {
            Text(
                if (selected == PlayerEngine.MEDIA3) stringResource(R.string.player_engine_media3) else stringResource(
                    R.string.player_engine_mpv
                )
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.player_engine_media3)) },
            onClick = { onChange(PlayerEngine.MEDIA3); expanded = false })
        DropdownMenuItem(
            text = { Text(stringResource(R.string.player_engine_mpv)) },
            onClick = { onChange(PlayerEngine.MPV); expanded = false })
    }
}

@Composable
private fun SubtitleSettings(
    style: com.zenstream.zenstreammobile.model.SubtitleStyle,
    onChange: (com.zenstream.zenstreammobile.model.SubtitleStyle.() -> com.zenstream.zenstreammobile.model.SubtitleStyle) -> Unit,
) {
    var fontExpanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.subtitle_font)) },
        supportingContent = { Text(style.fontFamily) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { fontExpanded = true },
    )
    DropdownMenu(expanded = fontExpanded, onDismissRequest = { fontExpanded = false }) {
        listOf("sans", "serif", "mono").forEach { family ->
            DropdownMenuItem(
                text = { Text(family) },
                onClick = { onChange { copy(fontFamily = family) }; fontExpanded = false })
        }
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.subtitle_bold)) },
        trailingContent = {
            Switch(
                checked = style.bold,
                onCheckedChange = { onChange { copy(bold = it) } })
        },
    )
    SliderRow(
        stringResource(R.string.subtitle_text_size),
        style.textScale,
        50f..200f,
        "%d%%"
    ) { onChange { copy(textScale = it) } }
    ColorField(stringResource(R.string.subtitle_font_color), style.fontColor) {
        onChange {
            copy(
                fontColor = it
            )
        }
    }
    SliderRow(
        stringResource(R.string.subtitle_border_size),
        style.borderSize,
        0f..8f,
        "%.0f"
    ) { onChange { copy(borderSize = it) } }
    ColorField(stringResource(R.string.subtitle_border_color), style.borderColor) {
        onChange {
            copy(
                borderColor = it
            )
        }
    }
    ColorField(
        stringResource(R.string.subtitle_background_color),
        style.backgroundColor
    ) { onChange { copy(backgroundColor = it) } }
    SliderRow(
        stringResource(R.string.subtitle_background_opacity),
        style.backgroundOpacity,
        0f..100f,
        "%d%%"
    ) { onChange { copy(backgroundOpacity = it) } }
    Text(
        stringResource(R.string.subtitle_preview),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
    )
    Text(
        "The quick brown fox jumps over the lazy dog.",
        color = runCatching { Color(android.graphics.Color.parseColor(style.fontColor)) }.getOrDefault(
            Color.White
        ),
        fontFamily = when (style.fontFamily) {
            "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; else -> FontFamily.SansSerif
        },
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
        fontSize = (20f * style.textScale / 100f).sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(16.dp),
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(if (format.contains("%d")) format.format(value.toInt()) else format.format(value))
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ColorField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; if (Regex("^#[0-9a-fA-F]{6}$").matches(it)) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private val MaterialThemeError = Color(0xFFFF8A80)

