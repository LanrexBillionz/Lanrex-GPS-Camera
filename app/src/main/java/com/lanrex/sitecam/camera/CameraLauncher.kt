package com.lanrex.sitecam.camera

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.MediaStore

/**
 * Opens the phone's own camera app as the full app (every mode, every lens),
 * not the limited "take one picture for another app" screen.
 */
object CameraLauncher {
    const val SAMSUNG_CAMERA_PACKAGE = "com.sec.android.app.camera"

    /** Returns false if no camera app could be opened. */
    fun open(context: Context): Boolean {
        val samsung = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .setPackage(SAMSUNG_CAMERA_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, samsung)) return true

        val anyCamera = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, anyCamera)) return true

        val launcher = context.packageManager.getLaunchIntentForPackage(SAMSUNG_CAMERA_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launcher != null && tryStart(context, launcher)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
