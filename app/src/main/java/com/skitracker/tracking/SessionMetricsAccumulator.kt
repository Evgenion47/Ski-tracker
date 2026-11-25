package com.skitracker.tracking

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live snapshot of the in-progress session, shared between service and UI. */
data class SessionSnapshot(
    val isTracking: Boolean = false,
    val startTimeMillis: Long = 0L,
    val hasFix: Boolean = false,
    val isPaused: Boolean = false,
    val isOnLift: Boolean = false,
    val currentSpeedMs: Float = 0f,
    val topSpeedMs: Float = 0f,
    val avgSpeedMs: Float = 0f,
    val distanceMeters: Float = 0f,
    /** Time spent actually skiing; standstill pauses and lift rides are excluded. */
    val movingTimeMillis: Long = 0L,
    val elevationGainM: Float = 0f,
    val elevationLossM: Float = 0f,
) {
    /** Wall-clock session length, including pauses. */
    val durationMs: Long
        get() = if (isTracking) System.currentTimeMillis() - startTimeMillis else 0L
}

/** Process-level singleton: the service writes, the ViewModels read. */
object SessionStateHolder {
    private val _snapshot = MutableStateFlow(SessionSnapshot())
    val snapshot: StateFlow<SessionSnapshot> = _snapshot.asStateFlow()

    fun startSession() {
        _snapshot.value = SessionSnapshot(
            isTracking = true,
            startTimeMillis = System.currentTimeMillis(),
        )
    }

    fun update(transform: (SessionSnapshot) -> SessionSnapshot) {
        _snapshot.value = transform(_snapshot.value)
    }

    fun reset() {
        _snapshot.value = SessionSnapshot()
    }
}

/**
 * Accumulates metrics from raw location samples.
 *
 * Auto-pause: the session pauses when speed stays below [RESUME_SPEED_MS]
 * for [PAUSE_DELAY_MS]; it resumes on the first faster sample.
 *
 * Lift detection: sustained ascent (smoothed altitude climbing at least
 * [TrackSegments.ASCEND_STEP_M] per sample for [TrackSegments.MIN_LIFT_MS]
 * while gaining [TrackSegments.MIN_LIFT_GAIN_M]) marks the ride as a
 * chairlift. Lift rides are excluded from distance, moving time and
 * average speed — like a pause, but the "On lift" state is shown instead.
 *
 * Samples with accuracy worse than [MAX_ACCEPTABLE_ACCURACY_M] are dropped.
 */
class SessionMetricsAccumulator {

    private var lastLocation: Location? = null
    private var lastMovingTimeMillis = 0L
    private var movingTimeMillis = 0L
    private var topSpeedMs = 0f
    private var distanceMeters = 0f
    private var smoothedAltitudeM: Double? = null
    private var lastElevationDeltaM: Double? = null
    private var elevationGainM = 0f
    private var elevationLossM = 0f

    // Barometric altitude (far more accurate than GPS for gain/loss).
    private var smoothedBaroAltM: Double? = null
    private var baroOffsetM: Double? = null
    private var pendingBaroDeltaM = 0.0
    private var hasBarometric = false

    // Lift state machine.
    private var isOnLift = false
    private var liftCandidateMs = 0L
    private var liftCandidateStartAltM = 0.0

    val startTimeMillis: Long = System.currentTimeMillis()

    fun onLocation(location: Location): SessionSnapshot? {
        if (location.hasAccuracy() && location.accuracy > MAX_ACCEPTABLE_ACCURACY_M) {
            return null
        }

        val speedMs = computeSpeedMs(location)
        if (speedMs > topSpeedMs) topSpeedMs = speedMs

        val prev = lastLocation
        val dtMs = if (prev != null) location.time - prev.time else 0L

        updateElevation(location)
        val delta: Double? = if (hasBarometric) {
            val d = pendingBaroDeltaM
            pendingBaroDeltaM = 0.0
            d.takeIf { it != 0.0 }
        } else {
            lastElevationDeltaM
        }
        updateLiftState(location, dtMs, delta)

        // Lift rides don't count toward skied distance.
        if (prev != null && !isOnLift && dtMs in 1..MAX_GAP_MS) {
            distanceMeters += prev.distanceTo(location)
        }
        lastLocation = location

        if (speedMs >= RESUME_SPEED_MS && !isOnLift) {
            if (dtMs in 1..MAX_GAP_MS) movingTimeMillis += dtMs
            lastMovingTimeMillis = location.time
        }
        val isPaused = lastMovingTimeMillis != 0L &&
            location.time - lastMovingTimeMillis >= PAUSE_DELAY_MS

        val avgSpeedMs = if (movingTimeMillis > 0L) {
            distanceMeters / (movingTimeMillis / 1000f)
        } else 0f

        return SessionSnapshot(
            isTracking = true,
            startTimeMillis = startTimeMillis,
            hasFix = true,
            isPaused = isPaused,
            isOnLift = isOnLift,
            currentSpeedMs = speedMs,
            topSpeedMs = topSpeedMs,
            avgSpeedMs = avgSpeedMs.coerceAtLeast(0f),
            distanceMeters = distanceMeters,
            movingTimeMillis = movingTimeMillis,
            elevationGainM = elevationGainM,
            elevationLossM = elevationLossM,
        )
    }

