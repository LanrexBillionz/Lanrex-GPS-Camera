package com.lanrex.sitecam.service

import android.content.Context
import com.lanrex.sitecam.data.SettingsRepository
import com.lanrex.sitecam.work.WorkScheduler

/**
 * Turns Site Mode on and off. While on, a foreground service watches the camera
 * folder; WorkManager jobs (triggered by MediaStore changes, plus a 15-minute
 * safety check) restart it and catch anything missed if Android stops it.
 */
class SiteModeController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scheduler: WorkScheduler,
    private val hasQueuedWork: suspend () -> Boolean,
) {
    suspend fun setEnabled(enabled: Boolean) {
        settings.setSiteMode(enabled)
        if (enabled) {
            SiteCamService.siteModeOn(context)
            scheduler.armSiteModeBackup()
        } else {
            SiteCamService.siteModeOff(context)
            scheduler.cancelSiteModeBackup()
            // Photos already found by Site Mode still get their stamped copy.
            if (hasQueuedWork()) scheduler.startStamping()
        }
    }

    /** Restarts the watcher after a reboot, an app update, or Android stopping it. */
    suspend fun ensureRunning() {
        if (settings.siteModeNow().enabled) {
            SiteCamService.siteModeOn(context)
            scheduler.armSiteModeBackup()
        }
    }
}
