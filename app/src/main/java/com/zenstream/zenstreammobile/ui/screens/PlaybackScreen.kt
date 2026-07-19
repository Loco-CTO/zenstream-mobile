package com.zenstream.zenstreammobile.ui.screens

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.JellyfinRepository
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.ui.PlaybackViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun PlaybackScreen(
    repository: JellyfinRepository,
    session: AuthSession,
    orchestratorUrl: String?,
    itemId: String,
    initialItemName: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: PlaybackViewModel = viewModel(
        key = "playback-${session.userId}-$itemId",
        factory = PlaybackViewModel.Factory(repository, session, orchestratorUrl, itemId, context),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(false) }
    var controlsLocked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<PlayerMenu?>(null) }
    var subtitlePositionSeconds by remember(vm) { mutableStateOf(0.0) }

    DisposableEffect(vm) {
        onDispose {
            vm.onPause()
        }
    }

    LaunchedEffect(controlsVisible, controlsLocked, menu, state.engine.isPlaying) {
        if (shouldAutoHidePlaybackControls(controlsVisible, controlsLocked, menu != null, state.engine.isPlaying)) {
            delay(4_500)
            controlsVisible = false
        }
    }

    LaunchedEffect(vm) {
        while (isActive) {
            withFrameNanos { }
            subtitlePositionSeconds = vm.subtitlePositionSeconds()
        }
    }

    val playerView = remember(state.loading, state.engineType) {
        if (!state.loading) vm.createView(context) else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (playerView != null) {
            AndroidView(
                factory = { playerView },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!controlsLocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(controlsLocked) {
                        detectTapGestures { controlsVisible = !controlsVisible }
                    },
            )
        }

        state.activeCuesAt(subtitlePositionSeconds).forEach { cue ->
            Text(
                text = cue.text,
                color = parseColor(state.subtitleStyle.fontColor, Color.White),
                fontFamily = when (state.subtitleStyle.fontFamily) {
                    "serif" -> FontFamily.Serif
                    "mono" -> FontFamily.Monospace
                    else -> FontFamily.SansSerif
                },
                fontWeight = if (state.subtitleStyle.bold) FontWeight.Bold else FontWeight.Normal,
                fontSize = (22f * state.subtitleStyle.textScale / 100f).sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = if (controlsVisible && !controlsLocked) 128.dp else 48.dp)
                    .background(
                        parseColor(state.subtitleStyle.backgroundColor, Color.Black).copy(
                            alpha = state.subtitleStyle.backgroundOpacity / 100f
                        ),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.titleMedium.copy(
                    shadow = Shadow(
                        color = parseColor(state.subtitleStyle.borderColor, Color.Black),
                        blurRadius = state.subtitleStyle.borderSize * 2f,
                    )
                ),
            )
        }

        if (state.loading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (state.error != null && !state.loading) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.error ?: stringResourceCompat(R.string.media_playback_failed), color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = onBack) { Text(stringResourceCompat(R.string.back)) }
            }
        }

        if (controlsVisible || controlsLocked) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = .78f), Color.Black.copy(alpha = 0f))
                        )
                    )
                    .padding(top = 8.dp, start = 10.dp, end = 10.dp, bottom = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.onPause(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResourceCompat(R.string.back), tint = Color.White)
                    }
                    Text(
                        playbackTitle(state, initialItemName),
                        color = Color.White,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { controlsLocked = !controlsLocked; menu = null }) {
                        Icon(
                            if (controlsLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            stringResourceCompat(if (controlsLocked) R.string.player_unlock else R.string.player_lock),
                            tint = Color.White,
                        )
                    }
                    if (!controlsLocked) {
                        PlayerMenuButton(Icons.Default.Speed, stringResourceCompat(R.string.player_speed)) { menu = PlayerMenu.Settings }
                        PlayerMenuButton(Icons.Default.AudioFile, stringResourceCompat(R.string.audio_track)) { menu = PlayerMenu.Audio }
                        PlayerMenuButton(Icons.Default.VolumeUp, stringResourceCompat(R.string.player_volume)) { menu = PlayerMenu.Volume }
                        PlayerMenuButton(Icons.Default.PictureInPictureAlt, stringResourceCompat(R.string.player_pip), enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { enterPip(context) }
                        PlayerMenuButton(Icons.Default.ClosedCaption, stringResourceCompat(R.string.subtitle_track)) { menu = PlayerMenu.Subtitles }
                    }
                }
            }
            if (controlsVisible && !controlsLocked) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(34.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerMenuButton(Icons.Default.SkipPrevious, stringResourceCompat(R.string.player_previous), enabled = false) {}
                    PlayerMenuButton(Icons.Default.Replay10, stringResourceCompat(R.string.player_seek_back)) { vm.seekBy(-10.0) }
                    Surface(
                        onClick = vm::togglePlay,
                        modifier = Modifier.size(88.dp),
                        shape = CircleShape,
                        color = Color.White,
                        contentColor = Color.Black,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (state.engine.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                stringResourceCompat(if (state.engine.isPlaying) R.string.pause else R.string.play),
                                modifier = Modifier.size(44.dp),
                            )
                        }
                    }
                    PlayerMenuButton(Icons.Default.Forward10, stringResourceCompat(R.string.player_seek_forward)) { vm.seekBy(10.0) }
                    PlayerMenuButton(Icons.Default.SkipNext, stringResourceCompat(R.string.player_next), enabled = false) {}
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = .8f))
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        "${formatTime(state.engine.positionSeconds)}  -  ${formatTime(state.engine.durationSeconds)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Slider(
                        value = state.engine.positionSeconds.toFloat().coerceIn(0f, state.engine.durationSeconds.toFloat().coerceAtLeast(0.1f)),
                        onValueChange = { vm.seekTo(it.toDouble()) },
                        valueRange = 0f..state.engine.durationSeconds.toFloat().coerceAtLeast(0.1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        PlayerDropdown(menu, state.selectedSubtitle, state.selectedAudio, state.playback?.audio.orEmpty(), state.playback?.subtitles.orEmpty(), state.playback?.qualities.orEmpty(), state.engine.speed, state.engine.volume, state.engine.muted, onDismiss = { menu = null }, onSubtitle = { vm.chooseSubtitle(it); menu = null }, onAudio = { vm.chooseAudio(it); menu = null }, onQuality = { vm.chooseQuality(it); menu = null }, onSpeed = { vm.setSpeed(it); menu = null }, onVolume = vm::setVolume, onMute = { vm.setMuted(!state.engine.muted) })
    }
}

