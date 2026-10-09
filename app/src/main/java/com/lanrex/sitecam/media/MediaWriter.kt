package com.lanrex.sitecam.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Saves stamped copies into Pictures/<album> through MediaStore. */
class MediaWriter(private val context: Context) {

    data class Output(val uri: Uri, val displayName: String)

    /** "20261009_164900.heic" -> "20261009_164900_stamped.jpg" */
    fun stampedName(originalName: String, extension: String): String {
        val base = originalName.substringBeforeLast('.', originalName).ifBlank { "SiteCam" }
        return "${base}_stamped.$extension"
    }

    /**
     * Copies [file] into a new MediaStore entry. The entry stays hidden (pending)
     * until it is completely written.
     */
    fun saveNew(
        file: File,
        displayName: String,
        mimeType: String,
        album: String,
        isVideo: Boolean,
        dateTakenMillis: Long?,
    ): Output {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$album")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (dateTakenMillis != null) put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMillis)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("Android refused to create the stamped copy.")
        try {
            copyInto(uri, file, mode = "w")
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw e
        }
        return Output(uri, queryName(uri) ?: displayName)
    }

    /** Replaces the contents of a stamped copy SiteCam made earlier (used when re-stamping). */
    fun overwrite(uri: Uri, file: File) {
        copyInto(uri, file, mode = "wt")
    }

    fun delete(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    private fun copyInto(uri: Uri, file: File, mode: String) {
        val out = context.contentResolver.openOutputStream(uri, mode)
            ?: throw IOException("Could not open the stamped copy for writing.")
        out.use { stream -> file.inputStream().use { it.copyTo(stream, 1 shl 20) } }
    }

    private fun queryName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
