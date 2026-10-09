package com.lanrex.sitecam.ui.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** What the user has allowed so far. */
data class PermissionSnapshot(
    val fineLocation: Boolean,
    val anyLocation: Boolean,
    /** "Allow all" photos and videos. */
    val mediaFull: Boolean,
    /** Android 14+: only some selected photos/videos are visible. */
    val mediaPartial: Boolean,
    val mediaLocation: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val mediaAny: Boolean get() = mediaFull || mediaPartial
    val essentialsGranted: Boolean get() = fineLocation && mediaFull && mediaLocation && notifications
}

object AppPermissions {

    val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Photos and videos, plus the hidden GPS data inside them. */
    fun mediaRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
    }

    fun notificationRequest(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun snapshot(context: Context): PermissionSnapshot {
        val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val mediaFull = if (Build.VERSION.SDK_INT >= 33) {
            granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
                granted(context, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val mediaPartial = !mediaFull && Build.VERSION.SDK_INT >= 34 &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val notifications = if (Build.VERSION.SDK_INT >= 33) {
            granted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        return PermissionSnapshot(
            fineLocation = fine,
            anyLocation = fine || coarse,
            mediaFull = mediaFull,
            mediaPartial = mediaPartial,
            mediaLocation = granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION),
            notifications = notifications,
            batteryUnrestricted = isBatteryUnrestricted(context),
        )
    }

    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Shows Android's "Let SiteCam always run in background?" dialog (sets Unrestricted). */
    @SuppressLint("BatteryLife")
    fun requestBatteryUnrestricted(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        if (!startSafely(context, direct)) openAppSettings(context)
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        startSafely(context, intent)
    }

    fun openLocationSettings(context: Context) {
        startSafely(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    /** True if Android will no longer show the permission dialog (user chose "Don't ask again"). */
    fun isBlocked(context: Context, permission: String): Boolean {
        val activity = context.findActivity() ?: return false
        return !granted(context, permission) && !activity.shouldShowRequestPermissionRationale(permission)
    }

    private fun startSafely(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
