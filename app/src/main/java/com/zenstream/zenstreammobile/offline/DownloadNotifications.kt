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
import java.text.NumberFormat
import kotlin.math.ceil

internal object DownloadNotifications {
    fun build(
        context: Context,
        profile: String,
        progress: DownloadNotificationProgress = DownloadNotificationProgress(),
        api: Int = Build.VERSION.SDK_INT,
    ): Notification {
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
        val titles = progress.items.map { title(context, it) }
        val title =
            titles.joinToString(" · ").ifBlank { context.getString(R.string.offline_downloads) }
        val phase =
            context.getString(
                when (progress.phase) {
                    DownloadNotificationPhase.DOWNLOADING -> R.string.offline_downloading
                    DownloadNotificationPhase.PREPARING -> R.string.offline_preparing
                    DownloadNotificationPhase.VERIFYING -> R.string.offline_verifying
                    DownloadNotificationPhase.RETRYING -> R.string.download_notification_retrying
                    DownloadNotificationPhase.QUEUED -> R.string.offline_queued
                    DownloadNotificationPhase.STARTING -> R.string.offline_transfer_running
                }
            )
        val percentage =
            progress.percent?.let {
                NumberFormat.getPercentInstance(context.resources.configuration.locales[0])
                    .format(it / 100.0)
            }
        val details =
            buildList {
                    add(phase)
                    if (progress.phase == DownloadNotificationPhase.DOWNLOADING) {
                        percentage?.let(::add)
                        add(
                            progress.total?.let {
                                context.getString(
                                    R.string.download_notification_bytes,
                                    Formatter.formatShortFileSize(
                                        context,
                                        progress.bytes.coerceAtMost(it),
                                    ),
                                    Formatter.formatShortFileSize(context, it),
                                )
                            } ?: Formatter.formatShortFileSize(context, progress.bytes)
                        )
                        progress.bytesPerSecond?.let {
                            add(
                                context.getString(
                                    R.string.download_notification_speed,
                                    Formatter.formatShortFileSize(context, it),
                                )
                            )
                        }
                        add(
                            progress.remainingSeconds?.let { eta(context, it) }
                                ?: context.getString(R.string.download_notification_estimating)
                        )
                    }
                }
                .joinToString(" · ")
        val queue =
            if (progress.pending == 0) context.getString(R.string.offline_downloads)
            else
                context.getString(
                    R.string.download_notification_queue,
                    context.resources.getQuantityString(
                        R.plurals.download_notification_active,
                        progress.active,
                        progress.active,
                    ),
                    (progress.pending - progress.active).coerceAtLeast(0).let {
                        context.resources.getQuantityString(
                            R.plurals.download_notification_queued,
                            it,
                            it,
                        )
                    },
                )
        val builder =
            NotificationCompat.Builder(context, DownloadExecution.CHANNEL)
                .setSmallIcon(LucideR.drawable.lucide_ic_download)
                .setContentTitle(title)
                .setContentText(details)
                .setSubText(queue)
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
                    context.getString(R.string.offline_pause_all),
                    action("pause"),
                )
                .addAction(
                    LucideR.drawable.lucide_ic_x,
                    context.getString(R.string.offline_cancel_remaining),
                    action("cancel"),
                )
        // Compat ProgressStyle falls back to a plain template below 36, not a progress bar.
        if (Build.VERSION.SDK_INT >= 36 && api >= 36) {
            builder.setStyle(
                NotificationCompat.ProgressStyle()
                    .addProgressSegment(NotificationCompat.ProgressStyle.Segment(100))
                    .setProgress(progress.percent ?: 0)
                    .setProgressIndeterminate(progress.percent == null)
                    .setProgressTrackerIcon(
                        IconCompat.createWithResource(context, LucideR.drawable.lucide_ic_download)
                    )
                    .setProgressEndIcon(
                        IconCompat.createWithResource(context, LucideR.drawable.lucide_ic_check)
                    )
            )
        } else {
            builder
                .setProgress(100, progress.percent ?: 0, progress.percent == null)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            buildList {
                                    if (titles.size > 1) addAll(titles)
                                    add(details)
                                }
                                .joinToString("\n")
                        )
                )
        }
        // ProgressStyle does not require Live Update promotion. Routine library downloads do
        // not require continuous attention, so do not request promoted ongoing/status-bar chips.
        return builder.build()
    }

    private fun title(context: Context, item: DownloadNotificationItem): String {
        val name =
            item.name?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.offline_unknown_item)
        val parent =
            when {
                item.type == "episode" && item.parentType == "season" -> item.seriesName
                item.type in setOf("episode", "track") -> item.parentName
                else -> null
            }
        val episode =
            if (item.type == "episode" && (item.episode ?: 0) > 0) {
                val number =
                    if ((item.episodeEnd ?: 0) > requireNotNull(item.episode))
                        "${item.episode}–${item.episodeEnd}"
                    else "${item.episode}"
                context.getString(R.string.download_notification_episode, item.season ?: 0, number)
            } else null
        val bidi = BidiFormatter.getInstance()
        return listOfNotNull(parent?.takeIf { it.isNotBlank() }, episode, name).joinToString(
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
