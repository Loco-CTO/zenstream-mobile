package com.zenstream.zenstreammobile.model

enum class MpvVideoOutput(val storageValue: String) {
    GPU("gpu"),
    GPU_NEXT("gpu-next");

    companion object {
        fun fromStorageValue(value: String?): MpvVideoOutput =
            entries.firstOrNull { it.storageValue == value } ?: GPU
    }
}

enum class MpvVideoProfile(val storageValue: String) {
    FAST("fast"),
    GPU_HQ("gpu-hq");

    companion object {
        fun fromStorageValue(value: String?): MpvVideoProfile =
            entries.firstOrNull { it.storageValue == value } ?: FAST
    }
}

enum class MpvVideoScaler(val storageValue: String) {
    BILINEAR("bilinear"),
    LANCZOS("lanczos");

    companion object {
        fun fromStorageValue(value: String?): MpvVideoScaler =
            entries.firstOrNull { it.storageValue == value } ?: BILINEAR
    }
}

data class MpvPlaybackSettings(
    val videoOutput: MpvVideoOutput = MpvVideoOutput.GPU,
    val profile: MpvVideoProfile = MpvVideoProfile.FAST,
    val scaler: MpvVideoScaler = MpvVideoScaler.BILINEAR,
)
