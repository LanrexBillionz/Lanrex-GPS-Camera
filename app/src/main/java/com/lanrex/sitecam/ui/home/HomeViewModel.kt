package com.lanrex.sitecam.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanrex.sitecam.AppContainer
import com.lanrex.sitecam.core.format.CoordinateFormat
import com.lanrex.sitecam.data.SiteModeState
import com.lanrex.sitecam.data.db.ItemStatus
import com.lanrex.sitecam.data.db.StampItem
import com.lanrex.sitecam.location.AddressLookup
import com.lanrex.sitecam.location.GpsFix
import com.lanrex.sitecam.stamp.StampProgress
import com.lanrex.sitecam.ui.permissions.AppPermissions
import com.lanrex.sitecam.ui.permissions.PermissionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Address preview shown under the live coordinates. */
sealed interface AddressUi {
    data object Idle : AddressUi
    data object Loading : AddressUi
    data class Found(val title: String?, val fullAddress: String?, val fromCache: Boolean) : AddressUi
    data class Pending(val reason: String) : AddressUi
    data object Unavailable : AddressUi
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val app = container.app
    private val locationRepository = container.locationRepository

    private val _permissions = MutableStateFlow(AppPermissions.snapshot(app))
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    private val _locationEnabled = MutableStateFlow(locationRepository.isLocationEnabled())
    val locationEnabled: StateFlow<Boolean> = _locationEnabled.asStateFlow()

    /** Called whenever the screen resumes or a permission dialog closes. */
    fun refresh() {
        _permissions.value = AppPermissions.snapshot(app)
        _locationEnabled.value = locationRepository.isLocationEnabled()
        checkLatestPhoto()
    }

    // ---- Site Mode ---------------------------------------------------------------------------

    val siteMode: StateFlow<SiteModeState> = container.settingsRepository.siteMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SiteModeState(false, 0L))

    val siteModeStamped: StateFlow<Int> = siteMode
        .flatMapLatest { state ->
            if (state.enabled) container.stampRepository.siteModeDoneSince(state.sinceMillis) else flowOf(0)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setSiteMode(enabled: Boolean) {
        viewModelScope.launch { container.siteModeController.setEnabled(enabled) }
    }

    // ---- Open Camera -----------------------------------------------------------------------

    /** Records the session (time + current fix) right before the camera opens. */
    fun beginCameraSession() {
        container.cameraSessionManager.begin(fix.value)
    }

    // ---- "Location tags are off" warning ------------------------------------------------------

    private val _latestPhotoWarning = MutableStateFlow<String?>(null)
    val latestPhotoWarning: StateFlow<String?> = _latestPhotoWarning.asStateFlow()
    private var checkedPhotoKey: String? = null

    /** Warns when the newest camera photo has no GPS (Samsung Camera's Location tags are off). */
    private fun checkLatestPhoto() {
        val snapshot = _permissions.value
        if (!snapshot.mediaAny || !snapshot.mediaLocation) {
            _latestPhotoWarning.value = null
            return
        }
        viewModelScope.launch {
            _latestPhotoWarning.value = withContext(Dispatchers.IO) {
                val latest = container.mediaStoreRepository.latestCameraPhoto() ?: return@withContext null
                val ageMillis = System.currentTimeMillis() - latest.dateAddedSeconds * 1000
                if (ageMillis > 3L * 24 * 3600 * 1000) return@withContext null
                val key = "${latest.sourceKey}:${latest.dateModifiedSeconds}"
                if (key == checkedPhotoKey) return@withContext _latestPhotoWarning.value
                checkedPhotoKey = key
                val info = try {
                    container.metadataReader.readPhoto(
                        container.mediaStoreRepository.originalUri(latest.uri),
                        latest.dateTakenMillis,
                    )
                } catch (e: Exception) {
                    return@withContext null
                }
                if (info.hasLocation) null else latest.displayName
            }
        }
    }

    /** Latest live fix (high accuracy, never older than 30 s when received). */
    val fix: StateFlow<GpsFix?> = _permissions
        .map { it.anyLocation }
        .distinctUntilChanged()
        .flatMapLatest { allowed -> if (allowed) locationRepository.liveFixes() else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Address for the current position, looked up again after moving ~15 m. */
    val address: StateFlow<AddressUi> = addressFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddressUi.Idle)

    val recent: StateFlow<List<StampItem>> = container.stampRepository.recentDone
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeCount: StateFlow<Int> = container.stampRepository.active.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val needsLocationCount: StateFlow<Int> = container.stampRepository.needsLocation.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val problemCount: StateFlow<Int> = container.stampRepository.problems.map { list -> list.count { it.status == ItemStatus.FAILED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val progress: StateFlow<StampProgress?> = container.stampProcessor.progress

    private fun addressFlow(): Flow<AddressUi> = fix
        .filterNotNull()
        .distinctUntilChanged { old, new ->
            CoordinateFormat.distanceMeters(old.latitude, old.longitude, new.latitude, new.longitude) < 15.0
        }
        .transformLatest { f ->
            emit(AddressUi.Loading)
            var result = container.addressRepository.lookup(f.latitude, f.longitude)
            emit(result.toUi())
            while (result is AddressLookup.Pending) {
                delay(20_000)
                result = container.addressRepository.lookup(f.latitude, f.longitude)
                emit(result.toUi())
            }
        }

    private fun AddressLookup.toUi(): AddressUi = when (this) {
        is AddressLookup.Found -> AddressUi.Found(title, fullAddress, fromCache)
        is AddressLookup.Pending -> AddressUi.Pending(reason)
        AddressLookup.Unavailable -> AddressUi.Unavailable
    }
}
