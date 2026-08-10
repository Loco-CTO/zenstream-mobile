package com.zenstream.zenstreammobile.data

sealed interface SyncplayNotification {
    data object GroupCreated : SyncplayNotification

    data class JoinedGroup(val name: String) : SyncplayNotification

    data class LeftGroup(val name: String) : SyncplayNotification

    data class MemberJoined(val name: String) : SyncplayNotification

    data class MemberLeft(val name: String) : SyncplayNotification

    data class GroupEnded(val name: String) : SyncplayNotification

    data class NowPlaying(val itemId: String) : SyncplayNotification

    data object ViewerControlsEnabled : SyncplayNotification

    data object ViewerControlsDisabled : SyncplayNotification

    data object HostDisconnected : SyncplayNotification

    data object ParticipantReplaced : SyncplayNotification

    data class Failure(val operation: SyncplayFailure) : SyncplayNotification
}

enum class SyncplayFailure {
    CREATE,
    CREATE_ALREADY_IN_GROUP,
    JOIN,
    JOIN_MUST_LEAVE_GROUP,
    LEAVE,
    SETTINGS,
    PLAYBACK,
    PRESENCE,
}
