package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.db.DownloadEntity
import com.example.domain.model.DownloadStatus
import com.example.domain.model.LibrarySortOption
import com.example.domain.model.Track
import com.example.ui.components.TrackArtwork
import com.example.ui.components.TrackRowItem
import com.example.ui.components.formatBytes
import com.example.ui.viewmodel.MainViewModel

@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onRequestAddTracksToPlaylist: (List<Track>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val localTracks by viewModel.allLocalTracks.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteTracks.collectAsStateWithLifecycle()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsStateWithLifecycle()
    val mostPlayed by viewModel.mostPlayed.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanningLocal.collectAsStateWithLifecycle()

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) viewModel.rescanLocalLibrary()
        else viewModel.showMessage("Audio permission denied — use Folder or File picker (SAF) to import audio.")
    }

    val safFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.addCustomSafFolder(uri)
        }
    }

    val safFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importLocalAudioFile(uri)
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Songs", "Albums", "Artists", "Folders", "Genres", "Favorites", "Downloads", "History", "Most Played")
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedTrackIds by remember { mutableStateOf(setOf<String>()) }
    var editingTagTrack by remember { mutableStateOf<Track?>(null) }
    var activeGroupFilter by remember { mutableStateOf<Pair<String, List<Track>>?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
            .testTag("library_screen")
    ) {
        // Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (multiSelectMode) "${selectedTrackIds.size} Selected" else "Music Library",
                style = MaterialTheme.typography.headlineMedium
            )
            Row {
                if (multiSelectMode) {
                    val chosen = (localTracks + favorites).distinctBy { it.id }.filter { it.id in selectedTrackIds }
                    IconButton(onClick = {
                        if (chosen.isNotEmpty()) viewModel.playTrack(chosen.first(), chosen)
                        multiSelectMode = false; selectedTrackIds = emptySet()
                    }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Play selected")
                    }
                    IconButton(onClick = {
                        if (chosen.isNotEmpty()) onRequestAddTracksToPlaylist(chosen)
                        multiSelectMode = false; selectedTrackIds = emptySet()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = "Add selected to playlist")
                    }
                    IconButton(onClick = { multiSelectMode = false; selectedTrackIds = emptySet() }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Exit multi-select")
                    }
                } else {
                    IconButton(onClick = { multiSelectMode = true }) {
                        Icon(Icons.Rounded.CheckBox, contentDescription = "Multi-select mode")
                    }
                    IconButton(onClick = { safFileLauncher.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Rounded.AudioFile, contentDescription = "Import audio file")
                    }
                    IconButton(onClick = { safFolderLauncher.launch(null) }) {
                        Icon(Icons.Rounded.CreateNewFolder, contentDescription = "Add SAF music folder")
                    }
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort tracks")
                        }
                        DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                            LibrarySortOption.entries.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.label) },
                                    onClick = {
                                        sortMenuExpanded = false
                                        viewModel.updateLibrarySort(opt)
                                    }
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            if (hasAudioPermission) viewModel.rescanLocalLibrary()
                            else permLauncher.launch(audioPermission)
                        },
                        modifier = Modifier.testTag("library_rescan_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Scan local music")
                    }
                }
            }
        }

        if (isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 16.dp) {
            tabs.forEachIndexed { idx, label ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx; activeGroupFilter = null },
                    text = { Text(label) }
                )
            }
        }

        // Permission banner when local audio permission not yet granted
        if (!hasAudioPermission && selectedTab in 0..4) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Scan Device Audio Library", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Grant audio access to automatically discover MP3, FLAC, WAV, AAC, M4A, OGG & OPUS files, or pick folders via Storage Access Framework.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { permLauncher.launch(audioPermission) }) {
                            Text("Grant & Scan")
                        }
                        OutlinedButton(onClick = { safFolderLauncher.launch(null) }) {
                            Text("Pick Folder (SAF)")
                        }
                    }
                }
            }
        }

        // Drill-down group view (Album / Artist / Folder / Genre)
        val drillDown = activeGroupFilter
        if (drillDown != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(drillDown.first, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { activeGroupFilter = null }) { Text("Back") }
            }
            TrackListBody(
                tracks = drillDown.second,
                currentTrackId = playbackState.currentTrack?.id,
                multiSelectMode = multiSelectMode,
                selectedIds = selectedTrackIds,
                onToggleSelect = { id ->
                    selectedTrackIds = if (id in selectedTrackIds) selectedTrackIds - id else selectedTrackIds + id
                },
                viewModel = viewModel,
                onRequestAddToPlaylist = { onRequestAddTracksToPlaylist(listOf(it)) },
                onEditTags = { editingTagTrack = it }
            )
        } else {
            when (selectedTab) {
                0 -> TrackListBody(
                    tracks = localTracks,
                    emptyText = "No local audio files found yet. Tap Refresh to scan or pick a folder/file.",
                    currentTrackId = playbackState.currentTrack?.id,
                    multiSelectMode = multiSelectMode,
                    selectedIds = selectedTrackIds,
                    onToggleSelect = { id ->
                        selectedTrackIds = if (id in selectedTrackIds) selectedTrackIds - id else selectedTrackIds + id
                    },
                    viewModel = viewModel,
                    onRequestAddToPlaylist = { onRequestAddTracksToPlaylist(listOf(it)) },
                    onEditTags = { editingTagTrack = it }
                )
                1 -> GroupedLibraryList(
                    groups = localTracks.groupBy { it.album.ifBlank { "Unknown Album" } },
                    icon = Icons.Rounded.Album,
                    onSelectGroup = { name, list -> activeGroupFilter = name to list }
                )
                2 -> GroupedLibraryList(
                    groups = localTracks.groupBy { it.artist.ifBlank { "Unknown Artist" } },
                    icon = Icons.Rounded.Person,
                    onSelectGroup = { name, list -> activeGroupFilter = name to list }
                )
                3 -> GroupedLibraryList(
                    groups = localTracks.groupBy { it.folderPath.ifBlank { "Music" } },
                    icon = Icons.Rounded.Folder,
                    onSelectGroup = { name, list -> activeGroupFilter = name to list }
                )
                4 -> GroupedLibraryList(
                    groups = localTracks.groupBy { it.genre.ifBlank { "Local Audio" } },
                    icon = Icons.Rounded.LibraryMusic,
                    onSelectGroup = { name, list -> activeGroupFilter = name to list }
                )
                5 -> TrackListBody(
                    tracks = favorites,
                    emptyText = "No favorite tracks yet. Tap the heart icon on any song to save it here.",
                    currentTrackId = playbackState.currentTrack?.id,
                    multiSelectMode = multiSelectMode,
                    selectedIds = selectedTrackIds,
                    onToggleSelect = { id ->
                        selectedTrackIds = if (id in selectedTrackIds) selectedTrackIds - id else selectedTrackIds + id
                    },
                    viewModel = viewModel,
                    onRequestAddToPlaylist = { onRequestAddTracksToPlaylist(listOf(it)) },
                    onEditTags = { editingTagTrack = it }
                )
                6 -> DownloadsTabContent(
                    downloads = downloads,
                    viewModel = viewModel
                )
                7 -> TrackListBody(
                    tracks = recentlyPlayed,
                    emptyText = "Your listening history will appear here as you play songs.",
                    currentTrackId = playbackState.currentTrack?.id,
                    multiSelectMode = false,
                    selectedIds = emptySet(),
                    onToggleSelect = {},
                    viewModel = viewModel,
                    onRequestAddToPlaylist = { onRequestAddTracksToPlaylist(listOf(it)) },
                    onEditTags = { editingTagTrack = it }
                )
                8 -> TrackListBody(
                    tracks = mostPlayed,
                    emptyText = "Most played tracks will populate automatically.",
                    currentTrackId = playbackState.currentTrack?.id,
                    multiSelectMode = false,
                    selectedIds = emptySet(),
                    onToggleSelect = {},
                    viewModel = viewModel,
                    onRequestAddToPlaylist = { onRequestAddTracksToPlaylist(listOf(it)) },
                    onEditTags = { editingTagTrack = it }
                )
            }
        }
    }

    editingTagTrack?.let { target ->
        AudioTagEditorDialog(
            track = target,
            onDismiss = { editingTagTrack = null },
            onSave = { title, artist, album, albumArtist, genre, year, trackNo, art ->
                viewModel.saveAudioTags(target, title, artist, album, albumArtist, genre, year, trackNo, art)
                editingTagTrack = null
            }
        )
    }
}

