package com.zenstream.zenstreammobile.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.BidiFormatter
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.MainActivity
import com.zenstream.zenstreammobile.R
import kotlin.math.ceil

private const val EPISODE_TYPE = "episode"

internal object DownloadNotifications {
    fun build(
        context: Context,
        profile: String,
        progress: DownloadNotificationProgress = DownloadNotificationProgress(),
        api: Int = Build.VERSION.SDK_INT,
    ): Notification {
        createChannel(context)
        val open =
            PendingIntent.getActivity(
                context,
                DownloadExecution.NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("open_downloads", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        fun action(name: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, DownloadActionReceiver::class.java)
                    .setAction(name)
                    .setData(
                        Uri.Builder()
                            .scheme("zenstream")
                            .authority("downloads")
                            .appendPath(profile)
                            .appendPath(name)
                            .build()
                    )
                    .putExtra(DownloadExecution.PROFILE, profile),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val primaryTitle =
            progress.items.firstOrNull()?.let { title(context, it) }
                ?: context.getString(R.string.offline_downloads)
        val title =
            if (progress.active > 1)
                context.getString(
                    R.string.download_notification_more,
                    primaryTitle,
                    progress.active - 1,
                )
            else primaryTitle
        val details = contentText(context, progress)
        val builder =
            NotificationCompat.Builder(context, DownloadExecution.CHANNEL)
                .setSmallIcon(LucideR.drawable.lucide_ic_download)
                .setContentTitle(title)
                .setContentText(details)
                .setSubText(statusText(context, progress))
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setOngoing(true)
                .addAction(
                    LucideR.drawable.lucide_ic_pause,
                    context.getString(
                        if (progress.pending > 1) R.string.offline_pause_all
                        else R.string.offline_pause
                    ),
                    action("pause"),
                )
                .addAction(
                    LucideR.drawable.lucide_ic_x,
                    context.getString(
                        if (progress.pending > 1) R.string.download_notification_cancel_all
                        else R.string.cancel
                    ),
                    action("cancel"),
                )
        applyProgressStyle(builder, context, progress, api)
        // ProgressStyle does not require Live Update promotion. Routine library downloads do
        // not require continuous attention, so do not request promoted ongoing/status-bar chips.
        return builder.build()
    }

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                        DownloadExecution.CHANNEL,
                        context.getString(R.string.offline_downloads),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply {
                        description =
                            context.getString(R.string.download_notification_channel_description)
                        setShowBadge(false)
                    }
            )
        }
    }

    private fun statusText(context: Context, progress: DownloadNotificationProgress): String {
        if (progress.phase == DownloadNotificationPhase.DOWNLOADING) {
            progress.remainingSeconds?.let {
                return eta(context, it)
            }
        }
        return context.getString(
            when (progress.phase) {
                DownloadNotificationPhase.DOWNLOADING -> R.string.offline_downloading
                DownloadNotificationPhase.PREPARING -> R.string.offline_preparing
                DownloadNotificationPhase.VERIFYING -> R.string.offline_verifying
                DownloadNotificationPhase.RETRYING -> R.string.download_notification_retrying
                DownloadNotificationPhase.QUEUED -> R.string.offline_queued
                DownloadNotificationPhase.STARTING -> R.string.download_notification_starting
            }
        )
    }

    private fun contentText(context: Context, progress: DownloadNotificationProgress): String? {
        if (progress.phase != DownloadNotificationPhase.DOWNLOADING) return null
        return progress.total?.let {
            context.getString(
                R.string.download_notification_bytes,
                Formatter.formatShortFileSize(context, progress.bytes.coerceAtMost(it)),
                Formatter.formatShortFileSize(context, it),
            )
        } ?: Formatter.formatShortFileSize(context, progress.bytes)
    }

    private fun applyProgressStyle(
        builder: NotificationCompat.Builder,
        context: Context,
        progress: DownloadNotificationProgress,
        api: Int,
    ) {
        // Compat ProgressStyle falls back to a plain template below 36, not a progress bar.
        if (Build.VERSION.SDK_INT >= 36 && api >= 36) {
            builder.setStyle(
                NotificationCompat.ProgressStyle()
                    .addProgressSegment(NotificationCompat.ProgressStyle.Segment(100))
                    .setProgress(progress.percent ?: 0)
                    .setProgressIndeterminate(progress.percent == null)
                    .setProgressTrackerIcon(
                        IconCompat.createWithResource(context, R.drawable.ic_download_progress_dot)
                    )
            )
        } else {
            builder.setProgress(100, progress.percent ?: 0, progress.percent == null)
        }
    }

    private fun title(context: Context, item: DownloadNotificationItem): String {
        val name =
            item.name?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.offline_unknown_item)
        val parent =
            when {
                item.type == EPISODE_TYPE && item.parentType == "season" -> item.seriesName
                item.type in setOf(EPISODE_TYPE, "track") -> item.parentName
                else -> null
            }
        val episodeNumber = item.episode ?: 0
        val episodeEnd = item.episodeEnd ?: 0
        val episode =
            if (item.type == EPISODE_TYPE && episodeNumber > 0) {
                val number =
                    if (episodeEnd > episodeNumber) "$episodeNumber–$episodeEnd"
                    else episodeNumber.toString()
                context.getString(R.string.download_notification_episode, item.season ?: 0, number)
            } else null
        val bidi = BidiFormatter.getInstance()
        return listOfNotNull(name, parent?.takeIf { it.isNotBlank() }, episode).joinToString(
            " · "
        ) {
            bidi.unicodeWrap(it.replace('\n', ' ').replace('\r', ' ').take(160))
        }
    }

    private fun eta(context: Context, seconds: Long): String =
        when {
            seconds < 60 -> context.getString(R.string.download_notification_eta_short)
            seconds < 3600 ->
                ceil(seconds / 60.0).toInt().let {
                    context.resources.getQuantityString(
                        R.plurals.download_notification_eta_minutes,
                        it,
                        it,
                    )
                }
            seconds < 86_400 ->
                ceil(seconds / 3600.0).toInt().let {
                    context.resources.getQuantityString(
                        R.plurals.download_notification_eta_hours,
                        it,
                        it,
                    )
                }
            else -> context.getString(R.string.download_notification_eta_long)
        }
}
