package com.lanrex.sitecam.data

import com.lanrex.sitecam.data.db.ItemOrigin
import com.lanrex.sitecam.data.db.ItemStatus
import com.lanrex.sitecam.data.db.LocationSource
import com.lanrex.sitecam.data.db.StampItem
import com.lanrex.sitecam.data.db.StampItemDao
import com.lanrex.sitecam.media.MediaEntry
import com.lanrex.sitecam.media.MediaTypes
import com.lanrex.sitecam.work.WorkScheduler
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Adds photos/videos to the stamping queue and applies the user's choices. */
class StampRepository(
    private val dao: StampItemDao,
    private val scheduler: WorkScheduler,
) {
    enum class AddResult { ADDED, ALREADY_STAMPED, ALREADY_QUEUED, RETRYING, UNSUPPORTED }

    val recentDone: Flow<List<StampItem>> = dao.recentDone(30)
    val active: Flow<List<StampItem>> = dao.active()
    val needsLocation: Flow<List<StampItem>> = dao.needsLocation()
    val problems: Flow<List<StampItem>> = dao.problems()
    val stampedMediaIds: Flow<List<Long>> = dao.stampedMediaIds()
    val pendingRestampCount: Flow<Int> = dao.pendingRestampCount()

    /** Queues a MediaStore photo/video. The same file is never stamped twice. */
    suspend fun add(entry: MediaEntry, origin: ItemOrigin, startWork: Boolean = true): AddResult {
        // Automatic scans never retry what failed or what the user chose to skip.
        val automatic = origin == ItemOrigin.CAMERA_SESSION || origin == ItemOrigin.SITE_MODE
        val result = addInternal(
            retryFailed = !automatic,
            sourceKey = entry.sourceKey,
            sourceUri = entry.uri.toString(),
            mediaStoreId = entry.id,
            localCopyPath = null,
            displayName = entry.displayName,
            mimeType = entry.mimeType,
            isVideo = entry.isVideo,
            sizeBytes = entry.sizeBytes,
            dateTakenMillis = entry.dateTakenMillis,
            origin = origin,
        )
        if (startWork && (result == AddResult.ADDED || result == AddResult.RETRYING)) scheduler.startStamping()
        return result
    }

    /** Queues a shared file that is not in MediaStore (a private copy was made). */
    suspend fun addCopy(
        sourceKey: String,
        sourceUri: String,
        localCopyPath: String,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
    ): AddResult {
        val result = addInternal(
            retryFailed = true,
            sourceKey = sourceKey,
            sourceUri = sourceUri,
            mediaStoreId = null,
            localCopyPath = localCopyPath,
            displayName = displayName,
            mimeType = mimeType,
            isVideo = MediaTypes.isVideo(mimeType),
            sizeBytes = sizeBytes,
            dateTakenMillis = null,
            origin = ItemOrigin.SHARE,
        )
        if (result == AddResult.ADDED || result == AddResult.RETRYING) scheduler.startStamping()
        return result
    }

    private suspend fun addInternal(
        retryFailed: Boolean,
        sourceKey: String,
        sourceUri: String,
        mediaStoreId: Long?,
        localCopyPath: String?,
        displayName: String,
        mimeType: String,
        isVideo: Boolean,
        sizeBytes: Long,
        dateTakenMillis: Long?,
        origin: ItemOrigin,
    ): AddResult {
        val existing = dao.findByKey(sourceKey)
        val now = System.currentTimeMillis()
        if (existing != null) {
            return when (existing.status) {
                ItemStatus.DONE -> AddResult.ALREADY_STAMPED
                ItemStatus.QUEUED, ItemStatus.PROCESSING, ItemStatus.NEEDS_LOCATION -> AddResult.ALREADY_QUEUED
                ItemStatus.FAILED, ItemStatus.SKIPPED -> if (retryFailed) {
                    dao.update(existing.copy(status = ItemStatus.QUEUED, attempts = 0, message = null, updatedAt = now))
                    AddResult.RETRYING
                } else {
                    AddResult.ALREADY_QUEUED
                }
            }
        }
        val raw = !isVideo && MediaTypes.isRaw(mimeType, displayName)
        val item = StampItem(
            sourceKey = sourceKey,
            sourceUri = sourceUri,
            mediaStoreId = mediaStoreId,
            localCopyPath = localCopyPath,
            displayName = displayName,
            mimeType = mimeType,
            isVideo = isVideo,
            sizeBytes = sizeBytes,
            origin = origin,
            status = if (raw) ItemStatus.SKIPPED else ItemStatus.QUEUED,
            createdAt = now,
            updatedAt = now,
            dateTakenMillis = dateTakenMillis,
            message = if (raw) "RAW files are skipped. Stamp the JPEG or HEIC version instead." else null,
        )
        return if (dao.insert(item) == -1L) AddResult.ALREADY_QUEUED else if (raw) AddResult.UNSUPPORTED else AddResult.ADDED
    }

    /** Applies a location the user chose to items that had none, and queues them. */
    suspend fun setLocation(
        ids: Collection<Long>,
        latitude: Double,
        longitude: Double,
        altitude: Double?,
        accuracyMeters: Float?,
        source: LocationSource,
    ) {
        val now = System.currentTimeMillis()
        for (id in ids) {
            val item = dao.get(id) ?: continue
            dao.update(
                item.copy(
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    locationAccuracyMeters = accuracyMeters,
                    locationSource = source,
                    status = ItemStatus.QUEUED,
                    attempts = 0,
                    message = null,
                    updatedAt = now,
                ),
            )
        }
        scheduler.startStamping()
    }

    suspend fun skip(ids: Collection<Long>) {
        val now = System.currentTimeMillis()
        for (id in ids) {
            val item = dao.get(id) ?: continue
            dao.update(item.copy(status = ItemStatus.SKIPPED, message = "Skipped: no location chosen.", updatedAt = now))
        }
    }

    suspend fun retry(id: Long) {
        val item = dao.get(id) ?: return
        dao.update(item.copy(status = ItemStatus.QUEUED, attempts = 0, message = null, updatedAt = System.currentTimeMillis()))
        scheduler.startStamping()
    }

    suspend fun remove(id: Long) {
        // A private copy of a shared file is no longer needed once the item is gone.
        dao.get(id)?.localCopyPath?.let { File(it).delete() }
        dao.delete(id)
    }

    /** True when this file is already in SiteCam's list (stamped, queued, skipped or failed). */
    suspend fun isKnown(sourceKey: String): Boolean = dao.findByKey(sourceKey) != null

    /** Camera direction recorded by SiteCam's compass while the camera was open. */
    suspend fun setHeading(sourceKey: String, headingDegrees: Float) {
        dao.setHeading(sourceKey, headingDegrees)
    }

    fun siteModeDoneSince(since: Long): Flow<Int> = dao.siteModeDoneSince(since)
}
