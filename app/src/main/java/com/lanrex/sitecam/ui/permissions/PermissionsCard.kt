package com.lanrex.sitecam.ui.permissions

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Plain-English explanations and buttons for every permission SiteCam needs.
 * Only the missing ones are shown.
 */
@Composable
fun PermissionsCard(
    snapshot: PermissionSnapshot,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
    showBattery: Boolean = true,
) {
    val context = LocalContext.current
    var locationAsked by rememberSaveable { mutableStateOf(false) }
    var mediaAsked by rememberSaveable { mutableStateOf(false) }
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationAsked = true
        onChanged()
    }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mediaAsked = true
        onChanged()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notificationsAsked = true
        onChanged()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Finish setting up SiteCam",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            if (!snapshot.fineLocation) {
                val blocked = locationAsked && AppPermissions.isBlocked(context, Manifest.permission.ACCESS_FINE_LOCATION)
                PermissionRow(
                    icon = Icons.Filled.LocationOn,
                    title = "Precise location",
                    text = if (snapshot.anyLocation) {
                        "You allowed only approximate location. Stamps need exact coordinates, so turn on " +
                            "\"Use precise location\"."
                    } else {
                        "Shows your live coordinates and records where you are when you tap Open Camera. " +
                            "Only used while you are using SiteCam."
                    },
                    button = if (blocked) "Open settings" else "Allow",
                    onClick = {
                        if (blocked) AppPermissions.openAppSettings(context) else locationLauncher.launch(AppPermissions.LOCATION)
                    },
                )
            }

            if (!snapshot.mediaFull) {
                val mediaPermission = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                val blocked = mediaAsked && !snapshot.mediaPartial && AppPermissions.isBlocked(context, mediaPermission)
                PermissionRow(
                    icon = Icons.Filled.PhotoLibrary,
                    title = if (snapshot.mediaPartial) "Photos and videos: only some allowed" else "Photos and videos",
                    text = if (snapshot.mediaPartial) {
                        "SiteCam can only see the photos you picked, so it can't find new camera photos. " +
                            "Tap the button and choose \"Allow all\"."
                    } else {
                        "Lets SiteCam find the photos and videos your camera saves and make stamped copies. " +
                            "Your originals are never changed. Please choose \"Allow all\"."
                    },
                    button = if (blocked) "Open settings" else "Allow all",
                    onClick = {
                        if (blocked) AppPermissions.openAppSettings(context) else mediaLauncher.launch(AppPermissions.mediaRequest())
                    },
                )
            } else if (!snapshot.mediaLocation) {
                PermissionRow(
                    icon = Icons.Filled.TravelExplore,
                    title = "Location inside photos",
                    text = "Android hides the GPS position saved in your photos from other apps. " +
                        "This lets SiteCam read where each photo was taken.",
                    button = if (mediaAsked) "Open settings" else "Allow",
                    onClick = {
                        if (mediaAsked) AppPermissions.openAppSettings(context) else mediaLauncher.launch(AppPermissions.mediaRequest())
                    },
                )
            }

            if (!snapshot.notifications) {
                val blocked = notificationsAsked && android.os.Build.VERSION.SDK_INT >= 33 &&
                    AppPermissions.isBlocked(context, Manifest.permission.POST_NOTIFICATIONS)
                PermissionRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    text = "Shows stamping progress and keeps Site Mode running in the background.",
                    button = if (blocked) "Open settings" else "Allow",
                    onClick = {
                        if (blocked) {
                            AppPermissions.openAppSettings(context)
                        } else {
                            notificationLauncher.launch(AppPermissions.notificationRequest())
                        }
                    },
                )
            }

            if (showBattery && !snapshot.batteryUnrestricted) {
                PermissionRow(
                    icon = Icons.Filled.BatteryAlert,
                    title = "Battery usage: Unrestricted",
                    text = "Samsung can stop apps in the background to save battery. Set SiteCam to " +
                        "\"Unrestricted\" so Site Mode keeps stamping new photos.",
                    button = "Set Unrestricted",
                    onClick = { AppPermissions.requestBatteryUnrestricted(context) },
                    secondaryButton = "App settings",
                    onSecondary = { AppPermissions.openAppSettings(context) },
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    text: String,
    button: String,
    onClick: () -> Unit,
    secondaryButton: String? = null,
    onSecondary: () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 6.dp)) { Text(button) }
                if (secondaryButton != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onSecondary, modifier = Modifier.padding(top = 6.dp)) { Text(secondaryButton) }
                }
            }
        }
    }
}
