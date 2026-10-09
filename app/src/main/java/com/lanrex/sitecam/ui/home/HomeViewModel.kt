package com.lanrex.sitecam.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanrex.sitecam.AppContainer
import com.lanrex.sitecam.core.format.CoordinateFormat
import com.lanrex.sitecam.location.AddressLookup
import com.lanrex.sitecam.location.GpsFix
import com.lanrex.sitecam.ui.permissions.AppPermissions
import com.lanrex.sitecam.ui.permissions.PermissionSnapshot
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
