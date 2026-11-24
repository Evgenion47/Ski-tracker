package com.skitracker.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpeedUnit(val label: String) {
    KMH("km/h"),
    MPH("mph");

    fun speedFromMs(ms: Float): Float = when (this) {
        KMH -> ms * 3.6f
        MPH -> ms * 2.23694f
    }

    fun distanceFromMeters(meters: Float): Float = when (this) {
        KMH -> meters / 1000f
        MPH -> meters / 1609.344f
    }
}

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _speedUnit = MutableStateFlow(readUnit())
    val speedUnit: StateFlow<SpeedUnit> = _speedUnit.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (KEY_UNIT == key) _speedUnit.value = readUnit()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setSpeedUnit(unit: SpeedUnit) {
        prefs.edit().putString(KEY_UNIT, unit.name).apply()
    }

    private fun readUnit(): SpeedUnit =
        prefs.getString(KEY_UNIT, SpeedUnit.KMH.name)
            ?.let { runCatching { SpeedUnit.valueOf(it) }.getOrNull() }
            ?: SpeedUnit.KMH

    private companion object {
        const val KEY_UNIT = "speed_unit"
    }
}
