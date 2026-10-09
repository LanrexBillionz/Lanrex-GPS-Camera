package com.lanrex.sitecam.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ItemStatus {
    /** Waiting to be stamped. */
    QUEUED,
    PROCESSING,

    /** The file has no location; waiting for the user to choose one. */
    NEEDS_LOCATION,
    DONE,
    FAILED,

    /** The user chose to skip it, or the format is not supported (RAW). */
    SKIPPED,
}

enum class ItemOrigin { GALLERY, SHARE, CAMERA_SESSION, SITE_MODE }

enum class LocationSource {
    /** GPS saved inside the photo or video by the camera. */
    FILE,

    /** The fix SiteCam recorded when Open Camera was tapped. */
    OPEN_CAMERA_FIX,
    CURRENT_LOCATION,
    MAP_PIN,
}

/**
 * One photo or video that SiteCam has seen. The unique [sourceKey] makes sure
 * the same file is never stamped twice.
 */
@Entity(
    tableName = "stamp_items",
    indices = [
        Index(value = ["sourceKey"], unique = true),
        Index(value = ["status"]),
        Index(value = ["updatedAt"]),
    ],
)
data class StampItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "ms:<volume>:<id>" for MediaStore items, "share:<hash>" for other shared files. */
    val sourceKey: String,
    val sourceUri: String,
    val mediaStoreId: Long? = null,
    /** Private copy of a shared file that could not be found in MediaStore. */
    val localCopyPath: String? = null,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val sizeBytes: Long = 0,
    val origin: ItemOrigin,
    val status: ItemStatus,
    val createdAt: Long,
    val updatedAt: Long,
    /** MediaStore DATE_TAKEN, used only if the file itself has no date. */
    val dateTakenMillis: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    /** Set when the user (not the file) chose the location. */
    val locationSource: LocationSource? = null,
    val locationAccuracyMeters: Float? = null,
    val headingDegrees: Float? = null,
    val addressPending: Boolean = false,
    val mapPending: Boolean = false,
    val outputUri: String? = null,
    val outputName: String? = null,
    val message: String? = null,
    val progress: Int = 0,
    val attempts: Int = 0,
)

/** True when the user (not the file) chose this item's location. */
fun StampItem.hasChosenLocation(): Boolean =
    latitude != null && longitude != null && locationSource != null && locationSource != LocationSource.FILE
