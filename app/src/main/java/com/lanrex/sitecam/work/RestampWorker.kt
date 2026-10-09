package com.lanrex.sitecam.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lanrex.sitecam.appContainer
import com.lanrex.sitecam.util.Network
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Runs when the phone is back online: copies stamped without internet get their
 * address (and map) by being stamped again from the original file.
 */
class RestampWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val processor = container.stampProcessor
        if (processor.pendingRestampCount() == 0) return Result.success()
        // "Connected" can still mean no working internet (e.g. Wi-Fi without data): try again later.
        if (!Network.isOnline(applicationContext)) return retryOrStop()

        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            // Not allowed from the background right now; photos still finish in time.
        }
        val remaining = coroutineScope {
            val updates = launch {
                processor.progress.filterNotNull().sample(750).collect { p ->
                    val more = if (p.remaining > 0) " · ${p.remaining} more waiting" else ""
                    container.notifier.updateStampingWithId(
                        Notifier.ID_RESTAMP,
                        "Adding the address",
                        p.displayName + more,
                        (p.fraction * 100).toInt(),
                    )
                }
            }
            try {
                processor.restampPending()
            } finally {
                updates.cancel()
                container.notifier.cancel(Notifier.ID_RESTAMP)
            }
        }
        return if (remaining > 0) retryOrStop() else Result.success()
    }

    /** Retries with growing gaps; after many tries it waits until SiteCam is opened again. */
    private fun retryOrStop(): Result = if (runAttemptCount < MAX_RUNS) Result.retry() else Result.success()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.appContainer.notifier.stampingNotification(
            "Adding the address",
            "Updating copies stamped without internet…",
            null,
        )
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(Notifier.ID_RESTAMP, notification, type)
    }

    private companion object {
        const val MAX_RUNS = 20
    }
}
