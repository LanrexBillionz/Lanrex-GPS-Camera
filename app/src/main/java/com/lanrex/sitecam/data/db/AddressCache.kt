package com.lanrex.sitecam.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.lanrex.sitecam.core.format.AddressParts
import com.lanrex.sitecam.core.format.CoordinateFormat
import com.lanrex.sitecam.location.AddressCacheStore
import kotlin.math.cos

/** An address looked up earlier, reused for photos taken nearby (also offline). */
@Entity(tableName = "address_cache", indices = [Index(value = ["latitude", "longitude"])])
data class AddressCacheEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val featureName: String?,
    val subThoroughfare: String?,
    val thoroughfare: String?,
    val subLocality: String?,
    val locality: String?,
    val subAdminArea: String?,
    val adminArea: String?,
    val postalCode: String?,
    val countryName: String?,
    val countryCode: String?,
    val addressLine: String?,
    val fetchedAt: Long,
) {
    fun toParts() = AddressParts(
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
        addressLine = addressLine,
    )
}

@Dao
interface AddressCacheDao {
    @Query(
        "SELECT * FROM address_cache WHERE latitude BETWEEN :minLat AND :maxLat " +
            "AND longitude BETWEEN :minLon AND :maxLon ORDER BY fetchedAt DESC LIMIT 50",
    )
    suspend fun inBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<AddressCacheEntry>

    @Insert
    suspend fun insert(entry: AddressCacheEntry)

    @Query("DELETE FROM address_cache WHERE fetchedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

class RoomAddressCacheStore(private val dao: AddressCacheDao) : AddressCacheStore {

    override suspend fun findNear(latitude: Double, longitude: Double, radiusMeters: Double): AddressParts? {
        val dLat = radiusMeters / 111_000.0
        val dLon = radiusMeters / (111_000.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
        return dao.inBox(latitude - dLat, latitude + dLat, longitude - dLon, longitude + dLon)
            .map { it to CoordinateFormat.distanceMeters(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= radiusMeters }
            .minByOrNull { it.second }
            ?.first
            ?.toParts()
    }

    override suspend fun save(latitude: Double, longitude: Double, parts: AddressParts) {
        dao.insert(
            AddressCacheEntry(
                latitude = latitude,
                longitude = longitude,
                featureName = parts.featureName,
                subThoroughfare = parts.subThoroughfare,
                thoroughfare = parts.thoroughfare,
                subLocality = parts.subLocality,
                locality = parts.locality,
                subAdminArea = parts.subAdminArea,
                adminArea = parts.adminArea,
                postalCode = parts.postalCode,
                countryName = parts.countryName,
                countryCode = parts.countryCode,
                addressLine = parts.addressLine,
                fetchedAt = System.currentTimeMillis(),
            ),
        )
    }
}
