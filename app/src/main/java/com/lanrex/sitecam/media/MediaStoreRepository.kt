package com.lanrex.sitecam.media

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.lanrex.sitecam.ui.permissions.AppPermissions

/** One photo or video row from Android's media database. */
data class MediaEntry(
    val id: Long,
    val uri: Uri,
    val volume: String,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val dateTakenMillis: Long?,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
    val width: Int,
    val height: Int,
    val durationMillis: Long?,
    val relativePath: String?,
    val isPending: Boolean,
) {
    val sourceKey: String get() = "ms:$volume:$id"

    val isCameraFolder: Boolean get() = relativePath?.startsWith(CAMERA_PATH, ignoreCase = true) == true

    /** JPEG and HEIC photos and normal videos; RAW files are skipped. */
    val isSupported: Boolean get() = isVideo || MediaTypes.isSupportedPhoto(mimeType, displayName)

    companion object {
        const val CAMERA_PATH = "DCIM/Camera"
    }
}

enum class GalleryFilter { CAMERA, ALL }

object MediaTypes {
    private val photoMimes = setOf("image/jpeg", "image/jpg", "image/pjpeg", "image/heic", "image/heif")

    fun isSupportedPhoto(mime: String?, name: String?): Boolean {
        val m = mime?.lowercase()
        if (m != null && m in photoMimes) return true
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        return m.isNullOrEmpty() && ext in setOf("jpg", "jpeg", "heic", "heif")
    }

    fun isRaw(mime: String?, name: String?): Boolean {
        val m = mime?.lowercase().orEmpty()
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return "dng" in m || "raw" in m || ext in setOf("dng", "raw", "cr2", "nef", "arw", "srw")
    }

    fun isVideo(mime: String?): Boolean = mime?.startsWith("video/") == true
}

