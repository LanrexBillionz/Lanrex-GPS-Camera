package com.lanrex.sitecam.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.lanrex.sitecam.R
import com.lanrex.sitecam.appContainer
import com.lanrex.sitecam.data.db.ItemOrigin
import com.lanrex.sitecam.stamp.StampProgress
import com.lanrex.sitecam.ui.permissions.AppPermissions
import com.lanrex.sitecam.util.CrashLog
import com.lanrex.sitecam.work.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Small foreground service with two jobs:
 *
 *  - **Site Mode**: watches MediaStore and stamps every new photo/video saved in
 *    DCIM/Camera, however the camera was opened (side key, lock screen, ...).
 *  - **Camera session** (after tapping Open Camera): records a GPS fix if none
 *    was available at the tap, while SiteCam is in the background.
 *
 * In both cases it records the compass heading while a camera is in use.
 */
class SiteCamService : LifecycleService() {

    private val container get() = applicationContext.appContainer
    private val mainHandler = Handler(Looper.getMainLooper())

    private var siteMode = false
    private var sessionStart = 0L
    private var sessionUntil = 0L
    private var progress: StampProgress? = null

    /** Foreground service types currently in use (location is only needed during a camera session). */
    private var currentTypes = -1

    /** Latest start request; stopping with it never drops a request that is still on its way. */
    private var lastStartId = 0

    private var observer: ContentObserver? = null
    private var cameraCallback: CameraManager.AvailabilityCallback? = null
    private val openCameras = HashSet<String>()
    private var fixJob: Job? = null
    private var sessionTimer: Job? = null

    /** Scan requests; bursts of MediaStore changes collapse into one scan. */
    private val scanRequests = Channel<Unit>(Channel.CONFLATED)

