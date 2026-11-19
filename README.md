# KOPLINGFY — Premium Multi-Source Android Music Player

- **Package ID:** `com.developer.koplingfy`
- **Developer:** `RIFANDI`
- **Version:** `1.0.0`
- **Minimum SDK:** Android 8.0 (API 26) | **Target SDK:** API 36

## Overview

Koplingfy is a native Kotlin & Jetpack Compose Material 3 music streaming and offline audio player engineered with a modular Clean/MVVM architecture. It combines real-time multi-source online streaming, MediaStore + SAF local library scanning, uninterrupted Media3 background playback, LRCLIB synchronized lyrics with ML Kit on-device translation, a hardware 5-band Equalizer, and a real PCM FFT audio-reactive visualizer.

## Core Architecture

```
app/src/main/java/com/example/
├── KoplingfyApplication.kt         # AppContainer init, Notification Channels, Coil disk cache
├── MainActivity.kt                 # SplashScreen, Edge-to-Edge, Adaptive NavBar/NavRail, NavHost
├── di/AppContainer.kt              # Manual constructor dependency injection graph
├── domain/model/MusicModels.kt     # Pure domain models (Track, Source, Radio, EQ, Settings)
├── data/
│   ├── local/db/                   # Room v1 Database (10 tables) & reactive DAOs
│   ├── local/prefs/                # DataStore Preferences for settings & EQ persistence
│   ├── local/media/                # LocalMusicScanner (MediaStore + SAF + Audio Tag Editor)
│   └── providers/                  # Separated source adapters + MultiSourceRepository
├── lyrics/                         # LrcParser, LyricsEngine (LRCLIB/Ovh/MM), LyricsTranslationEngine
├── playback/                       # Media3 MediaLibraryService, PlaybackController, VisualizerAudioProcessor, EqualizerManager
├── download/                       # WorkManager DownloadWorker & DownloadManagerRepository
├── widget/                         # Home Screen AppWidgetProvider synced with Media3
└── ui/
    ├── theme/                      # AMOLED Black, Obsidian Dark, Studio Light + 5 Accents + Custom Fonts
    ├── components/                 # TrackComponents, MiniPlayerBar, AudioVisualizerCanvas
    ├── viewmodel/MainViewModel.kt  # Unified reactive state flows
    └── screens/                    # Home, Search, Explore/Sources, Library, Playlists, NowPlaying, Lyrics, Equalizer, Settings
```

## Key Features

- **Background Playback (Media3 `MediaLibraryService`):** Continues playing when minimized, switching apps, or with screen locked (`foregroundServiceType="mediaPlayback"`), with MediaStyle notifications, Lock Screen controls, Bluetooth/headset AVRCP support, Android Auto browse tree, audio focus management, and Home Screen Widget.
- **Multi-Source Engine:** Audius Open API (free), YouTube Music via open Piped/Invidious federation (free, no paid API key), Jamendo Creative Commons API, Global Internet Radio Browser + custom URLs, and Local Device MediaStore/SAF.
- **Global Deduplicated Search:** Searches all enabled providers concurrently with live debounce, voice search, field/category/source filters, and normalized metadata deduplication.
- **Independent Synced Lyrics & Translation:** LRCLIB `/api/get` & `/api/search` with strict duration/title/artist matching, word-level & line-level LRC sync, live offset adjustment, SAF `.lrc` import/export, and timestamp-preserving translation via ML Kit On-Device Translation (free), Google Cloud Translate, or LibreTranslate.
- **Real PCM Audio Visualizer & Studio Equalizer:** Custom Media3 `BaseAudioProcessor` computing 256-point radix-2 FFT spectrum & waveform directly from ExoPlayer's decoded audio (zero `RECORD_AUDIO` permission needed), paired with Android hardware `Equalizer`, `BassBoost`, `Virtualizer`, and `LoudnessEnhancer`.

## Building & CI/CD

### Local Gradle Build
```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

### GitHub Actions
The included `.github/workflows/android-build.yml` automatically validates the project, runs JVM & Robolectric unit tests, builds `app-debug.apk`, conditionally signs `app-release.apk` when repository secrets (`RELEASE_KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`) are set, and uploads APK artifacts.

## Documentation

- [API Configuration Guide](docs/API_CONFIGURATION.md)
- [Music & Lyrics Providers Reference](docs/PROVIDERS.md)
- [Room Database & Caching Schema](docs/DATABASE.md)
- [Changelog](CHANGELOG.md)
