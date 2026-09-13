package com.zenstream.zenstreammobile.audio

import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.flac.FlacLibrary
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.zenstream.zenstreammobile.BuildConfig
import java.io.IOException
import java.util.Locale

/** Debug-only playback evidence that never includes bearer credentials or negotiated URLs. */
@UnstableApi
internal class AudioPlaybackDiagnostics(private val sourceProvider: () -> NormalizedAudioSource?) :
    AnalyticsListener {
    fun rendererConfigurationCreated() {
        if (!enabled) return
        val flacAvailable = runCatching { FlacLibrary.isAvailable() }.getOrDefault(false)
        debug("renderer configuration extension=libflac mode=prefer " + "available=$flacAvailable")
    }

    fun sourceSelected(source: NormalizedAudioSource) {
        debug(
            "source selected kind=${source.kind.name.lowercase(Locale.ROOT)} " +
                "mime=${source.mimeType ?: "auto"} mode=${source.mode ?: "none"} " +
                "session=${if (source.sessionId == null) "none" else "present"} " +
                "duration=${source.durationSeconds?.takeIf { it.isFinite() } ?: "unknown"} " +
                "expiry=${if (source.expiresAt == null) "none" else "present"}"
        )
    }

    fun playerError(error: PlaybackException) {
        debug(
            "player error ${sourceSummary()} code=${error.errorCodeName} " +
                "cause=${error.cause?.javaClass?.simpleName ?: "none"}"
        )
    }

    fun recoveryScheduled(positionMs: Long) {
        debug("bounded recovery scheduled resumePositionMs=${positionMs.coerceAtLeast(0L)}")
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        debug("playback state=${stateName(state)} ${sourceSummary()}")
    }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
        debug("isPlaying=$isPlaying ${sourceSummary()}")
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        debug(
            "audio decoder initialized name=$decoderName initMs=$initializationDurationMs " +
                "${sourceSummary()}"
        )
    }

    override fun onAudioDecoderReleased(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
    ) {
        debug("audio decoder released name=$decoderName ${sourceSummary()}")
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        debug(
            "audio format mime=${format.sampleMimeType ?: "unknown"} " +
                "sampleRate=${format.sampleRate} channels=${format.channelCount} " +
                "maxInputSize=${format.maxInputSize} ${sourceSummary()}"
        )
    }

    override fun onAudioCodecError(
        eventTime: AnalyticsListener.EventTime,
        audioCodecError: Exception,
    ) {
        debug("audio codec error type=${audioCodecError.javaClass.simpleName} ${sourceSummary()}")
    }

    override fun onAudioSinkError(
        eventTime: AnalyticsListener.EventTime,
        audioSinkError: Exception,
    ) {
        debug("audio sink error type=${audioSinkError.javaClass.simpleName} ${sourceSummary()}")
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        debug(
            "audio underrun bufferBytes=$bufferSize bufferMs=$bufferSizeMs " +
                "elapsedSinceFeedMs=$elapsedSinceLastFeedMs ${sourceSummary()}"
        )
    }

    override fun onAudioSessionIdChanged(
        eventTime: AnalyticsListener.EventTime,
        audioSessionId: Int,
    ) {
        debug("audio session id=$audioSessionId ${sourceSummary()}")
    }

    override fun onAudioAttributesChanged(
        eventTime: AnalyticsListener.EventTime,
        audioAttributes: AudioAttributes,
    ) {
        debug(
            "audio attributes usage=${audioAttributes.usage} " +
                "contentType=${audioAttributes.contentType}"
        )
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        debug(
            "AudioTrack initialized encoding=${audioTrackConfig.encoding} " +
                "sampleRate=${audioTrackConfig.sampleRate} " +
                "channels=${audioTrackConfig.channelConfig} " +
                "bufferBytes=${audioTrackConfig.bufferSize} " +
                "offload=${audioTrackConfig.offload} ${sourceSummary()}"
        )
    }

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        debug(
            "AudioTrack released encoding=${audioTrackConfig.encoding} " +
                "sampleRate=${audioTrackConfig.sampleRate} " +
                "bufferBytes=${audioTrackConfig.bufferSize} ${sourceSummary()}"
        )
    }

    override fun onVolumeChanged(eventTime: AnalyticsListener.EventTime, volume: Float) {
        debug("player volume=$volume (unity gain; phone controls output)")
    }

    override fun onDeviceVolumeChanged(
        eventTime: AnalyticsListener.EventTime,
        volume: Int,
        muted: Boolean,
    ) {
        debug("device media volume=$volume muted=$muted")
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        debug("load completed ${loadDetails(loadEventInfo)} ${sourceSummary()}")
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        debug(
            "load error type=${error.javaClass.simpleName} canceled=$wasCanceled " +
                "${loadDetails(loadEventInfo)} ${sourceSummary()}"
        )
    }

    private fun sourceSummary(): String {
        val source = sourceProvider() ?: return "source=none"
        return "source=${source.kind.name.lowercase(Locale.ROOT)} mime=${source.mimeType ?: "auto"} " +
            "mode=${source.mode ?: "none"}"
    }

    private fun loadDetails(loadEventInfo: LoadEventInfo): String {
        val headers = loadEventInfo.responseHeaders
        return "rangeStart=${loadEventInfo.dataSpec.position} " +
            "requestedBytes=${loadEventInfo.dataSpec.length} " +
            "loadedBytes=${loadEventInfo.bytesLoaded} " +
            "durationMs=${loadEventInfo.loadDurationMs} " +
            "contentRange=${safeHeader(headers, "Content-Range")} " +
            "contentLength=${safeHeader(headers, "Content-Length")} " +
            "acceptRanges=${safeHeader(headers, "Accept-Ranges")}"
    }

    private fun safeHeader(headers: Map<String, List<String>>, name: String): String =
        headers.entries
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.take(120) ?: "unknown"

    private fun debug(message: String) {
        if (enabled) Log.d(TAG, message)
    }

    private val enabled: Boolean
        get() = BuildConfig.DEBUG

    private companion object {
        const val TAG = "ZenStreamAudio"

        fun stateName(state: Int): String =
            when (state) {
                Player.STATE_IDLE -> "idle"
                Player.STATE_BUFFERING -> "buffering"
                Player.STATE_READY -> "ready"
                Player.STATE_ENDED -> "ended"
                else -> "unknown($state)"
            }
    }
}