    private val sessionActive: Boolean get() = sessionUntil > System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        lifecycleScope.launch {
            for (request in scanRequests) {
                delay(SETTLE_MS)
                runScan()
            }
        }
        lifecycleScope.launch {
            container.stampProcessor.progress.sample(1_000).collect {
                progress = it
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        var needsFix = false
        var restoring = false
        when (intent?.action) {
            ACTION_SITE_MODE_ON -> siteMode = true
            ACTION_SITE_MODE_OFF -> siteMode = false
            ACTION_TURN_OFF_FROM_NOTIFICATION -> {
                siteMode = false
                // App scope: this service stops right away, the setting must still be saved.
                container.appScope.launch { container.siteModeController.setEnabled(false) }
            }
            ACTION_SESSION_START -> {
                val start = intent?.getLongExtra(EXTRA_SESSION_START, 0L) ?: 0L
                sessionStart = if (start > 0) start else System.currentTimeMillis()
                sessionUntil = System.currentTimeMillis() + SESSION_LIMIT_MS
                needsFix = intent?.getBooleanExtra(EXTRA_NEEDS_FIX, false) == true
            }
            ACTION_SESSION_END -> sessionUntil = 0L
            else -> restoring = true
        }
        // Android requires startForeground() soon after every startForegroundService().
        if (!goForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (restoring) {
            // Restarted by Android after it stopped the app: the saved Site Mode setting decides.
            lifecycleScope.launch {
                siteMode = siteMode || container.settingsRepository.siteModeNow().enabled
                applyState()
            }
            return START_STICKY
        }
        if (needsFix) startFixCapture()
        applyState()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        unregisterObserver()
        unregisterCameraCallback()
        container.headingRecorder.stop()
        // Anything still waiting (e.g. a stamp interrupted by turning Site Mode off) is finished by WorkManager.
        val app = container
        app.appScope.launch {
            if (app.stampProcessor.hasWork()) app.workScheduler.startStamping()
        }
        super.onDestroy()
    }

    // ---- Foreground state --------------------------------------------------------------------

    private fun foregroundTypes(): Int {
        var types = 0
        if (sessionActive && AppPermissions.granted(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 34) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        return types
    }

    private fun goForeground(): Boolean {
        val notification = buildNotification()
        val types = foregroundTypes()
        return try {
            ServiceCompat.startForeground(this, Notifier.ID_SITE_MODE, notification, types)
            currentTypes = types
            true
        } catch (e: Exception) {
            // Location may not be allowed from the background; try without it.
            try {
                val fallback = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                ServiceCompat.startForeground(this, Notifier.ID_SITE_MODE, notification, fallback)
                currentTypes = fallback
                true
            } catch (e2: Exception) {
                CrashLog.note(this, "Site Mode service could not start in the foreground", e2)
                false
            }
        }
    }

    private fun applyState() {
        if (siteMode) registerObserver() else unregisterObserver()
        if (siteMode || sessionActive) registerCameraCallback() else unregisterCameraCallback()
        if (sessionActive) {
            container.headingRecorder.start()
            scheduleSessionEnd()
        } else if (openCameras.isEmpty()) {
            container.headingRecorder.stop()
        }
        if (!siteMode && !sessionActive) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            currentTypes = -1
            stopSelf(lastStartId)
            return
        }
        if (siteMode) scanRequests.trySend(Unit)
        // Drop the location type once the camera session is over (Site Mode alone doesn't need it).
        if (foregroundTypes() != currentTypes) goForeground() else updateNotification()
    }

    private fun scheduleSessionEnd() {
        sessionTimer?.cancel()
        sessionTimer = lifecycleScope.launch {
            delay((sessionUntil - System.currentTimeMillis()).coerceAtLeast(1_000))
            sessionUntil = 0L
            applyState()
        }
    }

    // ---- Camera session GPS fix ----------------------------------------------------------------

    /** Open Camera was tapped without a fresh fix: wait (up to 2 minutes) for one. */
    private fun startFixCapture() {
        fixJob?.cancel()
        val start = sessionStart
        fixJob = lifecycleScope.launch {
            try {
                // Empty (null) when location isn't allowed.
                val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) {
                    container.locationRepository.liveFixes().firstOrNull()
                } ?: return@launch
                container.headingRecorder.lastKnownPosition = fix.latitude to fix.longitude
                container.settingsRepository.recordSessionFix(fix, start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLog.note(this@SiteCamService, "Could not get a location for the camera session", e)
            }
        }
    }

    // ---- Site Mode: MediaStore watching ----------------------------------------------------------

    private fun registerObserver() {
        if (observer != null) return
        val o = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                scanRequests.trySend(Unit)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scanRequests.trySend(Unit)
            }
        }
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, o)
        observer = o
    }

    private fun unregisterObserver() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    private suspend fun runScan() {
        try {
            val state = container.settingsRepository.siteModeNow()
            if (!state.enabled) return
            val result = container.cameraFolderScanner.scan(state.sinceMillis, ItemOrigin.SITE_MODE)
            if (result.notReady > 0) {
                // Some files are still being written (e.g. a long video); look again shortly.
                lifecycleScope.launch {
                    delay(RETRY_NOT_READY_MS)
                    scanRequests.trySend(Unit)
                }
            }
            if (container.stampProcessor.hasWork()) container.stampProcessor.drain()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLog.note(this, "Site Mode scan failed", e)
        }
    }

    // ---- Camera in use → compass heading ----------------------------------------------------------

    private fun registerCameraCallback() {
        if (cameraCallback != null) return
        val manager = getSystemService(CameraManager::class.java) ?: return
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                openCameras.add(cameraId)
                container.headingRecorder.start()
            }

            override fun onCameraAvailable(cameraId: String) {
                openCameras.remove(cameraId)
                if (openCameras.isEmpty() && !sessionActive) container.headingRecorder.stop()
            }
        }
        try {
            manager.registerAvailabilityCallback(callback, mainHandler)
            cameraCallback = callback
        } catch (e: Exception) {
            // Heading is optional.
        }
    }

    private fun unregisterCameraCallback() {
        val callback = cameraCallback ?: return
        getSystemService(CameraManager::class.java)?.unregisterAvailabilityCallback(callback)
        cameraCallback = null
        openCameras.clear()
    }

    // ---- Notification ------------------------------------------------------------------------

    private fun updateNotification() {
        if (!siteMode && !sessionActive) return
        container.notifier.postRaw(Notifier.ID_SITE_MODE, buildNotification())
    }

    private fun buildNotification(): Notification {
        val p = progress
        val builder = NotificationCompat.Builder(this, Notifier.CHANNEL_SITE_MODE)
            .setSmallIcon(R.drawable.ic_stat_sitecam)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(container.notifier.openAppIntent(Notifier.DEST_HOME))
        when {
            p != null -> builder
                .setContentTitle(if (p.isVideo) "Stamping video" else "Stamping photo")
                .setContentText(p.displayName + if (p.remaining > 0) " · ${p.remaining} more waiting" else "")
                .setProgress(100, (p.fraction * 100).toInt(), false)
            siteMode -> builder
                .setContentTitle("Site Mode is on")
                .setContentText("New camera photos and videos are stamped automatically.")
            else -> builder
                .setContentTitle("Camera session")
                .setContentText("SiteCam is noting your location and compass heading while the camera is open.")
        }
        if (siteMode) {
            val off = PendingIntent.getForegroundService(
                this,
                1,
                Intent(this, SiteCamService::class.java).setAction(ACTION_TURN_OFF_FROM_NOTIFICATION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Turn off Site Mode", off)
        }
        return builder.build()
    }

    companion object {
        private const val ACTION_SITE_MODE_ON = "com.lanrex.sitecam.SITE_MODE_ON"
        private const val ACTION_SITE_MODE_OFF = "com.lanrex.sitecam.SITE_MODE_OFF"
        private const val ACTION_TURN_OFF_FROM_NOTIFICATION = "com.lanrex.sitecam.SITE_MODE_OFF_NOTIFICATION"
        private const val ACTION_SESSION_START = "com.lanrex.sitecam.SESSION_START"
        private const val ACTION_SESSION_END = "com.lanrex.sitecam.SESSION_END"
        private const val EXTRA_SESSION_START = "session_start"
        private const val EXTRA_NEEDS_FIX = "needs_fix"

        private const val SETTLE_MS = 2_500L
        private const val RETRY_NOT_READY_MS = 6_000L
        private const val SESSION_LIMIT_MS = 30 * 60_000L
        private const val FIX_TIMEOUT_MS = 120_000L

        /** True while the service exists in this process. */
        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var loggedRefusal = false

        fun siteModeOn(context: Context): Boolean = start(context, Intent(context, SiteCamService::class.java).setAction(ACTION_SITE_MODE_ON))

        /** Nothing to do if the service isn't running. */
        fun siteModeOff(context: Context): Boolean =
            !isRunning || start(context, Intent(context, SiteCamService::class.java).setAction(ACTION_SITE_MODE_OFF))

        /** Must be called while SiteCam is on screen (right before the camera opens). */
        fun startSession(context: Context, sessionStart: Long, needsFix: Boolean): Boolean = start(
            context,
            Intent(context, SiteCamService::class.java)
                .setAction(ACTION_SESSION_START)
                .putExtra(EXTRA_SESSION_START, sessionStart)
                .putExtra(EXTRA_NEEDS_FIX, needsFix),
        )

        fun endSession(context: Context): Boolean =
            !isRunning || start(context, Intent(context, SiteCamService::class.java).setAction(ACTION_SESSION_END))

        private fun start(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            // Android refuses background starts unless battery usage is Unrestricted.
            // Noted once per run so the WorkManager backup's retries don't flood the log.
            if (!loggedRefusal) {
                loggedRefusal = true
                CrashLog.note(context, "Could not start the Site Mode service (${intent.action})", e)
            }
            false
        }
    }
}
