package com.lanrex.sitecam.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StampItemDao {

    /** Returns the new row id, or -1 if an item with the same sourceKey already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: StampItem): Long

    @Update
    suspend fun update(item: StampItem)

    @Query("SELECT * FROM stamp_items WHERE id = :id")
    suspend fun get(id: Long): StampItem?

    @Query("SELECT * FROM stamp_items WHERE id = :id")
    fun observe(id: Long): Flow<StampItem?>

    @Query("SELECT * FROM stamp_items WHERE sourceKey = :key")
    suspend fun findByKey(key: String): StampItem?

    @Query("SELECT * FROM stamp_items WHERE status = 'QUEUED' ORDER BY createdAt, id LIMIT 1")
    suspend fun nextQueued(): StampItem?

    @Query("SELECT COUNT(*) FROM stamp_items WHERE status = 'QUEUED'")
    suspend fun queuedCount(): Int

    /** Queued items plus any left half-done by a killed process. */
    @Query("SELECT COUNT(*) FROM stamp_items WHERE status IN ('QUEUED', 'PROCESSING')")
    suspend fun unfinishedCount(): Int

    /** Items left half-done when the app was killed go back in the queue. */
    @Query("UPDATE stamp_items SET status = 'QUEUED', progress = 0, updatedAt = :now WHERE status = 'PROCESSING'")
    suspend fun requeueInterrupted(now: Long): Int

    @Query("SELECT * FROM stamp_items WHERE status = 'DONE' ORDER BY updatedAt DESC LIMIT :limit")
    fun recentDone(limit: Int): Flow<List<StampItem>>

    @Query("SELECT * FROM stamp_items WHERE status IN ('QUEUED', 'PROCESSING') ORDER BY createdAt, id")
    fun active(): Flow<List<StampItem>>

    @Query("SELECT * FROM stamp_items WHERE status = 'NEEDS_LOCATION' ORDER BY createdAt, id")
    fun needsLocation(): Flow<List<StampItem>>

    @Query("SELECT * FROM stamp_items WHERE status = 'NEEDS_LOCATION' ORDER BY createdAt, id")
    suspend fun needsLocationNow(): List<StampItem>

    @Query("SELECT * FROM stamp_items WHERE status IN ('FAILED', 'SKIPPED') ORDER BY updatedAt DESC LIMIT 50")
    fun problems(): Flow<List<StampItem>>

    @Query("SELECT * FROM stamp_items WHERE status = 'DONE' AND (addressPending = 1 OR mapPending = 1) ORDER BY updatedAt")
    suspend fun pendingRestamp(): List<StampItem>

    @Query("SELECT COUNT(*) FROM stamp_items WHERE status = 'DONE' AND (addressPending = 1 OR mapPending = 1)")
    fun pendingRestampCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM stamp_items WHERE status = 'DONE' AND (addressPending = 1 OR mapPending = 1)")
    suspend fun pendingRestampCountNow(): Int

    /** Videos put aside by earlier versions (before video stamping existed) go back in the queue. */
    @Query(
        "UPDATE stamp_items SET status = 'QUEUED', attempts = 0, message = NULL, updatedAt = :now " +
            "WHERE status = 'SKIPPED' AND isVideo = 1 AND message LIKE 'Video stamping arrives%'",
    )
    suspend fun requeueWaitingVideos(now: Long): Int

    @Query("SELECT mediaStoreId FROM stamp_items WHERE mediaStoreId IS NOT NULL AND status = 'DONE'")
    fun stampedMediaIds(): Flow<List<Long>>

    @Query("SELECT outputUri FROM stamp_items WHERE outputUri IS NOT NULL")
    suspend fun outputUris(): List<String>

    @Query("DELETE FROM stamp_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE stamp_items SET headingDegrees = :heading WHERE sourceKey = :key AND headingDegrees IS NULL")
    suspend fun setHeading(key: String, heading: Float)

    @Query("SELECT COUNT(*) FROM stamp_items WHERE status = 'DONE' AND origin = 'SITE_MODE' AND createdAt >= :since")
    fun siteModeDoneSince(since: Long): Flow<Int>
}
