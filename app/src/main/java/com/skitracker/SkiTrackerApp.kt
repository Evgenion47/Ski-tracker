package com.skitracker

import android.app.Application
import androidx.room.Room
import com.skitracker.data.AppDatabase
import com.skitracker.data.SessionRepository
import com.skitracker.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual DI container. The app is small enough that a hand-written
 * container is less code than Hilt/Koin setup.
 */
class SkiTrackerApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "ski_tracker.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
    }

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(database.sessionDao(), database.trackPointDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Clean up sessions that were left incomplete by an app kill.
        appScope.launch { sessionRepository.deleteIncomplete() }
    }
}