    /**
     * Feeds barometric altitude (meters relative to the first reading).
     * Pressure sensors are smooth to sub-meter precision, so once
     * available they take over elevation gain/loss entirely; [lastElevationDeltaM]
     * switches to barometric deltas so lift detection benefits too.
     */
    fun onBarometricAltitude(relativeAltitudeM: Double) {
        hasBarometric = true
        val prev = smoothedBaroAltM
        smoothedBaroAltM = if (prev == null) {
            relativeAltitudeM
        } else {
            prev + (relativeAltitudeM - prev) * BARO_SMOOTHING
        }
        if (prev == null) return

        val delta = smoothedBaroAltM!! - prev
        pendingBaroDeltaM += delta
        lastElevationDeltaM = delta
        if (delta >= MIN_ELEVATION_STEP_M) elevationGainM += delta.toFloat()
        if (delta <= -MIN_ELEVATION_STEP_M) elevationLossM -= delta.toFloat()
    }

    /**
     * Best available absolute altitude for track-point storage: barometric
     * (offset-calibrated against GPS on the first fix) when present,
     * raw GPS otherwise.
     */
    fun currentAltitudeForStorage(gpsAltitudeM: Double): Double {
        val baro = smoothedBaroAltM
        return if (hasBarometric && baro != null) {
            baro + (baroOffsetM ?: 0.0)
        } else {
            gpsAltitudeM
        }
    }

    /**
     * Accumulates vertical gain/loss from altitude. GPS altitude is noisy,
     * so it is smoothed with an exponential moving average and steps below
     * [MIN_ELEVATION_STEP_M] are ignored; it is used only when no
     * barometric sensor is available. Samples with poor vertical accuracy
     * are skipped entirely.
     */
    private fun updateElevation(location: Location) {
        if (!location.hasAltitude()) return
        if (location.hasVerticalAccuracy() &&
            location.verticalAccuracyMeters > MAX_VERTICAL_ACCURACY_M
        ) {
            return
        }
        val altitude = location.altitude
        val prevSmoothed = smoothedAltitudeM
        smoothedAltitudeM = if (prevSmoothed == null) {
            altitude
        } else {
            prevSmoothed + (altitude - prevSmoothed) * ALTITUDE_SMOOTHING
        }

        // Anchor the barometric stream to absolute altitude once.
        val baro = smoothedBaroAltM
        if (hasBarometric && baro != null && baroOffsetM == null) {
            baroOffsetM = smoothedAltitudeM!! - baro
        }

        if (prevSmoothed == null || hasBarometric) return

        val delta = smoothedAltitudeM!! - prevSmoothed
        lastElevationDeltaM = delta
        if (delta >= MIN_ELEVATION_STEP_M) elevationGainM += delta.toFloat()
        if (delta <= -MIN_ELEVATION_STEP_M) elevationLossM -= delta.toFloat()
    }

    /**
     * Promotes a sustained ascent to "on lift". Exits only on a clearly
     * descending sample (hysteresis), so flat spots at the top station
     * don't flip-flop the state.
     */
    private fun updateLiftState(location: Location, dtMs: Long, delta: Double?) {
        if (delta == null) {
            isOnLift = false
            liftCandidateMs = 0L
            return
        }
        val smoothed = (if (hasBarometric) smoothedBaroAltM else smoothedAltitudeM)
            ?: smoothedBaroAltM ?: smoothedAltitudeM ?: return
        when {
            delta >= TrackSegments.ASCEND_STEP_M -> {
                if (liftCandidateMs == 0L) {
                    liftCandidateStartAltM = smoothed - delta
                }
                liftCandidateMs += dtMs.coerceIn(0L, MAX_GAP_MS)
                if (!isOnLift &&
                    liftCandidateMs >= TrackSegments.MIN_LIFT_MS &&
                    smoothed - liftCandidateStartAltM >= TrackSegments.MIN_LIFT_GAIN_M
                ) {
                    isOnLift = true
                }
            }
            delta <= -TrackSegments.DESCEND_STEP_M -> {
                isOnLift = false
                liftCandidateMs = 0L
            }
            // Flat: keep current state (neither promote nor reset).
        }
    }

    private fun computeSpeedMs(location: Location): Float {
        if (location.hasSpeed() && location.speed >= 0f) return location.speed
        val prev = lastLocation ?: return 0f
        val dtSec = (location.time - prev.time) / 1000f
        if (dtSec <= 0f) return 0f
        return prev.distanceTo(location) / dtSec
    }

    companion object {
        const val MAX_ACCEPTABLE_ACCURACY_M = 20f
        private const val MAX_GAP_MS = 15_000L

        /** Below this (m/s) you are considered standing still. */
        private const val RESUME_SPEED_MS = 1f

        /** Standstill must last this long before the session pauses. */
        private const val PAUSE_DELAY_MS = 5_000L

        /** EMA factor for altitude smoothing (0..1); lower = smoother. */
        private const val ALTITUDE_SMOOTHING = 0.25

        /** Altitude steps below this are treated as noise. */
        private const val MIN_ELEVATION_STEP_M = 2.0

        /** Samples with worse vertical accuracy are ignored for elevation. */
        private const val MAX_VERTICAL_ACCURACY_M = 25f

        /** EMA factor for barometric smoothing; the sensor runs ~5 Hz. */
        private const val BARO_SMOOTHING = 0.1
    }
}
