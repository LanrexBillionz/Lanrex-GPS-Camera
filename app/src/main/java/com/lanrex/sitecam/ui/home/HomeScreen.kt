package com.lanrex.sitecam.ui.home

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanrex.sitecam.camera.CameraLauncher
import com.lanrex.sitecam.core.format.CoordinateFormat
import com.lanrex.sitecam.location.GpsFix
import com.lanrex.sitecam.ui.permissions.AppPermissions
import com.lanrex.sitecam.ui.permissions.PermissionsCard
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val locationEnabled by viewModel.locationEnabled.collectAsStateWithLifecycle()
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val address by viewModel.address.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SiteCam", fontWeight = FontWeight.Bold) }) },
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
                Button(
                    onClick = {
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
            }
        }
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
