package com.lanrex.sitecam.ui.map

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

enum class MapLayer(val label: String, val attribution: String, val maxZoom: Int) {
    STREETS("Map", "© OpenStreetMap contributors", 19),
    SATELLITE("Satellite", "Imagery © Esri, Maxar, Earthstar Geographics", 19);

    fun url(z: Int, x: Int, y: Int): String = when (this) {
        STREETS -> "https://tile.openstreetmap.org/$z/$x/$y.png"
        SATELLITE -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
    }
}

/** Centre and zoom of the map. The pin is always drawn at the centre. */
class MapViewState(latitude: Double, longitude: Double, zoom: Float) {
    var latitude by mutableDoubleStateOf(latitude)
    var longitude by mutableDoubleStateOf(longitude)
    var zoom by mutableFloatStateOf(zoom)

    fun moveTo(lat: Double, lon: Double, newZoom: Float? = null) {
        latitude = lat.coerceIn(-85.0, 85.0)
        longitude = wrapLon(lon)
        if (newZoom != null) zoom = newZoom
    }

    companion object {
        fun wrapLon(lon: Double): Double {
            var l = lon
            while (l > 180) l -= 360
            while (l < -180) l += 360
            return l
        }
    }
}

/** Tiles kept in memory and on disk so panning back and forth doesn't download again. */
private class TileStore(context: Context) {
    val dir = File(context.cacheDir, "tiles").apply { mkdirs() }
    val memory = object : LruCache<String, ImageBitmap>(160) {}
    val loading = HashSet<String>()
    val io = Dispatchers.IO.limitedParallelism(2)
}

/**
 * Simple pannable, zoomable map (drag to move, pinch or double-tap to zoom).
 * Tiles: OpenStreetMap (streets) or Esri World Imagery (satellite).
 */
@Composable
fun SlippyMap(state: MapViewState, layer: MapLayer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { TileStore(context) }
    val loaded = remember { mutableStateMapOf<String, ImageBitmap>() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val baseTile = 256f * max(1f, density * 0.6f)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    val z = state.zoom
                    val scale = 2.0.pow((z - floor(z)).toDouble()).toFloat()
                    val tilePx = baseTile * scale
                    val n = 2.0.pow(floor(z).toDouble())
                    val cx = lonToX(state.longitude, n) - pan.x / tilePx
                    val cy = latToY(state.latitude, n) - pan.y / tilePx
                    state.moveTo(yToLat(cy, n), xToLon(cx, n))
                    if (zoomChange != 1f) {
                        state.zoom = (state.zoom + log2(zoomChange)).coerceIn(2f, layer.maxZoom.toFloat())
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { state.zoom = (state.zoom + 1f).coerceAtMost(layer.maxZoom.toFloat()) })
            },
    ) {
        drawRect(Color(0xFFE8E4DC))
        val z = floor(state.zoom).toInt()
        val n = 2.0.pow(z.toDouble())
        val scale = 2.0.pow((state.zoom - z).toDouble()).toFloat()
        val tilePx = baseTile * scale
        val cx = lonToX(state.longitude, n)
        val cy = latToY(state.latitude, n)
        val halfW = size.width / 2f
        val halfH = size.height / 2f
        val minX = floor(cx - halfW / tilePx).toInt()
        val maxX = floor(cx + halfW / tilePx).toInt()
        val minY = floor(cy - halfH / tilePx).toInt().coerceAtLeast(0)
        val maxY = floor(cy + halfH / tilePx).toInt().coerceAtMost(n.toInt() - 1)
        val drawSize = IntSize(kotlin.math.ceil(tilePx).toInt() + 1, kotlin.math.ceil(tilePx).toInt() + 1)
        for (ty in minY..maxY) {
            for (tx in minX..maxX) {
                val wrappedX = ((tx % n.toInt()) + n.toInt()) % n.toInt()
                val key = "${layer.name}/$z/$wrappedX/$ty"
                val image = loaded[key] ?: store.memory.get(key)
                val left = halfW + ((tx - cx) * tilePx).toFloat()
                val top = halfH + ((ty - cy) * tilePx).toFloat()
                if (image != null) {
                    drawImage(image, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = drawSize)
                } else {
                    requestTile(context, store, loaded, scope, layer, z, wrappedX, ty, key)
                }
            }
        }
    }
}

private fun requestTile(
    context: Context,
    store: TileStore,
    loaded: MutableMap<String, ImageBitmap>,
    scope: CoroutineScope,
    layer: MapLayer,
    z: Int,
    x: Int,
    y: Int,
    key: String,
) {
    synchronized(store.loading) {
        if (!store.loading.add(key)) return
    }
    scope.launch {
        val bitmap = withContext(store.io) { loadTile(store, layer, z, x, y) }
        synchronized(store.loading) { store.loading.remove(key) }
        if (bitmap != null) {
            store.memory.put(key, bitmap)
            loaded[key] = bitmap
            if (loaded.size > 120) loaded.keys.take(40).forEach { loaded.remove(it) }
        }
    }
}

private fun loadTile(store: TileStore, layer: MapLayer, z: Int, x: Int, y: Int): ImageBitmap? {
    val file = File(store.dir, "${layer.name}_${z}_${x}_$y")
    try {
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 14L * 24 * 3600 * 1000) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it.asImageBitmap() }
        }
        val connection = URL(layer.url(z, x, y)).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        // OpenStreetMap asks every app to identify itself.
        connection.setRequestProperty("User-Agent", "SiteCam/1.0 (Android; personal construction photo app)")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            file.writeBytes(bytes)
            return bitmap.asImageBitmap()
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        return null
    }
}

private fun lonToX(lon: Double, n: Double): Double = (lon + 180.0) / 360.0 * n

private fun latToY(lat: Double, n: Double): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n
}

private fun xToLon(x: Double, n: Double): Double = MapViewState.wrapLon(x / n * 360.0 - 180.0)

private fun yToLat(y: Double, n: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / n))))
