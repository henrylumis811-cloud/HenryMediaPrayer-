# Henry's Media Prayer v2

Rebuilt to fix the 6-second crash, fix music auto-scan, and add full Android 14 support.

## What was actually wrong (most likely) and how it's fixed

**1. Crash ~6 seconds into playback**
`android.media.audiofx.Visualizer` throws if you construct it before ExoPlayer's
audio session id is valid (it becomes valid a moment *after* playback starts,
not at song load — matches a crash a few seconds in). `VisualizerView.kt` now:
- waits for `Player.STATE_READY` and a non-zero `audioSessionId` before attaching
- wraps every visualizer call in try/catch
- falls back to a fake animated waveform instead of crashing if the device's
  audio stack refuses a capture session (common on budget/OEM chipsets)
- also added a global `Player.Listener.onPlayerError` handler in `PlaybackService`
  that skips a broken track instead of letting playback errors kill the app

**2. Auto-scan not working**
Old code likely relied on `READ_EXTERNAL_STORAGE`, which Android 13+ ignores for
media apps. `MusicScanner.kt` now queries `MediaStore` directly (scoped-storage
safe) and `MainActivity` requests `READ_MEDIA_AUDIO` (13+) or
`READ_EXTERNAL_STORAGE` (below 13) at runtime, plus `POST_NOTIFICATIONS`.

**3. Android 14 compatibility**
- `compileSdk` / `targetSdk` bumped to 34
- Playback rebuilt on `MediaSessionService` (Media3 1.4.1), which correctly
  declares `foregroundServiceType="mediaPlayback"` and manages the notification,
  lock-screen controls, and foreground lifecycle — Android 14 kills services that
  get this wrong
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission added (required as of API 34)
- Runtime `POST_NOTIFICATIONS` request (required as of API 33)

## Important: this needs newer build tooling than your phone's ACS setup

Your on-device constraints (AGP 7.2.2 / Gradle 7.4.2 / compileSdk 33) **cannot
target Android 14** — Google requires AGP 8.x and Gradle 8.x for compileSdk 34.
This project is set up for **GitHub Actions only**:
- AGP 8.5.2, Gradle 8.7, Kotlin 1.9.24, JDK 17
- `.github/workflows/android-build.yml` builds a debug APK on every push to
  `main` and uploads it as a workflow artifact — download it from the Actions
  tab after pushing.

## Four tabs, as before
- **Altar** — now playing, visualizer, transport controls, progress
- **Library** — auto-scanned song list, pull-to-refresh
- **Verses** — lyrics screen (placeholder wired for LRC lyrics next)
- **Signal** — dark/light HUD toggle, sleep timer

## Max quality playback
No re-encoding or downsampling anywhere — ExoPlayer plays each file at its
native source quality by default.

## Not yet wired (flag if you want these next)
- Synced LRC lyrics in Verses
- Album art in the Library list and Altar screen
- Equalizer
