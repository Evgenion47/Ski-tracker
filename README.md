# Ski Tracker

Minimal offline ski speed tracker for Android 8.0+ (API 26). No network
permissions, no ads, no accounts, no cloud.

## Build

Requires the Android SDK (platform `android-35`). JDK 17+ required to run
Gradle — the simplest option on macOS is the JBR bundled with Android Studio:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew build
```

Or open the project in Android Studio and press **Run**.

Release builds are signed automatically via `keystore.properties` +
`ski-tracker.keystore` in the project root (both gitignored; regenerate with
`keytool -genkeypair` if lost). Without them, `assembleRelease` falls back to
an unsigned APK, which Android refuses to install.

## Features

- **Current session** — large live speed readout plus top speed, average
  speed, duration, and distance. Start/stop controls.
- **Foreground tracking** — a location foreground service records GPS at
  1-second intervals while a session is active, with a persistent
  notification. Works with the screen locked; keep-screen-on applies while
  you're watching the display.
- **Auto pause/resume** — the session pauses automatically after 5 seconds
  below ~3.6 km/h (standing in lines, breaks) and resumes the moment you
  move again. Paused time is excluded from duration and average speed.
- **Lift detection** — sustained ascent (≥10 s and ≥20 m of smoothed climb)
  marks a chairlift ride: it shows "On lift" and is excluded from distance,
  moving time, and average speed, like a pause. Exported runs start at the
  top station, not the lift exit. (Uphill hikes are also classified as
  lifts.)
- **Elevation** — vertical gain/loss in meters, smoothed to filter GPS
  altitude noise, shown live and stored with each session in history.
- **Track & export** — every accepted GPS sample is stored with the
  session. Tap any history entry to see the day's track drawn on a canvas
  (start/end marked, no base map — fully offline). "Export GPX" shares a
  GPX file where each detected run (split at standstill breaks) is its own
  track, importable into Organic Maps or any GPX viewer.
- **History** — every completed session is stored locally in Room and listed
  newest-first; each entry can be deleted.
- **Units** — km/h ↔ mph toggle in the top bar, applied to all speed and
  distance readouts instantly.
- Keeps the screen on during an active session; respects system light/dark
  theme (Material 3).

## Implementation notes

- **Single Activity + Compose Navigation**, MVVM with `ViewModel`s.
- **Manual DI** — a hand-written container in `SkiTrackerApp` (database,
  repository, settings). Less code than Hilt/Koin at this size.
- **Room**, one table (`sessions`): id, start/end time, top speed, average
  speed, distance, duration.
- **Location**: `FusedLocationProviderClient` with
  `PRIORITY_HIGH_ACCURACY`, 1 s updates while tracking. Samples with
  accuracy worse than 20 m are dropped; distance gaps longer than 15 s are
  ignored to avoid GPS jump inflation.
- **Persistence of the toggle**: units selection is kept in
  `SharedPreferences` (no extra dependency).
- **Edge cases**: permission rationale + settings redirect on permanent
  denial; GPS-off banner with a shortcut to location settings; service
  saves the session and stops on explicit stop, app swipe-away
  (`onTaskRemoved`), or service destruction.

## Permissions

Declared in the manifest: fine/coarse/background location, foreground
service (location type), and notifications (Android 13+). Runtime requests
cover fine location and, on Android 13+, notifications. **No network
permissions are declared.**
