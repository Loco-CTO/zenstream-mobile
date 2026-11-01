package com.zenstream.zenstreammobile.offline

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Dns
import okhttp3.OkHttpClient

internal enum class TransferExecution {
    UIDT,
    FOREGROUND_SERVICE,
}

internal fun executionForApi(api: Int): TransferExecution =
    if (api >= 34) TransferExecution.UIDT else TransferExecution.FOREGROUND_SERVICE

internal fun transferRetryDelay(attempt: Int): Long =
    (1000L shl attempt.coerceIn(0, 6)).coerceAtMost(60000L)

class TransferNetwork(val network: Network) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransferNetwork>
}

internal fun transferClient(base: OkHttpClient, network: Network?): OkHttpClient =
    if (network == null) base
    else
        base
            .newBuilder()
            .socketFactory(network.socketFactory)
            .dns(Dns { hostname -> network.getAllByName(hostname).toList() })
            .build()

object DownloadExecution {
    const val JOB_ID = 1
    const val NOTIFICATION_ID = 2701
    const val CHANNEL = "offline-downloads"
    const val PROFILE = "offline-profile"
    const val RECONCILE = "offline-reconcile"
    const val RECONCILE_NOW = "offline-reconcile-now"
    const val RECONCILE_DEBOUNCED = "offline-reconcile-debounced"

    @RequiresApi(34)
    private fun scheduler(context: Context): JobScheduler =
        context.getSystemService(JobScheduler::class.java).forNamespace("zenstream.offline")

