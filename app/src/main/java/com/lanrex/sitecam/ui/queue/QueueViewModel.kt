package com.lanrex.sitecam.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanrex.sitecam.AppContainer
import com.lanrex.sitecam.data.CameraSession
import com.lanrex.sitecam.data.db.LocationSource
import com.lanrex.sitecam.data.db.StampItem
import com.lanrex.sitecam.stamp.StampProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QueueViewModel(private val container: AppContainer) : ViewModel() {

    private val repo = container.stampRepository

    val active: StateFlow<List<StampItem>> =
        repo.active.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val needsLocation: StateFlow<List<StampItem>> =
        repo.needsLocation.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val problems: StateFlow<List<StampItem>> =
        repo.problems.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val progress: StateFlow<StampProgress?> = container.stampProcessor.progress
    val session: StateFlow<CameraSession?> =
        container.settingsRepository.cameraSession.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    /**
     * The Open Camera fix can be used for an item taken up to 30 minutes after the
     * tap (and at most 2 minutes before it).
     */
    fun sessionFixApplies(session: CameraSession?, item: StampItem): Boolean {
        if (session == null || !session.hasFix) return false
        val taken = item.dateTakenMillis ?: return false
        val fixTime = session.fixTimeMillis ?: return false
        return taken >= fixTime - 2 * 60_000L && taken <= fixTime + SESSION_FIX_WINDOW_MS
    }

    fun skip(ids: Collection<Long>) {
        viewModelScope.launch { repo.skip(ids) }
    }

    fun useSessionFix(ids: Collection<Long>) {
        val s = session.value ?: return
        val lat = s.fixLatitude ?: return
        val lon = s.fixLongitude ?: return
        viewModelScope.launch {
            repo.setLocation(ids, lat, lon, null, s.fixAccuracyMeters, LocationSource.OPEN_CAMERA_FIX)
            _message.value = "Using the location from when you opened the camera."
        }
    }

    fun useCurrentLocation(ids: Collection<Long>) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val fix = container.locationRepository.currentFix(20_000)
                if (fix == null) {
                    _message.value = "Couldn't get a fresh GPS fix. Make sure location is on and try again in the open."
                } else {
                    repo.setLocation(ids, fix.latitude, fix.longitude, fix.altitude, fix.accuracyMeters, LocationSource.CURRENT_LOCATION)
                    _message.value = "Using your current location."
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun usePin(ids: Collection<Long>, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            repo.setLocation(ids, latitude, longitude, null, null, LocationSource.MAP_PIN)
            _message.value = "Using the pin you dropped."
        }
    }

    fun retry(id: Long) {
        viewModelScope.launch { repo.retry(id) }
    }

    fun remove(id: Long) {
        viewModelScope.launch { repo.remove(id) }
    }

    companion object {
        const val SESSION_FIX_WINDOW_MS = 30 * 60_000L
    }
}
