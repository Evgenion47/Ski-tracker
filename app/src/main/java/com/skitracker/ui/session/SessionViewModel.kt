package com.skitracker.ui.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.skitracker.SkiTrackerApp
import com.skitracker.settings.SpeedUnit
import com.skitracker.tracking.SessionSnapshot
import com.skitracker.tracking.SessionStateHolder
import com.skitracker.tracking.TrackingService
import kotlinx.coroutines.flow.StateFlow

class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SkiTrackerApp

    val snapshot: StateFlow<SessionSnapshot> = SessionStateHolder.snapshot

    val speedUnit: StateFlow<SpeedUnit> = app.settingsRepository.speedUnit

    fun startSession() {
        val context = getApplication<Application>()
        context.startForegroundService(TrackingService.startIntent(context))
    }

    fun stopSession() {
        val context = getApplication<Application>()
        context.startService(TrackingService.stopIntent(context))
    }
}
