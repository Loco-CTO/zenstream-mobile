package com.zenstream.zenstreammobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.SyncplayFailure
import com.zenstream.zenstreammobile.data.SyncplayManager
import com.zenstream.zenstreammobile.data.SyncplayNotification
import com.zenstream.zenstreammobile.model.AuthSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val TOAST_DURATION_MILLIS = 5_000L

internal enum class ToastVariant {
    Success,
    Error,
}

internal data class ToastMessage(
    val id: Int,
    val message: String,
    val variant: ToastVariant,
)

@Stable
class ToastHostState
internal constructor(
    private val scope: CoroutineScope,
    private val durationMillis: Long,
) {
    private val messages = mutableStateListOf<ToastMessage>()
    private var nextId by mutableIntStateOf(0)

    internal val current: List<ToastMessage>
        get() = messages

    fun success(message: String) = show(message, ToastVariant.Success)

    fun error(message: String) = show(message, ToastVariant.Error)

    fun dismiss(id: Int) {
        messages.removeAll { it.id == id }
    }

    private fun show(message: String, variant: ToastVariant) {
        val toast = ToastMessage(nextId++, message, variant)
        messages += toast
        scope.launch {
            delay(durationMillis)
            dismiss(toast.id)
        }
    }
}

@Composable
fun rememberToastHostState(durationMillis: Long = TOAST_DURATION_MILLIS): ToastHostState {
    val scope = rememberCoroutineScope()
    return remember(scope, durationMillis) { ToastHostState(scope, durationMillis) }
}

@Composable
fun ToastHost(
    state: ToastHostState,
    modifier: Modifier = Modifier,
    playerContext: Boolean = false,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .widthIn(max = 384.dp)
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.current.forEach { toast ->
                ToastCard(
                    toast = toast,
                    onDismiss = { state.dismiss(toast.id) },
                    playerContext = playerContext,
                )
            }
        }
    }
}

@Composable
private fun ToastCard(
    toast: ToastMessage,
    onDismiss: () -> Unit,
    playerContext: Boolean,
) {
    val icon =
        if (toast.variant == ToastVariant.Success) {
            LucideR.drawable.lucide_ic_circle_check
        } else {
            LucideR.drawable.lucide_ic_circle_alert
        }
    val iconColor =
        if (toast.variant == ToastVariant.Success) Color(0xFFC4B5FD) else Color(0xFFFCA5A5)
    Surface(
        modifier =
            Modifier.fillMaxWidth().semantics {
                liveRegion =
                    if (toast.variant == ToastVariant.Error) {
                        LiveRegionMode.Assertive
                    } else {
                        LiveRegionMode.Polite
                    }
            },
        color = if (playerContext) Color(0xE6151519) else Color.Black.copy(alpha = .78f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 12.dp,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(icon),
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
            Text(
                text = toast.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(LucideR.drawable.lucide_ic_x),
                    contentDescription = stringResource(R.string.toast_dismiss),
                    tint = Color.White.copy(alpha = .65f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun SyncplayToastNotifications(
    manager: SyncplayManager,
    repository: CatalogRepository,
    session: AuthSession,
    toast: ToastHostState,
) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(manager, repository, session, toast) {
        val titleCache = mutableMapOf<String, String>()
        manager.notifications.collect { notification ->
            when (notification) {
                is SyncplayNotification.Failure ->
                    toast.error(notification.operation.message(context))
                is SyncplayNotification.NowPlaying ->
                    launch {
                        val title =
                            titleCache[notification.itemId]
                                ?: runCatching {
                                        repository.detail(session, notification.itemId).item.name
                                    }
                                    .getOrNull()
                                    ?.also { titleCache[notification.itemId] = it }
                        toast.success(
                            if (title != null) {
                                context.getString(R.string.syncplay_now_playing, title)
                            } else {
                                context.getString(R.string.syncplay_now_playing_fallback)
                            }
                        )
                    }

                else -> notification.message(context)?.let(toast::success)
            }
        }
    }
}

private fun SyncplayNotification.message(context: android.content.Context): String? =
    when (this) {
        SyncplayNotification.GroupCreated -> context.getString(R.string.syncplay_group_created)
        is SyncplayNotification.JoinedGroup ->
            context.getString(R.string.syncplay_joined_group, name)
        is SyncplayNotification.LeftGroup -> context.getString(R.string.syncplay_left_group, name)
        is SyncplayNotification.MemberJoined ->
            context.getString(R.string.syncplay_member_joined, name)
        is SyncplayNotification.MemberLeft -> context.getString(R.string.syncplay_member_left, name)
        is SyncplayNotification.GroupEnded -> context.getString(R.string.syncplay_group_ended, name)
        SyncplayNotification.ViewerControlsEnabled ->
            context.getString(R.string.syncplay_viewer_controls_enabled)
        SyncplayNotification.ViewerControlsDisabled ->
            context.getString(R.string.syncplay_viewer_controls_disabled)
        SyncplayNotification.HostDisconnected ->
            context.getString(R.string.syncplay_host_disconnected)
        SyncplayNotification.ParticipantReplaced ->
            context.getString(R.string.syncplay_participant_replaced)
        is SyncplayNotification.Failure -> null
        is SyncplayNotification.NowPlaying -> null
    }

private fun SyncplayFailure.message(context: android.content.Context): String =
    context.getString(
        when (this) {
            SyncplayFailure.CREATE -> R.string.syncplay_create_failed
            SyncplayFailure.CREATE_ALREADY_IN_GROUP -> R.string.syncplay_already_in_group
            SyncplayFailure.JOIN -> R.string.syncplay_join_failed
            SyncplayFailure.JOIN_MUST_LEAVE_GROUP -> R.string.syncplay_must_leave_group
            SyncplayFailure.LEAVE -> R.string.syncplay_leave_failed
            SyncplayFailure.SETTINGS -> R.string.syncplay_settings_failed
            SyncplayFailure.PLAYBACK -> R.string.syncplay_playback_failed
            SyncplayFailure.PRESENCE -> R.string.syncplay_presence_failed
        }
    )
