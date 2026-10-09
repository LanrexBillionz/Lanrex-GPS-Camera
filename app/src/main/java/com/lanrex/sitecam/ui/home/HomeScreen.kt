package com.lanrex.sitecam.ui.home

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanrex.sitecam.camera.CameraLauncher
import com.lanrex.sitecam.core.format.CoordinateFormat
import com.lanrex.sitecam.data.db.StampItem
import com.lanrex.sitecam.location.GpsFix
import com.lanrex.sitecam.stamp.StampProgress
import com.lanrex.sitecam.ui.components.MediaThumbnail
import com.lanrex.sitecam.ui.permissions.AppPermissions
import com.lanrex.sitecam.ui.permissions.PermissionsCard
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val context = LocalContext.current
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val locationEnabled by viewModel.locationEnabled.collectAsStateWithLifecycle()
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val address by viewModel.address.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val activeCount by viewModel.activeCount.collectAsStateWithLifecycle()
    val needsLocation by viewModel.needsLocationCount.collectAsStateWithLifecycle()
    val problems by viewModel.problemCount.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val siteMode by viewModel.siteMode.collectAsStateWithLifecycle()
    val siteModeStamped by viewModel.siteModeStamped.collectAsStateWithLifecycle()
    val latestPhotoWarning by viewModel.latestPhotoWarning.collectAsStateWithLifecycle()
    var askBattery by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SiteCam", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenGallery) { Icon(Icons.Filled.PhotoLibrary, contentDescription = "Stamp from gallery") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!permissions.essentialsGranted || !permissions.batteryUnrestricted) {
                item { PermissionsCard(snapshot = permissions, onChanged = viewModel::refresh) }
            }
            item {
                LiveGpsCard(
                    fix = fix,
                    address = address,
                    hasPermission = permissions.anyLocation,
                    locationEnabled = locationEnabled,
                    onOpenLocationSettings = { AppPermissions.openLocationSettings(context) },
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.beginCameraSession()
                            if (!CameraLauncher.open(context)) {
                                Toast.makeText(context, "No camera app found", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Open Camera", style = MaterialTheme.typography.titleLarge)
                    }
                    OutlinedButton(onClick = onOpenGallery, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stamp photos from the gallery")
                    }
                }
            }
            item {
                SiteModeCard(
                    enabled = siteMode.enabled,
                    sinceMillis = siteMode.sinceMillis,
                    stamped = siteModeStamped,
                    batteryUnrestricted = permissions.batteryUnrestricted,
                    canWatch = permissions.mediaFull,
                    onToggle = { on ->
                        if (on && !permissions.batteryUnrestricted) askBattery = true
                        viewModel.setSiteMode(on)
                    },
                    onFixBattery = { AppPermissions.requestBatteryUnrestricted(context) },
                )
            }
            latestPhotoWarning?.let { name ->
                item { LocationTagsWarning(name) }
            }
            if (activeCount > 0) {
                item { StampingCard(activeCount, progress, onOpenQueue) }
            }
            if (needsLocation > 0 || problems > 0) {
                item { AttentionCard(needsLocation, problems, onOpenQueue) }
            }
            if (recent.isNotEmpty()) {
                item {
                    Text("Recent stamped copies", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                items(recent, key = { it.id }) { item -> RecentItemRow(item) }
            }
        }
    }
    if (askBattery) {
        AlertDialog(
            onDismissRequest = { askBattery = false },
            title = { Text("Keep Site Mode running") },
            text = {
                Text(
                    "Samsung puts apps to sleep to save battery. Set SiteCam's battery usage to " +
                        "\"Unrestricted\" so Site Mode keeps stamping new photos in the background.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askBattery = false
                    AppPermissions.requestBatteryUnrestricted(context)
                }) { Text("Set Unrestricted") }
            },
            dismissButton = { TextButton(onClick = { askBattery = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun SiteModeCard(
    enabled: Boolean,
    sinceMillis: Long,
    stamped: Int,
    batteryUnrestricted: Boolean,
    canWatch: Boolean,
    onToggle: (Boolean) -> Unit,
    onFixBattery: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Construction, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Site Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Stamps every new camera photo and video automatically, even when you open the camera " +
                            "from the side key or the lock screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle, enabled = canWatch || enabled)
            }
            if (!canWatch && !enabled) {
                Text("Allow access to all photos and videos above to use Site Mode.", style = MaterialTheme.typography.bodySmall)
            }
            if (enabled) {
                val since = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sinceMillis))
                Text(
                    "On since $since · $stamped stamped",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                if (!batteryUnrestricted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Battery usage is not Unrestricted, so Samsung may pause Site Mode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onFixBattery) { Text("Fix") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationTagsWarning(fileName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Your latest camera photo has no location", fontWeight = FontWeight.SemiBold)
                Text(
                    "$fileName was saved without GPS, which means Location tags are off in Samsung Camera. " +
                        "Open Camera → Settings (gear icon) → turn on \"Location tags\".",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StampingCard(activeCount: Int, progress: StampProgress?, onOpenQueue: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpenQueue)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (activeCount == 1) "Stamping 1 item…" else "Stamping $activeCount items…",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (progress != null) {
                Text(progress.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AttentionCard(needsLocation: Int, problems: Int, onOpenQueue: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (needsLocation > 0) {
                    Text(
                        if (needsLocation == 1) "1 photo or video has no location" else "$needsLocation photos or videos have no location",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("Choose a location for them, or skip them.", style = MaterialTheme.typography.bodySmall)
                }
                if (problems > 0) {
                    Text(if (problems == 1) "1 file was not stamped" else "$problems files were not stamped", fontWeight = FontWeight.SemiBold)
                }
            }
            FilledTonalButton(onClick = onOpenQueue) { Text("Open") }
        }
    }
}

@Composable
private fun RecentItemRow(item: StampItem) {
    val context = LocalContext.current
    val output = item.outputUri?.let { Uri.parse(it) } ?: return
    val mime = if (item.isVideo) "video/mp4" else "image/jpeg"
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaThumbnail(output, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)), sizePx = 192)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.outputName ?: item.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.addressPending || item.mapPending) {
                    Text(
                        if (item.addressPending) "Address pending · will update when online" else "Map pending · will update when online",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                item.message?.let { note ->
                    Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { openMedia(context, output, mime) }) {
                Icon(Icons.Filled.OpenInNew, contentDescription = "Open")
            }
            IconButton(onClick = { shareMedia(context, output, mime) }) {
                Icon(Icons.Filled.Share, contentDescription = "Share")
            }
        }
    }
}

fun shareMedia(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(intent, "Share stamped copy"))
}

fun openMedia(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun LiveGpsCard(
    fix: GpsFix?,
    address: AddressUi,
    hasPermission: Boolean,
    locationEnabled: Boolean,
    onOpenLocationSettings: () -> Unit,
) {
    // Re-draw every second so the "updated … ago" text and staleness stay current.
    var nowNanos by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowNanos = SystemClock.elapsedRealtimeNanos()
        }
    }
    val ageSeconds = fix?.let { ((nowNanos - it.elapsedRealtimeNanos) / 1_000_000_000L).coerceAtLeast(0) }
    val fresh = fix != null && ageSeconds != null && ageSeconds * 1000 <= GpsFix.MAX_FIX_AGE_MS

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        !hasPermission || !locationEnabled -> Icons.Filled.LocationOff
                        fresh -> Icons.Filled.GpsFixed
                        else -> Icons.Filled.GpsNotFixed
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text("Live GPS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (fresh && fix != null) AccuracyChip(fix.accuracyMeters)
            }

            when {
                !hasPermission -> Text("Allow location access above to see your coordinates.")
                !locationEnabled -> {
                    Text("Location is turned off on this phone.")
                    OutlinedButton(onClick = onOpenLocationSettings) { Text("Turn on location") }
                }
                fix == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Waiting for a GPS fix… Stand in the open if this takes long.")
                }
                else -> {
                    Text(
                        CoordinateFormat.stampLine(fix.latitude, fix.longitude),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    val accuracy = fix.accuracyMeters?.let { CoordinateFormat.accuracy(it) } ?: "accuracy unknown"
                    Text(
                        if (fresh) "$accuracy · updated ${ageSeconds}s ago"
                        else "GPS signal lost · last fix ${ageSeconds}s ago (older fixes are not used)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (fresh) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                    AddressPreview(address)
                }
            }
        }
    }
}

@Composable
private fun AddressPreview(address: AddressUi) {
    when (address) {
        AddressUi.Idle, AddressUi.Loading -> Text(
            "Looking up the address…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is AddressUi.Found -> Column {
            address.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            address.fullAddress?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        is AddressUi.Pending -> Text(
            "Address pending: ${address.reason}. Coordinates and time still work offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AddressUi.Unavailable -> Text(
            "No address found for this spot.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccuracyChip(accuracyMeters: Float?) {
    val color = when {
        accuracyMeters == null -> MaterialTheme.colorScheme.outline
        accuracyMeters <= 10f -> Color(0xFF2E7D32)
        accuracyMeters <= 30f -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.error
    }
    Text(
        text = accuracyMeters?.let { CoordinateFormat.accuracy(it) } ?: "± ?",
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .padding(start = 8.dp)
            .background(color, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
