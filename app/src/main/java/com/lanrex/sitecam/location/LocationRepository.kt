package com.lanrex.sitecam.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GPS through Google's Fused Location Provider, always at high accuracy.
 * Fixes older than 30 seconds are ignored.
 */
class LocationRepository(private val context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasAnyPermission(): Boolean = hasFinePermission() ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    /** True when the phone's Location switch is on. */
    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /** Live fixes every ~2 s while collected. Emits nothing without permission. */
    @SuppressLint("MissingPermission")
    fun liveFixes(intervalMillis: Long = 2_000L): Flow<GpsFix> = callbackFlow {
        if (!hasAnyPermission()) {
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(1_000L)
            .setMaxUpdateAgeMillis(GpsFix.MAX_FIX_AGE_MS)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    val fix = GpsFix.from(location) ?: continue
                    if (fix.isFresh()) trySend(fix)
                }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            close()
            return@callbackFlow
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    /** A single fresh high-accuracy fix, or null if none arrives within [timeoutMillis]. */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(timeoutMillis: Long = 15_000L): GpsFix? {
        if (!hasAnyPermission()) return null
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(GpsFix.MAX_FIX_AGE_MS)
            .setDurationMillis(timeoutMillis)
            .build()
        val cancellation = CancellationTokenSource()
        return try {
            withTimeoutOrNull(timeoutMillis + 2_000L) {
                val location: android.location.Location? =
                    client.getCurrentLocation(request, cancellation.token).await()
                location?.let { GpsFix.from(it) }?.takeIf { it.isFresh() }
            }
        } catch (e: Exception) {
            null
        } finally {
            cancellation.cancel()
        }
    }
}
