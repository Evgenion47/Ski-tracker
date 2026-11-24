package com.skitracker.ui.session

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skitracker.settings.SpeedUnit
import com.skitracker.ui.formatDistance
import com.skitracker.ui.formatDuration
import com.skitracker.ui.formatElevation
import com.skitracker.ui.formatSpeed

@Composable
fun SessionScreen(
    speedUnit: SpeedUnit,
    contentPadding: PaddingValues,
    viewModel: SessionViewModel = viewModel(),
) {
    val context = LocalContext.current
    val activity = context as Activity
    val snapshot by viewModel.snapshot.collectAsState()
    val isTracking = snapshot.isTracking
    // Duration shown to the user is moving time: auto-pause freezes it.
    // It advances via location-sample emissions (~1/s while moving).
    val durationMs = if (isTracking) snapshot.movingTimeMillis else 0L

    var gpsEnabled by remember { mutableStateOf(isGpsEnabled(context)) }
    var showRationale by remember { mutableStateOf(false) }
    var showPermanentlyDenied by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) gpsEnabled = isGpsEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        when {
            fineGranted -> viewModel.startSession()
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.ACCESS_FINE_LOCATION,
            ) -> showRationale = true
            else -> showPermanentlyDenied = true
        }
    }

    fun onStartClicked() {
        when {
            !hasFineLocation(context) -> permissionLauncher.launch(permissionsToRequest())
            else -> viewModel.startSession()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(8.dp))

        if (isTracking) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatSpeed(snapshot.currentSpeedMs, speedUnit),
                    style = MaterialTheme.typography.displayLarge,
                )
                Text(
                    text = speedUnit.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!snapshot.hasFix) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Waiting for GPS fix…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (snapshot.isOnLift) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "On lift",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else if (snapshot.isPaused) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Paused",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        } else {
            Text(
                text = "Ready to ski",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
        }

        ReadoutGrid(
            isTracking = isTracking,
            topSpeedMs = snapshot.topSpeedMs,
            avgSpeedMs = snapshot.avgSpeedMs,
            durationMs = durationMs,
            distanceMeters = snapshot.distanceMeters,
            elevationGainM = snapshot.elevationGainM,
            elevationLossM = snapshot.elevationLossM,
            speedUnit = speedUnit,
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!gpsEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = "Location is turned off. Enable it to track speed.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }) { Text("Enable") }
                    }
                }
            }

            Button(
                onClick = { if (isTracking) viewModel.stopSession() else onStartClicked() },
                enabled = gpsEnabled || isTracking,
                modifier = Modifier
                    .widthIn(min = 200.dp)
                    .height(56.dp),
                colors = if (isTracking) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    )
                } else ButtonDefaults.buttonColors(),
            ) {
                Icon(
                    imageVector = if (isTracking) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isTracking) "Stop session" else "Start session")
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("Location permission needed") },
            text = { Text("Ski Tracker measures your speed using GPS. Location permission is required while a session is active.") },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    permissionLauncher.launch(permissionsToRequest())
                }) { Text("Grant") }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text("Cancel") }
            },
        )
    }

    if (showPermanentlyDenied) {
        AlertDialog(
            onDismissRequest = { showPermanentlyDenied = false },
            title = { Text("Permission blocked") },
            text = { Text("Location permission was permanently denied. Enable it in app settings to record sessions.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermanentlyDenied = false
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                    context.startActivity(intent)
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentlyDenied = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ReadoutGrid(
    isTracking: Boolean,
    topSpeedMs: Float,
    avgSpeedMs: Float,
    durationMs: Long,
    distanceMeters: Float,
    elevationGainM: Float,
    elevationLossM: Float,
    speedUnit: SpeedUnit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Readout(
                label = "Top speed",
                value = if (isTracking) formatSpeed(topSpeedMs, speedUnit) else "--",
                unit = if (isTracking) speedUnit.label else "",
                modifier = Modifier.weight(1f),
            )
            Readout(
                label = "Average",
                value = if (isTracking) formatSpeed(avgSpeedMs, speedUnit) else "--",
                unit = if (isTracking) speedUnit.label else "",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Readout(
                label = "Duration",
                value = if (isTracking) formatDuration(durationMs) else "--:--",
                unit = "",
                modifier = Modifier.weight(1f),
            )
            Readout(
                label = "Distance",
                value = if (isTracking) formatDistance(distanceMeters, speedUnit) else "--",
                unit = "",
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Readout(
                label = "Elevation",
                value = if (isTracking) formatElevation(elevationGainM, elevationLossM) else "--",
                unit = "",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Readout(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = value, style = MaterialTheme.typography.headlineSmall)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun permissionsToRequest(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

private fun isGpsEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
}

