package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.domain.model.Track
import com.example.ui.components.MiniPlayerBar
import com.example.ui.screens.EqualizerScreen
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.LyricsScreen
import com.example.ui.screens.NowPlayingScreen
import com.example.ui.screens.PlaylistsScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.KoplingfyTheme
import com.example.ui.viewmodel.MainViewModel

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val EXPLORE = "explore"
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"
    const val NOW_PLAYING = "now_playing"
    const val LYRICS = "lyrics"
    const val EQUALIZER = "equalizer"
}

private data class NavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

class MainActivity : ComponentActivity() {

    private var openNowPlayingOnStart by mutableStateOf(false)
    private var incomingAudioUri by mutableStateOf<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        val container = (application as KoplingfyApplication).container

        setContent {
            val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(container))
            val settings by vm.settings.collectAsStateWithLifecycle()

            KoplingfyTheme(
                themeMode = settings.themeMode,
                accentPreset = settings.accentPreset,
                dynamicColor = settings.dynamicColor
            ) {
                KoplingfyRootApp(
                    viewModel = vm,
                    openNowPlayingInitially = openNowPlayingOnStart,
                    onConsumedOpenNowPlaying = { openNowPlayingOnStart = false },
                    incomingAudioUri = incomingAudioUri,
                    onConsumedAudioUri = { incomingAudioUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("open_now_playing", false)) {
            openNowPlayingOnStart = true
        }
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            incomingAudioUri = intent.data
        }
    }
}

@Composable
private fun KoplingfyRootApp(
    viewModel: MainViewModel,
    openNowPlayingInitially: Boolean,
    onConsumedOpenNowPlaying: () -> Unit,
    incomingAudioUri: android.net.Uri?,
    onConsumedAudioUri: () -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val customPlaylists by viewModel.customPlaylists.collectAsStateWithLifecycle()

    var pendingTracksForPlaylist by remember { mutableStateOf<List<Track>?>(null) }

    // Request POST_NOTIFICATIONS on Android 13+ so Media3 Foreground notification displays cleanly
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(openNowPlayingInitially) {
        if (openNowPlayingInitially) {
            navController.navigate(Routes.NOW_PLAYING) { launchSingleTop = true }
            onConsumedOpenNowPlaying()
        }
    }

    LaunchedEffect(incomingAudioUri) {
        if (incomingAudioUri != null) {
            viewModel.importLocalAudioFile(incomingAudioUri)
            onConsumedAudioUri()
        }
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeMessage()
        }
    }

    val tabs = remember {
        listOf(
            NavTab(Routes.HOME, "Home", Icons.Rounded.Home, Icons.Outlined.Home),
            NavTab(Routes.SEARCH, "Search", Icons.Rounded.Search, Icons.Outlined.Search),
            NavTab(Routes.EXPLORE, "Explore", Icons.Rounded.Explore, Icons.Outlined.Explore),
            NavTab(Routes.LIBRARY, "Library", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic),
            NavTab(Routes.PLAYLISTS, "Playlists", Icons.AutoMirrored.Rounded.PlaylistPlay, Icons.AutoMirrored.Rounded.PlaylistPlay),
            NavTab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings)
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME
    val isFullScreenOverlay = currentRoute in setOf(Routes.NOW_PLAYING, Routes.LYRICS, Routes.EQUALIZER)

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!isFullScreenOverlay && !useNavRail) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (playbackState.currentTrack != null) {
                            MiniPlayerBar(
                                playbackState = playbackState,
                                onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                                onTogglePlayPause = { viewModel.container.playbackController.togglePlayPause() },
                                onSkipNext = { viewModel.container.playbackController.skipToNext() }
                            )
                        }
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.testTag("bottom_nav_bar")
                        ) {
                            tabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label, maxLines = 1) },
                                    alwaysShowLabel = false,
                                    modifier = Modifier.testTag("nav_${tab.route}")
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (isFullScreenOverlay) androidx.compose.foundation.layout.PaddingValues(0.dp) else innerPadding)
            ) {
                if (!isFullScreenOverlay && useNavRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        Spacer(Modifier.height(12.dp))
                        tabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                viewModel = viewModel,
                                onOpenSources = {
                                    viewModel.selectExploreTab(4)
                                    navigateToTab(Routes.EXPLORE)
                                },
                                onOpenGenreSearch = { genre ->
                                    viewModel.submitSearch(genre)
                                    navigateToTab(Routes.SEARCH)
                                },
                                onRequestAddToPlaylist = { track -> pendingTracksForPlaylist = listOf(track) }
                            )
                        }
                        composable(Routes.SEARCH) {
                            SearchScreen(
                                viewModel = viewModel,
                                onRequestAddToPlaylist = { track -> pendingTracksForPlaylist = listOf(track) }
                            )
                        }
                        composable(Routes.EXPLORE) {
                            ExploreScreen(
                                viewModel = viewModel,
                                onRequestAddToPlaylist = { track -> pendingTracksForPlaylist = listOf(track) }
                            )
                        }
                        composable(Routes.LIBRARY) {
                            LibraryScreen(
                                viewModel = viewModel,
                                onRequestAddTracksToPlaylist = { tracks -> pendingTracksForPlaylist = tracks }
                            )
                        }
                        composable(Routes.PLAYLISTS) {
                            PlaylistsScreen(viewModel = viewModel)
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                viewModel = viewModel,
                                onOpenEqualizer = { navController.navigate(Routes.EQUALIZER) },
                                onOpenSources = {
                                    viewModel.selectExploreTab(4)
                                    navigateToTab(Routes.EXPLORE)
                                }
                            )
                        }
                        composable(Routes.NOW_PLAYING) {
                            NowPlayingScreen(
                                viewModel = viewModel,
                                onClose = { navController.popBackStack() },
                                onOpenLyrics = { navController.navigate(Routes.LYRICS) },
                                onOpenEqualizer = { navController.navigate(Routes.EQUALIZER) }
                            )
                        }
                        composable(Routes.LYRICS) {
                            LyricsScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Routes.EQUALIZER) {
                            EqualizerScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    // Tablet / Expanded screen floating MiniPlayer overlay
                    if (!isFullScreenOverlay && useNavRail && playbackState.currentTrack != null) {
                        MiniPlayerBar(
                            playbackState = playbackState,
                            onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                            onTogglePlayPause = { viewModel.container.playbackController.togglePlayPause() },
                            onSkipNext = { viewModel.container.playbackController.skipToNext() },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                        )
                    }
                }
            }
        }
    }

    // Add-to-Playlist Picker Dialog
    pendingTracksForPlaylist?.let { tracksToAdd ->
        AlertDialog(
            onDismissRequest = { pendingTracksForPlaylist = null },
            title = { Text("Add ${tracksToAdd.size} Track(s) to Playlist") },
            text = {
                if (customPlaylists.isEmpty()) {
                    Text("No custom playlists yet. Create one quickly below.")
                } else {
                    LazyColumn(modifier = Modifier.height(220.dp)) {
                        items(customPlaylists, key = { it.id }) { pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.addTracksToPlaylist(pl.id, tracksToAdd)
                                        pendingTracksForPlaylist = null
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(pl.name, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createPlaylist("Mix #${customPlaylists.size + 1}", initialTracks = tracksToAdd)
                    pendingTracksForPlaylist = null
                }) { Text("New Playlist") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTracksForPlaylist = null }) { Text("Cancel") }
            }
        )
    }
}
