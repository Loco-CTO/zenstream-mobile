package com.zenstream.zenstreammobile.offline

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Share only referenced artwork with connected media controllers; media stays app-private. */
class OfflineArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun file(uri: Uri): File =
        runBlocking(Dispatchers.IO) {
            withTimeout(3000) {
                val context = requireNotNull(context)
                val runtime = OfflineRuntime.get(context)
                val parts = uri.pathSegments
                if (
                    uri.authority != context.packageName + ".offline-art" ||
                        parts.size != 2 ||
                        parts.any { !Regex("[a-f0-9]{64}").matches(it) }
                )
                    throw FileNotFoundException("Artwork is unavailable.")
                val profile = parts[0]
                val policy = runtime.connection.state.first { it.initialized }
                if (
                    policy.profile != profile ||
                        runtime.database.offline().profile(profile)?.locked != false
                )
                    throw FileNotFoundException("Artwork profile is unavailable.")
                val path = runtime.storage.paths.relative(profile, parts[1], "art")
                if (!runtime.database.offline().hasArtworkFile(profile, path))
                    throw FileNotFoundException("Artwork is unavailable.")
                runtime.storage.paths.resolve(path).takeIf { it.isFile }
                    ?: throw FileNotFoundException("Artwork is unavailable.")
            }
        }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Artwork is read-only.")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/*"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> "artwork"
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                }
            )
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        fun uri(context: Context, profile: String, digest: String): Uri {
            require(Regex("[a-f0-9]{64}").matches(profile) && Regex("[a-f0-9]{64}").matches(digest))
            return Uri.Builder()
                .scheme("content")
                .authority(context.packageName + ".offline-art")
                .appendPath(profile)
                .appendPath(digest)
                .build()
        }
    }
}
