package com.lanrex.sitecam.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lanrex.sitecam.appContainer
import com.lanrex.sitecam.util.CrashLog
import kotlinx.coroutines.launch

/** Restarts Site Mode after the phone reboots or SiteCam is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val container = context.appContainer
        container.appScope.launch {
            try {
                container.siteModeController.ensureRunning()
                if (container.stampProcessor.hasWork()) container.workScheduler.startStamping()
            } catch (e: Exception) {
                CrashLog.note(context, "Could not restart Site Mode after ${intent.action}", e)
            } finally {
                pending.finish()
            }
        }
    }
}
