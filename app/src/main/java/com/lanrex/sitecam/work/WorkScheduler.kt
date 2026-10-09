package com.lanrex.sitecam.work

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Starts background work through WorkManager (survives the app being closed). */
class WorkScheduler(private val context: Context) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** Runs the stamping queue. A new run is appended so nothing queued is ever missed. */
    fun startStamping() {
        val request = OneTimeWorkRequestBuilder<StampWorker>()
            .addTag(TAG_STAMPING)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_STAMPING, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Site Mode backups: a MediaStore-triggered job and a 15-minute safety check. */
    fun armSiteModeBackup() {
        workManager.enqueueUniqueWork(UNIQUE_SITE_TRIGGER, ExistingWorkPolicy.KEEP, contentTriggerRequest())
        val periodic = PeriodicWorkRequestBuilder<SiteModeWorker>(15, TimeUnit.MINUTES)
            .addTag(TAG_SITE_MODE)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_SITE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    /** Content-triggered jobs fire once; queue the next one after the current run. */
    fun rearmSiteModeTrigger() {
        workManager.enqueueUniqueWork(UNIQUE_SITE_TRIGGER, ExistingWorkPolicy.APPEND_OR_REPLACE, contentTriggerRequest())
    }

    fun cancelSiteModeBackup() {
        workManager.cancelUniqueWork(UNIQUE_SITE_TRIGGER)
        workManager.cancelUniqueWork(UNIQUE_SITE_PERIODIC)
    }

    private fun contentTriggerRequest() = OneTimeWorkRequestBuilder<SiteModeWorker>()
        .setConstraints(
            Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
                .setTriggerContentMaxDelay(Duration.ofSeconds(60))
                .build(),
        )
        .setInputData(workDataOf(KEY_CONTENT_TRIGGER to true))
        .addTag(TAG_SITE_MODE)
        .build()

    companion object {
        const val UNIQUE_STAMPING = "stamp-queue"
        const val UNIQUE_SITE_TRIGGER = "site-mode-trigger"
        const val UNIQUE_SITE_PERIODIC = "site-mode-periodic"
        const val TAG_STAMPING = "stamping"
        const val TAG_SITE_MODE = "site-mode"
        const val KEY_CONTENT_TRIGGER = "content_trigger"
    }
}
