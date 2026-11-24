package com.skitracker.tracking

import com.skitracker.data.SessionEntity
import com.skitracker.data.TrackPointEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Serializes sessions as GPX 1.1 with one <trk> per run. */
object GpxExporter {

    fun export(session: SessionEntity, runs: List<List<TrackPointEntity>>): String {
        val gpx = StringBuilder()
        gpx.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        gpx.append('\n')
        gpx.append(
            """<gpx version="1.1" creator="Ski Tracker" xmlns="http://www.topografix.com/GPX/1/1">""",
        )
        gpx.append('\n')
        gpx.append("  <metadata><time>").append(isoTime(session.startTimeMillis)).append("</time></metadata>\n")

        runs.forEachIndexed { index, run ->
            gpx.append("  <trk><name>Run ").append(index + 1).append("</name><trkseg>\n")
            for (p in run) {
                gpx.append("    <trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lng)
                    .append("\"><ele>").append(p.altitudeM.toInt())
                    .append("</ele><time>").append(isoTime(p.timeMillis))
                    .append("</time></trkpt>\n")
            }
            gpx.append("  </trkseg></trk>\n")
        }

        gpx.append("</gpx>\n")
        return gpx.toString()
    }

    private val isoFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun isoTime(millis: Long): String = isoFormat.format(Date(millis))
}
