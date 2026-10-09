package com.lanrex.sitecam.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import com.lanrex.sitecam.core.format.AddressFormat
import com.lanrex.sitecam.core.format.AddressParts
import com.lanrex.sitecam.core.format.CoordinateFormat
import com.lanrex.sitecam.util.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Result of turning coordinates into an address. */
sealed interface AddressLookup {
    data class Found(val parts: AddressParts, val fromCache: Boolean) : AddressLookup {
        val title: String? get() = AddressFormat.titleLine(parts)
        val fullAddress: String? get() = AddressFormat.fullAddress(parts)
    }

    /** No internet (or the lookup service failed): try again later. */
    data class Pending(val reason: String) : AddressLookup

    /** The service answered but knows no address here, or there is no geocoder at all. */
    data object Unavailable : AddressLookup
}

/** Saves and finds addresses already looked up nearby, so offline stamps still get one. */
interface AddressCacheStore {
    suspend fun findNear(latitude: Double, longitude: Double, radiusMeters: Double): AddressParts?
    suspend fun save(latitude: Double, longitude: Double, parts: AddressParts)
}

/**
 * Reverse geocoding with Android's Geocoder.
 * Results are cached: a stamp within [CACHE_RADIUS_METERS] of an earlier lookup reuses it.
 */
class AddressRepository(
    private val context: Context,
    private val store: AddressCacheStore? = null,
) {
    private data class MemoryEntry(val latitude: Double, val longitude: Double, val parts: AddressParts)

    private val memory = ArrayDeque<MemoryEntry>()

    suspend fun lookup(latitude: Double, longitude: Double): AddressLookup {
        nearestInMemory(latitude, longitude)?.let { return AddressLookup.Found(it, fromCache = true) }
        store?.findNear(latitude, longitude, CACHE_RADIUS_METERS)?.let {
            remember(latitude, longitude, it)
            return AddressLookup.Found(it, fromCache = true)
        }
        if (!Geocoder.isPresent()) return AddressLookup.Unavailable
        if (!Network.isOnline(context)) return AddressLookup.Pending("No internet connection")

        return try {
            val addresses = withTimeout(GEOCODER_TIMEOUT_MS) { geocode(latitude, longitude) }
            val parts = addresses.firstOrNull()?.toParts()
            if (parts == null || AddressFormat.titleLine(parts) == null) {
                AddressLookup.Unavailable
            } else {
                remember(latitude, longitude, parts)
                store?.save(latitude, longitude, parts)
                AddressLookup.Found(parts, fromCache = false)
            }
        } catch (e: IOException) {
            AddressLookup.Pending(e.message ?: "Address service not reachable")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            AddressLookup.Pending("Address lookup timed out")
        } catch (e: IllegalArgumentException) {
            AddressLookup.Unavailable
        }
    }

    private suspend fun geocode(latitude: Double, longitude: Double): List<Address> {
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocodeAsync(geocoder, latitude, longitude)
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1).orEmpty()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun geocodeAsync(geocoder: Geocoder, latitude: Double, longitude: Double): List<Address> =
        suspendCancellableCoroutine { cont ->
            geocoder.getFromLocation(
                latitude,
                longitude,
                1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses.toList())
                    }

                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resumeWithException(IOException(errorMessage ?: "Geocoder error"))
                    }
                },
            )
        }

    private fun nearestInMemory(latitude: Double, longitude: Double): AddressParts? = synchronized(memory) {
        memory.firstOrNull {
            CoordinateFormat.distanceMeters(latitude, longitude, it.latitude, it.longitude) <= CACHE_RADIUS_METERS
        }?.parts
    }

    private fun remember(latitude: Double, longitude: Double, parts: AddressParts) = synchronized(memory) {
        memory.addFirst(MemoryEntry(latitude, longitude, parts))
        while (memory.size > 64) memory.removeLast()
    }

    companion object {
        /** Photos taken within this distance of an earlier lookup reuse its address. */
        const val CACHE_RADIUS_METERS = 20.0
        private const val GEOCODER_TIMEOUT_MS = 20_000L

        fun Address.toParts(): AddressParts = AddressParts(
            featureName = featureName,
            subThoroughfare = subThoroughfare,
            thoroughfare = thoroughfare,
            subLocality = subLocality,
            locality = locality,
            subAdminArea = subAdminArea,
            adminArea = adminArea,
            postalCode = postalCode,
            countryName = countryName,
            countryCode = countryCode,
            addressLine = if (maxAddressLineIndex >= 0) getAddressLine(0) else null,
        )
    }
}
