package com.lanrex.sitecam.camera

import android.content.Context
import com.lanrex.sitecam.data.SettingsRepository
import com.lanrex.sitecam.data.db.ItemOrigin
import com.lanrex.sitecam.location.GpsFix
import com.lanrex.sitecam.location.HeadingRecorder
import com.lanrex.sitecam.media.CameraFolderScanner
import com.lanrex.sitecam.service.SiteCamService
import com.lanrex.sitecam.util.CrashLog
import com.lanrex.sitecam.work.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Open Camera": records the time and a GPS fix, opens Samsung Camera, and when
 * the user comes back to SiteCam, stamps everything the camera saved since.
 */
class CameraSessionManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scanner: CameraFolderScanner,
    private val scheduler: WorkScheduler,
    private val heading: HeadingRecorder,
    private val scope: CoroutineScope,
) {
    /**
     * Call from the Open Camera button, before opening the camera.
     * [fix] is the live fix shown on screen; null if none is fresh yet.
     */
    fun begin(fix: GpsFix?) {
        val start = System.currentTimeMillis()
        val freshFix = fix?.takeIf { it.isFresh() }
        freshFix?.let { heading.lastKnownPosition = it.latitude to it.longitude }
        // Started now, while SiteCam is still on screen, so it may use location in the background.
        sessionServiceStarted = SiteCamService.startSession(context, start, needsFix = freshFix == null)
        scope.launch { settings.startCameraSession(start, freshFix) }
    }

    /** True while this app run has a camera-session service running. */
    @Volatile
    private var sessionServiceStarted = false

    /**
     * Called whenever SiteCam comes back to the screen. Returns how many new
     * photos/videos were found and queued.
     */
    suspend fun onReturn(): Int {
        val session = settings.cameraSessionNow() ?: return 0
        if (System.currentTimeMillis() - session.startMillis > MAX_SESSION_MS) {
            settings.endCameraSession()
            return 0
        }
        val result = try {
            scanner.scan(session.startMillis, ItemOrigin.CAMERA_SESSION)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLog.note(context, "Could not look for new camera photos", e)
            CameraFolderScanner.Result(0, 0)
        }
        if (result.added > 0) scheduler.startStamping()
        if (result.notReady > 0) rescanLater(session.startMillis)
        settings.setLastSessionScan(System.currentTimeMillis())
        // Back in SiteCam: stop the background location/compass tracking for this session.
        if (sessionServiceStarted) {
            sessionServiceStarted = false
            SiteCamService.endSession(context)
        }
        return result.added
    }

    /** A video (or burst) may still be being saved; look again a few times. */
    private fun rescanLater(sinceMillis: Long) {
        scope.launch {
            repeat(RESCAN_ATTEMPTS) {
                delay(RESCAN_DELAY_MS)
                val again = try {
                    scanner.scan(sinceMillis, ItemOrigin.CAMERA_SESSION)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch
                }
                if (again.added > 0) scheduler.startStamping()
                if (again.notReady == 0) return@launch
            }
        }
    }

    companion object {
        /** Photos taken more than this long after tapping Open Camera are not picked up. */
        const val MAX_SESSION_MS = 2 * 60 * 60_000L
        private const val RESCAN_DELAY_MS = 5_000L
        private const val RESCAN_ATTEMPTS = 6
    }
}
