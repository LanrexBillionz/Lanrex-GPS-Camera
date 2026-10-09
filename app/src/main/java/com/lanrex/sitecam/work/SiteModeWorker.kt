package com.lanrex.sitecam.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lanrex.sitecam.appContainer
import com.lanrex.sitecam.data.db.ItemOrigin
import com.lanrex.sitecam.util.CrashLog
import kotlinx.coroutines.CancellationException

/**
 * Site Mode backup: runs when MediaStore changes (and every 15 minutes) in case
 * Android stopped the Site Mode service. Restarts it, scans and stamps.
 */
class SiteModeWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val state = container.settingsRepository.siteModeNow()
        if (!state.enabled) return Result.success()

        try {
            container.siteModeController.ensureRunning()
            container.cameraFolderScanner.scan(state.sinceMillis, ItemOrigin.SITE_MODE)
            if (container.stampProcessor.hasWork()) {
                try {
                    setForeground(getForegroundInfo())
                } catch (e: Exception) {
                    // Background start not allowed; normal photos still finish in time.
                }
                container.stampProcessor.drain()
            }
        } catch (e: CancellationException) {
            // Site Mode was turned off (or Android stopped the job): don't re-arm.
            throw e
        } catch (e: Exception) {
            CrashLog.note(applicationContext, "Site Mode check failed", e)
        }
        // Content-triggered jobs fire once; queue the next one.
        if (inputData.getBoolean(WorkScheduler.KEY_CONTENT_TRIGGER, false)) container.workScheduler.rearmSiteModeTrigger()
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.appContainer.notifier.stampingNotification("Site Mode", "Stamping new camera files…", null)
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(Notifier.ID_STAMPING, notification, type)
    }
}
