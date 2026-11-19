# Database & Caching Documentation — KOPLINGFY

Koplingfy uses an offline-first **Room Database** (`koplingfy_database.db`, version `1`) paired with **Jetpack DataStore Preferences** (`koplingfy_settings`) and Coil disk/memory caching.

## Room Tables (`Entities.kt`)

| Table | Primary Key | Purpose |
| --- | --- | --- |
| `tracks` | `id` | Unified catalog of local scanned files, favorited online tracks, downloaded tracks, play counts, and metadata. |
| `playlists` | `id` | User-created playlists with title, description, cover art, and creation timestamp. |
| `playlist_tracks` | `(playlistId, trackId)` | Ordered many-to-many join table with `position` for reordering tracks inside playlists. |
| `playback_history` | `entryId` (auto) | Chronological listening history powering Continue Listening and Recently Played. |
| `lyrics_cache` | `trackId` | Cached synced LRC, plain lyrics, provider attribution, and per-track sync `offsetMs`. |
| `translated_lyrics` | `(trackId, targetLanguage)` | Timestamp-preserving translated LRC and plain lyrics cached per target language so tracks are never re-translated unnecessarily. |
| `downloads` | `trackId` | WorkManager download queue state, byte progress, status (`QUEUED`, `DOWNLOADING`, `PAUSED`, `COMPLETED`, `FAILED`, `CANCELLED`), and offline file URI. |
| `search_history` | `query` | Recent global search queries with timestamp. |
| `radio_stations` | `id` | Saved favorite stations, recently played stations, and custom user-added radio URLs. |
| `local_tag_overrides` | `trackId` | Persistent user metadata & artwork edits for local tracks that survive rescans and Android Scoped Storage restrictions. |

## Caching Strategy & Clear Cache Safety

- **HTTP Cache:** OkHttp 30 MB disk cache (`koplingfy_http_cache`).
- **Artwork Cache:** Coil 25% memory cache + 80 MB disk cache (`coil_artwork_cache`).
- **Clear Cache Action:** In **Settings -> Storage & Cache**, clearing cache evicts OkHttp cache, Coil artwork cache, `lyrics_cache`, `translated_lyrics`, and non-favorited/non-downloaded online track metadata while **strictly preserving** user playlists, favorites, local scanned library, tag overrides, custom radio stations, and completed offline downloads.
