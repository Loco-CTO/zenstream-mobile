package com.zenstream.zenstreammobile.ui.player

import androidx.media3.common.C

internal class LocalDecoderRecovery {
    private var source: String? = null
    var softwareAudio = false
        private set

    var softwareVideo = false
        private set

    fun source(value: String) {
        if (source == value) return
        source = value
        softwareAudio = false
        softwareVideo = false
    }

    fun attempt(trackType: Int?, hdr: Boolean): Boolean {
        return if (trackType == C.TRACK_TYPE_AUDIO) {
            if (softwareAudio) false
            else {
                softwareAudio = true
                true
            }
        } else {
            if (hdr || softwareVideo) false
            else {
                softwareVideo = true
                true
            }
        }
    }

    fun softwareFor(mime: String): Boolean =
        mime.startsWith("audio/") && softwareAudio || mime.startsWith("video/") && softwareVideo
}
