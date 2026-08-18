package com.zenstream.zenstreammobile.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.AVATAR_MAX_BYTES
import com.zenstream.zenstreammobile.data.AvatarImageDimensions
import com.zenstream.zenstreammobile.data.AvatarPan
import com.zenstream.zenstreammobile.data.CatalogException
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.avatarCropForEditor
import com.zenstream.zenstreammobile.data.avatarSourceInfo
import com.zenstream.zenstreammobile.data.clampAvatarPan
import com.zenstream.zenstreammobile.data.clampAvatarZoom
import com.zenstream.zenstreammobile.model.AuthSession
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
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var sourceInfo by remember {
        mutableStateOf<com.zenstream.zenstreammobile.data.AvatarSourceInfo?>(null)
    }
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
    val removeFailed = stringResource(R.string.avatar_remove_failed)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
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
                sourceInfo = null
                sourceDimensions = null
                return@rememberLauncherForActivityResult
            }
            selectedUri = uri
            sourceInfo = info
            sourceDimensions = null
            viewport = null
            zoom = 1f
            pan = AvatarPan()
            rotation = 0
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
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.avatar_editor_description),
                        color = Color.White.copy(alpha = .72f),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    )
                    if (selectedUri == null) {
                        AvatarPickCard(
                            onPick = {
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        )
                    } else {
                        sourceDimensions?.let { dimensions ->
                            AvatarEditorPreview(
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
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(stringResource(R.string.avatar_zoom))
                                Slider(
                                    value = zoom,
                                    onValueChange = { next ->
                                        val size = viewport ?: return@Slider
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
                                    },
                                    valueRange = 1f..4f,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("%.1f×".format(zoom))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        rotation = (rotation + 270) % 360
                                        val size = viewport ?: return@OutlinedButton
                                        val cropViewport =
                                            com.zenstream.zenstreammobile.data.AvatarViewport(
                                                size.width,
                                                size.height,
                                            )
                                        pan =
                                            clampAvatarPan(
                                                dimensions,
                                                cropViewport,
                                                zoom,
                                                rotation,
                                                pan,
                                            )
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        painter =
                                            painterResource(LucideR.drawable.lucide_ic_refresh_cw),
                                        contentDescription =
                                            stringResource(
                                                R.string.avatar_rotate_counter_clockwise
                                            ),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.avatar_rotate_counter_clockwise))
                                }
                                OutlinedButton(
                                    onClick = {
                                        rotation = (rotation + 90) % 360
                                        val size = viewport ?: return@OutlinedButton
                                        val cropViewport =
                                            com.zenstream.zenstreammobile.data.AvatarViewport(
                                                size.width,
                                                size.height,
                                            )
                                        pan =
                                            clampAvatarPan(
                                                dimensions,
                                                cropViewport,
                                                zoom,
                                                rotation,
                                                pan,
                                            )
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        painter =
                                            painterResource(LucideR.drawable.lucide_ic_refresh_cw),
                                        contentDescription =
                                            stringResource(R.string.avatar_rotate_clockwise),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.avatar_rotate_clockwise))
                                }
                            }
                            sourceInfo?.let { info ->
                                Text(
                                    text =
                                        stringResource(
                                            R.string.avatar_file_details,
                                            info.mimeType,
                                            formatBytes(info.sizeBytes),
                                        ),
                                    color = Color.White.copy(alpha = .58f),
                                    style =
                                        androidx.compose.material3.MaterialTheme.typography
                                            .labelSmall,
                                )
                            }
                            editorError?.let { message ->
                                Text(message, color = Color(0xFFFF8A80))
                            }
                            EditorActions(
                                saving = saving,
                                hasExistingAvatar = session.avatarVersion != null,
                                onChooseAnother = {
                                    picker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                onCancel = onDismiss,
                                onRemove = {
                                    if (saving) return@EditorActions
                                    saving = true
                                    editorError = null
                                    scope.launch {
                                        runCatching { repository.removeAvatar(session) }
                                            .onSuccess {
                                                onSessionChanged(it)
                                                onDismiss()
                                            }
                                            .onFailure { editorError = removeFailed }
                                        saving = false
                                    }
                                },
                                onSave = {
                                    if (saving) return@EditorActions
                                    val size = viewport
                                    val crop =
                                        if (size != null) {
                                            avatarCropForEditor(
                                                dimensions,
                                                com.zenstream.zenstreammobile.data.AvatarViewport(
                                                    size.width,
                                                    size.height,
                                                ),
                                                zoom,
                                                pan,
                                                rotation,
                                            )
                                        } else null
                                    if (crop == null) {
                                        editorError = fileInvalid
                                        return@EditorActions
                                    }
                                    saving = true
                                    editorError = null
                                    scope.launch {
                                        runCatching {
                                                repository.uploadAvatar(
                                                    session,
                                                    resolver,
                                                    selectedUri!!,
                                                    crop,
                                                )
                                            }
                                            .onSuccess {
                                                onSessionChanged(it)
                                                onDismiss()
                                            }
                                            .onFailure { error ->
                                                editorError =
                                                    if (
                                                        error is CatalogException &&
                                                            error.statusCode == 413
                                                    ) {
                                                        fileTooLarge
                                                    } else uploadFailed
                                            }
                                        saving = false
                                    }
                                },
                            )
                        }
                            ?: if (sourceError == null) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                Text(sourceError.orEmpty(), color = Color(0xFFFF8A80))
                            }
                    }
                    sourceError
                        ?.takeIf { selectedUri == null }
                        ?.let {
                            Text(it, color = Color(0xFFFF8A80))
                        }
                    if (selectedUri == null) {
                        Text(
                            stringResource(
                                R.string.avatar_file_guidance,
                                formatBytes(AVATAR_MAX_BYTES),
                            ),
                            color = Color.White.copy(alpha = .58f),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun AvatarPickCard(onPick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().aspectRatio(1.2f).clickable(onClick = onPick),
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
    val viewportSize = viewport?.takeIf { it.width > 0 && it.height > 0 }
    val fitScale = viewportSize?.let {
        minOf(it.width / dimensions.width.toFloat(), it.height / dimensions.height.toFloat())
    }
    val layerScale =
        if (viewportSize != null && fitScale != null) {
            com.zenstream.zenstreammobile.data.avatarCoverScale(
                dimensions,
                com.zenstream.zenstreammobile.data.AvatarViewport(
                    viewportSize.width,
                    viewportSize.height,
                ),
                zoom,
                rotation,
            ) / fitScale
        } else 1f
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black)
                .onSizeChanged(onViewportChanged)
                .pointerInput(dimensions, viewport, rotation, zoom, pan) {
                    detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                        onTransform(
                            zoom * gestureZoom,
                            AvatarPan(pan.x + gesturePan.x, pan.y + gesturePan.y),
                        )
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(uri).build(),
            contentDescription = stringResource(R.string.avatar_preview),
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = layerScale
                    scaleY = layerScale
                    rotationZ = rotation.toFloat()
                    translationX = pan.x
                    translationY = pan.y
                },
        )
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                color = Color.White.copy(alpha = .86f),
                style = Stroke(width = 2.dp.toPx()),
            )
            val lineColor = Color.White.copy(alpha = .28f)
            drawLine(
                lineColor,
                Offset(size.width / 3f, 0f),
                Offset(size.width / 3f, size.height.toFloat()),
            )
            drawLine(
                lineColor,
                Offset(size.width * 2f / 3f, 0f),
                Offset(size.width * 2f / 3f, size.height.toFloat()),
            )
            drawLine(
                lineColor,
                Offset(0f, size.height / 3f),
                Offset(size.width.toFloat(), size.height / 3f),
            )
            drawLine(
                lineColor,
                Offset(0f, size.height * 2f / 3f),
                Offset(size.width.toFloat(), size.height * 2f / 3f),
            )
        }
    }
}

@Composable
private fun EditorActions(
    saving: Boolean,
    hasExistingAvatar: Boolean,
    onChooseAnother: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onChooseAnother,
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.avatar_choose_another))
            }
            OutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cancel))
            }
        }
        if (hasExistingAvatar) {
            OutlinedButton(
                onClick = onRemove,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.remove_avatar))
            }
        }
        Button(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.avatar_processing))
            } else {
                Text(stringResource(R.string.save))
            }
        }
    }
}

private fun formatBytes(bytes: Long?): String =
    when {
        bytes == null -> "—"
        bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / (1024f * 1024f))
        else -> "%.0f KiB".format(bytes / 1024f)
    }
