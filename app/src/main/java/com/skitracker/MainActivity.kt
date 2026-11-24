package com.skitracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.skitracker.settings.SpeedUnit
import com.skitracker.tracking.SessionStateHolder
import com.skitracker.ui.history.HistoryScreen
import com.skitracker.ui.session.SessionScreen
import com.skitracker.ui.theme.SkiTrackerTheme
import com.skitracker.ui.track.TrackScreen

object Routes {
    const val SESSION = "session"
    const val HISTORY = "history"
    const val TRACK = "track/{sessionId}"

    fun track(sessionId: Long) = "track/$sessionId"
}

class MainActivity : ComponentActivity() {

    private val app: SkiTrackerApp get() = application as SkiTrackerApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SkiTrackerTheme {
                App(
                    speedUnit = app.settingsRepository.speedUnit.collectAsState().value,
                    onToggleUnits = {
                        val next = if (it == SpeedUnit.KMH) SpeedUnit.MPH else SpeedUnit.KMH
                        app.settingsRepository.setSpeedUnit(next)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(
    speedUnit: SpeedUnit,
    onToggleUnits: (SpeedUnit) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Keep the screen on while a session is recording, whichever screen is shown.
    val isTracking = SessionStateHolder.snapshot.collectAsState().value.isTracking
    val view = LocalView.current
    DisposableEffect(isTracking) {
        view.keepScreenOn = isTracking
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentRoute) {
                            Routes.HISTORY -> "History"
                            Routes.TRACK -> "Track"
                            else -> "Ski Tracker"
                        },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = { onToggleUnits(speedUnit) }) {
                        Text(
                            text = speedUnit.label,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (currentRoute == Routes.HISTORY || currentRoute == Routes.TRACK) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close history")
                        }
                    } else {
                        IconButton(onClick = { navController.navigate(Routes.HISTORY) }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "History")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SESSION,
        ) {
            composable(Routes.SESSION) {
                SessionScreen(
                    speedUnit = speedUnit,
                    contentPadding = innerPadding,
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    speedUnit = speedUnit,
                    contentPadding = innerPadding,
                    onOpenTrack = { sessionId -> navController.navigate(Routes.track(sessionId)) },
                )
            }
            composable(
                route = Routes.TRACK,
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
            ) { entry ->
                TrackScreen(
                    sessionId = entry.arguments?.getLong("sessionId") ?: 0L,
                    contentPadding = innerPadding,
                )
            }
        }
    }
}
