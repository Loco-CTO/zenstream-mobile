package com.zenstream.zenstreammobile.offline

import android.os.StatFs
import androidx.room.withTransaction
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val OFFLINE_FREE_RESERVE = 1073741824L

internal fun fitsOfflineBudget(
    free: Long,
    reserved: Long,
    requested: Long,
    used: Long,
    limit: Long?,
): Boolean {
    if (free < 0 || reserved < 0 || requested < 0 || used < 0) return false
    val available = (free - OFFLINE_FREE_RESERVE).coerceAtLeast(0)
    if (reserved > available || requested > available - reserved) return false
    if (limit != null) {
        if (used > limit || reserved > limit - used || requested > limit - used - reserved)
            return false
    }
    return true
}

class OfflinePaths(val root: File) {
    private val identity = Regex("[a-f0-9]{64}")

    fun relative(profile: String, asset: String, extension: String = "media"): String {
        require(identity.matches(profile) && identity.matches(asset))
        require(Regex("[a-z0-9.-]+").matches(extension) && !extension.contains(".."))
        return "$profile/${asset.take(2)}/$asset.$extension"
    }

    fun resolve(relative: String): File {
        val parts = relative.split("/")
        require(
            parts.size == 3 &&
                identity.matches(parts[0]) &&
                Regex("[a-f0-9]{2}").matches(parts[1]) &&
                Regex("[a-f0-9]{64}\\.[a-z0-9.-]+").matches(parts[2]) &&
                parts[2].startsWith(parts[1]) &&
                !parts[2].contains("..")
        )
        val result = File(root, relative).canonicalFile
        require(result.path.startsWith(root.canonicalPath + File.separator))
        return result
    }
}

internal suspend fun sha256(file: File): String =
    withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(1024 * 1024)
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

class OfflineFileLease internal constructor(val file: File, private val release: () -> Unit) :
    Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

