package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.RadioStation
import com.example.domain.model.Track
import com.example.ui.components.SourceBadge
import com.example.ui.components.TrackArtwork
import com.example.ui.components.TrackRowItem
import com.example.ui.viewmodel.MainViewModel
import java.util.Calendar

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenSources: () -> Unit,
    onOpenGenreSearch: (String) -> Unit,
    onRequestAddToPlaylist: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val homeState by viewModel.homeState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val recentlyPlayed by viewModel.recentlyPlayed.collectAsStateWithLifecycle()
    val favoriteTracks by viewModel.favoriteTracks.collectAsStateWithLifecycle()

    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..20 -> "Good Evening"
            else -> "Late Night Session"
        }
    }

    val genres = remember {
        listOf("Electronic", "Lo-Fi", "Pop", "Indie", "Hip-Hop", "Rock", "Jazz", "Acoustic", "Ambient")
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        // Luxury Obsidian Hero Header
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(216.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_home_hero),
                    contentDescription = "Koplingfy studio atmosphere",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.28f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "KOPLINGFY",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row {
                            IconButton(
                                onClick = onOpenSources,
                                modifier = Modifier.testTag("home_sources_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Hub,
                                    contentDescription = "Manage music sources",
                                    tint = Color.White
                                )
                            }
                            IconButton(
                                onClick = { viewModel.refreshHomeDiscovery() },
                                modifier = Modifier.testTag("home_refresh_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Refresh discovery",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    Column {
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Multi-source hi-fi streaming, synced lyrics & offline studio",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Continue Listening Card
        val resumeTrack = playbackState.currentTrack ?: recentlyPlayed.firstOrNull()
        if (resumeTrack != null) {
            item {
                SectionHeader(title = "Continue Listening")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable {
                            viewModel.playTrack(
                                resumeTrack,
                                recentlyPlayed.ifEmpty { listOf(resumeTrack) }
                            )
                        }
                        .testTag("continue_listening_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TrackArtwork(
                            artworkUrl = resumeTrack.artworkUrl,
                            contentDescription = resumeTrack.title,
                            modifier = Modifier.size(68.dp),
                            cornerRadius = 14.dp,
                            isRadio = resumeTrack.isLiveStream
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            SourceBadge(source = resumeTrack.source)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = resumeTrack.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = resumeTrack.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Resume track",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Genre Categories Strip
        item {
            SectionHeader(title = "Browse by Mood & Genre")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(genres) { genre ->
                    AssistChip(
                        onClick = { onOpenGenreSearch(genre) },
                        label = { Text(genre) },
                        modifier = Modifier.testTag("genre_chip_$genre")
                    )
                }
            }
        }

        // Recently Played Rail
        if (recentlyPlayed.isNotEmpty()) {
            item {
                SectionHeader(title = "Recently Played")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(recentlyPlayed.take(12), key = { "recent_${it.id}" }) { track ->
                        SquareTrackCard(
                            track = track,
                            onClick = { viewModel.playTrack(track, recentlyPlayed) }
                        )
                    }
                }
            }
        }

        // Loading / Offline Notice
        if (homeState.isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (homeState.errorMessage != null) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = homeState.errorMessage ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        // Trending Music (Real Audius & YouTube Music Catalog)
        if (homeState.trendingTracks.isNotEmpty()) {
            item {
                SectionHeader(title = "Trending Across Providers")
            }
            items(homeState.trendingTracks.take(10), key = { "trend_${it.id}" }) { track ->
                TrackRowItem(
                    track = track,
                    isPlayingNow = playbackState.currentTrack?.id == track.id,
                    onClick = { viewModel.playTrack(track, homeState.trendingTracks) },
                    onPlayNext = { viewModel.playNext(track) },
                    onAddToQueue = { viewModel.addToQueue(track) },
                    onAddToPlaylist = { onRequestAddToPlaylist(track) },
                    onToggleFavorite = { viewModel.toggleFavorite(track) },
                    onDownload = { viewModel.downloadTrack(track) },
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }
        }

        // New & Underground Releases Rail
        if (homeState.newReleases.isNotEmpty()) {
            item {
                SectionHeader(title = "New & Underground Releases")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(homeState.newReleases.take(12), key = { "new_${it.id}" }) { track ->
                        SquareTrackCard(
                            track = track,
                            onClick = { viewModel.playTrack(track, homeState.newReleases) }
                        )
                    }
                }
            }
        }

        // Live Global Radio Rail
        if (homeState.featuredRadio.isNotEmpty()) {
            item {
                SectionHeader(title = "Live Global Radio")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(homeState.featuredRadio.take(10), key = { "rad_${it.id}" }) { station ->
                        RadioHomeCard(
                            station = station,
                            onClick = { viewModel.playRadioStation(station) }
                        )
                    }
                }
            }
        }

        // Quick Favorites Rail
        if (favoriteTracks.isNotEmpty()) {
            item {
                SectionHeader(title = "Your Favorites")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(favoriteTracks.take(10), key = { "fav_${it.id}" }) { track ->
                        SquareTrackCard(
                            track = track,
                            onClick = { viewModel.playTrack(track, favoriteTracks) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 10.dp)
    )
}

@Composable
private fun SquareTrackCard(track: Track, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(142.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(modifier = Modifier.size(134.dp)) {
            TrackArtwork(
                artworkUrl = track.artworkUrl,
                contentDescription = track.title,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 16.dp,
                isRadio = track.isLiveStream
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            ) {
                SourceBadge(source = track.source)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RadioHomeCard(station: RadioStation, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackArtwork(
                artworkUrl = station.artworkUrl,
                contentDescription = station.name,
                modifier = Modifier.size(48.dp),
                cornerRadius = 10.dp,
                isRadio = true
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Radio,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${station.bitrateKbps}k LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = station.country.ifBlank { "Global" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
