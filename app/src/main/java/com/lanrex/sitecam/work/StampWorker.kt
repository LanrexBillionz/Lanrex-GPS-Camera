package com.lanrex.sitecam.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lanrex.sitecam.appContainer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Works through the stamping queue in the background, with a progress notification. */
class StampWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val processor = container.stampProcessor
        if (!processor.hasWork()) return Result.success()

        // Long jobs (200 MP photos, videos) need a foreground notification so Android
        // doesn't stop them. Android may refuse when the app is in the background and
        // battery usage isn't Unrestricted; stamping still continues for normal photos.
        try {
            setForeground(foregroundInfo("Preparing…", null, null))
        } catch (e: Exception) {
            // Not allowed right now.
        }

        coroutineScope {
            val updates = launch {
                processor.progress.filterNotNull().sample(750).collect { p ->
                    val title = if (p.isVideo) "Stamping video" else "Stamping photo"
                    val more = if (p.remaining > 0) " · ${p.remaining} more waiting" else ""
                    container.notifier.updateStamping(title, p.displayName + more, (p.fraction * 100).toInt())
                }
            }
            try {
                processor.drain()
            } finally {
                updates.cancel()
                container.notifier.cancel(Notifier.ID_STAMPING)
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Stamping…", null, null)

    private fun foregroundInfo(title: String, text: String?, percent: Int?): ForegroundInfo {
        val notification = applicationContext.appContainer.notifier.stampingNotification(title, text, percent)
        return if (Build.VERSION.SDK_INT >= 35) {
            ForegroundInfo(Notifier.ID_STAMPING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            ForegroundInfo(Notifier.ID_STAMPING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
    }
}
