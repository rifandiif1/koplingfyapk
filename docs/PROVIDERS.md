# Music & Lyrics Providers Architecture — KOPLINGFY

All music providers implement `com.example.data.providers.MusicSourceProvider` so every adapter is isolated and independently maintainable.

## Music Source Adapters

### 1. Audius (`AudiusProvider`)
- **Discovery:** Queries `https://api.audius.co` to dynamically select healthy decentralized discovery nodes with multi-host fallback.
- **Endpoints:** `/v1/tracks/trending`, `/v1/tracks/trending/underground`, `/v1/tracks/search`, `/v1/users/search`, `/v1/playlists/search`, `/v1/tracks/{id}/stream`.
- **Offline Downloads:** Permitted strictly when the artist sets `is_downloadable = true`.

### 2. YouTube Music (`YouTubeMusicProvider`)
- **Free Access Strategy:** Uses open-source **Piped API** federation (`/search?filter=music_songs|music_artists|music_albums|music_playlists`, `/trending`, `/streams/{videoId}`) with automatic failover across multiple public Piped nodes and **Invidious API** (`/api/v1/search`, `/api/v1/trending`, `/api/v1/videos/{videoId}`).
- **Fresh Stream Resolution:** Audio-only M4A/Opus URLs are resolved fresh at playback time so signed stream URLs do not expire in long queues.
- **Offline Downloads:** Strictly disabled (`isDownloadable = false`) to comply with platform policies.

### 3. Jamendo (`JamendoProvider`)
- **Endpoints:** `https://api.jamendo.com/v3.0/tracks/`, `artists/`, `albums/`, `playlists/`.
- **Offline Downloads:** Permitted strictly when `audiodownload_allowed = true` and a valid Creative Commons `audiodownload` URL is returned.

### 4. Local Device (`LocalMusicScanner`)
- **Engines:** Android `MediaStore.Audio.Media` (API 26–36 scoped) + Storage Access Framework (`OpenDocumentTree` / `OpenDocument`).
- **Formats:** MP3, FLAC, WAV, AAC, M4A, OGG, OPUS, and all device-supported decoders.
- **Tag Editor:** Persists user tag & Photo Picker artwork edits in Room (`local_tag_overrides`) plus best-effort `ContentResolver.update` on MediaStore.

### 5. Internet Radio (`RadioBrowserProvider`)
- **Directory:** Free Radio Browser API (`de1`, `at1`, `nl1.api.radio-browser.info`) supporting top voted, search, and tag browsing plus user-added custom HTTP/HLS stream URLs.

## Failover & Deduplication (`MultiSourceRepository`)
- Providers execute in parallel using Kotlin Coroutines `async`/`awaitAll`.
- Results are deduplicated by `TrackMetadataNormalizer` comparing normalized title (stripping `(Official Audio)`, `[HQ]`, `feat.`), primary artist, and duration (±5s) while respecting the user's configured provider priority order.
- During playback, if a stream fails, `PlaybackController` requests a fallback stream from the next enabled provider automatically.

## Lyrics Provider Chain (`LyricsEngine`)
1. **Room Local Cache** (`lyrics_cache`) — instant offline load.
2. **LRCLIB Exact Match** (`GET /api/get`) with normalized metadata and 300ms rate-limit throttling.
3. **LRCLIB Scored Search** (`GET /api/search`) scoring title, artist, album, duration (±5s tolerance), and synced LRC availability (minimum threshold score `60` prevents false matches).
4. **Lyrics.ovh** fallback for plain lyrics.
5. **Musixmatch API** (optional when user configures a licensed key).
6. **Manual LRC Import/Export** via Android SAF.
