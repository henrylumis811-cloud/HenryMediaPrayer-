# HenryMediaPrayer — Playlists v1

Based on Audio v6.

## This pass
- Added persistent named playlists alongside Favorites.
- Playlist names are local and survive app/service restarts.
- Added current-song playlist chooser from Now Playing.
- Supports creating a playlist and adding the current song immediately.
- Existing Favorites behavior remains available from the same chooser.
- Playlist membership toggles without changing playback state.

## Validation
- ZIP contents tested with `unzip -t`.
- Full Android Gradle build not run because the project environment does not contain the required Gradle/dependency setup.
