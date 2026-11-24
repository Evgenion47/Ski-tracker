package com.skitracker.tracking

import com.skitracker.data.TrackPointEntity

/**
 * Splits a session's recorded points into individual runs.
 *
 * A break between runs is either:
 * - a "still run" — consecutive points below [STILL_SPEED_MS] lasting at
 *   least [MIN_STILL_MS] (lift lines, rests), or
 * - a "lift run" — sustained ascent (smoothed altitude climbing at least
 *   [ASCEND_STEP_M] per point for [MIN_LIFT_MS] while gaining
 *   [MIN_LIFT_GAIN_M]), i.e. a chairlift ride.
 *
 * Break points themselves are dropped, so each segment is one ski run
 * starting at the lift exit (or the top station for T-bars).
 */
object TrackSegments {

    internal const val STILL_SPEED_MS = 1f
    private const val MIN_STILL_MS = 4_000L
    private const val MIN_POINTS_PER_RUN = 3

    /** Smoothed climb per sample that counts as ascending. */
    internal const val ASCEND_STEP_M = 1.0

    /** Smoothed drop per sample that counts as clearly descending. */
    internal const val DESCEND_STEP_M = 0.5

    /** Ascent must last this long to qualify as a lift. */
    internal const val MIN_LIFT_MS = 10_000L

    /** Ascent must gain this much to qualify as a lift. */
    internal const val MIN_LIFT_GAIN_M = 20.0

    private const val ALTITUDE_SMOOTHING = 0.25

    fun segment(points: List<TrackPointEntity>): List<List<TrackPointEntity>> {
        if (points.size < MIN_POINTS_PER_RUN) return emptyList()

        val sorted = points.sortedBy { it.timeMillis }
        val n = sorted.size

        val still = BooleanArray(n) { sorted[it].speedMs < STILL_SPEED_MS }
        val smoothed = smoothAltitude(sorted)
        val ascending = BooleanArray(n)
        for (i in 1 until n) {
            val altValid = sorted[i].altitudeM > 0.0 && sorted[i - 1].altitudeM > 0.0
            ascending[i] = altValid &&
                smoothed[i] - smoothed[i - 1] >= ASCEND_STEP_M
        }

        val runs = mutableListOf<List<TrackPointEntity>>()
        var current = mutableListOf<TrackPointEntity>()

        fun flush() {
            if (current.size >= MIN_POINTS_PER_RUN) runs.add(current)
            current = mutableListOf()
        }

        var i = 0
        while (i < n) {
            when {
                still[i] -> {
                    var j = i
                    while (j < n && still[j]) j++
                    if (sorted[j - 1].timeMillis - sorted[i].timeMillis >= MIN_STILL_MS) {
                        flush()
                    } else {
                        for (k in i until j) current.add(sorted[k])
                    }
                    i = j
                }

                ascending[i] -> {
                    var j = i
                    while (j < n && ascending[j]) j++
                    val duration = sorted[j - 1].timeMillis - sorted[i].timeMillis
                    val gain = smoothed[j - 1] - smoothed[i]
                    if (duration >= MIN_LIFT_MS && gain >= MIN_LIFT_GAIN_M) {
                        flush()
                    } else {
                        for (k in i until j) current.add(sorted[k])
                    }
                    i = j
                }

                else -> {
                    current.add(sorted[i])
                    i++
                }
            }
        }
        flush()
        return runs
    }

    private fun smoothAltitude(points: List<TrackPointEntity>): DoubleArray {
        val out = DoubleArray(points.size)
        var smoothed = 0.0
        for (i in points.indices) {
            val alt = points[i].altitudeM
            smoothed = when {
                i == 0 -> alt
                alt <= 0.0 -> smoothed // invalid reading: keep previous
                else -> smoothed + (alt - smoothed) * ALTITUDE_SMOOTHING
            }
            out[i] = smoothed
        }
        return out
    }
}
