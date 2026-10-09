package com.lanrex.sitecam.ui.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanrex.sitecam.media.GalleryFilter
import com.lanrex.sitecam.media.MediaEntry
import com.lanrex.sitecam.ui.components.MediaThumbnail
import com.lanrex.sitecam.ui.permissions.AppPermissions

@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onQueued: (String) -> Unit,
) {
    val context = LocalContext.current
    val items by viewModel.items.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val stamped by viewModel.stampedIds.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.reload() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.reload()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) "Stamp from gallery" else "${selected.size} selected") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
                    }
                },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = { viewModel.stampSelected(onQueued) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp),
                    ) {
                        Text("Stamp ${selected.size} item${if (selected.size == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter == GalleryFilter.CAMERA,
                    onClick = { viewModel.setFilter(GalleryFilter.CAMERA) },
                    label = { Text("Camera") },
                )
                FilterChip(
                    selected = filter == GalleryFilter.ALL,
                    onClick = { viewModel.setFilter(GalleryFilter.ALL) },
                    label = { Text("All photos & videos") },
                )
            }

            if (!permissions.mediaFull) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (permissions.mediaPartial) "You only allowed some photos" else "Photo access needed",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (permissions.mediaPartial) {
                                "SiteCam can only see the photos you picked. Choose \"Allow all\" to see everything."
                            } else {
                                "Allow access to photos and videos so SiteCam can show them here."
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { mediaLauncher.launch(AppPermissions.mediaRequest()) }) { Text("Allow all") }
                            TextButton(onClick = { AppPermissions.openAppSettings(context) }) { Text("App settings") }
                        }
                    }
                }
            }

            if (loading && items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (items.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (filter == GalleryFilter.CAMERA) "No camera photos or videos found." else "No photos or videos found.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.sourceKey }) { entry ->
                        GalleryCell(
                            entry = entry,
                            selected = entry.id in selected,
                            stamped = entry.id in stamped,
                            onClick = { viewModel.toggle(entry.id) },
                        )
                    }
                    if (viewModel.canLoadMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = viewModel::loadMore, modifier = Modifier.padding(16.dp)) { Text("Show more") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryCell(entry: MediaEntry, selected: Boolean, stamped: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
    ) {
        MediaThumbnail(entry.uri, Modifier.fillMaxSize())
        if (entry.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(14.dp))
                entry.durationMillis?.let {
                    Text(formatDuration(it), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (stamped) {
            Icon(
                Icons.Filled.Verified,
                contentDescription = "Already stamped",
                tint = Color(0xFF66BB6A),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(Color(0x99000000), RoundedCornerShape(10.dp)),
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(Color.White, RoundedCornerShape(12.dp)),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
