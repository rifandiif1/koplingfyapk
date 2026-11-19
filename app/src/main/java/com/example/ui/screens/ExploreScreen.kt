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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.MusicSource
import com.example.domain.model.ProviderHealthState
import com.example.domain.model.RadioStation
import com.example.domain.model.Track
import com.example.ui.components.SourceBadge
import com.example.ui.components.TrackArtwork
import com.example.ui.components.TrackRowItem
import com.example.ui.viewmodel.MainViewModel

@Composable
fun ExploreScreen(
    viewModel: MainViewModel,
    onRequestAddToPlaylist: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val explore by viewModel.exploreState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val favRadio by viewModel.favoriteRadioStations.collectAsStateWithLifecycle()
    val customRadio by viewModel.customRadioStations.collectAsStateWithLifecycle()

    var showAddRadioDialog by remember { mutableStateOf(false) }

    val tabs = listOf("Audius", "YT Music", "Jamendo", "Radio", "Sources")
    val jamendoGenres = listOf("electronic", "rock", "pop", "jazz", "classical", "ambient", "hiphop", "acoustic")

    LaunchedEffect(explore.selectedTab) {
        when (explore.selectedTab) {
            0 -> if (explore.audiusTracks.isEmpty()) viewModel.refreshExploreTab(0)
            1 -> if (explore.ytMusicTracks.isEmpty()) viewModel.refreshExploreTab(1)
            2 -> if (explore.jamendoTracks.isEmpty()) viewModel.refreshExploreTab(2)
            3 -> if (explore.radioStations.isEmpty()) viewModel.refreshExploreTab(3)
            4 -> if (explore.providerStatuses.isEmpty()) viewModel.refreshExploreTab(4)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
            .testTag("explore_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Explore & Sources", style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = { viewModel.refreshExploreTab() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh tab")
            }
        }

        ScrollableTabRow(
            selectedTabIndex = explore.selectedTab,
            edgePadding = 16.dp
        ) {
            tabs.forEachIndexed { idx, label ->
                Tab(
                    selected = explore.selectedTab == idx,
                    onClick = { viewModel.selectExploreTab(idx) },
                    text = { Text(label) },
                    modifier = Modifier.testTag("explore_tab_$idx")
                )
            }
        }

        if (explore.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        when (explore.selectedTab) {
            0 -> TrackProviderList(
                tracks = explore.audiusTracks,
                emptyText = "Tap Refresh to load Audius trending tracks.",
                currentTrackId = playbackState.currentTrack?.id,
                viewModel = viewModel,
                onRequestAddToPlaylist = onRequestAddToPlaylist
            )
            1 -> TrackProviderList(
                tracks = explore.ytMusicTracks,
                emptyText = "No YouTube Music results from current Piped/Invidious node. Configure a custom Piped URL in Sources tab.",
                currentTrackId = playbackState.currentTrack?.id,
                viewModel = viewModel,
                onRequestAddToPlaylist = onRequestAddToPlaylist
            )
            2 -> Column(modifier = Modifier.fillMaxSize()) {
                if (settings.jamendoClientId.isBlank()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Jamendo API Client ID Required", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Jamendo provides free Creative Commons streaming & downloads. Get a free client_id at devportal.jamendo.com and enter it in the Sources tab.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { viewModel.selectExploreTab(4) }) {
                                Text("Configure in Sources")
                            }
                        }
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(jamendoGenres) { g ->
                            FilterChip(
                                selected = explore.selectedJamendoGenre == g,
                                onClick = { viewModel.selectJamendoGenre(g) },
                                label = { Text(g.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                    TrackProviderList(
                        tracks = explore.jamendoTracks,
                        emptyText = "No Jamendo tracks returned. Verify your client_id in Sources.",
                        currentTrackId = playbackState.currentTrack?.id,
                        viewModel = viewModel,
                        onRequestAddToPlaylist = onRequestAddToPlaylist
                    )
                }
            }
            3 -> RadioExploreContent(
                stations = explore.radioStations,
                favorites = favRadio,
                customStations = customRadio,
                query = explore.radioQuery,
                onQuerySubmit = { viewModel.searchRadioStations(it) },
                onPlayStation = { viewModel.playRadioStation(it) },
                onToggleFavorite = { viewModel.toggleFavoriteRadio(it) },
                onAddCustomClick = { showAddRadioDialog = true }
            )
            4 -> MusicSourcesManagerContent(viewModel = viewModel)
        }
    }

    if (showAddRadioDialog) {
        AddCustomRadioDialog(
            onDismiss = { showAddRadioDialog = false },
            onAdd = { name, url, genre ->
                viewModel.addCustomRadioStation(name, url, genre)
                showAddRadioDialog = false
            }
        )
    }
}

@Composable
private fun TrackProviderList(
    tracks: List<Track>,
    emptyText: String,
    currentTrackId: String?,
    viewModel: MainViewModel,
    onRequestAddToPlaylist: (Track) -> Unit
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
        items(tracks, key = { "exp_${it.id}" }) { track ->
            TrackRowItem(
                track = track,
                isPlayingNow = currentTrackId == track.id,
                onClick = { viewModel.playTrack(track, tracks) },
                onPlayNext = { viewModel.playNext(track) },
                onAddToQueue = { viewModel.addToQueue(track) },
                onAddToPlaylist = { onRequestAddToPlaylist(track) },
                onToggleFavorite = { viewModel.toggleFavorite(track) },
                onDownload = { viewModel.downloadTrack(track) },
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
    }
}

@Composable
private fun RadioExploreContent(
    stations: List<RadioStation>,
    favorites: List<RadioStation>,
    customStations: List<RadioStation>,
    query: String,
    onQuerySubmit: (String) -> Unit,
    onPlayStation: (RadioStation) -> Unit,
    onToggleFavorite: (RadioStation) -> Unit,
    onAddCustomClick: () -> Unit
) {
    var localQuery by remember(query) { mutableStateOf(query) }
    val favIds = favorites.map { it.id }.toSet()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 140.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = localQuery,
                    onValueChange = { localQuery = it },
                    placeholder = { Text("Search global radio...") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        IconButton(onClick = { onQuerySubmit(localQuery) }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search radio")
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onAddCustomClick,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("add_custom_radio_btn")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("URL")
                }
            }
        }

        if (favorites.isNotEmpty() || customStations.isNotEmpty()) {
            item {
                Text("Saved & Custom Stations", style = MaterialTheme.typography.titleMedium)
            }
            val saved = (customStations + favorites).distinctBy { it.id }
            items(saved, key = { "fav_rad_${it.id}" }) { st ->
                RadioStationRow(
                    station = st.copy(isFavorite = st.id in favIds || st.isFavorite),
                    onClick = { onPlayStation(st) },
                    onToggleFav = { onToggleFavorite(st.copy(isFavorite = st.id in favIds)) }
                )
            }
        }

        item {
            Text("Directory Stations", style = MaterialTheme.typography.titleMedium)
        }
        items(stations, key = { "dir_rad_${it.id}" }) { st ->
            val isFav = st.id in favIds
            RadioStationRow(
                station = st.copy(isFavorite = isFav),
                onClick = { onPlayStation(st) },
                onToggleFav = { onToggleFavorite(st.copy(isFavorite = isFav)) }
            )
        }
    }
}

@Composable
private fun RadioStationRow(
    station: RadioStation,
    onClick: () -> Unit,
    onToggleFav: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackArtwork(
                artworkUrl = station.artworkUrl,
                contentDescription = station.name,
                modifier = Modifier.size(50.dp),
                cornerRadius = 10.dp,
                isRadio = true
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(station.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        station.genre.takeIf { it.isNotBlank() },
                        station.country.takeIf { it.isNotBlank() },
                        "${station.bitrateKbps} kbps"
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggleFav) {
                Icon(
                    imageVector = if (station.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Favorite radio station",
                    tint = if (station.isFavorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MusicSourcesManagerContent(viewModel: MainViewModel) {
    val explore by viewModel.exploreState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var pipedInput by remember(settings.customPipedInstanceUrl) { mutableStateOf(settings.customPipedInstanceUrl) }
    var jamendoInput by remember(settings.jamendoClientId) { mutableStateOf(settings.jamendoClientId) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 140.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Provider Priority & Health", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Automatic failover uses priority order when a provider fails.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { viewModel.checkProvidersHealth() }) {
                    Text("Test All")
                }
            }
        }

        items(settings.sourcePriorityOrder, key = { it.name }) { source ->
            val enabled = source in settings.enabledSources
            val status = explore.providerStatuses.firstOrNull { it.source == source }
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SourceBadge(source)
                            Text(source.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        val statusColor = when (status?.health) {
                            ProviderHealthState.ONLINE -> Color(0xFF10D981)
                            ProviderHealthState.DEGRADED, ProviderHealthState.REQUIRES_KEY -> Color(0xFFF5A623)
                            ProviderHealthState.OFFLINE -> Color(0xFFFF5252)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when (status?.health) {
                                    ProviderHealthState.ONLINE -> Icons.Rounded.CheckCircle
                                    ProviderHealthState.REQUIRES_KEY -> Icons.Rounded.Key
                                    else -> Icons.Rounded.ErrorOutline
                                },
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = status?.let {
                                    if (it.latencyMs > 0) "${it.message} (${it.latencyMs}ms)" else it.message
                                } ?: if (enabled) "Enabled — tap Test All" else "Disabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.moveSourcePriority(source, -1) }) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move priority up")
                    }
                    IconButton(onClick = { viewModel.moveSourcePriority(source, 1) }) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move priority down")
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { viewModel.toggleSourceEnabled(source, it) }
                    )
                }
            }
        }

        // Custom Piped Instance Config
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("YouTube Music Free Gateway (Piped API)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "No paid API key needed. Uses open Piped/Invidious federation with automatic fallback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = pipedInput,
                        onValueChange = { pipedInput = it },
                        label = { Text("Preferred Piped API URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { viewModel.updatePipedUrl(pipedInput) }) {
                        Text("Save Gateway URL")
                    }
                }
            }
        }

        // Jamendo Client ID Config
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Jamendo API Configuration", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Register for a free client_id at devportal.jamendo.com to enable Creative Commons catalog & downloads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = jamendoInput,
                        onValueChange = { jamendoInput = it },
                        label = { Text("Jamendo Client ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { viewModel.updateJamendoClientId(jamendoInput) }) {
                        Text("Save Jamendo Client ID")
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCustomRadioDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var genre by remember { mutableStateOf("Live Radio") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Radio URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Station Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream URL (MP3/AAC/HLS)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text("Genre / Tag") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name, url, genre) }) { Text("Add Station") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
