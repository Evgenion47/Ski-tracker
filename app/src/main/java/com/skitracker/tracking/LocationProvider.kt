package com.skitracker.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Thin wrapper over FusedLocationProviderClient so the service
 * doesn't talk to Play Services directly.
 */
class LocationProvider(context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    fun startUpdates(callback: LocationCallback) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stopUpdates(callback: LocationCallback) {
        client.removeLocationUpdates(callback)
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val MIN_UPDATE_INTERVAL_MS = 500L
    }
}
