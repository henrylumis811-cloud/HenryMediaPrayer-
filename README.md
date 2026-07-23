# Henry's Media Prayer — Android app

A native Android rebuild of `Henry.html`: four tabs (Altar / Library / Verses /
Signal), a canvas "mandala" visualizer, a 5-band equalizer + bass boost, timed
lyrics, shuffle/repeat/speed/volume, and a dark/light theme toggle.

## How playback quality is handled
Audio is played with **ExoPlayer (Media3)**, which decodes each file to PCM at
*that file's own* sample rate and channel layout and hands it straight to
`AudioTrack` — there's no forced downsampling or re-encoding. The EQ and
bass-boost are implemented with the platform's `android.media.audiofx`
`Equalizer`/`BassBoost`, attached to the player's audio session, so they colour
the signal without touching quality elsewhere in the chain.

## Project layout
```
HenrysMediaPrayer/
  settings.gradle, build.gradle, gradle.properties   – project-level Gradle
  gradle/wrapper/gradle-wrapper.properties           – Gradle 8.4
  app/build.gradle                                   – AGP 7.2.2, Kotlin 1.7.20,
                                                        compileSdk/targetSdk 33, minSdk 24
                                                        (chosen to match Android Code Studio's
                                                        bundled Gradle 7.4.2 — see note below)
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/henrylumis/mediaprayer/
    MediaPrayerApp.kt        – theme default + notification channel
    data/                    – Track model + plain SQLite repository (no Room)
    player/                  – PlayerManager (ExoPlayer + EQ/BassBoost/Visualizer),
                               PlaybackService (MediaSessionService for lock screen/BG)
    ui/                      – MainActivity + 4 fragments + AltarView + adapter
  app/src/main/res/          – layouts, drawables, colors (day + values-night), strings
```

## Opening the project
1. **Android Studio (recommended):** File → Open → select the `HenrysMediaPrayer`
   folder. Let it sync Gradle; it will download the wrapper distribution
   itself the first time (needs network access once).
2. **A mobile/on-device Android IDE (e.g. Android Code Studio):** open the
   same folder as a Gradle project. **This project is currently pinned to
   AGP 7.2.2 / Kotlin 1.7.20 / compileSdk 33, specifically because Android
   Code Studio's bundled Gradle was 7.4.2** (AGP 8.x needs Gradle 8.0+, which
   is what caused the original "Minimum supported Gradle version is 8.0"
   failure). If your copy of ACS ships a newer bundled Gradle, you can raise
   these versions again for access to newer SDK features — just keep
   AGP/Gradle/compileSdk moving together, not one at a time.
   The dependency set is intentionally small (no Room/KSP, no Compose, no
   Hilt) specifically to reduce the chance of a mobile build rejecting it.

## Permissions
The app asks for `POST_NOTIFICATIONS` (Android 13+) so the playback
notification can show, and uses the system document picker (Storage Access
Framework) to add audio files — no broad storage permission is required on
modern Android versions.

## Known simplifications vs. the original web app
- The equalizer's dB range is mapped onto whatever range the device's
  `Equalizer` effect reports (commonly ±15 dB); most phones support this fine.
- The FFT-driven visualizer bands are an approximation of the original Web
  Audio analyser bins, redrawn with `Canvas` instead of `<canvas>`.
- The launcher icon is a simple vector placeholder — swap
  `res/drawable/ic_launcher.xml` for real launcher art whenever you like.
