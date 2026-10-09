package com.lanrex.sitecam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lanrex.sitecam.ui.gallery.GalleryScreen
import com.lanrex.sitecam.ui.gallery.GalleryViewModel
import com.lanrex.sitecam.ui.home.HomeScreen
import com.lanrex.sitecam.ui.home.HomeViewModel
import com.lanrex.sitecam.ui.queue.QueueScreen
import com.lanrex.sitecam.ui.queue.QueueViewModel
import com.lanrex.sitecam.ui.settings.SettingsScreen
import com.lanrex.sitecam.ui.settings.SettingsViewModel
import com.lanrex.sitecam.ui.theme.SiteCamTheme
import com.lanrex.sitecam.util.Network
import com.lanrex.sitecam.work.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Screen to show next, requested by a notification or a share. */
    private val pendingDestination = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            SiteCamTheme {
                SiteCamNavHost(pendingDestination)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val container = appContainer
        lifecycleScope.launch {
            // Back from the camera: stamp everything it saved since Open Camera was tapped.
            val added = container.cameraSessionManager.onReturn()
            if (added > 0) {
                val what = if (added == 1) "1 new photo or video" else "$added new photos and videos"
                Toast.makeText(this@MainActivity, "Found $what from the camera. Stamping now.", Toast.LENGTH_LONG).show()
            }
            container.siteModeController.ensureRunning()
            // Copies stamped offline: add their address now if the phone is online.
            if (Network.isOnline(this@MainActivity) && container.stampProcessor.pendingRestampCount() > 0) {
                container.workScheduler.restampNow()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> importShared(intent)
            else -> intent.getStringExtra(Notifier.EXTRA_OPEN)?.let { pendingDestination.value = it }
        }
    }

    /** Photos/videos shared from Samsung Gallery (or any app) are added to the stamping queue. */
    private fun importShared(intent: Intent) {
        val uris = sharedUris(intent)
        if (uris.isEmpty()) return
        pendingDestination.value = Notifier.DEST_QUEUE
        lifecycleScope.launch {
            val summary = appContainer.sharedMediaImporter.import(uris)
            Toast.makeText(this@MainActivity, summary.message(), Toast.LENGTH_LONG).show()
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val uris = ArrayList<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
        }
        return uris.distinct()
    }
}

@Composable
private fun SiteCamNavHost(pendingDestination: MutableStateFlow<String?>) {
    val context = LocalContext.current
    val container = context.appContainer
    val navController = rememberNavController()
    val pending by pendingDestination.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel { HomeViewModel(container) }
            HomeScreen(
                viewModel = vm,
                onOpenGallery = { navController.navigate(Routes.GALLERY) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenQueue = { navController.navigate(Routes.QUEUE) { launchSingleTop = true } },
            )
        }
        composable(Routes.GALLERY) {
            val vm: GalleryViewModel = viewModel { GalleryViewModel(container) }
            GalleryScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onQueued = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    navController.navigate(Routes.QUEUE) {
                        popUpTo(Routes.HOME)
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.QUEUE) {
            val vm: QueueViewModel = viewModel { QueueViewModel(container) }
            QueueScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
            SettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }

    LaunchedEffect(pending) {
        when (pending) {
            Notifier.DEST_QUEUE -> navController.navigate(Routes.QUEUE) {
                popUpTo(Routes.HOME)
                launchSingleTop = true
            }
            Notifier.DEST_HOME -> navController.popBackStack(Routes.HOME, inclusive = false)
        }
        if (pending != null) pendingDestination.value = null
    }
}

private object Routes {
    const val HOME = "home"
    const val GALLERY = "gallery"
    const val QUEUE = "queue"
    const val SETTINGS = "settings"
}
