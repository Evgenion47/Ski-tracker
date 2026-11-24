package com.skitracker.ui.track

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.skitracker.SkiTrackerApp
import com.skitracker.data.TrackPointEntity
import com.skitracker.tracking.GpxExporter
import com.skitracker.tracking.TrackSegments
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val StartColor = Color(0xFF2E7D32)
private val EndColor = Color(0xFFC62828)

@Composable
fun TrackScreen(
    sessionId: Long,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val app = context.applicationContext as SkiTrackerApp
    val scope = rememberCoroutineScope()

    val points by produceState<List<TrackPointEntity>?>(initialValue = null, sessionId) {
        value = app.sessionRepository.trackPoints(sessionId)
    }
    val runs = remember(points) { points?.let { TrackSegments.segment(it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            points == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…")
                }
            }

            runs.isNullOrEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No track recorded for this session.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                val runList = runs!!
                Text(
                    text = "${runList.size} run${if (runList.size > 1) "s" else ""} detected",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                TrackCanvas(
                    runs = runList,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LegendDot(color = StartColor, label = "Start")
                    LegendDot(color = EndColor, label = "End")
                }
                Button(
                    onClick = {
                        scope.launch { exportGpx(app, context, sessionId) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                ) {
                    Text("Export GPX")
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) {
            drawCircle(color, radius = size.minDimension / 2)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun TrackCanvas(runs: List<List<TrackPointEntity>>, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val all = runs.flatten()
        val minLat = all.minOf { it.lat }
        val maxLat = all.maxOf { it.lat }
        val minLng = all.minOf { it.lng }
        val maxLng = all.maxOf { it.lng }
        if (minLat == maxLat && minLng == maxLng) return@Canvas

        // Longitude degrees are shorter than latitude degrees by cos(lat);
        // correcting this keeps the track's proportions true.
        val midLat = (minLat + maxLat) / 2.0
        val lngScale = cos(Math.toRadians(midLat))
        val xRange = max((maxLng - minLng) * lngScale, 1e-9)
        val yRange = max(maxLat - minLat, 1e-9)
        val pad = 24f
        val scale = min(
            (size.width - 2 * pad) / xRange.toFloat(),
            (size.height - 2 * pad) / yRange.toFloat(),
        )

        fun project(p: TrackPointEntity): Offset = Offset(
            x = pad + ((p.lng - minLng) * lngScale).toFloat() * scale,
            y = pad + (maxLat - p.lat).toFloat() * scale,
        )

        for (run in runs) {
            if (run.size < 2) continue
            val path = Path()
            run.forEachIndexed { i, p ->
                val o = project(p)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, color = trackColor, style = Stroke(width = 5f))
        }

        val first = runs.first().first()
        val last = runs.last().last()
        drawCircle(StartColor, radius = 10f, center = project(first))
        drawCircle(EndColor, radius = 10f, center = project(last))
    }
}

private suspend fun exportGpx(
    app: SkiTrackerApp,
    context: Context,
    sessionId: Long,
) = withContext(Dispatchers.IO) {
    val session = app.sessionRepository.session(sessionId) ?: return@withContext
    val points = app.sessionRepository.trackPoints(sessionId)
    val runs = TrackSegments.segment(points)
    if (runs.isEmpty()) return@withContext

    val gpx = GpxExporter.export(session, runs)
    val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val outFile = File(outDir, "ski_session_$sessionId.gpx")
    outFile.writeText(gpx)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        outFile,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Export track").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