private enum class PlayerMenu { Audio, Subtitles, Settings, Volume }

@Composable
private fun PlayerMenuButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, label, tint = if (enabled) Color.White else Color.White.copy(alpha = .3f))
    }
}

@Composable
private fun PlayerDropdown(
    menu: PlayerMenu?,
    selectedSubtitle: Int?,
    selectedAudio: Int?,
    audio: List<MediaStream>,
    subtitles: List<MediaStream>,
    qualities: List<Int>,
    speed: Float,
    volume: Float,
    muted: Boolean,
    onDismiss: () -> Unit,
    onSubtitle: (Int?) -> Unit,
    onAudio: (MediaStream) -> Unit,
    onQuality: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
) {
    if (menu == null) return
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        when (menu) {
            PlayerMenu.Audio -> audio.forEach { stream ->
                DropdownMenuItem(text = { Text(stream.displayTitle ?: stream.language ?: "Audio ${stream.index}") }, onClick = { onAudio(stream) }, leadingIcon = { if (selectedAudio == stream.index) Text("✓") })
            }
            PlayerMenu.Subtitles -> {
                DropdownMenuItem(text = { Text(stringResourceCompat(R.string.subtitles_off)) }, onClick = { onSubtitle(null) }, leadingIcon = { if (selectedSubtitle == null) Text("✓") })
                subtitles.forEach { stream ->
                    DropdownMenuItem(text = { Text(stream.displayTitle ?: stream.language ?: "Subtitle ${stream.index}") }, onClick = { onSubtitle(stream.index) }, leadingIcon = { if (selectedSubtitle == stream.index) Text("✓") })
                }
            }
            PlayerMenu.Settings -> {
                listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                    DropdownMenuItem(text = { Text("Speed ${value}x") }, onClick = { onSpeed(value) }, leadingIcon = { if (speed == value) Text("✓") })
                }
                qualities.forEach { value ->
                    DropdownMenuItem(text = { Text(if (value == 0) "Auto" else "${value / 1_000_000} Mbps") }, onClick = { onQuality(value) })
                }
            }
            PlayerMenu.Volume -> {
                DropdownMenuItem(
                    text = { Text(stringResourceCompat(R.string.player_volume)) },
                    onClick = onMute,
                    leadingIcon = { Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null) },
                )
                Slider(
                    value = if (muted) 0f else volume,
                    onValueChange = onVolume,
                    valueRange = 0f..1f,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

private fun enterPip(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        (context as? Activity)?.enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
        )
    }
}

private fun playbackTitle(
    state: com.zenstream.zenstreammobile.ui.PlaybackUiState,
    initialItemName: String,
): String {
    val item = state.playback?.item ?: return state.itemName.ifBlank { initialItemName }
    return if (item.parentIndexNumber != null && item.indexNumber != null) {
        "S${item.parentIndexNumber}:E${item.indexNumber} - ${item.name}"
    } else {
        item.name
    }
}

private fun parseColor(value: String, fallback: Color): Color = runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(fallback)
private fun formatTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

internal fun shouldAutoHidePlaybackControls(
    visible: Boolean,
    locked: Boolean,
    menuOpen: Boolean,
    isPlaying: Boolean,
): Boolean = visible && !locked && !menuOpen && isPlaying

@Composable
private fun stringResourceCompat(id: Int): String = androidx.compose.ui.res.stringResource(id)
