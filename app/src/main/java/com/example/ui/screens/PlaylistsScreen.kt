package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.db.PlaylistEntity
import com.example.domain.model.SmartPlaylistType
import com.example.domain.model.Track
import com.example.ui.components.TrackRowItem
import com.example.ui.viewmodel.MainViewModel

@Composable
fun PlaylistsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val customPlaylists by viewModel.customPlaylists.collectAsStateWithLifecycle()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsStateWithLifecycle()
    val mostPlayed by viewModel.mostPlayed.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteTracks.collectAsStateWithLifecycle()
    val localTracks by viewModel.allLocalTracks.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var openedCustomPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var openedSmartPlaylist by remember { mutableStateOf<SmartPlaylistType?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
            .testTag("playlists_screen")
    ) {
        val openCustom = openedCustomPlaylist
        val openSmart = openedSmartPlaylist

        when {
            openCustom != null -> {
                val tracks by viewModel.observePlaylistTracks(openCustom.id)
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                PlaylistDetailView(
                    title = openCustom.name,
                    subtitle = openCustom.description.ifBlank { "${tracks.size} tracks" },
                    tracks = tracks,
                    currentTrackId = playbackState.currentTrack?.id,
                    canRemoveTrack = true,
                    onBack = { openedCustomPlaylist = null },
                    onPlayAll = { if (tracks.isNotEmpty()) viewModel.playTrack(tracks.first(), tracks) },
                    onPlayTrack = { t -> viewModel.playTrack(t, tracks) },
                    onRemoveTrack = { t -> viewModel.removeTrackFromPlaylist(openCustom.id, t.id) }
                )
            }
            openSmart != null -> {
                val smartTracks = when (openSmart) {
                    SmartPlaylistType.RECENTLY_PLAYED -> recentlyPlayed
                    SmartPlaylistType.MOST_PLAYED -> mostPlayed
                    SmartPlaylistType.FAVORITES -> favorites
                    SmartPlaylistType.RECENTLY_ADDED -> localTracks.sortedByDescending { it.dateAdded }
                    SmartPlaylistType.DOWNLOADED -> localTracks.filter { !it.localPath.isNullOrBlank() }
                }
                PlaylistDetailView(
                    title = openSmart.title,
                    subtitle = "Smart Playlist • ${smartTracks.size} tracks",
                    tracks = smartTracks,
                    currentTrackId = playbackState.currentTrack?.id,
                    canRemoveTrack = false,
                    onBack = { openedSmartPlaylist = null },
                    onPlayAll = { if (smartTracks.isNotEmpty()) viewModel.playTrack(smartTracks.first(), smartTracks) },
                    onPlayTrack = { t -> viewModel.playTrack(t, smartTracks) },
                    onRemoveTrack = {}
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 150.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Playlists", style = MaterialTheme.typography.headlineMedium)
                            if (playbackState.queue.isNotEmpty()) {
                                OutlinedButton(onClick = {
                                    viewModel.saveCurrentQueueAsPlaylist("Queue Mix ${customPlaylists.size + 1}")
                                }) {
                                    Text("Save Queue")
                                }
                            }
                        }
                    }

                    item {
                        Text("Smart Playlists", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(SmartPlaylistType.entries) { smart ->
                                val count = when (smart) {
                                    SmartPlaylistType.RECENTLY_PLAYED -> recentlyPlayed.size
                                    SmartPlaylistType.MOST_PLAYED -> mostPlayed.size
                                    SmartPlaylistType.FAVORITES -> favorites.size
                                    SmartPlaylistType.RECENTLY_ADDED -> localTracks.size
                                    SmartPlaylistType.DOWNLOADED -> localTracks.count { !it.localPath.isNullOrBlank() }
                                }
                                Card(
                                    modifier = Modifier
                                        .width(156.dp)
                                        .clickable { openedSmartPlaylist = smart },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Icon(
                                            Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        Text(smart.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                        Text(
                                            "$count tracks",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text("Your Custom Playlists", style = MaterialTheme.typography.titleMedium)
                    }

                    if (customPlaylists.isEmpty()) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("Create Your First Mix", style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Tap the + button to build custom playlists mixing local tracks, Audius, YouTube Music, and Jamendo.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    items(customPlaylists, key = { it.id }) { pl ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openedCustomPlaylist = pl },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.PlaylistPlay,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(34.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(pl.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (pl.description.isNotBlank()) {
                                        Text(
                                            pl.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                                IconButton(onClick = { editingPlaylist = pl }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Edit playlist")
                                }
                                IconButton(onClick = { viewModel.deletePlaylist(pl.id) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete playlist")
                                }
                            }
                        }
                    }
                }

                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 150.dp)
                        .testTag("create_playlist_fab")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Create playlist")
                }
            }
        }
    }

    if (showCreateDialog) {
        PlaylistEditorDialog(
            title = "Create Playlist",
            initialName = "",
            initialDescription = "",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, desc ->
                viewModel.createPlaylist(name, desc)
                showCreateDialog = false
            }
        )
    }

    editingPlaylist?.let { target ->
        PlaylistEditorDialog(
            title = "Edit Playlist",
            initialName = target.name,
            initialDescription = target.description,
            onDismiss = { editingPlaylist = null },
            onConfirm = { name, desc ->
                viewModel.renamePlaylist(target.id, name, desc)
                editingPlaylist = null
            }
        )
    }
}

@Composable
private fun PlaylistDetailView(
    title: String,
    subtitle: String,
    tracks: List<Track>,
    currentTrackId: String?,
    canRemoveTrack: Boolean,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onRemoveTrack: (Track) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to playlists")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onPlayAll, enabled = tracks.isNotEmpty()) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Play All")
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {
            if (tracks.isEmpty()) {
                item {
                    Text(
                        "This playlist has no tracks yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
            items(tracks, key = { "pldet_${it.id}" }) { track ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackRowItem(
                        track = track,
                        isPlayingNow = currentTrackId == track.id,
                        onClick = { onPlayTrack(track) },
                        modifier = Modifier.weight(1f)
                    )
                    if (canRemoveTrack) {
                        IconButton(onClick = { onRemoveTrack(track) }) {
                            Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "Remove from playlist")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistEditorDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, description) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