@Composable
private fun TrackListBody(
    tracks: List<Track>,
    emptyText: String = "No tracks in this category.",
    currentTrackId: String?,
    multiSelectMode: Boolean,
    selectedIds: Set<String>,
    onToggleSelect: (String) -> Unit,
    viewModel: MainViewModel,
    onRequestAddToPlaylist: (Track) -> Unit,
    onEditTags: (Track) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 140.dp)
    ) {
        if (tracks.isEmpty()) {
            item {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
        items(tracks, key = { "lib_${it.id}" }) { track ->
            TrackRowItem(
                track = track,
                isPlayingNow = currentTrackId == track.id,
                isSelected = track.id in selectedIds,
                onClick = {
                    if (multiSelectMode) onToggleSelect(track.id)
                    else viewModel.playTrack(track, tracks)
                },
                onPlayNext = { viewModel.playNext(track) },
                onAddToQueue = { viewModel.addToQueue(track) },
                onAddToPlaylist = { onRequestAddToPlaylist(track) },
                onToggleFavorite = { viewModel.toggleFavorite(track) },
                onDownload = { viewModel.downloadTrack(track) },
                onEditTags = { onEditTags(track) },
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
    }
}

@Composable
private fun GroupedLibraryList(
    groups: Map<String, List<Track>>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onSelectGroup: (String, List<Track>) -> Unit
) {
    val entries = groups.entries.sortedBy { it.key.lowercase() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 140.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (entries.isEmpty()) {
            item {
                Text(
                    "Scan your device music to populate folders, albums, artists, and genres.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(entries, key = { it.key }) { (name, itemsList) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectGroup(name, itemsList) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${itemsList.size} track(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsTabContent(
    downloads: List<DownloadEntity>,
    viewModel: MainViewModel
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 140.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Offline downloads are enabled for Creative Commons & artist-permitted tracks on Audius and Jamendo. YouTube Music remains streaming-only per platform policy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        if (downloads.isEmpty()) {
            item {
                Text(
                    "No downloads yet. Open any downloadable Audius or Jamendo track menu and select Download Offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
        items(downloads, key = { it.trackId }) { dl ->
            val st = dl.statusEnum()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = st == DownloadStatus.COMPLETED) {
                        viewModel.playDownloadedEntity(dl)
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrackArtwork(
                            artworkUrl = dl.artworkUrl,
                            contentDescription = dl.title,
                            modifier = Modifier.size(48.dp),
                            cornerRadius = 10.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(dl.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${dl.artist} • ${st.name} ${if (dl.totalBytes > 0) "(${formatBytes(dl.totalBytes)})" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            dl.errorMessage?.let { err ->
                                Text(err, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        when (st) {
                            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                                IconButton(onClick = { viewModel.cancelDownload(dl.trackId) }) {
                                    Icon(Icons.Rounded.Cancel, contentDescription = "Cancel download")
                                }
                            }
                            DownloadStatus.FAILED, DownloadStatus.PAUSED, DownloadStatus.CANCELLED -> {
                                IconButton(onClick = { viewModel.retryDownload(dl) }) {
                                    Icon(Icons.Rounded.Replay, contentDescription = "Retry download")
                                }
                                IconButton(onClick = { viewModel.deleteDownload(dl) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Remove download")
                                }
                            }
                            DownloadStatus.COMPLETED -> {
                                IconButton(onClick = { viewModel.playDownloadedEntity(dl) }) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play offline track", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { viewModel.deleteDownload(dl) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete offline file")
                                }
                            }
                        }
                    }
                    if (st == DownloadStatus.DOWNLOADING || st == DownloadStatus.QUEUED) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { dl.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioTagEditorDialog(
    track: Track,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, Int, Int, String?) -> Unit
) {
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.album) }
    var albumArtist by remember { mutableStateOf(track.albumArtist) }
    var genre by remember { mutableStateOf(track.genre) }
    var year by remember { mutableStateOf(if (track.year > 0) track.year.toString() else "") }
    var trackNo by remember { mutableStateOf(if (track.trackNumber > 0) track.trackNumber.toString() else "") }
    var artworkUri by remember { mutableStateOf(track.artworkUrl) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) artworkUri = uri.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Audio Tags") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TrackArtwork(
                        artworkUrl = artworkUri,
                        contentDescription = title,
                        modifier = Modifier.size(64.dp),
                        cornerRadius = 12.dp
                    )
                    OutlinedButton(onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Rounded.Image, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Change Artwork")
                    }
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artist") }, singleLine = true)
                OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text("Album") }, singleLine = true)
                OutlinedTextField(value = albumArtist, onValueChange = { albumArtist = it }, label = { Text("Album Artist") }, singleLine = true)
                OutlinedTextField(value = genre, onValueChange = { genre = it }, label = { Text("Genre") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = year,
                        onValueChange = { year = it.filter(Char::isDigit).take(4) },
                        label = { Text("Year") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = trackNo,
                        onValueChange = { trackNo = it.filter(Char::isDigit).take(3) },
                        label = { Text("Track #") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    title, artist, album, albumArtist, genre,
                    year.toIntOrNull() ?: 0, trackNo.toIntOrNull() ?: 0, artworkUri
                )
            }) { Text("Save Tags") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
