package com.lanrex.sitecam.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanrex.sitecam.AppContainer
import com.lanrex.sitecam.core.format.AddressParts
import com.lanrex.sitecam.core.format.CaptureTime
import com.lanrex.sitecam.core.format.StampDateFormat
import com.lanrex.sitecam.data.StampSettings
import com.lanrex.sitecam.location.AddressLookup
import com.lanrex.sitecam.stamp.MapTile
import com.lanrex.sitecam.stamp.StampContent
import com.lanrex.sitecam.util.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val repo = container.settingsRepository

    val settings: StateFlow<StampSettings?> =
        repo.stampSettings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val lastMapError: StateFlow<String?> =
        repo.lastMapError.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _keyTest = MutableStateFlow<String?>(null)
    val keyTest: StateFlow<String?> = _keyTest.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private var previewMap: MapTile? = null

    /** A sample photo with the stamp as it would look with the current settings. */
    val preview: StateFlow<Bitmap?> = repo.stampSettings
        .debounce(150)
        .map { s -> withContext(Dispatchers.Default) { renderPreview(s) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (StampSettings) -> StampSettings) {
        viewModelScope.launch { repo.updateStamp(transform) }
    }

    fun testKey(key: String) {
        if (_testing.value) return
        _testing.value = true
        viewModelScope.launch {
            val error = container.mapTileProvider.testKey(key)
            _keyTest.value = error ?: "✓ The key works. Satellite maps will appear on new stamps."
            repo.setLastMapError(error)
            _testing.value = false
        }
    }

    fun crashReport(): String? = CrashLog.read(container.app)
    fun clearCrashReport() = CrashLog.clear(container.app)
    fun problemLog(): String? = CrashLog.readProblems(container.app)
    fun clearProblemLog() = CrashLog.clearProblems(container.app)

    private fun renderPreview(s: StampSettings): Bitmap {
        val width = 1200
        val height = 900
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val sky = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(0xFF7FA7D6.toInt(), 0xFFB9C9A3.toInt(), 0xFF9C6B45.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sky)

        val sample = AddressParts(
            subThoroughfare = "183",
            thoroughfare = "Ibadan - Iwo Rd",
            locality = "Iwo",
            adminArea = "Osun",
            postalCode = "232102",
            countryName = "Nigeria",
            countryCode = "NG",
        )
        val millis = System.currentTimeMillis()
        val time = CaptureTime(millis, StampDateFormat.zoneOffsetSeconds(millis, ZoneId.systemDefault()), false)
        val lines = StampContent.lines(s, AddressLookup.Found(sample, fromCache = false), 7.645066, 4.167863, time)
        val renderer = container.stampRenderer
        val geometry = renderer.layout(width, height, lines, s.showMap)
        renderer.draw(canvas, geometry, previewMap ?: MapTile.Placeholder, 135f)
        return bitmap
    }
}
