package com.lanrex.sitecam.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanrex.sitecam.data.db.ItemStatus
import com.lanrex.sitecam.data.db.StampItem
import com.lanrex.sitecam.ui.components.MediaThumbnail
import com.lanrex.sitecam.ui.map.PinPickerDialog
import java.text.DateFormat
import java.util.Date

@Composable
fun QueueScreen(viewModel: QueueViewModel, onBack: () -> Unit) {
    val active by viewModel.active.collectAsStateWithLifecycle()
    val needs by viewModel.needsLocation.collectAsStateWithLifecycle()
    val problems by viewModel.problems.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    /** Item ids waiting for a pin on the map, or null when the map is closed. */
    var pinFor by remember { mutableStateOf<List<Long>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stamping") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (active.isEmpty() && needs.isEmpty() && problems.isEmpty()) {
                item {
                    Text(
                        "Nothing waiting. Stamped copies are saved in your Pictures folder, in the SiteCam album.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }

            if (active.isNotEmpty()) {
                item { SectionTitle("Stamping now (${active.size})") }
                items(active, key = { "a${it.id}" }) { item ->
                    val p = progress?.takeIf { it.itemId == item.id }
                    ItemRow(item) {
                        if (item.status == ItemStatus.PROCESSING) {
                            if (p != null) {
                                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                        } else {
                            Text("Waiting…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (needs.isNotEmpty()) {
                item { SectionTitle("Needs a location (${needs.size})") }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.LocationOff, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "These files have no GPS saved in them. SiteCam never adds your current location " +
                                        "without asking — choose what to do:",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (needs.size > 1) {
                                LocationChoices(
                                    label = "For all ${needs.size}:",
                                    showSessionFix = needs.all { viewModel.sessionFixApplies(session, it) },
                                    busy = busy,
                                    onSkip = { viewModel.skip(needs.map { it.id }) },
                                    onSessionFix = { viewModel.useSessionFix(needs.map { it.id }) },
                                    onCurrent = { viewModel.useCurrentLocation(needs.map { it.id }) },
                                    onPin = { pinFor = needs.map { it.id } },
                                )
                            }
                        }
                    }
                }
                items(needs, key = { "n${it.id}" }) { item ->
                    ItemRow(item) {
                        LocationChoices(
                            label = null,
                            showSessionFix = viewModel.sessionFixApplies(session, item),
                            busy = busy,
                            onSkip = { viewModel.skip(listOf(item.id)) },
                            onSessionFix = { viewModel.useSessionFix(listOf(item.id)) },
                            onCurrent = { viewModel.useCurrentLocation(listOf(item.id)) },
                            onPin = { pinFor = listOf(item.id) },
                        )
                    }
                }
            }

            if (problems.isNotEmpty()) {
                item { SectionTitle("Not stamped (${problems.size})") }
                items(problems, key = { "p${it.id}" }) { item ->
                    ItemRow(item) {
                        Text(
                            item.message ?: if (item.status == ItemStatus.SKIPPED) "Skipped." else "Failed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.status == ItemStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(onClick = { viewModel.retry(item.id) }) { Text("Retry") }
                            TextButton(onClick = { viewModel.remove(item.id) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }

    pinFor?.let { ids ->
        PinPickerDialog(
            initialLatitude = session?.fixLatitude,
            initialLongitude = session?.fixLongitude,
            title = if (ids.size == 1) "Drop a pin" else "Drop a pin for ${ids.size} items",
            onDismiss = { pinFor = null },
            onPicked = { lat, lon ->
                viewModel.usePin(ids, lat, lon)
                pinFor = null
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ItemRow(item: StampItem, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            MediaThumbnail(
                uri = android.net.Uri.parse(item.sourceUri),
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                sizePx = 192,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.dateTakenMillis?.let {
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun LocationChoices(
    label: String?,
    showSessionFix: Boolean,
    busy: Boolean,
    onSkip: () -> Unit,
    onSessionFix: () -> Unit,
    onCurrent: () -> Unit,
    onPin: () -> Unit,
) {
    Column {
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showSessionFix) AssistChip(onClick = onSessionFix, label = { Text("Fix from Open Camera") })
            AssistChip(
                onClick = onCurrent,
                enabled = !busy,
                label = { Text(if (busy) "Getting GPS…" else "My current location") },
                leadingIcon = if (busy) {
                    { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
            )
            AssistChip(onClick = onPin, label = { Text("Drop a pin on the map") })
            AssistChip(onClick = onSkip, label = { Text("Skip") })
        }
    }
}
