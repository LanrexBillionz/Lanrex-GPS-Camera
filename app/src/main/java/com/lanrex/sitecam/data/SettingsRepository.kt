package com.lanrex.sitecam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lanrex.sitecam.location.GpsFix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class MapType(val apiValue: String, val label: String) {
    SATELLITE("satellite", "Satellite"),
    HYBRID("hybrid", "Satellite with labels"),
    ROADMAP("roadmap", "Road map"),
    TERRAIN("terrain", "Terrain"),
}

/** What goes on the stamp. Stored on the phone only. */
data class StampSettings(
    val showTitle: Boolean = true,
    val showAddress: Boolean = true,
    val showCoordinates: Boolean = true,
    val showTime: Boolean = true,
    val showMap: Boolean = true,
    val noteEnabled: Boolean = false,
    val noteText: String = "",
    val albumName: String = DEFAULT_ALBUM,
    val mapApiKey: String = "",
    val mapType: MapType = MapType.SATELLITE,
) {
    companion object {
        const val DEFAULT_ALBUM = "SiteCam"

        /** Keeps the album a single safe folder name inside Pictures. */
        fun sanitizeAlbum(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|]"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .trim('.')
                .take(60)
                .ifBlank { DEFAULT_ALBUM }
    }
}

/** Site Mode: while on, new camera photos/videos are stamped automatically. */
data class SiteModeState(val enabled: Boolean, val sinceMillis: Long)

