package com.example.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.remote.ListenRoom
import com.example.ui.components.TrackArtwork
import com.example.ui.viewmodel.MainViewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListenTogetherScreen(
    viewModel: MainViewModel,
    onOpenSearch: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Crash-safe FirebaseAuth lookup: returns null instead of throwing IllegalStateException
    // when an APK is built without google-services.json (e.g. GitHub Actions CI without secret).
    val firebaseAuth = remember(context) { safeGetFirebaseAuth(context) }
    val firebaseAvailable = firebaseAuth != null
    val credentialManager = remember(context) {
        runCatching { CredentialManager.create(context) }.getOrNull()
    }

    var currentUser by remember(firebaseAuth) { mutableStateOf<FirebaseUser?>(firebaseAuth?.currentUser) }

    DisposableEffect(firebaseAuth) {
        if (firebaseAuth == null) {
            onDispose { }
        } else {
            val listener = FirebaseAuth.AuthStateListener { auth ->
                currentUser = auth.currentUser
            }
            firebaseAuth.addAuthStateListener(listener)
            onDispose { firebaseAuth.removeAuthStateListener(listener) }
        }
    }

    val controller = viewModel.container.listenTogetherController
    val uiState by viewModel.listenTogetherState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    // 0 = Wi-Fi / Hotspot Lokal (Tanpa Login), 1 = Cloud Internet (Firebase + Google Sign-In)
    var syncMode by remember(firebaseAvailable) { mutableIntStateOf(if (firebaseAvailable) 1 else 0) }
    var actionTab by remember { mutableIntStateOf(0) } // 0 = Buat Room, 1 = Gabung Kode
    var roomNameInput by remember { mutableStateOf("") }
    var joinCodeInput by remember { mutableStateOf("") }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let {
            viewModel.showMessage(it)
            controller.clearMessages()
        }
    }

    // Observe user's Cloud rooms strictly when signed in with non-blank UID
    LaunchedEffect(currentUser?.uid) {
        val u = currentUser
        if (u != null && u.uid.isNotBlank()) {
            if (uiState.username.isBlank()) {
                val defaultName = u.displayName?.substringBefore(" ")?.take(24) ?: "Listener"
                controller.updateUsernameDraft(defaultName)
            }
            controller.startObservingUserRooms(u.uid)
        }
    }

    val activeRoom = uiState.activeRoom
    val activeParticipantUid = if (uiState.isLocalLanRoom) {
        uiState.localPeerId
    } else {
        currentUser?.uid.orEmpty()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .testTag("listen_together_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Dengarkan Bersama",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Sinkronkan musik real-time dengan kode room",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (firebaseAuth != null && currentUser != null) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(NonCancellable) {
                                    controller.stopAllListeners()
                                    runCatching {
                                        credentialManager?.clearCredentialState(ClearCredentialStateRequest())
                                    }
                                    runCatching { firebaseAuth.signOut() }
                                    currentUser = null
                                }
                            }
                        },
                        modifier = Modifier.testTag("listen_together_signout_btn")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ExitToApp,
                            contentDescription = "Keluar akun",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Ganti Akun")
                    }
                }
            }
        }

        // Error banner if any
        uiState.errorMessage?.let { err ->
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        if (activeRoom != null) {
            // ==================== ACTIVE ROOM DASHBOARD (BOTH LAN & CLOUD) ====================
            item {
                ActiveRoomHeroCard(
                    room = activeRoom,
                    isHost = activeRoom.hostUid == activeParticipantUid,
                    isLocalLan = uiState.isLocalLanRoom,
                    onCopyCode = {
                        runCatching {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cb?.setPrimaryClip(ClipData.newPlainText("Koplingfy Room Code", activeRoom.roomCode))
                        }
                        viewModel.showMessage("Kode room ${activeRoom.roomCode} disalin!")
                    },
                    onShareCode = {
                        runCatching {
                            val modeHint = if (uiState.isLocalLanRoom) {
                                "Pastikan kita di Wi-Fi/Hotspot yang sama, lalu masukkan kode: ${activeRoom.roomCode}"
                            } else {
                                "Masukkan kode room: ${activeRoom.roomCode}"
                            }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Ayo dengarkan musik bareng di KOPLINGFY! $modeHint"
                                )
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Bagikan Kode Room"))
                        }
                    }
                )
            }

            // Synced Now Playing Card
            item {
                val syncedTrack = activeRoom.toSyncedTrack()
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SEDANG DIPUTAR DI ROOM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            if (activeRoom.updatedByUsername.isNotBlank()) {
                                Text(
                                    text = "Oleh @${activeRoom.updatedByUsername}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (syncedTrack != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(onClick = onOpenNowPlaying)
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TrackArtwork(
                                    artworkUrl = syncedTrack.artworkUrl,
                                    contentDescription = syncedTrack.title,
                                    modifier = Modifier.size(64.dp),
                                    cornerRadius = 14.dp
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = syncedTrack.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = syncedTrack.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.GraphicEq,
                                            contentDescription = null,
                                            tint = if (activeRoom.playing) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (activeRoom.playing) "Sedang Berjalan Sinkron" else "Dijeda",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (activeRoom.playing) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Belum ada lagu di room. Pilih lagu di menu Cari atau kirim lagu yang sedang Anda putar!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { controller.forceResyncNow() },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("listen_resync_btn")
                            ) {
                                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Sinkronkan")
                            }
                            Button(
                                onClick = {
                                    if (playbackState.currentTrack != null) {
                                        controller.pushCurrentTrackToRoom()
                                    } else {
                                        onOpenSearch()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("listen_pick_or_push_btn")
                            ) {
                                Icon(
                                    if (playbackState.currentTrack != null) Icons.Rounded.PlayArrow else Icons.Rounded.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (playbackState.currentTrack != null) "Kirim Laguku" else "Cari Lagu")
                            }
                        }

                        OutlinedButton(
                            onClick = onOpenSearch,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Pilih Lagu Baru dari YouTube Music")
                        }
                    }
                }
            }

            // Connected Listeners Card
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Pendengar Aktif (${activeRoom.memberUids.size}/20)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            activeRoom.memberUids.forEach { memberUid ->
                                val memberName = activeRoom.memberNames[memberUid] ?: "Listener"
                                val isRoomHost = memberUid == activeRoom.hostUid
                                val isMe = memberUid == activeParticipantUid
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (isRoomHost) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = if (isMe) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = memberName.take(1).uppercase(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = buildString {
                                                append(memberName)
                                                if (isMe) append(" (Kamu)")
                                                if (isRoomHost) append(" • HOST")
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isRoomHost || isMe) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Room Controls: Leave or Close
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { controller.leaveCurrentRoom() },
                        enabled = !uiState.isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("listen_leave_room_btn")
                    ) {
                        Text("Keluar Room")
                    }
                    if (activeRoom.hostUid == activeParticipantUid) {
                        Button(
                            onClick = { controller.closeCurrentRoom() },
                            enabled = !uiState.isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("listen_close_room_btn")
                        ) {
                            Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Tutup Room")
                        }
                    }
                }
            }
        } else {
            // ==================== MODE SELECTOR (WI-FI LOKAL VS CLOUD) ====================
            item {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("listen_mode_selector")
                ) {
                    SegmentedButton(
                        selected = syncMode == 0,
                        onClick = {
                            syncMode = 0
                            controller.clearMessages()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("listen_mode_lan")
                    ) {
                        Text("Wi-Fi / Hotspot", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SegmentedButton(
                        selected = syncMode == 1,
                        onClick = {
                            syncMode = 1
                            controller.clearMessages()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { Icon(Icons.Rounded.Cloud, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("listen_mode_cloud")
                    ) {
                        Text("Cloud Internet", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            if (syncMode == 0) {
                // ==================== LOCAL WI-FI / HOTSPOT LOBBY (ZERO LOGIN, ZERO CRASH) ====================
                item {
                    RoomLobbyCard(
                        badgeText = "TANPA LOGIN • WI-FI / HOTSPOT LOKAL",
                        helperText = "Buat atau gabung room dengan teman di jaringan Wi-Fi atau Hotspot yang sama tanpa perlu login akun.",
                        username = uiState.username,
                        onUsernameChange = { controller.updateUsernameDraft(it) },
                        actionTab = actionTab,
                        onSelectTab = { actionTab = it },
                        roomNameInput = roomNameInput,
                        onRoomNameChange = { roomNameInput = it.take(60) },
                        joinCodeInput = joinCodeInput,
                        onJoinCodeChange = {
                            joinCodeInput = it.uppercase().replace(Regex("[^A-Z0-9.:]"), "").take(24)
                        },
                        joinPlaceholder = "cth. W2F8K9P1M4",
                        isBusy = uiState.isBusy,
                        onCreateRoom = { controller.createLocalLanRoom(uiState.username, roomNameInput) },
                        onJoinRoom = { controller.joinLocalLanRoom(joinCodeInput, uiState.username) }
                    )
                }
            } else {
                // ==================== CLOUD INTERNET MODE (FIREBASE + GOOGLE SIGN-IN) ====================
                if (firebaseAuth == null) {
                    item {
                        CloudUnavailableCard(
                            onSwitchToLocalWifi = { syncMode = 0 }
                        )
                    }
                } else if (currentUser == null) {
                    item {
                        ListenTogetherCloudAuthCard(
                            firebaseAuth = firebaseAuth,
                            credentialManager = credentialManager,
                            onAuthSuccess = { currentUser = firebaseAuth.currentUser },
                            onSwitchToLocalWifi = { syncMode = 0 }
                        )
                    }
                } else {
                    val signedInUser = currentUser!!
                    item {
                        key(signedInUser.uid) {
                            RoomLobbyCard(
                                badgeText = "CLOUD SINKRON • JARAK JAUH",
                                helperText = "Dengarkan lagu yang sama bersama teman di mana saja lewat internet dengan kode room 6 karakter.",
                                username = uiState.username,
                                onUsernameChange = { controller.updateUsernameDraft(it) },
                                actionTab = actionTab,
                                onSelectTab = { actionTab = it },
                                roomNameInput = roomNameInput,
                                onRoomNameChange = { roomNameInput = it.take(60) },
                                joinCodeInput = joinCodeInput,
                                onJoinCodeChange = {
                                    joinCodeInput = it.uppercase().replace(Regex("[^A-Z0-9]"), "").take(12)
                                },
                                joinPlaceholder = "cth. KPL84X",
                                isBusy = uiState.isBusy,
                                onCreateRoom = { controller.createRoom(uiState.username, roomNameInput) },
                                onJoinRoom = { controller.joinRoom(joinCodeInput, uiState.username) }
                            )
                        }
                    }

                    // Rejoin user's previously active cloud rooms
                    if (uiState.myActiveRooms.isNotEmpty()) {
                        item {
                            Text(
                                text = "Room Cloud Aktif Anda",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(
                            items = uiState.myActiveRooms,
                            key = { it.id.ifBlank { it.roomCode } }
                        ) { room ->
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        controller.joinRoom(room.roomCode, uiState.username.ifBlank { room.hostUsername })
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = room.roomCode,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = room.roomName.ifBlank { "Room ${room.roomCode}" },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${room.memberUids.size} pendengar • ${room.trackTitle.ifBlank { "Menunggu lagu" }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = "Masuk kembali",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomLobbyCard(
    badgeText: String,
    helperText: String,
    username: String,
    onUsernameChange: (String) -> Unit,
    actionTab: Int,
    onSelectTab: (Int) -> Unit,
    roomNameInput: String,
    onRoomNameChange: (String) -> Unit,
    joinCodeInput: String,
    onJoinCodeChange: (String) -> Unit,
    joinPlaceholder: String,
    isBusy: Boolean,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }

            Text(
                text = helperText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Username Anda") },
                placeholder = { Text("cth. Rifandi") },
                leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("listen_username_input")
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = actionTab == 0,
                    onClick = { onSelectTab(0) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.testTag("listen_tab_create")
                ) {
                    Text("Buat Room")
                }
                SegmentedButton(
                    selected = actionTab == 1,
                    onClick = { onSelectTab(1) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.testTag("listen_tab_join")
                ) {
                    Text("Gabung Kode")
                }
            }

            AnimatedVisibility(visible = actionTab == 0) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = roomNameInput,
                        onValueChange = onRoomNameChange,
                        label = { Text("Nama Room (Opsional)") },
                        placeholder = { Text("cth. Nongkrong Malam") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onCreateRoom,
                        enabled = !isBusy && username.trim().length >= 2,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("listen_create_room_btn")
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Rounded.Headphones, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Buat Room & Dapatkan Kode", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = actionTab == 1) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = joinCodeInput,
                        onValueChange = onJoinCodeChange,
                        label = { Text("Kode Sinkronisasi Room") },
                        placeholder = { Text(joinPlaceholder) },
                        leadingIcon = { Icon(Icons.Rounded.Tag, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("listen_join_code_input")
                    )
                    Button(
                        onClick = onJoinRoom,
                        enabled = !isBusy && username.trim().length >= 2 && joinCodeInput.length >= 4,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("listen_join_room_btn")
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Rounded.Sync, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Gabung & Sinkronkan", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudUnavailableCard(
    onSwitchToLocalWifi: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("listen_cloud_unavailable_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Cloud,
                        contentDescription = "Mode Cloud",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Text(
                text = "Mode Cloud Belum Dikonfigurasi",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Build APK ini belum menyertakan konfigurasi Firebase (google-services.json). Untuk sinkronisasi tanpa login sekarang juga, gunakan Mode Wi-Fi / Hotspot Lokal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onSwitchToLocalWifi,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("listen_switch_to_lan_btn")
            ) {
                Icon(Icons.Rounded.Wifi, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Gunakan Mode Wi-Fi / Hotspot", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ListenTogetherCloudAuthCard(
    firebaseAuth: FirebaseAuth,
    credentialManager: CredentialManager?,
    onAuthSuccess: () -> Unit,
    onSwitchToLocalWifi: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    // Silent auto sign-in check for returning users (separate single-option request)
    LaunchedEffect(firebaseAuth, credentialManager) {
        if (credentialManager != null) {
            attemptSilentGoogleSignIn(
                context = context,
                firebaseAuth = firebaseAuth,
                credentialManager = credentialManager,
                onAuthSuccess = onAuthSuccess
            )
        }
    }

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("listen_together_auth_gate")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Headphones,
                        contentDescription = "Dengarkan Bersama Cloud",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Text(
                text = "Sinkronisasi Cloud Jarak Jauh",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Masuk dengan Google untuk membuat room jarak jauh lintas jaringan internet. Pemutar musik utama tetap bisa dipakai tanpa login.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            authError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = {
                    if (credentialManager == null) {
                        authError = "Layanan Google Sign-In tidak tersedia di perangkat ini. Gunakan Mode Wi-Fi / Hotspot."
                        return@Button
                    }
                    isLoading = true
                    authError = null
                    launchInteractiveGoogleSignIn(
                        context = context,
                        firebaseAuth = firebaseAuth,
                        credentialManager = credentialManager,
                        scope = scope,
                        onSuccess = {
                            isLoading = false
                            onAuthSuccess()
                        },
                        onCancelled = {
                            isLoading = false
                        },
                        onError = { msg ->
                            isLoading = false
                            authError = msg
                        }
                    )
                },
                enabled = !isLoading,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("listen_together_google_signin_btn")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Rounded.Login, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Masuk dengan Google",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            OutlinedButton(
                onClick = onSwitchToLocalWifi,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tanpa Login: Pakai Wi-Fi / Hotspot")
            }
        }
    }
}

@Composable
private fun ActiveRoomHeroCard(
    room: ListenRoom,
    isHost: Boolean,
    isLocalLan: Boolean,
    onCopyCode: () -> Unit,
    onShareCode: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.20f),
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            )
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RadioButtonChecked,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        val modeLabel = if (isLocalLan) "WI-FI" else "CLOUD"
                        Text(
                            text = if (isHost) "LIVE $modeLabel • HOST" else "LIVE $modeLabel • SINKRON",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = room.roomName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.30f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "KODE SINKRONISASI",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = room.roomCode,
                            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 3.sp),
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("listen_active_room_code")
                        )
                    }
                    Row {
                        IconButton(onClick = onCopyCode, modifier = Modifier.testTag("listen_copy_code_btn")) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Salin kode")
                        }
                        IconButton(onClick = onShareCode, modifier = Modifier.testTag("listen_share_code_btn")) {
                            Icon(Icons.Rounded.Share, contentDescription = "Bagikan kode")
                        }
                    }
                }
            }
        }
    }
}

private fun safeGetFirebaseAuth(context: Context): FirebaseAuth? {
    return runCatching {
        val appContext = context.applicationContext ?: context
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            FirebaseApp.initializeApp(appContext)
        }
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            null
        } else {
            Firebase.auth
        }
    }.onFailure { err ->
        Log.w("ListenTogether", "FirebaseApp unavailable on this APK build: ${err.message}")
    }.getOrNull()
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private fun resolveDefaultWebClientId(context: Context): String? {
    val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    if (resId == 0) return null
    return runCatching { context.getString(resId) }.getOrNull()?.takeIf { it.isNotBlank() }
}

private suspend fun attemptSilentGoogleSignIn(
    context: Context,
    firebaseAuth: FirebaseAuth,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit
) {
    runCatching {
        if (firebaseAuth.currentUser != null) {
            onAuthSuccess()
            return
        }
        val clientId = resolveDefaultWebClientId(context) ?: return
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        val cred = credentialManager.getCredential(context, request).credential
        if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
            firebaseAuth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            onAuthSuccess()
        }
    }.onFailure { err ->
        Log.d("Auth", "Silent Google Sign-In skipped: ${err.message}")
    }
}

private fun launchInteractiveGoogleSignIn(
    context: Context,
    firebaseAuth: FirebaseAuth,
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSuccess: () -> Unit,
    onCancelled: () -> Unit,
    onError: (String) -> Unit
) {
    val clientId = resolveDefaultWebClientId(context)
    if (clientId == null) {
        onError("Konfigurasi Google Sign-In belum tersedia pada build ini. Gunakan Mode Wi-Fi / Hotspot.")
        return
    }
    val activity = context.findActivity()
    if (activity == null) {
        onError("Activity tidak tersedia untuk Google Sign-In.")
        return
    }
    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(signInOption)
        .build()
    scope.launch {
        try {
            val cred = credentialManager.getCredential(activity, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                firebaseAuth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                onSuccess()
            } else {
                onError("Tipe kredensial tidak dikenali.")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Google Sign-In cancelled or dismissed: ${e.message}", e)
            onCancelled()
        } catch (e: Throwable) {
            Log.e("Auth", "Google Sign-In failed", e)
            onError(e.localizedMessage ?: "Gagal masuk dengan Google. Coba Mode Wi-Fi / Hotspot.")
        }
    }
}
