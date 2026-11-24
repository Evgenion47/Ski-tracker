package com.skitracker.data

import com.skitracker.tracking.SessionSnapshot
import kotlinx.coroutines.flow.Flow

class SessionRepository(
    private val dao: SessionDao,
    private val trackPointDao: TrackPointDao,
) {

    val sessions: Flow<List<SessionEntity>> = dao.observeAll()

    suspend fun trackPoints(sessionId: Long): List<TrackPointEntity> =
        trackPointDao.forSession(sessionId)

    suspend fun session(id: Long): SessionEntity? = dao.getById(id)

    /**
     * Creates the session row up front so track points can reference it
     * while recording; completed on [completeSession].
     */
    suspend fun beginSession(): Long = dao.insert(
        SessionEntity(
            startTimeMillis = System.currentTimeMillis(),
            endTimeMillis = 0L,
            topSpeedMs = 0f,
            avgSpeedMs = 0f,
            distanceMeters = 0f,
            durationMs = 0L,
        ),
    )

    suspend fun completeSession(id: Long, snapshot: SessionSnapshot) = dao.update(
        SessionEntity(
            id = id,
            startTimeMillis = snapshot.startTimeMillis,
            endTimeMillis = System.currentTimeMillis(),
            topSpeedMs = snapshot.topSpeedMs,
            avgSpeedMs = snapshot.avgSpeedMs,
            distanceMeters = snapshot.distanceMeters,
            durationMs = snapshot.movingTimeMillis,
            elevationGainM = snapshot.elevationGainM,
            elevationLossM = snapshot.elevationLossM,
        ),
    )

    suspend fun saveTrackPoints(points: List<TrackPointEntity>) =
        trackPointDao.insertAll(points)

    suspend fun delete(id: Long) {
        trackPointDao.deleteForSession(id)
        dao.deleteById(id)
    }

    /** Removes sessions left incomplete by an app kill, with their points. */
    suspend fun deleteIncomplete() {
        val ids = dao.incompleteIds()
        if (ids.isEmpty()) return
        ids.forEach { trackPointDao.deleteForSession(it) }
        dao.deleteByIds(ids)
    }
}
