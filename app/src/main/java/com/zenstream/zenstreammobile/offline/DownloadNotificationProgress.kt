package com.zenstream.zenstreammobile.offline

import kotlin.math.ceil

/** Small SQL projections: never load the catalog, descriptors or the full transfer history. */
data class DownloadNotificationQueue(val pending: Int, val active: Int)

data class DownloadNotificationItem(
    val id: String,
    val state: String,
    val bytes: Long,
    val total: Long,
    val nextAttemptMs: Long,
    val name: String?,
    val type: String?,
    val parentName: String?,
    val parentType: String?,
    val seriesName: String?,
    val season: Int?,
    val episode: Int?,
    val episodeEnd: Int?,
)

internal enum class DownloadNotificationPhase {
    STARTING,
    DOWNLOADING,
    PREPARING,
    VERIFYING,
    RETRYING,
    QUEUED,
}

internal data class DownloadNotificationProgress(
    val items: List<DownloadNotificationItem> = emptyList(),
    val pending: Int = 0,
    val active: Int = 0,
    val phase: DownloadNotificationPhase = DownloadNotificationPhase.STARTING,
    val bytes: Long = 0,
    val total: Long? = null,
    val percent: Int? = null,
    val bytesPerSecond: Long? = null,
    val remainingSeconds: Long? = null,
)

/** Uses monotonic time and individual baselines, so resume offsets/new items aren't throughput. */
internal class DownloadNotificationEstimator {
    private data class Sample(val elapsedMs: Long, val bytes: Long)

    private data class Window(
        val total: Long,
        val samples: ArrayDeque<Sample> = ArrayDeque(),
        var lastMovementMs: Long = 0,
    )

    private val windows = mutableMapOf<String, Window>()

    fun update(
        queue: DownloadNotificationQueue,
        items: List<DownloadNotificationItem>,
        elapsedMs: Long,
        wallTimeMs: Long,
    ): DownloadNotificationProgress {
        val downloading = items.filter {
            it.state == "transferring" && it.nextAttemptMs <= wallTimeMs
        }
        windows.keys.retainAll(downloading.map { it.id }.toSet())
        val rates = downloading.map { item ->
            var window = windows[item.id]
            if (
                window == null ||
                    window.total != item.total ||
                    item.bytes < window.samples.last().bytes ||
                    elapsedMs - window.samples.last().elapsedMs > 10_000 ||
                    elapsedMs <= window.samples.last().elapsedMs
            ) {
                window = Window(item.total, lastMovementMs = elapsedMs)
                windows[item.id] = window
            }
            if (window.samples.lastOrNull()?.bytes != item.bytes) window.lastMovementMs = elapsedMs
            window.samples.addLast(Sample(elapsedMs, item.bytes.coerceAtLeast(0)))
            while (
                window.samples.size > 2 && elapsedMs - window.samples.first().elapsedMs > 10_000
            ) window.samples.removeFirst()
            val first = window.samples.first()
            val duration = elapsedMs - first.elapsedMs
            if (duration < 3_000 || elapsedMs - window.lastMovementMs >= 5_000) null
            else
                ((item.bytes - first.bytes).coerceAtLeast(0).toDouble() * 1000 / duration)
                    .toLong()
                    .takeIf { it > 0 }
        }
        val phase =
            when {
                downloading.isNotEmpty() -> DownloadNotificationPhase.DOWNLOADING
                items.any { it.state in setOf("verifying", "publishing") } ->
                    DownloadNotificationPhase.VERIFYING
                items.any {
                    it.state in setOf("resolving", "preparing") && it.nextAttemptMs <= wallTimeMs
                } -> DownloadNotificationPhase.PREPARING
                items.isNotEmpty() && items.all { it.nextAttemptMs > wallTimeMs } ->
                    DownloadNotificationPhase.RETRYING
                queue.pending > 0 -> DownloadNotificationPhase.QUEUED
                else -> DownloadNotificationPhase.STARTING
            }
        // This is the current transfer set, never a percentage polluted by historical downloads.
        val bytes =
            downloading
                .sumOf { it.bytes.coerceAtLeast(0).toDouble() }
                .coerceAtMost(Long.MAX_VALUE.toDouble())
                .toLong()
        val total =
            downloading
                .takeIf { it.isNotEmpty() && it.all { row -> row.total > 0 } }
                ?.sumOf { it.total.toDouble() }
                ?.coerceAtMost(Long.MAX_VALUE.toDouble())
                ?.toLong()
        val rate =
            rates
                .takeIf { it.isNotEmpty() && it.all { rate -> rate != null } }
                ?.sumOf { requireNotNull(it).toDouble() }
                ?.coerceAtMost(Long.MAX_VALUE.toDouble())
                ?.toLong()
        val remaining =
            if (total != null && rate != null && total > bytes)
                ceil((total - bytes).toDouble() / rate).toLong().coerceAtLeast(1)
            else null
        return DownloadNotificationProgress(
            items,
            queue.pending,
            queue.active,
            phase,
            bytes,
            total,
            total?.let { (bytes.toDouble() * 100 / it).toInt().coerceIn(0, 100) },
            rate,
            remaining,
        )
    }
}
