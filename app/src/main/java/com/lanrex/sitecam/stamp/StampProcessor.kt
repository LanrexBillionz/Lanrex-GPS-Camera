package com.lanrex.sitecam.stamp

import android.content.Context
import android.net.Uri
import com.lanrex.sitecam.core.format.CaptureTime
import com.lanrex.sitecam.core.format.CoordinateFormat
import com.lanrex.sitecam.core.format.StampDateFormat
import com.lanrex.sitecam.data.SettingsRepository
import com.lanrex.sitecam.data.StampSettings
import com.lanrex.sitecam.data.db.ItemStatus
import com.lanrex.sitecam.data.db.LocationSource
import com.lanrex.sitecam.data.db.StampItem
import com.lanrex.sitecam.data.db.StampItemDao
import com.lanrex.sitecam.data.db.hasChosenLocation
import com.lanrex.sitecam.location.AddressLookup
import com.lanrex.sitecam.location.AddressRepository
import com.lanrex.sitecam.location.HeadingRecorder
import com.lanrex.sitecam.media.MediaMetadataReader
import com.lanrex.sitecam.media.MediaStoreRepository
import com.lanrex.sitecam.media.MediaTypes
import com.lanrex.sitecam.media.MediaWriter
import com.lanrex.sitecam.util.CrashLog
import com.lanrex.sitecam.work.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/** Progress of the item being stamped right now. */
data class StampProgress(
    val itemId: Long,
    val displayName: String,
    val isVideo: Boolean,
    val fraction: Float,
    val remaining: Int,
)

/** What a stamper produced for one item. */
data class StampOutcome(
    val outputUri: Uri,
    val outputName: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val locationSource: LocationSource,
    val addressPending: Boolean,
    val mapPending: Boolean,
    val message: String? = null,
)

/** Implemented by the video stamper (stage 4). */
interface VideoStamping {
    suspend fun stamp(item: StampItem, onProgress: (Float) -> Unit): StampOutcome
}

/**
 * Works through the queue of photos/videos one at a time (to keep memory low),
 * making a stamped copy of each. Only one queue runner is active per process.
 */
