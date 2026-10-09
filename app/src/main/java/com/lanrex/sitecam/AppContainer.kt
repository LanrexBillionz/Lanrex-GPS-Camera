package com.lanrex.sitecam

import android.app.Application
import com.lanrex.sitecam.data.SettingsRepository
import com.lanrex.sitecam.data.StampRepository
import com.lanrex.sitecam.data.db.AppDatabase
import com.lanrex.sitecam.data.db.RoomAddressCacheStore
import com.lanrex.sitecam.location.AddressRepository
import com.lanrex.sitecam.location.LocationRepository
import com.lanrex.sitecam.media.MediaMetadataReader
import com.lanrex.sitecam.media.MediaStoreRepository
import com.lanrex.sitecam.media.MediaWriter
import com.lanrex.sitecam.media.SharedMediaImporter
import com.lanrex.sitecam.stamp.MapTileProvider
import com.lanrex.sitecam.stamp.PhotoStamper
import com.lanrex.sitecam.stamp.StampProcessor
import com.lanrex.sitecam.stamp.StampRenderer
import com.lanrex.sitecam.work.Notifier
import com.lanrex.sitecam.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Creates and holds the app's long-lived objects. */
class AppContainer(val app: Application) {

    /** Scope for work that should outlive a single screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.create(app) }
    val settingsRepository = SettingsRepository(app)
    val notifier = Notifier(app)
    val workScheduler = WorkScheduler(app)

    val locationRepository = LocationRepository(app)
    val addressRepository: AddressRepository by lazy {
        AddressRepository(app, RoomAddressCacheStore(database.addressCache()))
    }
    val mediaStoreRepository = MediaStoreRepository(app)
    val metadataReader = MediaMetadataReader(app)
    val mediaWriter = MediaWriter(app)
    val mapTileProvider = MapTileProvider(app)
    val stampRenderer: StampRenderer by lazy { StampRenderer(app) }
    val photoStamper: PhotoStamper by lazy { PhotoStamper(app, stampRenderer) }

    val stampRepository: StampRepository by lazy { StampRepository(database.stampItems(), workScheduler) }

    val stampProcessor: StampProcessor by lazy {
        StampProcessor(
            context = app,
            dao = database.stampItems(),
            settings = settingsRepository,
            mediaStore = mediaStoreRepository,
            metadata = metadataReader,
            addresses = addressRepository,
            maps = mapTileProvider,
            photoStamper = photoStamper,
            mediaWriter = mediaWriter,
            notifier = notifier,
        )
    }

    val sharedMediaImporter: SharedMediaImporter by lazy {
        SharedMediaImporter(app, mediaStoreRepository, stampRepository)
    }

    fun onAppStart() {
        notifier.createChannels()
        appScope.launch {
            mapTileProvider.trimCache()
            // Anything left in the queue (e.g. the phone restarted mid-way) continues.
            if (stampProcessor.hasWork()) workScheduler.startStamping()
        }
    }
}