class OfflineStorage(
    private val database: OfflineDatabase,
    root: File,
    private val scope: CoroutineScope,
    private val freeBytes: () -> Long = { StatFs(root.absolutePath).availableBytes },
    private val limitBytes: () -> Long? = { null },
) {
    val paths = OfflinePaths(root)
    private val dao = database.offline()
    private val mutex = Mutex()
    private val leases = ConcurrentHashMap<String, Int>()

    internal fun leaseCount(profile: String, assetId: String): Int =
        leases["$profile:$assetId"] ?: 0

    init {
        check(root.isDirectory || root.mkdirs()) { "Download storage is unavailable." }
    }

    suspend fun reserve(profile: String, transferId: String, total: Long): OfflineTransfer =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.withTransaction {
                    val current = requireNotNull(dao.transfer(profile, transferId))
                    require(total >= current.bytes && total >= 0)
                    val remaining = total - current.bytes
                    val others = dao.reservedExcept(profile, transferId)
                    val used = dao.totalAssetBytes() + dao.totalPartialBytes()
                    if (!fitsOfflineBudget(freeBytes(), others, remaining, used, limitBytes())) {
                        throw IOException(
                            "Not enough download storage. Keep at least 1 GiB free or raise your download limit."
                        )
                    }
                    current.copy(total = total, reserved = remaining).also { dao.putTransfer(it) }
                }
            }
        }

    fun staging(profile: String, asset: String): File =
        paths.resolve(paths.relative(profile, asset, "media.part"))

    suspend fun publish(asset: OfflineAsset, transferId: String): OfflineAsset =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val transfer = requireNotNull(dao.transfer(asset.profile, transferId))
                check(transfer.state !in setOf("cancelled", "paused")) {
                    "Download publication was cancelled."
                }
                val profile = requireNotNull(dao.profile(asset.profile))
                check(!profile.locked) { "Offline profile is locked." }
                val relative = paths.relative(asset.profile, asset.id)
                val target = paths.resolve(relative)
                val temporary = staging(asset.profile, asset.id)
                val existing = dao.asset(asset.profile, asset.id)
                if (
                    existing?.state == "complete" &&
                        existing.sha256 == asset.sha256 &&
                        target.isFile &&
                        target.length() == asset.size
                ) {
                    temporary.delete()
                    complete(existing, transfer)
                    return@withLock existing
                }
                check(
                    temporary.isFile &&
                        temporary.length() == asset.size &&
                        sha256(temporary) == asset.sha256
                ) {
                    "Downloaded media failed its integrity check."
                }
                FileOutputStream(temporary, true).use { it.fd.sync() }
                check(!target.exists() || (leases["${asset.profile}:${asset.id}"] ?: 0) == 0) {
                    "Stop playback before replacing this download."
                }
                val staged = asset.copy(relativePath = relative, state = "staged")
                dao.putAsset(staged)
                target.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
                check(!target.exists() || target.delete()) { "Cannot replace incomplete download." }
                check(temporary.renameTo(target)) { "Cannot finalize downloaded media." }
                val completed =
                    staged.copy(
                        state = "complete",
                        verifiedAt = System.currentTimeMillis(),
                        descriptor =
                            JSONObject(staged.descriptor)
                                .put("localModifiedMs", target.lastModified())
                                .toString(),
                    )
                complete(completed, transfer)
                completed
            }
        }

    private suspend fun complete(asset: OfflineAsset, transfer: OfflineTransfer) {
        database.withTransaction {
            val latest = requireNotNull(dao.transfer(transfer.profile, transfer.id))
            check(latest.state !in setOf("paused", "cancelled")) { "Download was cancelled." }
            dao.putAsset(asset)
            dao.putReference(OfflineAssetReference(asset.profile, transfer.selection, asset.id))
            dao.putTransfer(
                latest.copy(
                    state = "complete",
                    assetId = asset.id,
                    descriptor = asset.descriptor,
                    bytes = asset.size,
                    total = asset.size,
                    reserved = 0,
                    error = null,
                )
            )
        }
    }

    suspend fun adoptCompleted(
        profile: String,
        id: String,
        transferId: String,
        digest: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val existing = dao.asset(profile, id) ?: return@withLock false
                require(existing.relativePath == paths.relative(profile, id))
                val target = paths.resolve(existing.relativePath)
                if (
                    existing.state !in setOf("complete", "pending_delete") ||
                        existing.locked ||
                        existing.sha256 != digest ||
                        !target.isFile ||
                        target.length() != existing.size
                )
                    return@withLock false
                check(dao.profile(profile)?.locked == false)
                complete(
                    existing.copy(
                        state = "complete",
                        descriptor =
                            JSONObject(existing.descriptor)
                                .put(
                                    "downloadOptions",
                                    JSONObject(
                                        requireNotNull(dao.transfer(profile, transferId)).options
                                    ),
                                )
                                .toString(),
                    ),
                    requireNotNull(dao.transfer(profile, transferId)),
                )
                true
            }
        }

    suspend fun acquire(profile: String, assetId: String): OfflineFileLease =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(dao.profile(profile)?.locked == false) {
                    "Sign in to unlock these downloads."
                }
                val asset = requireNotNull(dao.asset(profile, assetId))
                check(asset.state == "complete" && !asset.locked)
                require(asset.relativePath == paths.relative(profile, assetId))
                val target = paths.resolve(asset.relativePath)
                check(target.isFile && target.length() == asset.size) {
                    "Saved media is missing or damaged. Retry its download."
                }
                val key = "$profile:$assetId"
                leases.compute(key) { _, count -> (count ?: 0) + 1 }
                OfflineFileLease(target) {
                    leases.compute(key) { _, count -> ((count ?: 1) - 1).takeIf { it > 0 } }
                    scope.launch { prune(profile, assetId) }
                }
            }
        }

    /** Publication hashes the file once; changed or legacy stat stamps require revalidation. */
    suspend fun acquireVerified(profile: String, assetId: String): OfflineFileLease {
        val lease = acquire(profile, assetId)
        try {
            return withContext(Dispatchers.IO) {
                val asset = requireNotNull(dao.asset(profile, assetId))
                val descriptor = JSONObject(asset.descriptor)
                val modified = lease.file.lastModified()
                if (
                    !descriptor.has("localModifiedMs") ||
                        descriptor.getLong("localModifiedMs") != modified
                ) {
                    val valid = sha256(lease.file) == asset.sha256
                    database.withTransaction {
                        val latest = dao.asset(profile, assetId)
                        if (
                            latest != null &&
                                latest.state == "complete" &&
                                latest.sha256 == asset.sha256
                        ) {
                            dao.putAsset(
                                if (valid)
                                    latest.copy(
                                        descriptor =
                                            JSONObject(latest.descriptor)
                                                .put("localModifiedMs", modified)
                                                .toString(),
                                        verifiedAt = System.currentTimeMillis(),
                                    )
                                else latest.copy(state = "corrupt")
                            )
                        }
                    }
                    check(valid) { "Saved media failed its integrity check." }
                }
                check(dao.profile(profile)?.locked == false)
                lease
            }
        } catch (error: Exception) {
            lease.close()
            throw error
        }
    }

    suspend fun removeSelection(profile: String, selection: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                database.withTransaction {
                    dao.cancelSelectionTransfers(profile, selection)
                    dao.removeReferences(profile, selection)
                    dao.deleteMembers(profile, selection)
                    dao.selection(profile, selection)?.let {
                        dao.putSelection(it.copy(state = "removal_pending", itemCount = 0))
                    }
                }
            }
            var offset = 0
            while (true) {
                val assets = dao.assetPage(profile, 100, offset)
                assets.forEach { prune(profile, it.id) }
                if (assets.size < 100) break
                // Deletions shrink this page, so restart after each destructive pass.
                if (assets.all { dao.asset(profile, it.id) != null }) offset += assets.size
            }
        }

    private suspend fun prune(profile: String, assetId: String) = mutex.withLock {
        if (dao.references(profile, assetId) != 0) return@withLock
        val asset = dao.asset(profile, assetId) ?: return@withLock
        dao.putAsset(asset.copy(state = "pending_delete"))
        if ((leases["$profile:$assetId"] ?: 0) > 0) return@withLock
        paths.resolve(asset.relativePath).let { check(!it.exists() || it.delete()) }
        val supplements = dao.supplements(profile, assetId)
        database.withTransaction {
            dao.deleteSupplements(profile, assetId)
            dao.deleteAsset(profile, assetId)
        }
        supplements.forEach {
            val file = paths.resolve(it.relativePath)
            if (dao.fileReferences(profile, it.relativePath, file.name.substringBefore('.')) == 0)
                check(!file.exists() || file.delete())
        }
    }

    /** One bounded journal page. The caller persists the next offset for further work. */
    suspend fun repairPage(profile: String, offset: Int = 0): Int? =
        withContext(Dispatchers.IO) {
            val assets = dao.assetPage(profile, 100, offset)
            for (asset in assets) {
                if (asset.state == "pending_delete") {
                    prune(profile, asset.id)
                    continue
                }
                val target = paths.resolve(asset.relativePath)
                if (
                    asset.state == "staged" &&
                        target.isFile &&
                        target.length() == asset.size &&
                        sha256(target) == asset.sha256
                ) {
                    dao.putAsset(
                        asset.copy(state = "complete", verifiedAt = System.currentTimeMillis())
                    )
                } else if (
                    asset.state == "complete" && (!target.isFile || target.length() != asset.size)
                ) {
                    dao.putAsset(asset.copy(state = "corrupt"))
                }
            }
            if (assets.size == 100) offset + 100 else null
        }

    /** Hash buckets keep directory inspection small; no recursive whole-storage walk. */
    suspend fun sweepBucket(profile: String, bucket: Int, offset: Int = 0): Int? =
        withContext(Dispatchers.IO) {
            require(bucket in 0..255 && offset >= 0)
            val bucketName = bucket.toString(16).padStart(2, '0')
            val probe = paths.resolve(paths.relative(profile, bucketName.padEnd(64, '0')))
            val entries = probe.parentFile?.listFiles()?.sortedBy { it.name }.orEmpty()
            val page = entries.drop(offset).take(100)
            for (file in page) {
                if (!file.isFile || System.currentTimeMillis() - file.lastModified() < 86400000)
                    continue
                val relative = file.relativeTo(paths.root).invariantSeparatorsPath
                val id = file.name.substringBefore('.')
                if (
                    dao.fileReferences(profile, relative, id) == 0 &&
                        paths.resolve(relative) == file.canonicalFile
                )
                    file.delete()
            }
            if (offset + page.size < entries.size) offset + page.size else null
        }
}