class StampProcessor(
    private val context: Context,
    private val dao: StampItemDao,
    private val settings: SettingsRepository,
    private val mediaStore: MediaStoreRepository,
    private val metadata: MediaMetadataReader,
    private val addresses: AddressRepository,
    private val maps: MapTileProvider,
    private val photoStamper: PhotoStamper,
    private val mediaWriter: MediaWriter,
    private val notifier: Notifier,
    private val heading: HeadingRecorder? = null,
    private val videoStamper: VideoStamping? = null,
) {
    private val mutex = Mutex()

    private val _progress = MutableStateFlow<StampProgress?>(null)
    val progress: StateFlow<StampProgress?> = _progress.asStateFlow()

    suspend fun hasWork(): Boolean = dao.unfinishedCount() > 0

    /**
     * Stamps everything that is queued. If another caller (the Site Mode service
     * or a WorkManager job) is already working through the queue, waits for it
     * and then stamps whatever is left, so nothing is stranded if that caller
     * gets stopped part-way.
     */
    suspend fun drain(): Int {
        var done = 0
        while (true) {
            mutex.lock()
            try {
                dao.requeueInterrupted(System.currentTimeMillis())
                while (currentCoroutineContext().isActive) {
                    val item = dao.nextQueued() ?: break
                    if (process(item)) done++
                }
            } finally {
                _progress.value = null
                mutex.unlock()
            }
            if (!currentCoroutineContext().isActive || dao.queuedCount() == 0) break
        }
        if (done > 0) notifier.finished(done, settings.stampSettingsNow().albumName)
        notifier.needsLocation(dao.needsLocationNow().size)
        return done
    }

    /** Returns true when a stamped copy was written. */
    private suspend fun process(item: StampItem): Boolean {
        if (item.attempts >= MAX_ATTEMPTS) {
            dao.update(item.copy(status = ItemStatus.FAILED, message = "Stamping kept failing for this file.", updatedAt = now()))
            return false
        }
        val working = item.copy(status = ItemStatus.PROCESSING, attempts = item.attempts + 1, progress = 0, updatedAt = now())
        dao.update(working)
        val remaining = dao.queuedCount()
        publish(working, 0f, remaining)

        return try {
            val outcome = if (item.isVideo) {
                val video = videoStamper ?: throw UnsupportedMediaException(
                    "Video stamping arrives in the next SiteCam update. Tap Retry after updating.",
                )
                video.stamp(working) { f -> publish(working, f, remaining) }
            } else {
                stampPhoto(working) { f -> publish(working, f, remaining) }
            }
            dao.update(
                working.copy(
                    status = ItemStatus.DONE,
                    attempts = 0,
                    progress = 100,
                    outputUri = outcome.outputUri.toString(),
                    outputName = outcome.outputName,
                    latitude = outcome.latitude,
                    longitude = outcome.longitude,
                    altitude = outcome.altitude,
                    locationSource = outcome.locationSource,
                    addressPending = outcome.addressPending,
                    mapPending = outcome.mapPending,
                    message = outcome.message,
                    updatedAt = now(),
                ),
            )
            true
        } catch (e: NeedsLocationException) {
            dao.update(working.copy(status = ItemStatus.NEEDS_LOCATION, attempts = 0, message = null, updatedAt = now()))
            false
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                dao.update(working.copy(status = ItemStatus.QUEUED, attempts = item.attempts, progress = 0, updatedAt = now()))
            }
            throw e
        } catch (e: UnsupportedMediaException) {
            dao.update(working.copy(status = ItemStatus.SKIPPED, attempts = 0, message = e.message, updatedAt = now()))
            false
        } catch (e: Throwable) {
            val message = when (e) {
                is CannotStampException -> e.message
                is FileNotFoundException -> "The original file could not be found. It may have been deleted or moved."
                is SecurityException -> "SiteCam is not allowed to read this file. Check the photo and video permission."
                is OutOfMemoryError -> "Not enough memory to stamp this file safely."
                else -> "Stamping failed: ${e.message ?: e.javaClass.simpleName}"
            } ?: "Stamping failed."
            CrashLog.note(context, "Failed to stamp ${item.displayName}: $message", e)
            dao.update(working.copy(status = ItemStatus.FAILED, attempts = 0, message = message, updatedAt = now()))
            notifier.failed(item.displayName, message)
            false
        }
    }

    private fun publish(item: StampItem, fraction: Float, remaining: Int) {
        _progress.value = StampProgress(item.id, item.displayName, item.isVideo, fraction.coerceIn(0f, 1f), remaining)
    }

    // ---- Photos ------------------------------------------------------------------------------

    private suspend fun stampPhoto(item: StampItem, onProgress: (Float) -> Unit): StampOutcome {
        if (MediaTypes.isRaw(item.mimeType, item.displayName)) {
            throw UnsupportedMediaException("RAW files are skipped. Stamp the JPEG or HEIC version of this photo instead.")
        }
        if (!MediaTypes.isSupportedPhoto(item.mimeType, item.displayName)) {
            throw UnsupportedMediaException("Only JPEG and HEIC photos can be stamped (this one is ${item.mimeType}).")
        }
        val readable = readableUri(item)
        val info = withContext(Dispatchers.IO) { metadata.readPhoto(readable, item.dateTakenMillis) }
        val location = resolveLocation(item, info.latitude, info.longitude, info.altitude)
            ?: throw NeedsLocationException()
        val stampSettings = settings.stampSettingsNow()
        val time = info.captureTime ?: item.dateTakenMillis?.let { fromMillis(it) } ?: StampContent.now()
        val prepared = prepare(stampSettings, location)
        val lines = StampContent.lines(stampSettings, prepared.address, location.latitude, location.longitude, time)
        // The camera's own compass value (EXIF) wins over SiteCam's recording.
        val facing = info.headingDegrees ?: item.headingDegrees
        heading?.lastKnownPosition = location.latitude to location.longitude

        val tmp = File(context.cacheDir, "stamping/item_${item.id}.jpg")
        tmp.parentFile?.mkdirs()
        tmp.delete()
        try {
            withContext(Dispatchers.Default) {
                val job = currentCoroutineContext()
                photoStamper.stamp(
                    PhotoStamper.Request(readable, info, lines, stampSettings.showMap, prepared.map?.tile, facing),
                    tmp,
                    isCancelled = { !job.isActive },
                    onProgress = { onProgress(it * 0.9f) },
                )
            }
            withContext(Dispatchers.IO) {
                val chosen = location.source != LocationSource.FILE
                ExifCopier.copy(
                    info.exif,
                    tmp,
                    ExifCopier.Overrides(
                        latitude = if (chosen) location.latitude else null,
                        longitude = if (chosen) location.longitude else null,
                        altitude = if (chosen) location.altitude else null,
                        dateTimeOriginal = StampContent.exifDateTime(time),
                        offsetTimeOriginal = StampDateFormat.offsetText(time.offsetSeconds),
                    ),
                )
            }
            onProgress(0.95f)
            val output = withContext(Dispatchers.IO) {
                writeOutput(item, tmp, "image/jpeg", "jpg", isVideo = false, album = stampSettings.albumName, dateTaken = time.epochMillis)
            }
            return StampOutcome(
                outputUri = output.uri,
                outputName = output.displayName,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                locationSource = location.source,
                addressPending = prepared.address is AddressLookup.Pending,
                mapPending = prepared.map?.pending == true,
            )
        } finally {
            tmp.delete()
        }
    }

    // ---- Shared helpers (also used by the video stamper) -------------------------------------

    data class ResolvedLocation(val latitude: Double, val longitude: Double, val altitude: Double?, val source: LocationSource)

    data class Prepared(val address: AddressLookup?, val map: MapResult?)

    /** A location chosen by the user wins; otherwise the file's own GPS; otherwise none. */
    fun resolveLocation(item: StampItem, fileLat: Double?, fileLon: Double?, fileAlt: Double?): ResolvedLocation? {
        if (item.hasChosenLocation()) {
            return ResolvedLocation(item.latitude!!, item.longitude!!, item.altitude, item.locationSource!!)
        }
        if (CoordinateFormat.isValid(fileLat, fileLon)) {
            return ResolvedLocation(fileLat!!, fileLon!!, fileAlt, LocationSource.FILE)
        }
        return null
    }

    /** Looks up the address and map thumbnail for a location. */
    suspend fun prepare(stampSettings: StampSettings, location: ResolvedLocation): Prepared {
        val address = if (stampSettings.showTitle || stampSettings.showAddress) {
            addresses.lookup(location.latitude, location.longitude)
        } else {
            null
        }
        val map = if (stampSettings.showMap) {
            maps.tile(location.latitude, location.longitude, stampSettings.mapApiKey, stampSettings.mapType)
        } else {
            null
        }
        if (map != null && stampSettings.mapApiKey.isNotBlank() && !map.pending) settings.setLastMapError(map.error)
        return Prepared(address, map)
    }

    /** The URI to read an item from: its private copy, or the MediaStore original with GPS. */
    fun readableUri(item: StampItem): Uri {
        item.localCopyPath?.let { path ->
            val f = File(path)
            if (f.exists()) return Uri.fromFile(f)
        }
        return mediaStore.originalUri(Uri.parse(item.sourceUri))
    }

    /** Writes a new stamped copy, or replaces the earlier copy when re-stamping. */
    fun writeOutput(
        item: StampItem,
        file: File,
        mimeType: String,
        extension: String,
        isVideo: Boolean,
        album: String,
        dateTaken: Long?,
    ): MediaWriter.Output {
        val existing = item.outputUri?.let { Uri.parse(it) }?.takeIf { mediaStore.exists(it) }
        if (existing != null) {
            mediaWriter.overwrite(existing, file)
            return MediaWriter.Output(existing, item.outputName ?: mediaWriter.stampedName(item.displayName, extension))
        }
        return mediaWriter.saveNew(file, mediaWriter.stampedName(item.displayName, extension), mimeType, album, isVideo, dateTaken)
    }

    fun fromMillis(millis: Long): CaptureTime =
        CaptureTime(millis, StampDateFormat.zoneOffsetSeconds(millis, java.time.ZoneId.systemDefault()), false)

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}
