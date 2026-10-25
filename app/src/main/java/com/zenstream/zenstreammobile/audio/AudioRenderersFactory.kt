package com.zenstream.zenstreammobile.audio

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.zenstream.zenstreammobile.data.Media3NativeDecoders
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer

/**
 * Keeps the renderer choice in one place for the service player used by the app and Android Auto.
 * The bundled FLAC and selected FFmpeg renderers are preferred when they support a track; other
 * formats keep Media3's normal renderer selection.
 */
@UnstableApi
internal fun preferredAudioRenderersFactory(context: Context): DefaultRenderersFactory =
    object : DefaultRenderersFactory(context) {
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) {
                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out,
                )
                // Keep libFLAC first; other native audio formats get NextLib before platform
                // decoding.
                if (Media3NativeDecoders.available) {
                    val platformIndex = out.indexOfFirst {
                        it is androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
                    }
                    out.add(
                        if (platformIndex >= 0) platformIndex else out.size,
                        FfmpegAudioRenderer(eventHandler, eventListener, audioSink),
                    )
                }
            }
        }
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(audioExtensionRendererMode())

@UnstableApi
internal fun audioExtensionRendererMode(): Int =
    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
