package com.lanrex.sitecam.stamp

import android.content.Context
import android.graphics.BitmapFactory
import com.lanrex.sitecam.data.MapType
import com.lanrex.sitecam.util.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** A map tile plus whether it should be fetched again later (offline). */
data class MapResult(
    val tile: MapTile,
    /** True when a key is set but the map could not be downloaded (no internet). */
    val pending: Boolean,
    /** Message from Google when the key was rejected, shown in Settings. */
    val error: String?,
)

/**
 * Satellite thumbnails from the Google Maps Static API, using the key pasted in
 * Settings (stored on the phone only). Without a key, or offline, a plain
 * placeholder tile is drawn instead. Google's own logo is part of the image
 * Google returns; SiteCam never draws one itself.
 */
class MapTileProvider(private val context: Context) {

    private val cacheDir: File get() = File(context.cacheDir, "maps").apply { mkdirs() }

    suspend fun tile(latitude: Double, longitude: Double, apiKey: String, mapType: MapType): MapResult {
        val key = apiKey.trim()
        if (key.isEmpty()) return MapResult(MapTile.Placeholder, pending = false, error = null)

        val lat = String.format(Locale.US, "%.6f", latitude)
        val lon = String.format(Locale.US, "%.6f", longitude)
        val format = if (mapType == MapType.SATELLITE || mapType == MapType.HYBRID) "jpg" else "png"
        val cacheFile = File(cacheDir, "${mapType.apiValue}_z${ZOOM}_${lat}_$lon.$format")

        return withContext(Dispatchers.IO) {
            if (cacheFile.exists()) {
                BitmapFactory.decodeFile(cacheFile.absolutePath)?.let {
                    return@withContext MapResult(MapTile.Image(it), pending = false, error = null)
                }
                cacheFile.delete()
            }
            if (!Network.isOnline(context)) {
                return@withContext MapResult(MapTile.Placeholder, pending = true, error = null)
            }
            // Zoom 18 at 640 px and scale 2 covers the same ground as a zoom-17
            // thumbnail at 320 px, but twice as sharp for large photos.
            val url = "https://maps.googleapis.com/maps/api/staticmap" +
                "?center=$lat,$lon&zoom=$ZOOM&size=${SIZE}x$SIZE&scale=2" +
                "&maptype=${mapType.apiValue}&format=$format" +
                "&key=${URLEncoder.encode(key, "UTF-8")}"
            try {
                val bytes = download(url)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext MapResult(MapTile.Placeholder, pending = false, error = "Google sent an unreadable map image.")
                cacheFile.writeBytes(bytes)
                MapResult(MapTile.Image(bitmap), pending = false, error = null)
            } catch (e: RejectedException) {
                MapResult(MapTile.Placeholder, pending = false, error = e.message)
            } catch (e: IOException) {
                MapResult(MapTile.Placeholder, pending = true, error = null)
            }
        }
    }

    /** Checks a key from Settings with one small request. Returns null when it works. */
    suspend fun testKey(apiKey: String): String? = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) return@withContext "Paste your key first."
        val url = "https://maps.googleapis.com/maps/api/staticmap?center=7.645066,4.167863&zoom=17" +
            "&size=64x64&maptype=satellite&key=${URLEncoder.encode(key, "UTF-8")}"
        try {
            val bytes = download(url)
            if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) "Google sent an unreadable image." else null
        } catch (e: RejectedException) {
            e.message
        } catch (e: IOException) {
            "No internet connection (${e.message})."
        }
    }

    /** Removes cached map tiles older than 30 days. */
    fun trimCache() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        cacheDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private class RejectedException(message: String) : Exception(message)

    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "SiteCam/1.0 (Android)")
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val type = connection.contentType.orEmpty()
                val bytes = connection.inputStream.use { it.readBytes() }
                if (!type.startsWith("image/")) {
                    throw RejectedException("Google rejected the map request: ${String(bytes).take(200)}")
                }
                return bytes
            }
            val body = try {
                connection.errorStream?.use { String(it.readBytes()) }.orEmpty()
            } catch (e: IOException) {
                ""
            }
            if (code in 400..499) {
                throw RejectedException("Google rejected the map key (HTTP $code). ${body.take(200)}".trim())
            }
            throw IOException("Map server error HTTP $code")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val ZOOM = 18
        const val SIZE = 640
    }
}
