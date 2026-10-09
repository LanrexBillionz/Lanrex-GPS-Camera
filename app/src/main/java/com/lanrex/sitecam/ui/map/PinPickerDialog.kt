package com.lanrex.sitecam.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lanrex.sitecam.appContainer
import com.lanrex.sitecam.core.format.CoordinateFormat
import kotlinx.coroutines.launch

/**
 * Full-screen map: drag the map so the pin sits on the right spot, then confirm.
 */
@Composable
fun PinPickerDialog(
    initialLatitude: Double?,
    initialLongitude: Double?,
    title: String,
    onDismiss: () -> Unit,
    onPicked: (latitude: Double, longitude: Double) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasStart = initialLatitude != null && initialLongitude != null
    val state = remember {
        MapViewState(initialLatitude ?: 9.08, initialLongitude ?: 8.68, if (hasStart) 17f else 5f)
    }
    var layer by remember { mutableStateOf(MapLayer.SATELLITE) }
    var locating by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                SlippyMap(state = state, layer = layer, modifier = Modifier.fillMaxSize())

                // Fixed pin in the middle; its tip marks the chosen spot.
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    tint = Color(0xFFEA4335),
                    modifier = Modifier.align(Alignment.Center).offset(y = (-20).dp).size(44.dp),
                )

                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(8.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.Bold)
                            Text("Drag the map so the pin is on the spot", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MapLayer.entries.forEach { l ->
                            FilterChip(selected = layer == l, onClick = { layer = l }, label = { Text(l.label) })
                        }
                    }
                }

                Column(
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(onClick = { state.zoom = (state.zoom + 1f).coerceAtMost(19f) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Zoom in")
                    }
                    FilledTonalIconButton(onClick = { state.zoom = (state.zoom - 1f).coerceAtLeast(2f) }) {
                        Icon(Icons.Filled.Remove, contentDescription = "Zoom out")
                    }
                    FilledTonalIconButton(
                        onClick = {
                            if (!locating) {
                                locating = true
                                scope.launch {
                                    val fix = context.appContainer.locationRepository.currentFix(10_000)
                                    if (fix != null) state.moveTo(fix.latitude, fix.longitude, maxOf(state.zoom, 17f))
                                    locating = false
                                }
                            }
                        },
                    ) { Icon(Icons.Filled.MyLocation, contentDescription = "My location") }
                }

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(12.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        CoordinateFormat.stampLine(state.latitude, state.longitude),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(layer.attribution, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = { onPicked(state.latitude, state.longitude) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = CoordinateFormat.isValid(state.latitude, state.longitude),
                    ) { Text("Use this spot") }
                }
            }
        }
    }
}
