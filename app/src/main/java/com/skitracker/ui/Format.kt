package com.skitracker.ui

import com.skitracker.settings.SpeedUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatSpeed(ms: Float, unit: SpeedUnit): String =
    "%.0f".format(unit.speedFromMs(ms))

fun formatDistance(meters: Float, unit: SpeedUnit): String = when {
    meters < 1000f -> "%.0f m".format(meters)
    unit == SpeedUnit.KMH -> "%.2f km".format(meters / 1000f)
    else -> "%.2f mi".format(meters / 1609.344f)
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Vertical gain/loss in meters, e.g. "+456 / −123 m". */
fun formatElevation(gainM: Float, lossM: Float): String =
    if (gainM < 1f && lossM < 1f) "--"
    else "+${gainM.toInt()} / −${lossM.toInt()} m"

fun formatDateTime(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(millis))
