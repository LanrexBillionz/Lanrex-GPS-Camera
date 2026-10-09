package com.lanrex.sitecam.ui.settings

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lanrex.sitecam.BuildConfig
import com.lanrex.sitecam.data.MapType
import com.lanrex.sitecam.data.StampSettings
import com.lanrex.sitecam.ui.permissions.AppPermissions
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                preview?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Stamp preview",
                        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
            item { StampLinesCard(s, viewModel) }
            item { NoteCard(s, viewModel) }
            item { AlbumCard(s, viewModel) }
            item { MapCard(s, viewModel) }
            item { BackgroundCard() }
            item { DiagnosticsCard(viewModel) }
            item {
                Text(
                    "SiteCam ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StampLinesCard(s: StampSettings, vm: SettingsViewModel) {
    SettingsCard("Stamp lines") {
        SwitchRow("City, state, country + flag", "Line 1, large and bold", s.showTitle) { v -> vm.update { it.copy(showTitle = v) } }
        SwitchRow("Full address", "Line 2", s.showAddress) { v -> vm.update { it.copy(showAddress = v) } }
        SwitchRow("Latitude and longitude", "Line 3, six decimal places", s.showCoordinates) { v -> vm.update { it.copy(showCoordinates = v) } }
        SwitchRow("Date and time", "Line 4, with the time zone", s.showTime) { v -> vm.update { it.copy(showTime = v) } }
        SwitchRow("Map thumbnail", "Satellite map with a red pin", s.showMap) { v -> vm.update { it.copy(showMap = v) } }
    }
}

@Composable
private fun NoteCard(s: StampSettings, vm: SettingsViewModel) {
    var text by rememberSaveable { mutableStateOf(s.noteText) }
    LaunchedEffect(text) {
        delay(500)
        if (text != s.noteText) vm.update { it.copy(noteText = text) }
    }
    SettingsCard("Extra line") {
        SwitchRow("Show a note on every stamp", "For example a project name", s.noteEnabled) { v -> vm.update { it.copy(noteEnabled = v) } }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.replace('\n', ' ').take(120) },
            label = { Text("Project name or note") },
            singleLine = true,
            enabled = s.noteEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AlbumCard(s: StampSettings, vm: SettingsViewModel) {
    var album by rememberSaveable { mutableStateOf(s.albumName) }
    SettingsCard("Album") {
        Text(
            "Stamped copies are saved in Pictures/${s.albumName}. Originals are never changed.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = album,
            onValueChange = { album = it.take(60) },
            label = { Text("Album name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.update { it.copy(albumName = StampSettings.sanitizeAlbum(album)) } },
            enabled = StampSettings.sanitizeAlbum(album) != s.albumName,
        ) { Text("Save album name") }
    }
}

@Composable
private fun MapCard(s: StampSettings, vm: SettingsViewModel) {
    var key by rememberSaveable { mutableStateOf(s.mapApiKey) }
    var visible by remember { mutableStateOf(false) }
    val keyTest by vm.keyTest.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val lastError by vm.lastMapError.collectAsStateWithLifecycle()

    SettingsCard("Satellite map") {
        Text(
            "Maps come from Google Maps Static API using your own key. The key is stored on this phone only. " +
                "Without a key (or offline) a plain map tile with the pin is drawn instead.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("Google Maps API key") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Show key")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.update { it.copy(mapApiKey = key) } }, enabled = key != s.mapApiKey) { Text("Save key") }
            OutlinedButton(onClick = { vm.testKey(key) }, enabled = key.isNotBlank() && !testing) { Text("Test key") }
            if (testing) CircularProgressIndicator(Modifier.padding(start = 4.dp))
        }
        keyTest?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (keyTest == null && lastError != null && s.mapApiKey.isNotBlank()) {
            Text("Last map error: $lastError", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        HorizontalDivider()
        Text("Map type", style = MaterialTheme.typography.titleSmall)
        MapType.entries.forEach { type ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = s.mapType == type, role = Role.RadioButton, onClick = { vm.update { it.copy(mapType = type) } }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.mapType == type, onClick = null)
                Text(type.label, modifier = Modifier.padding(start = 8.dp))
            }
        }
        HorizontalDivider()
        Text(
            "How to get a key: on console.cloud.google.com create a project, enable \"Maps Static API\", " +
                "then APIs & Services → Credentials → Create credentials → API key. Restrict the key to " +
                "\"Maps Static API\". Google gives a free monthly allowance; billing must be enabled on the project.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackgroundCard() {
    val context = LocalContext.current
    val unrestricted = AppPermissions.isBatteryUnrestricted(context)
    SettingsCard("Background and permissions") {
        Text(
            if (unrestricted) "Battery usage: Unrestricted ✓" else "Battery usage: Optimized. Site Mode may be stopped by Samsung.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!unrestricted) {
                OutlinedButton(onClick = { AppPermissions.requestBatteryUnrestricted(context) }) { Text("Set Unrestricted") }
            }
            OutlinedButton(onClick = { AppPermissions.openAppSettings(context) }) { Text("App permissions") }
        }
    }
}

@Composable
private fun DiagnosticsCard(vm: SettingsViewModel) {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(vm.crashReport()) }
    var problems by remember { mutableStateOf(vm.problemLog()) }
    SettingsCard("Problem reports") {
        if (crash == null && problems == null) {
            Text("No problems recorded.", style = MaterialTheme.typography.bodyMedium)
        }
        crash?.let { report ->
            Text("The app crashed recently.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { share(context, "SiteCam crash report", report) }) { Text("Share report") }
                TextButton(onClick = {
                    vm.clearCrashReport()
                    crash = null
                }) { Text("Clear") }
            }
        }
        problems?.let { log ->
            Text("Some files could not be stamped.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { share(context, "SiteCam problem log", log) }) { Text("Share log") }
                TextButton(onClick = {
                    vm.clearProblemLog()
                    problems = null
                }) { Text("Clear") }
            }
        }
    }
}

private fun share(context: android.content.Context, subject: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text.takeLast(60_000))
    context.startActivity(Intent.createChooser(intent, subject))
}