    /** Call from a visible, explicit user action. Failure leaves the durable queue intact. */
    suspend fun start(context: Context, profile: String): Boolean {
        val runtime = OfflineRuntime.get(context)
        val policy = runtime.connection.state.first { it.initialized }
        if (
            policy.mode == ConnectionMode.OFFLINE ||
                runtime.database.offline().profile(profile)?.locked != false
        )
            return false
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val network =
                    NetworkRequest.Builder()
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .apply {
                            if (!runtime.connection.allowMetered.value)
                                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        }
                        .build()
                val totals = runtime.database.offline().downloadTotals(profile)
                val job =
                    JobInfo.Builder(JOB_ID, ComponentName(context, DownloadJobService::class.java))
                        .setUserInitiated(true)
                        .setRequiredNetwork(network)
                        .setEstimatedNetworkBytes(
                            if (totals.totalBytes > 0)
                                (totals.totalBytes - totals.downloadedBytes).coerceAtLeast(0)
                            else JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
                            0,
                        )
                        .setExtras(PersistableBundle().apply { putString(PROFILE, profile) })
                        .build()
                scheduler(context).schedule(job) == JobScheduler.RESULT_SUCCESS
            } else {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DownloadTransferService::class.java).putExtra(PROFILE, profile),
                )
                true
            }
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun stop(context: Context) {
        if (Build.VERSION.SDK_INT >= 34) scheduler(context).cancel(JOB_ID)
        context.stopService(Intent(context, DownloadTransferService::class.java))
    }

    fun scheduleReconciliation(context: Context) {
        val work =
            PeriodicWorkRequestBuilder<OfflineReconcileWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(RECONCILE, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun scheduleReconciliationNow(context: Context) {
        val work =
            OneTimeWorkRequestBuilder<OfflineReconcileWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(RECONCILE_NOW, ExistingWorkPolicy.KEEP, work)
    }

    fun scheduleAccountSync(context: Context) {
        val work =
            OneTimeWorkRequestBuilder<OfflineReconcileWorker>()
                .setInitialDelay(5, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(RECONCILE_DEBOUNCED, ExistingWorkPolicy.KEEP, work)
    }

    fun cancelReconciliation(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(RECONCILE)
        WorkManager.getInstance(context).cancelUniqueWork(RECONCILE_NOW)
        WorkManager.getInstance(context).cancelUniqueWork(RECONCILE_DEBOUNCED)
    }

    internal fun notification(
        context: Context,
        profile: String,
        progress: DownloadNotificationProgress = DownloadNotificationProgress(),
    ): Notification = DownloadNotifications.build(context, profile, progress)

    /**
     * Independent from queue passes: a single large file must keep updating while runOnce blocks.
     */
    internal suspend fun runQueue(
        context: Context,
        profileId: String,
        progress: (Notification) -> Unit,
    ) = coroutineScope {
        val runtime = OfflineRuntime.get(context)
        val updates = launch {
            val locale = runtime.sessionStore.locale.first()
            val configuration =
                android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag(locale))
                }
            val localized = context.createConfigurationContext(configuration)
            monitorDownloadNotifications(
                read = { now ->
                    val session = runtime.sessionStore.session.first()
                    runtime.database.withTransaction {
                        val dao = runtime.database.offline()
                        val profile = dao.profile(profileId)
                        if (
                            profile == null ||
                                profile.locked ||
                                session == null ||
                                profile.account != session.userId ||
                                profile.serverUrl != session.serverUrl
                        )
                            null
                        else
                            dao.notificationQueue(profileId, now) to
                                dao.notificationItems(profileId, now)
                    }
                },
                enabled = { downloadNotificationsEnabled(context) },
                elapsedMillis = { android.os.SystemClock.elapsedRealtime() },
                wallMillis = { System.currentTimeMillis() },
                publish = { progress(notification(localized, profileId, it)) },
            )
        }
        try {
            executeQueue(context, profileId)
        } finally {
            withContext(NonCancellable) { updates.cancelAndJoin() }
        }
    }

    private suspend fun executeQueue(context: Context, profileId: String) {
        val runtime = OfflineRuntime.get(context)
        if (runtime.connection.state.first { it.initialized }.mode == ConnectionMode.OFFLINE) return
        val initialProfile = runtime.database.offline().profile(profileId) ?: return
        val initialSession = runtime.sessionStore.session.first() ?: return
        if (
            initialProfile.locked ||
                initialProfile.account != initialSession.userId ||
                initialProfile.serverUrl != initialSession.serverUrl
        )
            return
        var identityVerified = false
        var identityFailures = 0
        while (!identityVerified && currentCoroutineContext().isActive) {
            val policy = runtime.connection.state.value
            if (
                policy.mode == ConnectionMode.OFFLINE ||
                    policy.authentication == OnlineAuthentication.NEEDS_SIGN_IN
            )
                return
            val profile = runtime.database.offline().profile(profileId) ?: return
            val session = runtime.sessionStore.session.first() ?: return
            if (
                profile.locked ||
                    profile.account != session.userId ||
                    profile.serverUrl != session.serverUrl
            )
                return
            try {
                if (!runtime.matchesCurrentServerIdentity(profileId, session)) return
                identityVerified = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                delay(transferRetryDelay(++identityFailures).coerceAtMost(30_000L))
            }
        }
        if (!identityVerified) return
        var failures = 0
        while (currentCoroutineContext().isActive) {
            if (runtime.connection.state.value.mode == ConnectionMode.OFFLINE) return
            val profile = runtime.database.offline().profile(profileId) ?: return
            val session = runtime.sessionStore.session.first() ?: return
            if (
                profile.locked ||
                    profile.account != session.userId ||
                    profile.serverUrl != session.serverUrl
            )
                return
            val connectivity =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val assigned = currentCoroutineContext()[TransferNetwork]?.network
            val metered =
                if (assigned != null)
                    connectivity
                        .getNetworkCapabilities(assigned)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
                else connectivity.isActiveNetworkMetered
            if (!runtime.connection.allowMetered.value && metered) return
            val pending = runtime.database.offline().pendingWorkCount(profileId)
            if (pending == 0) return
            val dao = runtime.database.offline()
            val next =
                if (dao.pendingSelectionWorkCount(profileId) > 0) 0L
                else if (dao.preparingCount(profileId) >= 2) dao.nextPreparationAttempt(profileId)
                else dao.nextTransferAttempt(profileId)
            if (next > System.currentTimeMillis()) {
                delay((next - System.currentTimeMillis()).coerceIn(1000L, 30000L))
                continue
            }
            try {
                runtime.downloads.runOnce(session, profileId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (
                    error is RepresentationChanged ||
                        error is IllegalStateException ||
                        error is com.zenstream.zenstreammobile.data.CatalogException &&
                            error.statusCode in setOf(400, 401, 403, 404)
                )
                    return
                delay(transferRetryDelay(++failures))
                continue
            }
            failures = 0
            delay(2000)
        }
    }
}

@RequiresApi(34)
class DownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val profile = params.extras.getString(DownloadExecution.PROFILE) ?: return false
        setNotification(
            params,
            DownloadExecution.NOTIFICATION_ID,
            DownloadExecution.notification(this, profile),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        val previous = running
        previous?.cancel()
        running = scope.launch {
            previous?.join()
            val assigned = params.network
            try {
                if (assigned != null)
                    withContext(TransferNetwork(assigned)) {
                        DownloadExecution.runQueue(this@DownloadJobService, profile) { notification
                            ->
                            setNotification(
                                params,
                                DownloadExecution.NOTIFICATION_ID,
                                notification,
                                JOB_END_NOTIFICATION_POLICY_REMOVE,
                            )
                        }
                    }
                withContext(Dispatchers.Main) { jobFinished(params, false) }
            } catch (_: CancellationException) {
                /* onStopJob owns rescheduling. */
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        running = null
        return OfflineNetworkPolicy.allowed &&
            params.stopReason !in
                setOf(JobParameters.STOP_REASON_USER, JobParameters.STOP_REASON_CANCELLED_BY_APP)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class DownloadTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profile = intent?.getStringExtra(DownloadExecution.PROFILE) ?: return START_NOT_STICKY
        startForeground(
            DownloadExecution.NOTIFICATION_ID,
            DownloadExecution.notification(this, profile),
        )
        val previous = running
        previous?.cancel()
        running = scope.launch {
            previous?.join()
            try {
                DownloadExecution.runQueue(this@DownloadTransferService, profile) { notification ->
                    try {
                        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .notify(
                                DownloadExecution.NOTIFICATION_ID,
                                notification,
                            )
                    } catch (_: SecurityException) {
                        // Notification permission can be revoked while transferring.
                    }
                }
            } finally {
                // A cancelled older start must not remove the newer start's notification.
                if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val runtime = OfflineRuntime.get(context)
        runtime.scope.launch {
            try {
                val profile = intent.getStringExtra(DownloadExecution.PROFILE) ?: return@launch
                when (intent.action) {
                    "pause" ->
                        runtime.database.withTransaction {
                            runtime.database.offline().pauseAllTransfers(profile)
                            runtime.database.offline().pauseEnumeration(profile)
                        }
                    "cancel" ->
                        runtime.database.withTransaction {
                            runtime.database.offline().cancelAllTransfers(profile)
                            runtime.database.offline().cancelEnumeration(profile)
                        }
                }
                DownloadExecution.stop(context)
            } finally {
                pending.finish()
            }
        }
    }
}

class OfflineReconcileWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runtime = OfflineRuntime.get(applicationContext)
        val policy = runtime.connection.state.first { it.initialized }
        if (policy.mode == ConnectionMode.OFFLINE) return Result.success()
        return try {
            withTimeout(45000) { runtime.reconcileBounded() }
            Result.success()
        } catch (_: TimeoutCancellationException) {
            Result.retry()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class DownloadBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && OfflineRuntime.ENABLED)
            DownloadExecution.scheduleReconciliation(context)
        // Long transfers resume through an eligible job retry or an explicit user action.
    }
}
