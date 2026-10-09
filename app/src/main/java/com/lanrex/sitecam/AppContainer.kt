package com.lanrex.sitecam

import android.app.Application
import com.lanrex.sitecam.location.AddressRepository
import com.lanrex.sitecam.location.LocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Creates and holds the app's long-lived objects. */
class AppContainer(val app: Application) {

    /** Scope for work that should outlive a single screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val locationRepository = LocationRepository(app)
    val addressRepository = AddressRepository(app)

    fun onAppStart() {
        // Later stages start background work here.
    }
}
