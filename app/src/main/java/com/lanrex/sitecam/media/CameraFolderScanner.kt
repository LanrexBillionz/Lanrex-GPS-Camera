package com.lanrex.sitecam.media

import android.content.Context
import android.media.MediaMetadataRetriever
import com.lanrex.sitecam.data.StampRepository
import com.lanrex.sitecam.data.db.ItemOrigin
import com.lanrex.sitecam.location.HeadingRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds new photos/videos in DCIM/Camera and adds them to the stamping queue.
 * Used after Open Camera (on return to the app) and by Site Mode.
 */
class CameraFolderScanner(
    private val context: Context,
    private val mediaStore: MediaStoreRepository,
    private val repository: StampRepository,
    private val heading: HeadingRecorder,
) {
    data class Result(val added: Int, val notReady: Int)

    /** Queues camera items added at or after [sinceMillis]. Returns how many were new. */
    suspend fun scan(sinceMillis: Long, origin: ItemOrigin): Result = withContext(Dispatchers.IO) {
        val entries = mediaStore.cameraItemsSince(sinceMillis / 1000 - 2)
        var added = 0
        var notReady = 0
        for (entry in entries) {
            // RAW (DNG) files from Pro mode are ignored; their JPEG twin gets stamped.
            if (!entry.isSupported) continue
            // Already stamped, queued, skipped or failed: automatic scans never redo it.
            if (repository.isKnown(entry.sourceKey)) continue
            if (!isFullyWritten(entry)) {
                notReady++
                continue
            }
            val result = repository.add(entry, origin, startWork = false)
            if (result == StampRepository.AddResult.ADDED) {
                added++
                // Remember which way the camera faced when this was taken (if we were recording it).
                val taken = entry.dateTakenMillis ?: (entry.dateAddedSeconds * 1000)
                heading.headingAt(taken)?.let { repository.setHeading(entry.sourceKey, it) }
            }
        }
        Result(added, notReady)
    }

    /**
     * Only finished files are stamped: not pending, not empty, unchanged for a
     * couple of seconds, and (for videos) readable to the end.
     */
    private fun isFullyWritten(entry: MediaEntry): Boolean {
        if (entry.isPending || entry.sizeBytes <= 0) return false
        val ageSeconds = System.currentTimeMillis() / 1000 - maxOf(entry.dateModifiedSeconds, entry.dateAddedSeconds)
        if (ageSeconds < 2) return false
        if (!entry.isVideo) return true
        val retriever = MediaMetadataRetriever()
        return try {
            context.contentResolver.openFileDescriptor(entry.uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0L
            } ?: false
        } catch (e: Exception) {
            false
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
