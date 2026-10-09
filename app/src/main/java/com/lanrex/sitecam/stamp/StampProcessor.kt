package com.lanrex.sitecam.stamp

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import com.lanrex.sitecam.core.format.CaptureTime
import com.lanrex.sitecam.core.format.CoordinateFormat
import com.lanrex.sitecam.core.format.StampDateFormat
import com.lanrex.sitecam.core.stamp.StampLines
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
import com.lanrex.sitecam.media.VideoInfo
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

/** Burns the stamp into a copy of a video (see [VideoStamper]). */
interface VideoStamping {
    data class Request(
        /** Readable URI of the original. */
        val source: Uri,
        val info: VideoInfo,
        val sizeBytes: Long,
        val lines: StampLines,
        val showMap: Boolean,
        val map: MapTile?,
        val headingDegrees: Float?,
    )

    /** [note] is shown to the user, e.g. when HDR had to be converted. */
    data class Result(val note: String?)

    suspend fun stamp(request: Request, output: File, onProgress: (Float) -> Unit): Result
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
    /** Called when copies made offline are waiting for the internet to come back. */
    private val onRestampNeeded: () -> Unit = {},
) {
    private val mutex = Mutex()

    private val wakeLock: PowerManager.WakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SiteCam:stamping")
            .apply { setReferenceCounted(false) }
    }

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
            // Keeps the CPU running with the screen off (e.g. Site Mode while the phone is locked).
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            try {
                dao.requeueInterrupted(System.currentTimeMillis())
                while (currentCoroutineContext().isActive) {
                    val item = dao.nextQueued() ?: break
                    if (process(item)) done++
                }
            } finally {
                _progress.value = null
                if (wakeLock.isHeld) wakeLock.release()
                mutex.unlock()
            }
            if (!currentCoroutineContext().isActive || dao.queuedCount() == 0) break
        }
        if (done > 0) notifier.finished(done, settings.stampSettingsNow().albumName)
        notifier.needsLocation(dao.needsLocationNow().size)
        if (dao.pendingRestampCountNow() > 0) onRestampNeeded()
        return done
    }

    suspend fun pendingRestampCount(): Int = dao.pendingRestampCountNow()

    /**
     * Copies made while offline have no address (or no map) yet. Once the
     * internet is back, each is stamped again from its ORIGINAL file and the
     * earlier copy is replaced. Returns how many are still waiting.
     */
    suspend fun restampPending(): Int {
        mutex.lock()
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        try {
            val items = dao.pendingRestamp()
            for ((index, item) in items.withIndex()) {
                if (!currentCoroutineContext().isActive) break
                restampOne(item, remaining = items.size - index - 1)
            }
        } finally {
            _progress.value = null
            if (wakeLock.isHeld) wakeLock.release()
            mutex.unlock()
        }
        return dao.pendingRestampCountNow()
    }

    private suspend fun restampOne(item: StampItem, remaining: Int) {
        val output = item.outputUri?.let { Uri.parse(it) }
        val lat = item.latitude
        val lon = item.longitude
        if (output == null || lat == null || lon == null || !withContext(Dispatchers.IO) { mediaStore.exists(output) }) {
            // The stamped copy was deleted (or never written): nothing left to update.
            dao.update(item.copy(addressPending = false, mapPending = false, updatedAt = now()))
            return
        }
        // Only redo the work once the missing address or map can actually be fetched.
        val stampSettings = settings.stampSettingsNow()
        val prepared = prepare(stampSettings, ResolvedLocation(lat, lon, item.altitude, item.locationSource ?: LocationSource.FILE))
        val addressReady = item.addressPending && prepared.address !is AddressLookup.Pending
        val mapReady = item.mapPending && prepared.map?.pending != true
        if (!addressReady && !mapReady) return

        publish(item, 0f, remaining)
        try {
            val outcome = if (item.isVideo) {
                stampVideo(item) { f -> publish(item, f, remaining) }
            } else {
                stampPhoto(item) { f -> publish(item, f, remaining) }
            }
            val finished = !outcome.addressPending && !outcome.mapPending
            if (finished) deleteLocalCopy(item)
            dao.update(
                item.copy(
                    outputUri = outcome.outputUri.toString(),
                    outputName = outcome.outputName,
                    addressPending = outcome.addressPending,
                    mapPending = outcome.mapPending,
                    message = outcome.message,
                    attempts = 0,
                    localCopyPath = if (finished) null else item.localCopyPath,
                    updatedAt = now(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            // The original is gone, so the copy can't be made again: keep it as it is.
            dao.update(
                item.copy(
                    addressPending = false,
                    mapPending = false,
                    attempts = 0,
                    message = "The original was deleted, so the address could not be added later.",
                    updatedAt = now(),
                ),
            )
        } catch (e: Throwable) {
            CrashLog.note(context, "Could not update ${item.displayName} with its address", e)
            val attempts = item.attempts + 1
            if (attempts >= MAX_ATTEMPTS) {
                // Keep the copy as it is (coordinates and time) and stop trying.
                dao.update(
                    item.copy(
                        addressPending = false,
                        mapPending = false,
                        attempts = 0,
                        message = "The address could not be added later. The copy keeps its coordinates and time.",
                        updatedAt = now(),
                    ),
                )
            } else {
                dao.update(item.copy(attempts = attempts, updatedAt = now()))
            }
        }
    }

    /** Private copies of shared files are only kept while they may still be needed. */
    private fun deleteLocalCopy(item: StampItem) {
        item.localCopyPath?.let { File(it).delete() }
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
                stampVideo(working) { f -> publish(working, f, remaining) }
            } else {
                stampPhoto(working) { f -> publish(working, f, remaining) }
            }
            val finished = !outcome.addressPending && !outcome.mapPending
            if (finished) deleteLocalCopy(working)
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
                    localCopyPath = if (finished) null else working.localCopyPath,
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

    // ---- Videos ------------------------------------------------------------------------------

    private suspend fun stampVideo(item: StampItem, onProgress: (Float) -> Unit): StampOutcome {
        val stamper = videoStamper ?: throw UnsupportedMediaException("Video stamping is not available on this phone.")
        val readable = readableUri(item)
        val info = withContext(Dispatchers.IO) { metadata.readVideo(readable, item.dateTakenMillis) }
        val location = resolveLocation(item, info.latitude, info.longitude, info.altitude)
            ?: throw NeedsLocationException()
        val stampSettings = settings.stampSettingsNow()
        val time = info.captureTime ?: item.dateTakenMillis?.let { fromMillis(it) } ?: StampContent.now()
        val prepared = prepare(stampSettings, location)
        val lines = StampContent.lines(stampSettings, prepared.address, location.latitude, location.longitude, time)
        heading?.lastKnownPosition = location.latitude to location.longitude

        val dir = File(context.cacheDir, "stamping").apply { mkdirs() }
        val size = item.sizeBytes.takeIf { it > 0 } ?: withContext(Dispatchers.IO) { sizeOf(readable) }
        checkFreeSpace(dir, size)
        val tmp = File(dir, "item_${item.id}.mp4")
        tmp.delete()
        try {
            val result = stamper.stamp(
                VideoStamping.Request(readable, info, size, lines, stampSettings.showMap, prepared.map?.tile, item.headingDegrees),
                tmp,
            ) { f -> onProgress(f * 0.92f) }
            onProgress(0.93f)
            val output = withContext(Dispatchers.IO) {
                writeOutput(item, tmp, "video/mp4", "mp4", isVideo = true, album = stampSettings.albumName, dateTaken = time.epochMillis)
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
                message = result.note,
            )
        } finally {
            tmp.delete()
        }
    }

    /** A video needs room for the new copy while it is made, and again when it is saved. */
    private fun checkFreeSpace(dir: File, sourceBytes: Long) {
        if (sourceBytes <= 0) return
        val needed = sourceBytes / 10 * 23 + SPACE_MARGIN_BYTES
        val free = dir.usableSpace
        if (free in 1 until needed) {
            val gb = (needed - free) / 1e9
            throw CannotStampException(
                String.format(java.util.Locale.US, "Not enough free storage to stamp this video. Free up about %.1f GB and tap Retry.", gb),
            )
        }
    }

    private fun sizeOf(uri: Uri): Long = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    } catch (e: Exception) {
        0L
    }

    // ---- Shared helpers ----------------------------------------------------------------------

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
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60_000L
        private const val SPACE_MARGIN_BYTES = 300L * 1024 * 1024
    }
}
