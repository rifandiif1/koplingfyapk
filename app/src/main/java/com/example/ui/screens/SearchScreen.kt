package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.MusicSource
import com.example.domain.model.SearchCategory
import com.example.domain.model.SearchFieldFilter
import com.example.domain.model.Track
import com.example.ui.components.SourceBadge
import com.example.ui.components.TrackArtwork
import com.example.ui.components.TrackRowItem
import com.example.ui.viewmodel.MainViewModel

@Composable
fun SearchScreen(
    viewModel: MainViewModel,
    onRequestAddToPlaylist: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val category by viewModel.searchCategory.collectAsStateWithLifecycle()
    val fieldFilter by viewModel.searchFieldFilter.collectAsStateWithLifecycle()
    val selectedSources by viewModel.searchSelectedSources.collectAsStateWithLifecycle()
    val result by viewModel.searchResult.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { actResult ->
        if (actResult.resultCode == Activity.RESULT_OK) {
            val spoken = actResult.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                viewModel.submitSearch(spoken)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
            .testTag("search_screen")
    ) {
        Text(
            text = "Global Search",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
        )

        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.onSearchQueryChanged(it) },
            placeholder = { Text("Search songs, artists, albums, radio...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                Row {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Clear search")
                        }
                    }
                    IconButton(
                        onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                )
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak song, artist, or genre")
                            }
                            runCatching { voiceLauncher.launch(intent) }
                                .onFailure { viewModel.showMessage("Voice recognition unavailable on this device") }
                        },
                        modifier = Modifier.testTag("voice_search_button")
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Voice search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("global_search_input")
        )

        Spacer(Modifier.height(10.dp))

        // Category filter row
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(SearchCategory.entries) { cat ->
                FilterChip(
                    selected = category == cat,
                    onClick = { viewModel.setSearchCategory(cat) },
                    label = { Text(cat.label) }
                )
            }
        }

        // Source & Field Filters
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(MusicSource.entries) { src ->
                FilterChip(
                    selected = src in selectedSources,
                    onClick = { viewModel.toggleSearchSourceFilter(src) },
                    label = { Text(src.badgeLabel, style = MaterialTheme.typography.labelSmall) }
                )
            }
            items(SearchFieldFilter.entries) { f ->
                FilterChip(
                    selected = fieldFilter == f,
                    onClick = { viewModel.setSearchFieldFilter(f) },
                    label = { Text(f.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        if (result.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (query.isBlank()) {
            // Search History & Suggestions
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 140.dp)
            ) {
                if (recentSearches.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Recent Searches", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { viewModel.clearSearchHistory() }) {
                                Text("Clear All")
                            }
                        }
                    }
                    items(recentSearches, key = { it.query }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.submitSearch(item.query) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = item.query,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.deleteSearchHistoryItem(item.query) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove history item")
                            }
                        }
                    }
                } else {
                    item {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    "Cross-Provider Engine",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Search simultaneously across Local files, Audius, YouTube Music, Jamendo, and Radio Browser with automatic duplicate filtering.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 140.dp)
            ) {
                if ((category == SearchCategory.ALL || category == SearchCategory.SONGS) && result.tracks.isNotEmpty()) {
                    item {
                        Text(
                            "Songs (${result.tracks.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        )
                    }
                    items(result.tracks, key = { "sr_${it.id}" }) { track ->
                        TrackRowItem(
                            track = track,
                            isPlayingNow = playbackState.currentTrack?.id == track.id,
                            onClick = { viewModel.playTrack(track, listOf(track)) },
                            onPlayNext = { viewModel.playNext(track) },
                            onAddToQueue = { viewModel.addToQueue(track) },
                            onAddToPlaylist = { onRequestAddToPlaylist(track) },
                            onToggleFavorite = { viewModel.toggleFavorite(track) },
                            onDownload = { viewModel.downloadTrack(track) },
                            modifier = Modifier.padding(horizontal = 10.dp)
                        )
                    }
                }

                if ((category == SearchCategory.ALL || category == SearchCategory.ARTISTS) && result.artists.isNotEmpty()) {
                    item {
                        Text(
                            "Artists (${result.artists.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(result.artists, key = { it.id }) { artist ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(96.dp)
                                        .clickable { viewModel.submitSearch(artist.name) }
                                ) {
                                    TrackArtwork(
                                        artworkUrl = artist.artworkUrl,
                                        contentDescription = artist.name,
                                        modifier = Modifier.size(84.dp),
                                        cornerRadius = 500.dp
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        artist.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    SourceBadge(artist.source)
                                }
                            }
                        }
                    }
                }

                if ((category == SearchCategory.ALL || category == SearchCategory.ALBUMS) && result.albums.isNotEmpty()) {
                    item {
                        Text(
                            "Albums (${result.albums.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(result.albums, key = { it.id }) { album ->
                                Column(
                                    modifier = Modifier
                                        .width(126.dp)
                                        .clickable { viewModel.submitSearch("${album.title} ${album.artist}") }
                                ) {
                                    TrackArtwork(
                                        artworkUrl = album.artworkUrl,
                                        contentDescription = album.title,
                                        modifier = Modifier.size(120.dp),
                                        cornerRadius = 14.dp
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        album.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        album.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                if ((category == SearchCategory.ALL || category == SearchCategory.PLAYLISTS) && result.playlists.isNotEmpty()) {
                    item {
                        Text(
                            "Playlists (${result.playlists.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                    items(result.playlists, key = { it.id }) { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.submitSearch(pl.name) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TrackArtwork(
                                artworkUrl = pl.artworkUrl,
                                contentDescription = pl.name,
                                modifier = Modifier.size(48.dp),
                                cornerRadius = 10.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pl.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SourceBadge(pl.source)
                                    Text(
                                        pl.description.ifBlank { "${pl.trackCount} tracks" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                if ((category == SearchCategory.ALL || category == SearchCategory.RADIO) && result.radioStations.isNotEmpty()) {
                    item {
                        Text(
                            "Internet Radio (${result.radioStations.size})",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                    items(result.radioStations, key = { "srad_${it.id}" }) { st ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.playRadioStation(st) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TrackArtwork(
                                artworkUrl = st.artworkUrl,
                                contentDescription = st.name,
                                modifier = Modifier.size(48.dp),
                                cornerRadius = 10.dp,
                                isRadio = true
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(st.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(
                                    "${st.country} • ${st.genre} • ${st.bitrateKbps}kbps",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Icon(Icons.Rounded.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }

                if (result.tracks.isEmpty() && result.artists.isEmpty() && result.radioStations.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No matching results for \"$query\". Try switching active source filters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
