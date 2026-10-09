package com.lanrex.sitecam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lanrex.sitecam.ui.home.HomeScreen
import com.lanrex.sitecam.ui.home.HomeViewModel
import com.lanrex.sitecam.ui.theme.SiteCamTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SiteCamTheme {
                SiteCamNavHost()
            }
        }
    }
}

@Composable
private fun SiteCamNavHost() {
    val container = LocalContext.current.appContainer
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            val vm: HomeViewModel = viewModel { HomeViewModel(container) }
            HomeScreen(vm)
        }
    }
}
