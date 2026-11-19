package com.example.ui.viewmodel

import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.example.data.local.db.DownloadEntity
import com.example.data.local.db.LyricsEntity
import com.example.data.local.db.PlaylistEntity
import com.example.data.local.db.PlaylistTrackEntity
import com.example.data.local.db.RadioStationEntity
import com.example.data.local.db.SearchHistoryEntity
import com.example.data.local.db.TrackEntity
import com.example.data.local.prefs.AppSettings
import com.example.di.AppContainer
import com.example.domain.model.AccentPreset
import com.example.domain.model.ArtworkShapeStyle
import com.example.domain.model.EqualizerPresets
import com.example.domain.model.GlobalSearchResult
import com.example.domain.model.LibrarySortOption
import com.example.domain.model.LyricsAlignment
import com.example.domain.model.LyricsDisplayMode
import com.example.domain.model.MusicSource
import com.example.domain.model.ProviderStatus
import com.example.domain.model.RadioStation
import com.example.domain.model.RepeatModeState
import com.example.domain.model.SearchCategory
import com.example.domain.model.SearchFieldFilter
import com.example.domain.model.ThemeMode
import com.example.domain.model.Track
import com.example.domain.model.TranslationProviderType
import com.example.domain.model.VisualizerMode
import com.example.data.remote.ListenTogetherUiState
import com.example.lyrics.LyricsCandidate
import com.example.lyrics.ParsedLyrics
import com.example.playback.AudioAnalysisFrame
import com.example.playback.EqualizerState
import com.example.playback.PlaybackUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class HomeUiState(
    val trendingTracks: List<Track> = emptyList(),
    val newReleases: List<Track> = emptyList(),
    val featuredRadio: List<RadioStation> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

data class ExploreUiState(
    val selectedTab: Int = 0, // 0=Audius, 1=YT Music, 2=Jamendo, 3=Radio, 4=Sources
    val audiusTracks: List<Track> = emptyList(),
    val ytMusicTracks: List<Track> = emptyList(),
    val jamendoTracks: List<Track> = emptyList(),
    val radioStations: List<RadioStation> = emptyList(),
    val providerStatuses: List<ProviderStatus> = emptyList(),
    val selectedJamendoGenre: String = "electronic",
    val radioQuery: String = "",
    val isLoading: Boolean = false
)

data class LyricsUiState(
    val trackId: String? = null,
    val parsedLyrics: ParsedLyrics? = null,
    val isLoading: Boolean = false,
    val isTranslating: Boolean = false,
    val errorMessage: String? = null,
    val manualSearchResults: List<LyricsCandidate> = emptyList(),
    val isManualSearching: Boolean = false,
    val offsetMs: Long = 0L
)

@OptIn(UnstableApi::class)
class MainViewModel(val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val playbackState: StateFlow<PlaybackUiState> = container.playbackController.state
    val listenTogetherState: StateFlow<ListenTogetherUiState> = container.listenTogetherController.state
    val audioAnalysis: StateFlow<AudioAnalysisFrame> = container.visualizerAudioProcessor.analysisFlow
    val equalizerState: StateFlow<EqualizerState> = container.equalizerManager.state

    // Database reactive streams
    val allLocalTracks: StateFlow<List<Track>> = combine(
        container.database.trackDao().observeLocalTracks().map { list -> list.map { it.toDomain() } },
        settings
    ) { tracks, s ->
        when (s.librarySortOption) {
            LibrarySortOption.TITLE_ASC -> tracks.sortedBy { it.title.lowercase() }
            LibrarySortOption.ARTIST_ASC -> tracks.sortedBy { it.artist.lowercase() }
            LibrarySortOption.ALBUM_ASC -> tracks.sortedBy { it.album.lowercase() }
            LibrarySortOption.DATE_ADDED_DESC -> tracks.sortedByDescending { it.dateAdded }
            LibrarySortOption.DURATION_DESC -> tracks.sortedByDescending { it.durationMs }
            LibrarySortOption.MOST_PLAYED -> tracks.sortedByDescending { it.playCount }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteTracks: StateFlow<List<Track>> = container.database.trackDao().observeFavorites()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentlyPlayed: StateFlow<List<Track>> = container.database.historyDao().observeRecentlyPlayed()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mostPlayed: StateFlow<List<Track>> = container.database.trackDao().observeMostPlayed()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customPlaylists: StateFlow<List<PlaylistEntity>> = container.database.playlistDao().observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloads: StateFlow<List<DownloadEntity>> = container.downloadManagerRepository.downloadsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentSearches: StateFlow<List<SearchHistoryEntity>> = container.database.searchHistoryDao()
        .observeRecentSearches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteRadioStations: StateFlow<List<RadioStation>> = container.database.radioDao()
        .observeFavoriteStations().map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentRadioStations: StateFlow<List<RadioStation>> = container.database.radioDao()
        .observeRecentStations().map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customRadioStations: StateFlow<List<RadioStation>> = container.database.radioDao()
        .observeCustomStations().map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lyricsHistory: StateFlow<List<LyricsEntity>> = container.database.lyricsDao()
        .observeLyricsHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Feature states
    private val _homeState = MutableStateFlow(HomeUiState())
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    private val _exploreState = MutableStateFlow(ExploreUiState())
    val exploreState: StateFlow<ExploreUiState> = _exploreState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchCategory = MutableStateFlow(SearchCategory.ALL)
    val searchCategory: StateFlow<SearchCategory> = _searchCategory.asStateFlow()

    private val _searchFieldFilter = MutableStateFlow(SearchFieldFilter.ANY)
    val searchFieldFilter: StateFlow<SearchFieldFilter> = _searchFieldFilter.asStateFlow()

    private val _searchSelectedSources = MutableStateFlow(setOf(MusicSource.YOUTUBE_MUSIC))
    val searchSelectedSources: StateFlow<Set<MusicSource>> = _searchSelectedSources.asStateFlow()

    private val _searchResult = MutableStateFlow(GlobalSearchResult())
    val searchResult: StateFlow<GlobalSearchResult> = _searchResult.asStateFlow()

    private val _lyricsState = MutableStateFlow(LyricsUiState())
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _isScanningLocal = MutableStateFlow(false)
    val isScanningLocal: StateFlow<Boolean> = _isScanningLocal.asStateFlow()

    private var searchDebounceJob: Job? = null

    init {
        refreshHomeDiscovery()
        refreshCacheSize()
        syncEqualizerFromSettings()
        observeTrackChangesForAutoLyrics()
    }

    private fun syncEqualizerFromSettings() {
        viewModelScope.launch {
            settings.collect { s ->
                container.equalizerManager.applyConfig(
                    enabled = s.eqEnabled,
                    presetIndex = s.eqPresetIndex,
                    bandLevelsDb = s.eqBandLevelsDb,
                    bassBoostStrength = s.bassBoostStrength,
                    virtualizerStrength = s.virtualizerStrength,
                    loudnessGainMb = s.loudnessGainMb
                )
            }
        }
    }

    private fun observeTrackChangesForAutoLyrics() {
        viewModelScope.launch {
            playbackState
                .distinctUntilChangedBy { it.currentTrack?.id }
                .collect { state ->
                    val track = state.currentTrack
                    if (track != null && settings.value.autoLyricsEnabled) {
                        loadLyricsForTrack(track, forceRefresh = false)
                    }
                }
        }
    }

    fun showMessage(msg: String) {
        _snackbarMessage.value = msg
    }

    fun consumeMessage() {
        _snackbarMessage.value = null
    }

    // --- Home & Explore ---
    fun refreshHomeDiscovery() {
        viewModelScope.launch {
            _homeState.value = _homeState.value.copy(isLoading = true, errorMessage = null)
            val s = settings.first()
            _searchSelectedSources.value = s.enabledSources
            val trending = runCatching { container.multiSourceRepository.fetchHomeTrending(s) }.getOrDefault(emptyList())
            val newReleases = runCatching { container.multiSourceRepository.fetchHomeNewReleases(s) }.getOrDefault(emptyList())
            val radio = if (MusicSource.RADIO in s.enabledSources) {
                runCatching { container.multiSourceRepository.fetchTopRadio(12) }.getOrDefault(emptyList())
            } else emptyList()
            _homeState.value = HomeUiState(
                trendingTracks = trending,
                newReleases = newReleases,
                featuredRadio = radio,
                isLoading = false,
                errorMessage = if (trending.isEmpty() && radio.isEmpty()) {
                    "Tidak dapat terhubung ke YouTube Music — periksa koneksi internet Anda."
                } else null
            )
        }
    }

    fun selectExploreTab(tabIndex: Int) {
        _exploreState.value = _exploreState.value.copy(selectedTab = tabIndex)
        refreshExploreTab(tabIndex)
    }

    fun refreshExploreTab(tabIndex: Int = _exploreState.value.selectedTab) {
        viewModelScope.launch {
            _exploreState.value = _exploreState.value.copy(isLoading = true)
            when (tabIndex) {
                0 -> {
                    val list = container.multiSourceRepository.fetchSourceTrending(MusicSource.AUDIUS, 25)
                    _exploreState.value = _exploreState.value.copy(audiusTracks = list, isLoading = false)
                }
                1 -> {
                    val list = container.multiSourceRepository.fetchSourceTrending(MusicSource.YOUTUBE_MUSIC, 25)
                    _exploreState.value = _exploreState.value.copy(ytMusicTracks = list, isLoading = false)
                }
                2 -> {
                    val list = container.multiSourceRepository.fetchJamendoGenre(_exploreState.value.selectedJamendoGenre)
                    _exploreState.value = _exploreState.value.copy(jamendoTracks = list, isLoading = false)
                }
                3 -> {
                    val q = _exploreState.value.radioQuery
                    val stations = if (q.isBlank()) container.multiSourceRepository.fetchTopRadio(30)
                    else container.multiSourceRepository.searchRadio(q)
                    _exploreState.value = _exploreState.value.copy(radioStations = stations, isLoading = false)
                }
                4 -> {
                    val statuses = container.multiSourceRepository.checkAllProvidersHealth(settings.value)
                    _exploreState.value = _exploreState.value.copy(providerStatuses = statuses, isLoading = false)
                }
            }
        }
    }

    fun selectJamendoGenre(genre: String) {
        _exploreState.value = _exploreState.value.copy(selectedJamendoGenre = genre)
        refreshExploreTab(2)
    }

    fun searchRadioStations(query: String) {
        _exploreState.value = _exploreState.value.copy(radioQuery = query)
        refreshExploreTab(3)
    }

    fun checkProvidersHealth() {
        viewModelScope.launch {
            _exploreState.value = _exploreState.value.copy(isLoading = true)
            val statuses = container.multiSourceRepository.checkAllProvidersHealth(settings.value)
            _exploreState.value = _exploreState.value.copy(providerStatuses = statuses, isLoading = false)
        }
    }

    // --- Radio ---
    fun playRadioStation(station: RadioStation) {
        viewModelScope.launch {
            container.database.radioDao().upsertStation(
                RadioStationEntity.fromDomain(station.copy(lastPlayedAt = System.currentTimeMillis()))
            )
        }
        container.playbackController.playTrack(station.toTrack(), listOf(station.toTrack()))
    }

    fun toggleFavoriteRadio(station: RadioStation) {
        viewModelScope.launch {
            val next = !station.isFavorite
            container.database.radioDao().upsertStation(
                RadioStationEntity.fromDomain(station.copy(isFavorite = next))
            )
            showMessage(if (next) "Saved ${station.name} to Radio Favorites" else "Removed from Radio Favorites")
        }
    }

    fun addCustomRadioStation(name: String, streamUrl: String, genre: String) {
        if (name.isBlank() || !streamUrl.startsWith("http")) {
            showMessage("Enter a valid station name and http/https stream URL.")
            return
        }
        viewModelScope.launch {
            val station = RadioStation(
                id = "custom_${UUID.randomUUID().toString().take(8)}",
                name = name.trim(),
                streamUrl = streamUrl.trim(),
                genre = genre.trim().ifBlank { "Custom Radio" },
                country = "Custom",
                isFavorite = true,
                isCustom = true
            )
            container.database.radioDao().upsertStation(RadioStationEntity.fromDomain(station))
            showMessage("Added custom radio station: ${station.name}")
        }
    }

    fun deleteCustomRadioStation(stationId: String) {
        viewModelScope.launch {
            container.database.radioDao().deleteCustomStation(stationId)
        }
    }

    // --- Global Search ---
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchDebounceJob?.cancel()
        if (query.isBlank()) {
            _searchResult.value = GlobalSearchResult()
            return
        }
        searchDebounceJob = viewModelScope.launch {
            delay(380L)
            executeGlobalSearch(query, saveHistory = false)
        }
    }

    fun submitSearch(query: String = _searchQuery.value) {
        searchDebounceJob?.cancel()
        _searchQuery.value = query
        if (query.isBlank()) return
        viewModelScope.launch {
            executeGlobalSearch(query, saveHistory = true)
        }
    }

    private suspend fun executeGlobalSearch(query: String, saveHistory: Boolean) {
        _searchResult.value = _searchResult.value.copy(query = query, isLoading = true)
        if (saveHistory) {
            container.database.searchHistoryDao().saveQuery(SearchHistoryEntity(query.trim()))
        }
        val result = container.multiSourceRepository.globalSearch(
            query = query.trim(),
            settings = settings.value,
            selectedSources = _searchSelectedSources.value,
            fieldFilter = _searchFieldFilter.value
        )
        _searchResult.value = result
    }

    fun setSearchCategory(cat: SearchCategory) {
        _searchCategory.value = cat
    }

    fun setSearchFieldFilter(filter: SearchFieldFilter) {
        _searchFieldFilter.value = filter
        if (_searchQuery.value.isNotBlank()) submitSearch(_searchQuery.value)
    }

    fun toggleSearchSourceFilter(source: MusicSource) {
        val cur = _searchSelectedSources.value.toMutableSet()
        if (source in cur && cur.size > 1) cur.remove(source) else cur.add(source)
        _searchSelectedSources.value = cur
        if (_searchQuery.value.isNotBlank()) submitSearch(_searchQuery.value)
    }

    fun deleteSearchHistoryItem(query: String) {
        viewModelScope.launch { container.database.searchHistoryDao().deleteQuery(query) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { container.database.searchHistoryDao().clearAll() }
    }

    // --- Playback & Track Actions ---
    fun playTrack(track: Track, queue: List<Track> = listOf(track)) {
        container.playbackController.playTrack(track, queue)
    }

    fun playNext(track: Track) {
        container.playbackController.playNext(track)
        showMessage("Playing next: ${track.title}")
    }

    fun addToQueue(track: Track) {
        container.playbackController.addToQueue(track)
        showMessage("Added to queue: ${track.title}")
    }

    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            val existing = container.database.trackDao().getTrackById(track.id)
            val newFav = !(existing?.isFavorite ?: track.isFavorite)
            if (existing == null) {
                container.database.trackDao().upsertTrack(TrackEntity.fromDomain(track.copy(isFavorite = newFav)))
            } else {
                container.database.trackDao().setFavorite(track.id, newFav)
            }
            container.playbackController.refreshCurrentTrackFavorite(track.id, newFav)
            showMessage(if (newFav) "Added to Favorites" else "Removed from Favorites")
        }
    }

    // --- Local Library & Tag Editing ---
    fun rescanLocalLibrary() {
        viewModelScope.launch {
            _isScanningLocal.value = true
            val s = settings.value
            val found = container.localMusicScanner.scanDeviceMusic(
                customFolderUris = s.customScanFolders,
                excludedFolders = s.excludedFolders
            )
            _isScanningLocal.value = false
            showMessage("Library scan complete: ${found.size} local tracks found")
        }
    }

    fun addCustomSafFolder(uri: Uri) {
        viewModelScope.launch {
            container.settingsDataStore.addCustomScanFolder(uri.toString())
            rescanLocalLibrary()
        }
    }

    fun importLocalAudioFile(uri: Uri) {
        viewModelScope.launch {
            val track = container.localMusicScanner.importSingleAudioUri(uri)
            if (track != null) {
                playTrack(track, listOf(track) + allLocalTracks.value)
                showMessage("Imported & playing: ${track.title}")
            }
        }
    }

    fun saveAudioTags(
        track: Track,
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        genre: String,
        year: Int,
        trackNumber: Int,
        artworkUrl: String?
    ) {
        viewModelScope.launch {
            container.localMusicScanner.updateLocalTrackTags(
                track, title, artist, album, albumArtist, genre, year, trackNumber, artworkUrl
            )
            showMessage("Saved metadata tags for $title")
        }
    }

    // --- Playlists ---
    fun observePlaylistTracks(playlistId: String): Flow<List<Track>> =
        container.database.playlistDao().observePlaylistTracks(playlistId)
            .map { list -> list.map { it.toDomain() } }

    fun createPlaylist(name: String, description: String = "", initialTracks: List<Track> = emptyList()) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = "pl_${UUID.randomUUID().toString().take(8)}"
            container.database.playlistDao().upsertPlaylist(
                PlaylistEntity(
                    id = id,
                    name = name.trim(),
                    description = description.trim(),
                    artworkUrl = initialTracks.firstOrNull()?.artworkUrl,
                    source = MusicSource.LOCAL.name,
                    createdAt = System.currentTimeMillis()
                )
            )
            initialTracks.forEachIndexed { idx, t ->
                container.database.trackDao().upsertTrack(TrackEntity.fromDomain(t))
                container.database.playlistDao().addTrackToPlaylist(
                    PlaylistTrackEntity(id, t.id, idx, System.currentTimeMillis())
                )
            }
            showMessage("Created playlist \"$name\"")
        }
    }

    fun renamePlaylist(playlistId: String, name: String, description: String) {
        viewModelScope.launch {
            container.database.playlistDao().updatePlaylistMeta(playlistId, name.trim(), description.trim())
            showMessage("Updated playlist \"$name\"")
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            container.database.playlistDao().clearPlaylistTracks(playlistId)
            container.database.playlistDao().deletePlaylist(playlistId)
            showMessage("Playlist deleted")
        }
    }

    fun addTracksToPlaylist(playlistId: String, tracks: List<Track>) {
        viewModelScope.launch {
            var pos = container.database.playlistDao().getMaxPosition(playlistId) + 1
            tracks.forEach { t ->
                container.database.trackDao().upsertTrack(TrackEntity.fromDomain(t))
                container.database.playlistDao().addTrackToPlaylist(
                    PlaylistTrackEntity(playlistId, t.id, pos++, System.currentTimeMillis())
                )
            }
            showMessage("Added ${tracks.size} track(s) to playlist")
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            container.database.playlistDao().removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun saveCurrentQueueAsPlaylist(name: String) {
        val queue = playbackState.value.queue
        if (queue.isEmpty()) {
            showMessage("Queue is empty")
            return
        }
        createPlaylist(name, "Saved from queue (${queue.size} tracks)", queue)
    }

    // --- Downloads ---
    fun downloadTrack(track: Track) {
        viewModelScope.launch {
            val res = container.downloadManagerRepository.enqueueDownload(track)
            res.onSuccess { showMessage("Downloading ${track.title}...") }
                .onFailure { showMessage(it.message ?: "Download not permitted for this source") }
        }
    }

    fun cancelDownload(trackId: String) {
        viewModelScope.launch { container.downloadManagerRepository.cancelDownload(trackId) }
    }

    fun retryDownload(entity: DownloadEntity) {
        viewModelScope.launch { container.downloadManagerRepository.retryDownload(entity) }
    }

    fun deleteDownload(entity: DownloadEntity) {
        viewModelScope.launch {
            container.downloadManagerRepository.deleteDownload(entity)
            showMessage("Removed offline file: ${entity.title}")
        }
    }

    fun playDownloadedEntity(entity: DownloadEntity) {
        viewModelScope.launch {
            val track = container.database.trackDao().getTrackById(entity.trackId)?.toDomain()
                ?: Track(
                    id = entity.trackId,
                    title = entity.title,
                    artist = entity.artist,
                    artworkUrl = entity.artworkUrl,
                    localPath = entity.localFilePath,
                    streamUrl = entity.localFilePath,
                    source = runCatching { MusicSource.valueOf(entity.source) }.getOrDefault(MusicSource.LOCAL)
                )
            playTrack(track, listOf(track))
        }
    }

    // --- Lyrics & Translation ---
    fun loadLyricsForTrack(track: Track, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _lyricsState.value = _lyricsState.value.copy(
                trackId = track.id,
                isLoading = true,
                errorMessage = null
            )
            val parsed = container.lyricsEngine.getLyricsForTrack(track, forceRefresh)
            if (parsed == null) {
                _lyricsState.value = LyricsUiState(
                    trackId = track.id,
                    parsedLyrics = null,
                    isLoading = false,
                    errorMessage = "No matching lyrics found on LRCLIB or fallback providers. Tap Search or Import LRC."
                )
            } else {
                _lyricsState.value = LyricsUiState(
                    trackId = track.id,
                    parsedLyrics = parsed,
                    isLoading = false,
                    offsetMs = parsed.offsetMs
                )
                if (settings.value.autoTranslateLyrics) {
                    translateCurrentLyrics(settings.value.targetTranslationLang)
                }
            }
        }
    }

    fun searchLyricsManually(query: String) {
        viewModelScope.launch {
            _lyricsState.value = _lyricsState.value.copy(isManualSearching = true)
            val results = container.lyricsEngine.searchManualCandidates(query, playbackState.value.currentTrack)
            _lyricsState.value = _lyricsState.value.copy(
                manualSearchResults = results,
                isManualSearching = false
            )
        }
    }

    fun selectManualLyricsCandidate(candidate: LyricsCandidate) {
        val track = playbackState.value.currentTrack ?: return
        viewModelScope.launch {
            val parsed = container.lyricsEngine.selectCandidateForTrack(track, candidate)
            _lyricsState.value = _lyricsState.value.copy(
                parsedLyrics = parsed,
                errorMessage = null,
                manualSearchResults = emptyList(),
                offsetMs = 0L
            )
            showMessage("Applied lyrics from ${candidate.providerName}")
        }
    }

    fun adjustLyricsOffset(newOffsetMs: Long) {
        val clamped = newOffsetMs.coerceIn(-5000L, 5000L)
        val cur = _lyricsState.value
        _lyricsState.value = cur.copy(
            offsetMs = clamped,
            parsedLyrics = cur.parsedLyrics?.copy(offsetMs = clamped)
        )
        cur.trackId?.let { id ->
            viewModelScope.launch { container.lyricsEngine.updateLyricsOffset(id, clamped) }
        }
    }

    fun importLrcFile(uri: Uri) {
        val track = playbackState.value.currentTrack ?: return
        viewModelScope.launch {
            val parsed = container.lyricsEngine.importLocalLrc(track, uri)
            if (parsed != null) {
                _lyricsState.value = _lyricsState.value.copy(parsedLyrics = parsed, errorMessage = null, offsetMs = 0L)
                showMessage("Imported LRC lyrics for ${track.title}")
            } else {
                showMessage("Could not read LRC file")
            }
        }
    }

    fun exportLrcFile(uri: Uri) {
        val track = playbackState.value.currentTrack ?: return
        val parsed = _lyricsState.value.parsedLyrics ?: return
        viewModelScope.launch {
            val ok = container.lyricsEngine.exportLrcToUri(parsed, track, uri)
            showMessage(if (ok) "Exported .lrc successfully" else "Export failed")
        }
    }

    fun translateCurrentLyrics(targetLangCode: String = settings.value.targetTranslationLang) {
        val trackId = _lyricsState.value.trackId ?: return
        val parsed = _lyricsState.value.parsedLyrics ?: return
        viewModelScope.launch {
            _lyricsState.value = _lyricsState.value.copy(isTranslating = true)
            container.settingsDataStore.updateTargetLanguage(targetLangCode)
            val result = container.lyricsTranslationEngine.translateLyrics(
                trackId = trackId,
                parsed = parsed,
                targetLangCode = targetLangCode,
                settings = settings.value
            )
            result.onSuccess { translated ->
                _lyricsState.value = _lyricsState.value.copy(
                    parsedLyrics = translated,
                    isTranslating = false
                )
                if (settings.value.lyricsDisplayMode == LyricsDisplayMode.ORIGINAL) {
                    container.settingsDataStore.updateLyricsDisplayMode(LyricsDisplayMode.DUAL)
                }
                showMessage("Translated lyrics to ${targetLangCode.uppercase()} (cached)")
            }.onFailure { err ->
                _lyricsState.value = _lyricsState.value.copy(isTranslating = false)
                showMessage("Translation error: ${err.localizedMessage ?: "Check connection for ML Kit model"}")
            }
        }
    }

    // --- Equalizer Actions ---
    fun setEqualizerEnabled(enabled: Boolean) {
        val s = settings.value
        viewModelScope.launch {
            container.settingsDataStore.updateEqualizer(
                enabled, s.eqPresetIndex, s.eqBandLevelsDb, s.bassBoostStrength, s.virtualizerStrength, s.loudnessGainMb
            )
        }
    }

    fun selectEqualizerPreset(index: Int) {
        val preset = EqualizerPresets.ALL.getOrNull(index) ?: return
        val s = settings.value
        viewModelScope.launch {
            container.settingsDataStore.updateEqualizer(
                true, index, preset.bandLevelsDb, s.bassBoostStrength, s.virtualizerStrength, s.loudnessGainMb
            )
        }
    }

    fun setEqualizerBand(bandIndex: Int, levelDb: Float) {
        val s = settings.value
        val updated = s.eqBandLevelsDb.toMutableList()
        if (bandIndex in updated.indices) {
            updated[bandIndex] = levelDb.coerceIn(-12f, 12f)
            viewModelScope.launch {
                container.settingsDataStore.updateEqualizer(
                    true, -1, updated, s.bassBoostStrength, s.virtualizerStrength, s.loudnessGainMb
                )
            }
        }
    }

    fun setAudioEffects(bassBoost: Int, virtualizer: Int, loudnessMb: Int) {
        val s = settings.value
        viewModelScope.launch {
            container.settingsDataStore.updateEqualizer(
                s.eqEnabled, s.eqPresetIndex, s.eqBandLevelsDb, bassBoost, virtualizer, loudnessMb
            )
        }
    }

    fun resetEqualizer() {
        viewModelScope.launch {
            container.settingsDataStore.updateEqualizer(
                true, 0, listOf(0f, 0f, 0f, 0f, 0f), 0, 0, 0
            )
            showMessage("Equalizer reset to Normal")
        }
    }

    // --- Settings & Sources ---
    fun updateThemeMode(mode: ThemeMode) = viewModelScope.launch { container.settingsDataStore.updateThemeMode(mode) }
    fun updateAccentPreset(preset: AccentPreset) = viewModelScope.launch { container.settingsDataStore.updateAccentPreset(preset) }
    fun updateDynamicColor(enabled: Boolean) = viewModelScope.launch { container.settingsDataStore.updateDynamicColor(enabled) }
    fun updateAnimationsEnabled(enabled: Boolean) = viewModelScope.launch { container.settingsDataStore.updateAnimationsEnabled(enabled) }
    fun updateArtworkStyle(style: ArtworkShapeStyle) = viewModelScope.launch { container.settingsDataStore.updateArtworkStyle(style) }
    fun updateVisualizerMode(mode: VisualizerMode) = viewModelScope.launch { container.settingsDataStore.updateVisualizerMode(mode) }
    fun updateHighQuality(enabled: Boolean) = viewModelScope.launch { container.settingsDataStore.updateHighQualityAudio(enabled) }
    fun updateGapless(enabled: Boolean) = viewModelScope.launch { container.settingsDataStore.updateGaplessPlayback(enabled) }
    fun updateResumeOnStartup(enabled: Boolean) = viewModelScope.launch { container.settingsDataStore.updateResumeOnStartup(enabled) }
    fun updateAutoLyrics(enabled: Boolean) = viewModelScope.launch { container.settingsDataStore.updateAutoLyrics(enabled) }
    fun updateLyricsFontSize(sizeSp: Float) = viewModelScope.launch { container.settingsDataStore.updateLyricsFontSize(sizeSp) }
    fun updateLyricsLineSpacing(sp: Float) = viewModelScope.launch { container.settingsDataStore.updateLyricsLineSpacing(sp) }
    fun updateLyricsAlignment(a: LyricsAlignment) = viewModelScope.launch { container.settingsDataStore.updateLyricsAlignment(a) }
    fun updateLyricsDisplayMode(m: LyricsDisplayMode) = viewModelScope.launch { container.settingsDataStore.updateLyricsDisplayMode(m) }
    fun updateAutoTranslate(enabled: Boolean) = viewModelScope.launch { container.settingsDataStore.updateAutoTranslate(enabled) }
    fun updateTranslationProvider(p: TranslationProviderType) = viewModelScope.launch { container.settingsDataStore.updateTranslationProvider(p) }
    fun updateLibrarySort(s: LibrarySortOption) = viewModelScope.launch { container.settingsDataStore.updateLibrarySort(s) }
    fun toggleExcludedFolder(folder: String) = viewModelScope.launch {
        container.settingsDataStore.toggleExcludedFolder(folder)
        rescanLocalLibrary()
    }
    fun toggleSourceEnabled(source: MusicSource, enabled: Boolean) = viewModelScope.launch {
        container.settingsDataStore.toggleSourceEnabled(source, enabled)
        val updated = settings.first().enabledSources
        _searchSelectedSources.value = updated
        checkProvidersHealth()
        refreshHomeDiscovery()
    }
    fun moveSourcePriority(source: MusicSource, delta: Int) = viewModelScope.launch {
        val list = settings.value.sourcePriorityOrder.toMutableList()
        val idx = list.indexOf(source)
        val target = (idx + delta).coerceIn(0, list.lastIndex)
        if (idx >= 0 && idx != target) {
            list.removeAt(idx)
            list.add(target, source)
            container.settingsDataStore.updateSourcePriority(list)
            checkProvidersHealth()
        }
    }
    fun updatePipedUrl(url: String) = viewModelScope.launch {
        container.settingsDataStore.updateCustomPipedUrl(url)
        showMessage("Updated Piped instance URL")
    }
    fun updateJamendoClientId(id: String) = viewModelScope.launch {
        container.settingsDataStore.updateJamendoClientId(id)
        showMessage("Saved Jamendo Client ID")
        checkProvidersHealth()
    }
    fun updateMusixmatchKey(key: String) = viewModelScope.launch {
        container.settingsDataStore.updateMusixmatchKey(key)
        showMessage("Saved Musixmatch API Key")
    }
    fun updateGoogleTranslateKey(key: String) = viewModelScope.launch {
        container.settingsDataStore.updateGoogleTranslateKey(key)
        showMessage("Saved Google Cloud Translation Key")
    }
    fun updateLibreTranslateUrl(url: String) = viewModelScope.launch {
        container.settingsDataStore.updateLibreTranslateUrl(url)
        showMessage("Saved LibreTranslate endpoint")
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSizeBytes.value = container.calculateCacheSizeBytes()
        }
    }

    fun clearCachePreservingLibrary() {
        viewModelScope.launch {
            container.clearCachesPreservingLibrary()
            refreshCacheSize()
            showMessage("Cleared artwork, HTTP, and lyrics caches (library preserved)")
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(container) as T
        }
    }
}