/** Started when Open Camera is tapped. */
data class CameraSession(
    val startMillis: Long,
    val fixLatitude: Double?,
    val fixLongitude: Double?,
    val fixAccuracyMeters: Float?,
    val fixTimeMillis: Long?,
) {
    val hasFix: Boolean get() = fixLatitude != null && fixLongitude != null && fixTimeMillis != null
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private val data: Flow<Preferences> = context.settingsStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val stampSettings: Flow<StampSettings> = data.map { it.toStampSettings() }.distinctUntilChanged()

    suspend fun stampSettingsNow(): StampSettings = stampSettings.first()

    suspend fun updateStamp(transform: (StampSettings) -> StampSettings) {
        context.settingsStore.edit { prefs ->
            val s = transform(prefs.toStampSettings())
            prefs[SHOW_TITLE] = s.showTitle
            prefs[SHOW_ADDRESS] = s.showAddress
            prefs[SHOW_COORDINATES] = s.showCoordinates
            prefs[SHOW_TIME] = s.showTime
            prefs[SHOW_MAP] = s.showMap
            prefs[NOTE_ENABLED] = s.noteEnabled
            prefs[NOTE_TEXT] = s.noteText
            prefs[ALBUM] = StampSettings.sanitizeAlbum(s.albumName)
            prefs[MAP_KEY] = s.mapApiKey.trim()
            prefs[MAP_TYPE] = s.mapType.name
        }
    }

    // ---- Site Mode -----------------------------------------------------------------------

    val siteMode: Flow<SiteModeState> = data.map {
        SiteModeState(enabled = it[SITE_MODE] ?: false, sinceMillis = it[SITE_MODE_SINCE] ?: 0L)
    }.distinctUntilChanged()

    suspend fun siteModeNow(): SiteModeState = siteMode.first()

    suspend fun setSiteMode(enabled: Boolean) {
        context.settingsStore.edit {
            it[SITE_MODE] = enabled
            if (enabled) it[SITE_MODE_SINCE] = System.currentTimeMillis()
        }
    }

    // ---- Camera session (Open Camera) ----------------------------------------------------

    val cameraSession: Flow<CameraSession?> = data.map { it.toSession() }.distinctUntilChanged()

    suspend fun cameraSessionNow(): CameraSession? = cameraSession.first()

    suspend fun startCameraSession(startMillis: Long, fix: GpsFix?) {
        context.settingsStore.edit {
            it[SESSION_START] = startMillis
            if (fix != null) it.writeFix(fix) else it.clearFix()
        }
    }

    /** A fix that arrived shortly after Open Camera was tapped (only for that same session). */
    suspend fun recordSessionFix(fix: GpsFix, sessionStart: Long) {
        context.settingsStore.edit {
            if (it[SESSION_START] == sessionStart && it[SESSION_LAT] == null) it.writeFix(fix)
        }
    }

    suspend fun endCameraSession() {
        context.settingsStore.edit {
            it.remove(SESSION_START)
            it.clearFix()
        }
    }

    /** Last time the camera folder was scanned after returning to the app. */
    suspend fun setLastSessionScan(millis: Long) {
        context.settingsStore.edit { it[SESSION_LAST_SCAN] = millis }
    }

    // ---- Misc ------------------------------------------------------------------------------

    val lastMapError: Flow<String?> = data.map { it[LAST_MAP_ERROR] }.distinctUntilChanged()

    suspend fun setLastMapError(message: String?) {
        context.settingsStore.edit {
            if (message == null) it.remove(LAST_MAP_ERROR) else it[LAST_MAP_ERROR] = message
        }
    }

    private fun Preferences.toStampSettings() = StampSettings(
        showTitle = this[SHOW_TITLE] ?: true,
        showAddress = this[SHOW_ADDRESS] ?: true,
        showCoordinates = this[SHOW_COORDINATES] ?: true,
        showTime = this[SHOW_TIME] ?: true,
        showMap = this[SHOW_MAP] ?: true,
        noteEnabled = this[NOTE_ENABLED] ?: false,
        noteText = this[NOTE_TEXT] ?: "",
        albumName = this[ALBUM] ?: StampSettings.DEFAULT_ALBUM,
        mapApiKey = this[MAP_KEY] ?: "",
        mapType = this[MAP_TYPE]?.let { name -> MapType.entries.firstOrNull { it.name == name } } ?: MapType.SATELLITE,
    )

    private fun Preferences.toSession(): CameraSession? {
        val start = this[SESSION_START] ?: return null
        return CameraSession(
            startMillis = start,
            fixLatitude = this[SESSION_LAT],
            fixLongitude = this[SESSION_LON],
            fixAccuracyMeters = this[SESSION_ACC],
            fixTimeMillis = this[SESSION_FIX_TIME],
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeFix(fix: GpsFix) {
        this[SESSION_LAT] = fix.latitude
        this[SESSION_LON] = fix.longitude
        fix.accuracyMeters?.let { this[SESSION_ACC] = it } ?: remove(SESSION_ACC)
        this[SESSION_FIX_TIME] = fix.timeMillis
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.clearFix() {
        remove(SESSION_LAT)
        remove(SESSION_LON)
        remove(SESSION_ACC)
        remove(SESSION_FIX_TIME)
    }

    private companion object {
        val SHOW_TITLE = booleanPreferencesKey("show_title")
        val SHOW_ADDRESS = booleanPreferencesKey("show_address")
        val SHOW_COORDINATES = booleanPreferencesKey("show_coordinates")
        val SHOW_TIME = booleanPreferencesKey("show_time")
        val SHOW_MAP = booleanPreferencesKey("show_map")
        val NOTE_ENABLED = booleanPreferencesKey("note_enabled")
        val NOTE_TEXT = stringPreferencesKey("note_text")
        val ALBUM = stringPreferencesKey("album_name")
        val MAP_KEY = stringPreferencesKey("maps_static_api_key")
        val MAP_TYPE = stringPreferencesKey("map_type")
        val SITE_MODE = booleanPreferencesKey("site_mode")
        val SITE_MODE_SINCE = longPreferencesKey("site_mode_since")
        val SESSION_START = longPreferencesKey("session_start")
        val SESSION_LAT = doublePreferencesKey("session_lat")
        val SESSION_LON = doublePreferencesKey("session_lon")
        val SESSION_ACC = floatPreferencesKey("session_acc")
        val SESSION_FIX_TIME = longPreferencesKey("session_fix_time")
        val SESSION_LAST_SCAN = longPreferencesKey("session_last_scan")
        val LAST_MAP_ERROR = stringPreferencesKey("last_map_error")
    }
}
