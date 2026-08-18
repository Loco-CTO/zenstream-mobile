package com.zenstream.zenstreammobile.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.AVATAR_MAX_BYTES
import com.zenstream.zenstreammobile.data.AvatarFileTooLargeException
import com.zenstream.zenstreammobile.data.AvatarImageDimensions
import com.zenstream.zenstreammobile.data.AvatarPan
import com.zenstream.zenstreammobile.data.CatalogException
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.avatarCropForEditor
import com.zenstream.zenstreammobile.data.avatarSourceInfo
import com.zenstream.zenstreammobile.data.clampAvatarPan
import com.zenstream.zenstreammobile.data.clampAvatarZoom
import com.zenstream.zenstreammobile.model.AuthSession
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarEditorDialog(
    session: AuthSession,
    repository: CatalogRepository,
    onSessionChanged: (AuthSession) -> Unit,
    onDismiss: () -> Unit,
    pickedUri: Uri? = null,
    onPickImage: () -> Unit = {},
    onPickedImageConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var sourceDimensions by remember { mutableStateOf<AvatarImageDimensions?>(null) }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf<IntSize?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(AvatarPan()) }
    var rotation by remember { mutableStateOf(0) }

    val fileTooLarge = stringResource(R.string.avatar_file_too_large)
    val unsupported = stringResource(R.string.avatar_unsupported_format)
    val fileInvalid = stringResource(R.string.avatar_file_invalid)
    val uploadFailed = stringResource(R.string.avatar_upload_failed)
    val selectUri: (Uri) -> Unit = { uri ->
        sourceError = null
        editorError = null
        val info =
            runCatching { resolver.avatarSourceInfo(uri) }
                .onFailure { error ->
                    sourceError =
                        when (error) {
                            is com.zenstream.zenstreammobile.data.AvatarFileTooLargeException ->
                                fileTooLarge
                            is com.zenstream.zenstreammobile.data.AvatarUnsupportedFormatException ->
                                unsupported
                            else -> fileInvalid
                        }
                }
                .getOrNull()
        if (info == null) {
            selectedUri = null
            sourceDimensions = null
        } else {
            selectedUri = uri
            sourceDimensions = null
            viewport = null
            zoom = 1f
            pan = AvatarPan()
            rotation = 0
        }
    }

    LaunchedEffect(pickedUri) {
        pickedUri?.let {
            selectUri(it)
            onPickedImageConsumed()
        }
    }

    LaunchedEffect(selectedUri) {
        val uri = selectedUri ?: return@LaunchedEffect
        sourceDimensions =
            withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        AvatarImageDimensions(options.outWidth, options.outHeight)
                    } else null
                }
            }
        if (sourceDimensions == null) sourceError = fileInvalid
    }

    fun saveAvatar() {
        if (saving) return
        val uri = selectedUri
        val dimensions = sourceDimensions
        val size = viewport
        if (uri == null || dimensions == null || size == null) {
            editorError = fileInvalid
            return
        }
        val crop =
            avatarCropForEditor(
                dimensions,
                com.zenstream.zenstreammobile.data.AvatarViewport(size.width, size.height),
                zoom,
                pan,
                rotation,
            )
        saving = true
        editorError = null
        scope.launch {
            runCatching { repository.uploadAvatar(session, resolver, uri, crop) }
                .onSuccess {
                    onSessionChanged(it)
                    onDismiss()
                }
                .onFailure { error ->
                    editorError =
                        if (
                            (error is CatalogException && error.statusCode == 413) ||
                                error is AvatarFileTooLargeException
                        ) {
                            fileTooLarge
                        } else uploadFailed
                }
            saving = false
        }
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0B0B0D),
        ) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.avatar_editor_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !saving) {
                            Icon(
                                painter = painterResource(LucideR.drawable.lucide_ic_x),
                                contentDescription = stringResource(R.string.cancel),
                            )
                        }
                    },
                    actions = {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(horizontal = 16.dp).size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(
                                onClick = { saveAvatar() },
                                enabled =
                                    selectedUri != null &&
                                        sourceDimensions != null &&
                                        viewport != null,
                            ) {
                                Icon(
                                    painter = painterResource(LucideR.drawable.lucide_ic_check),
                                    contentDescription = stringResource(R.string.save),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
                Column(
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (selectedUri == null) {
                        Text(
                            text = stringResource(R.string.avatar_editor_description),
                            color = Color.White.copy(alpha = .72f),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                    if (selectedUri == null) {
                        AvatarPickCard(
                            enabled = !saving,
                            onPick = onPickImage,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    } else {
                        sourceDimensions?.let { dimensions ->
                            AvatarEditorPreview(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                uri = selectedUri!!,
                                dimensions = dimensions,
                                viewport = viewport,
                                zoom = zoom,
                                pan = pan,
                                rotation = rotation,
                                onViewportChanged = { viewport = it },
                                onTransform = { nextZoom, nextPan ->
                                    val size = viewport ?: return@AvatarEditorPreview
                                    val cropViewport =
                                        com.zenstream.zenstreammobile.data.AvatarViewport(
                                            size.width,
                                            size.height,
                                        )
                                    zoom = clampAvatarZoom(nextZoom)
                                    pan =
                                        clampAvatarPan(
                                            dimensions,
                                            cropViewport,
                                            zoom,
                                            rotation,
                                            nextPan,
                                        )
                                },
                            )
                            Text(
                                text = stringResource(R.string.avatar_pan_hint),
                                color = Color.White.copy(alpha = .62f),
                                style =
                                    androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                            AvatarZoomPanel(
                                zoom = zoom,
                                onZoomChange = { next ->
                                    viewport?.let { size ->
                                        val cropViewport =
                                            com.zenstream.zenstreammobile.data.AvatarViewport(
                                                size.width,
                                                size.height,
                                            )
                                        zoom = clampAvatarZoom(next)
                                        pan =
                                            clampAvatarPan(
                                                dimensions,
                                                cropViewport,
                                                zoom,
                                                rotation,
                                                pan,
                                            )
                                    }
                                },
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                AvatarEditorIconAction(
                                    icon = LucideR.drawable.lucide_ic_refresh_cw,
                                    label = stringResource(R.string.avatar_rotate),
                                    onClick = {
                                        val nextRotation = (rotation + 90) % 360
                                        rotation = nextRotation
                                        viewport?.let { size ->
                                            pan =
                                                clampAvatarPan(
                                                    dimensions,
                                                    com.zenstream.zenstreammobile.data
                                                        .AvatarViewport(
                                                            size.width,
                                                            size.height,
                                                        ),
                                                    zoom,
                                                    rotation,
                                                    pan,
                                                )
                                        }
                                    },
                                )
                            }
                            editorError?.let { message ->
                                Text(
                                    message,
                                    color = Color(0xFFFF8A80),
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                )
                            }
                            AvatarEditorChooseAnother(
                                saving = saving,
                                onChooseAnother = onPickImage,
                            )
                        }
                            ?: if (sourceError == null) {
                                Box(
                                    Modifier.fillMaxWidth().weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                Text(
                                    sourceError.orEmpty(),
                                    color = Color(0xFFFF8A80),
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                )
                            }
                    }
                    sourceError
                        ?.takeIf { selectedUri == null }
                        ?.let {
                            Text(
                                it,
                                color = Color(0xFFFF8A80),
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    if (selectedUri == null) {
                        Text(
                            stringResource(
                                R.string.avatar_file_guidance,
                                formatBytes(AVATAR_MAX_BYTES),
                            ),
                            color = Color.White.copy(alpha = .58f),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun AvatarPickCard(
    enabled: Boolean = true,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(1.2f)
                .clickable(enabled = enabled, onClick = onPick),
        color = Color(0xFF16161A),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_image),
                contentDescription = null,
                tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.avatar_choose_image))
            Text(
                stringResource(R.string.avatar_supported_formats),
                color = Color.White.copy(alpha = .58f),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AvatarEditorPreview(
    modifier: Modifier = Modifier,
    uri: Uri,
    dimensions: AvatarImageDimensions,
    viewport: IntSize?,
    zoom: Float,
    pan: AvatarPan,
    rotation: Int,
    onViewportChanged: (IntSize) -> Unit,
    onTransform: (Float, AvatarPan) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val imageLoader =
        remember(context) {
            ImageLoader.Builder(context)
                .components {
                    if (Build.VERSION.SDK_INT >= 28) {
                        add(AnimatedImageDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
        }
    val viewportSize = viewport?.takeIf { it.width > 0 && it.height > 0 }
    val baseScale = viewportSize?.let {
        com.zenstream.zenstreammobile.data.avatarCoverScale(
            dimensions,
            com.zenstream.zenstreammobile.data.AvatarViewport(
                it.width,
                it.height,
            ),
            1f,
            rotation,
        )
    }
    val latestZoom by androidx.compose.runtime.rememberUpdatedState(zoom)
    val latestPan by androidx.compose.runtime.rememberUpdatedState(pan)
    val latestOnTransform by androidx.compose.runtime.rememberUpdatedState(onTransform)
    Box(
        modifier = modifier.clip(RoundedCornerShape(0.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (viewportSize != null && baseScale != null) {
            val imageWidth = with(density) { (dimensions.width * baseScale).toDp() }
            val imageHeight = with(density) { (dimensions.height * baseScale).toDp() }
            AsyncImage(
                imageLoader = imageLoader,
                model = ImageRequest.Builder(context).data(uri).build(),
                contentDescription = stringResource(R.string.avatar_preview),
                contentScale = ContentScale.Fit,
                modifier =
                    Modifier.width(imageWidth).height(imageHeight).graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        rotationZ = rotation.toFloat()
                        translationX = pan.x
                        translationY = pan.y
                    },
            )
        } else {
            AsyncImage(
                imageLoader = imageLoader,
                model = ImageRequest.Builder(context).data(uri).build(),
                contentDescription = stringResource(R.string.avatar_preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(2.dp))
                    .onSizeChanged(onViewportChanged)
                    .pointerInput(dimensions, viewport, rotation) {
                        detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                            latestOnTransform(
                                latestZoom * gestureZoom,
                                AvatarPan(
                                    latestPan.x + gesturePan.x,
                                    latestPan.y + gesturePan.y,
                                ),
                            )
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    color = Color.White.copy(alpha = .86f),
                    style = Stroke(width = 2.dp.toPx()),
                )
                val lineColor = Color.White.copy(alpha = .3f)
                drawLine(
                    lineColor,
                    Offset(size.width / 3f, 0f),
                    Offset(size.width / 3f, size.height),
                )
                drawLine(
                    lineColor,
                    Offset(size.width * 2f / 3f, 0f),
                    Offset(size.width * 2f / 3f, size.height),
                )
                drawLine(
                    lineColor,
                    Offset(0f, size.height / 3f),
                    Offset(size.width, size.height / 3f),
                )
                drawLine(
                    lineColor,
                    Offset(0f, size.height * 2f / 3f),
                    Offset(size.width, size.height * 2f / 3f),
                )
            }
        }
    }
}

@Composable
private fun AvatarZoomPanel(
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF17151B),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "${(zoom * 100).roundToInt()}%",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = zoom,
                onValueChange = onZoomChange,
                valueRange = 1f..4f,
                steps = 29,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AvatarEditorIconAction(
    icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = .72f),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun AvatarEditorChooseAnother(
    saving: Boolean,
    onChooseAnother: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = onChooseAnother, enabled = !saving) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_image),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.avatar_choose_another))
        }
    }
}

private fun formatBytes(bytes: Long?): String =
    when {
        bytes == null -> "—"
        bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / (1024f * 1024f))
        else -> "%.0f KiB".format(bytes / 1024f)
    }
