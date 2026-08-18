package com.zenstream.zenstreammobile.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

internal const val AVATAR_MAX_BYTES = 20L * 1024L * 1024L

/** Pixel-space crop coordinates measured after the requested quarter-turn rotation. */
data class AvatarCrop(
    val cropX: Int,
    val cropY: Int,
    val cropSize: Int,
    val rotation: Int,
) {
    init {
        require(cropX >= 0) { "cropX must be non-negative" }
        require(cropY >= 0) { "cropY must be non-negative" }
        require(cropSize > 0) { "cropSize must be positive" }
        require(rotation in setOf(0, 90, 180, 270)) { "rotation must be a quarter turn" }
    }
}

data class AvatarSourceInfo(
    val mimeType: String,
    val sizeBytes: Long?,
)

class AvatarFileTooLargeException : IOException("Avatar file is larger than 20 MiB")

class AvatarUnsupportedFormatException : IOException("Avatar format is not supported")

internal fun ContentResolver.avatarSourceInfo(uri: Uri): AvatarSourceInfo {
    val mimeType =
        getType(uri)?.lowercase()?.substringBefore(';')?.trim()
            ?: mimeTypeFromUri(uri)
            ?: throw AvatarUnsupportedFormatException()
    if (mimeType !in AVATAR_MIME_TYPES) throw AvatarUnsupportedFormatException()
    val size =
        query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)).takeIf {
                    it >= 0L
                }
            } else null
        }
    if (size != null && size > AVATAR_MAX_BYTES) throw AvatarFileTooLargeException()
    return AvatarSourceInfo(mimeType, size)
}

private fun mimeTypeFromUri(uri: Uri): String? =
    uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()?.let {
        when (it) {
            "jpg",
            "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> null
        }
    }

internal val AVATAR_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

internal class AvatarUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mimeType: MediaType,
    private val declaredSize: Long?,
) : RequestBody() {
    override fun contentType(): MediaType = mimeType

    override fun contentLength(): Long = declaredSize ?: -1L

    override fun writeTo(sink: BufferedSink) {
        val stream =
            resolver.openInputStream(uri) ?: throw IOException("Avatar file cannot be read")
        stream.use { input -> copyBounded(input, sink) }
    }

    private fun copyBounded(input: InputStream, sink: BufferedSink) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > AVATAR_MAX_BYTES) throw AvatarFileTooLargeException()
            sink.write(buffer, 0, count)
        }
    }
}
