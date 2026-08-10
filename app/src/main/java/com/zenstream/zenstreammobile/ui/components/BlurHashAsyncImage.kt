package com.zenstream.zenstreammobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.vanniktech.blurhash.BlurHash

@Composable
fun BlurHashAsyncImage(
    model: Any?,
    imageKey: String?,
    blurHash: String?,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    onError: (() -> Unit)? = null,
) {
    var loaded by remember(imageKey) { mutableStateOf(false) }
    val placeholder = remember(blurHash) { decodeBlurHashBitmap(blurHash) }
    Box(modifier) {
        if (placeholder != null && !loaded) {
            Image(
                bitmap = placeholder,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { loaded = true },
            onError = { onError?.invoke() },
        )
    }
}

internal fun decodeBlurHashBitmap(value: String?): ImageBitmap? = value?.let {
    runCatching { BlurHash.decode(it, 24, 24, 1f, false)?.asImageBitmap() }.getOrNull()
}
