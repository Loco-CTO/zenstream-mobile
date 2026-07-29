package com.zenstream.zenstreammobile.model

data class SyncplayMember(
    val userId: String,
    val participantId: String,
    val username: String,
    val watchingTogether: Boolean,
    val viewing: Boolean,
    val loading: Boolean,
    val readyGeneration: Int,
    val role: String,
)

data class SyncplayGroup(
    val id: String,
    val name: String,
    val hostUserId: String,
    val hostName: String,
    val allowViewerControls: Boolean,
    val itemId: String?,
    val position: Double,
    val playing: Boolean,
    val resumeWhenReady: Boolean,
    val revision: Int,
    val timelineRevision: Int,
    val mediaGeneration: Int,
    val anchorPosition: Double,
    val anchorServerTime: Double,
    val effectiveAt: Double,
    val playbackState: String,
    val pauseReason: String?,
    val hostDisconnectedAt: Double?,
    val updatedAt: Double,
    val members: List<SyncplayMember>,
)

data class SyncplayUiState(
    val groups: List<SyncplayGroup> = emptyList(),
    val active: SyncplayGroup? = null,
    val participantId: String = "",
    val connected: Boolean = false,
    val error: String? = null,
) {
    fun currentMember(): SyncplayMember? = active?.members?.firstOrNull {
        it.participantId == participantId
    }

    fun canControl(userId: String): Boolean = active?.let {
        it.hostUserId == userId || it.allowViewerControls
    } == true
}
