package com.lanrex.sitecam.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

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

    companion object {
        const val UNIQUE_STAMPING = "stamp-queue"
        const val TAG_STAMPING = "stamping"
    }
}
