package com.zenstream.zenstreammobile.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * Keeps the renderer choice in one place for the service player used by the app and Android Auto.
 * The bundled FLAC renderer is preferred over the device MediaCodec renderer when both support a
 * track, while all other formats keep Media3's normal renderer selection.
 */
@UnstableApi
internal fun preferredAudioRenderersFactory(context: Context): DefaultRenderersFactory =
    DefaultRenderersFactory(context).setExtensionRendererMode(audioExtensionRendererMode())

@UnstableApi
internal fun audioExtensionRendererMode(): Int =
    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
