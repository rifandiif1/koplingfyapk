# Changelog — KOPLINGFY

## [1.0.0] — Initial Production Release
### Added
- **Identity & Branding:** Full `KOPLINGFY` (`com.developer.koplingfy`) identity by `RIFANDI` with custom adaptive launcher icon, Android 12+ Splash Screen, Home Screen Widget, and custom typography (`Space Grotesk`, `Plus Jakarta Sans`, `JetBrains Mono`).
- **Media3 Background Playback Engine:** `KoplingfyPlaybackService` (`MediaLibraryService` with `foregroundServiceType="mediaPlayback"`), MediaStyle notifications, Lock Screen & Bluetooth AVRCP controls, Android Auto media tree, audio focus & noisy unplug handling, gapless queue, playback speed (`0.5x–2.0x`), and resume state.
- **Multi-Source Music Engine:** Isolated adapters for Audius Open API, YouTube Music (free Piped/Invidious federation with failover), Jamendo v3.0 API, Global Radio Browser + custom URLs, and Local MediaStore + SAF scanner.
- **Global Search Engine:** Concurrent cross-provider search with live debounce, voice input, category/field/source filters, recent search history, and normalized metadata deduplication.
- **Independent Lyrics & Translation Engine:** LRCLIB `/api/get` & `/api/search` with strict duration scoring, line-level & word-level LRC sync, live sync offset, SAF `.lrc` import/export, and timestamp-preserving translation via ML Kit On-Device, Google Cloud Translate, and LibreTranslate with Room caching.
- **Library, Playlists & Download Manager:** Songs, Albums, Artists, Folders, Genres, Favorites, History, Most Played, Smart Playlists, custom playlist CRUD, multi-select batch actions, Audio Tag Editor with Android Photo Picker artwork, and policy-compliant WorkManager offline downloads.
- **Audio DSP & Real-Time Visualizer:** 5-band Android hardware `Equalizer` (9 presets + Custom), `BassBoost`, `Virtualizer`, `LoudnessEnhancer`, and custom Media3 `VisualizerAudioProcessor` rendering real decoded PCM FFT Spectrum, Frequency Bars, Oscilloscope Waveform, and Circular Radial visualizers.
- **CI/CD & Tests:** GitHub Actions workflow (`.github/workflows/android-build.yml`) plus JVM & Robolectric test suites.