class MediaStoreRepository(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.MediaColumns.VOLUME_NAME,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.IS_PENDING,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
    )

    /** Single-item URIs (images/video tables) have no media_type column. */
    private val itemProjection = projection.filter { it != MediaStore.Files.FileColumns.MEDIA_TYPE }.toTypedArray()

    /** Photos and videos for the in-app gallery, newest first. */
    fun gallery(filter: GalleryFilter, limit: Int, excludeAlbum: String?): List<MediaEntry> {
        val selection = StringBuilder(
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})",
        )
        val args = ArrayList<String>()
        if (filter == GalleryFilter.CAMERA) {
            selection.append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
            args += "${MediaEntry.CAMERA_PATH}%"
        }
        val out = ArrayList<MediaEntry>()
        query(filesUri, selection.toString(), args.toTypedArray(), "${MediaStore.MediaColumns.DATE_ADDED} DESC") { c ->
            val e = read(c) ?: return@query true
            val ownOutput = excludeAlbum != null &&
                e.relativePath?.startsWith("Pictures/$excludeAlbum", ignoreCase = true) == true
            if (!ownOutput && !e.displayName.contains("_stamped", ignoreCase = true)) out += e
            out.size < limit
        }
        return out
    }

    /** Camera photos and videos added at or after [sinceSeconds] (fully written ones only). */
    fun cameraItemsSince(sinceSeconds: Long): List<MediaEntry> {
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?"
        val out = ArrayList<MediaEntry>()
        query(filesUri, selection, arrayOf("${MediaEntry.CAMERA_PATH}%", sinceSeconds.toString()), "${MediaStore.MediaColumns.DATE_ADDED} ASC") { c ->
            read(c)?.let { if (!it.isPending) out += it }
            true
        }
        return out
    }

    /** Newest photo in the camera folder. */
    fun latestCameraPhoto(): MediaEntry? {
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        var found: MediaEntry? = null
        query(filesUri, selection, arrayOf("${MediaEntry.CAMERA_PATH}%"), "${MediaStore.MediaColumns.DATE_ADDED} DESC") { c ->
            found = read(c)
            found == null
        }
        return found
    }

    /** Looks up a MediaStore content URI (works with a temporary share grant too). */
    fun get(uri: Uri): MediaEntry? {
        if (uri.authority != MediaStore.AUTHORITY) return null
        var found: MediaEntry? = null
        try {
            query(uri, null, null, null, itemProjection) { c ->
                found = read(c, fallbackUri = uri)
                false
            }
        } catch (e: Exception) {
            return null
        }
        return found
    }

    /** Finds the MediaStore item for a shared file by its name and size. */
    fun findByNameAndSize(displayName: String, sizeBytes: Long?): MediaEntry? {
        val selection = StringBuilder("${MediaStore.MediaColumns.DISPLAY_NAME}=?")
        val args = arrayListOf(displayName)
        if (sizeBytes != null && sizeBytes > 0) {
            selection.append(" AND ${MediaStore.MediaColumns.SIZE}=?")
            args += sizeBytes.toString()
        }
        var found: MediaEntry? = null
        query(filesUri, selection.toString(), args.toTypedArray(), "${MediaStore.MediaColumns.DATE_ADDED} DESC") { c ->
            found = read(c)
            found == null
        }
        return found
    }

    /**
     * The URI to read a file with its GPS intact. Android removes location data
     * unless the app holds ACCESS_MEDIA_LOCATION and asks for the "original".
     */
    fun originalUri(uri: Uri): Uri {
        if (uri.authority != MediaStore.AUTHORITY) return uri
        if (!AppPermissions.granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)) return uri
        return try {
            MediaStore.setRequireOriginal(uri)
        } catch (e: Exception) {
            uri
        }
    }

    fun exists(uri: Uri): Boolean = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
    } catch (e: Exception) {
        false
    }

    /** Runs a query; [onRow] returns false to stop early. */
    private fun query(
        uri: Uri,
        selection: String?,
        args: Array<String>?,
        sortOrder: String?,
        columns: Array<String> = projection,
        onRow: (Cursor) -> Boolean,
    ) {
        val bundle = Bundle().apply {
            if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            if (sortOrder != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
        }
        try {
            resolver.query(uri, columns, bundle, null)?.use { c ->
                while (c.moveToNext()) {
                    if (!onRow(c)) break
                }
            }
        } catch (e: SecurityException) {
            // No media permission yet: behave as if there is nothing.
        }
    }

    private fun read(c: Cursor, fallbackUri: Uri? = null): MediaEntry? {
        val id = c.long(MediaStore.Files.FileColumns._ID) ?: return null
        val volume = c.string(MediaStore.MediaColumns.VOLUME_NAME) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        val mime = c.string(MediaStore.MediaColumns.MIME_TYPE).orEmpty()
        val mediaType = c.int(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO || MediaTypes.isVideo(mime)
        val base = if (isVideo) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
        val uri = if (fallbackUri != null && mediaType == null) fallbackUri else ContentUris.withAppendedId(base, id)
        return MediaEntry(
            id = id,
            uri = uri,
            volume = volume,
            displayName = c.string(MediaStore.MediaColumns.DISPLAY_NAME) ?: "media_$id",
            mimeType = mime,
            isVideo = isVideo,
            sizeBytes = c.long(MediaStore.MediaColumns.SIZE) ?: 0L,
            dateTakenMillis = c.long(MediaStore.MediaColumns.DATE_TAKEN)?.takeIf { it > 0 },
            dateAddedSeconds = c.long(MediaStore.MediaColumns.DATE_ADDED) ?: 0L,
            dateModifiedSeconds = c.long(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L,
            width = c.int(MediaStore.MediaColumns.WIDTH) ?: 0,
            height = c.int(MediaStore.MediaColumns.HEIGHT) ?: 0,
            durationMillis = c.long(MediaStore.MediaColumns.DURATION)?.takeIf { it > 0 },
            relativePath = c.string(MediaStore.MediaColumns.RELATIVE_PATH),
            isPending = (c.int(MediaStore.MediaColumns.IS_PENDING) ?: 0) != 0,
        )
    }

    private fun Cursor.index(column: String): Int? = getColumnIndex(column).takeIf { it >= 0 }
    private fun Cursor.string(column: String): String? = index(column)?.let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(column: String): Long? = index(column)?.let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(column: String): Int? = index(column)?.let { if (isNull(it)) null else getInt(it) }
}
