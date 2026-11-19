package com.example.data.remote

import android.util.Log
import com.example.data.local.prefs.SettingsDataStore
import com.example.domain.model.Track
import com.example.playback.PlaybackController
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class ListenTogetherUiState(
    val activeRoom: ListenRoom? = null,
    val isLocalLanRoom: Boolean = false,
    val localPeerId: String = "",
    val myActiveRooms: List<ListenRoom> = emptyList(),
    val username: String = "",
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

/**
 * Dual-mode real-time synchronization controller for "Dengarkan Bersama":
 * - Cloud Mode (Internet): strictly auth-gated behind Firebase Google Sign-In + Firestore.
 * - Local Wi-Fi / Hotspot Mode: zero-login LAN TCP sync (works on every Android install even
 *   when google-services.json is absent).
 *
 * Every Firebase call is safely guarded so missing Firebase initialization never causes a crash.
 */
class ListenTogetherController(
    private val repositoryProvider: () -> ListenTogetherRepository,
    private val playbackController: PlaybackController,
    private val settingsDataStore: SettingsDataStore,
    private val lanEngine: LocalLanListenTogetherEngine = LocalLanListenTogetherEngine()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        ListenTogetherUiState(localPeerId = lanEngine.localPeerId)
    )
    val state: StateFlow<ListenTogetherUiState> = _state.asStateFlow()

    private var activeRoomJob: Job? = null
    private var myRoomsJob: Job? = null
    private var lastAppliedSignature: String? = null

    private fun safeFirebaseUid(): String? = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()

    private fun safeRepository(): ListenTogetherRepository? = runCatching { repositoryProvider() }
        .onFailure { Log.w("ListenTogether", "Firebase Firestore unavailable on this build", it) }
        .getOrNull()

    init {
        scope.launch {
            settingsDataStore.settingsFlow.collect { s ->
                if (_state.value.username.isBlank() && s.listenUsername.isNotBlank()) {
                    _state.value = _state.value.copy(username = s.listenUsername)
                }
            }
        }

        // Broadcast local playback changes when connected to an active Listen Together room
        playbackController.onLocalPlaybackAction = { track, isPlaying, posMs ->
            val currentState = _state.value
            val room = currentState.activeRoom
            if (room != null && room.active) {
                if (currentState.isLocalLanRoom) {
                    val uname = currentState.username.ifBlank {
                        room.memberNames[lanEngine.localPeerId] ?: "Listener"
                    }
                    scope.launch {
                        lanEngine.syncPlaybackState(
                            username = uname,
                            track = track,
                            playing = isPlaying,
                            positionMs = posMs
                        )
                    }
                } else {
                    val uid = safeFirebaseUid()
                    val repo = if (!uid.isNullOrBlank()) safeRepository() else null
                    if (!uid.isNullOrBlank() && repo != null) {
                        val uname = currentState.username.ifBlank { room.memberNames[uid] ?: "Listener" }
                        scope.launch {
                            repo.syncPlaybackState(
                                roomCode = room.roomCode,
                                username = uname,
                                track = track,
                                playing = isPlaying,
                                positionMs = posMs
                            )
                        }
                    }
                }
            }
        }
    }

    fun updateUsernameDraft(name: String) {
        _state.value = _state.value.copy(username = name.take(30), errorMessage = null)
    }

    fun startObservingUserRooms(userId: String) {
        val currentUid = safeFirebaseUid()
        if (userId.isBlank() || currentUid != userId) return
        val repo = safeRepository() ?: return
        myRoomsJob?.cancel()
        myRoomsJob = scope.launch {
            repo.observeMyRooms(userId)
                .catch { err ->
                    val fsErr = generateSequence(err) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && err is CancellationException) throw err
                    Log.w("ListenTogether", "Error observing user rooms", fsErr ?: err)
                    _state.value = _state.value.copy(
                        errorMessage = (fsErr ?: err).localizedMessage ?: "Gagal memuat daftar room"
                    )
                }
                .collect { rooms ->
                    _state.value = _state.value.copy(myActiveRooms = rooms)
                }
        }
    }

    fun stopAllListeners() {
        activeRoomJob?.cancel()
        activeRoomJob = null
        myRoomsJob?.cancel()
        myRoomsJob = null
        lastAppliedSignature = null
        lanEngine.stopHostServer()
        _state.value = _state.value.copy(
            activeRoom = null,
            isLocalLanRoom = false,
            myActiveRooms = emptyList(),
            isBusy = false
        )
    }

    // ==================== CLOUD MODE (FIREBASE FIRESTORE) ====================

    fun createRoom(username: String, roomName: String) {
        val cleanName = username.trim()
        if (cleanName.length < 2) {
            _state.value = _state.value.copy(errorMessage = "Masukkan username minimal 2 karakter.")
            return
        }
        val repo = safeRepository()
        if (repo == null) {
            _state.value = _state.value.copy(
                errorMessage = "Layanan Cloud belum tersedia di build ini. Gunakan mode Wi-Fi / Hotspot."
            )
            return
        }
        scope.launch {
            _state.value = _state.value.copy(isBusy = true, errorMessage = null, statusMessage = null)
            lanEngine.leaveOrClose()
            settingsDataStore.updateListenUsername(cleanName)
            val pb = playbackController.state.value
            val res = repo.createRoom(
                username = cleanName,
                roomName = roomName,
                currentTrack = pb.currentTrack,
                isPlaying = pb.isPlaying,
                positionMs = pb.positionMs
            )
            res.onSuccess { room ->
                _state.value = _state.value.copy(
                    activeRoom = room,
                    isLocalLanRoom = false,
                    username = cleanName,
                    isBusy = false,
                    statusMessage = "Room Cloud ${room.roomCode} dibuat! Bagikan kode ke temanmu."
                )
                observeActiveCloudRoom(room.roomCode)
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isBusy = false,
                    errorMessage = err.localizedMessage ?: "Gagal membuat room cloud"
                )
            }
        }
    }

    fun joinRoom(roomCode: String, username: String) {
        val cleanName = username.trim()
        if (cleanName.length < 2) {
            _state.value = _state.value.copy(errorMessage = "Masukkan username minimal 2 karakter.")
            return
        }
        val cleanCode = roomCode.trim().uppercase()
        if (cleanCode.length !in 4..12) {
            _state.value = _state.value.copy(errorMessage = "Kode room harus 4–12 huruf/angka.")
            return
        }
        val repo = safeRepository()
        if (repo == null) {
            _state.value = _state.value.copy(
                errorMessage = "Layanan Cloud belum tersedia di build ini. Gunakan mode Wi-Fi / Hotspot."
            )
            return
        }
        scope.launch {
            _state.value = _state.value.copy(isBusy = true, errorMessage = null, statusMessage = null)
            lanEngine.leaveOrClose()
            settingsDataStore.updateListenUsername(cleanName)
            val res = repo.joinRoom(cleanCode, cleanName)
            res.onSuccess { room ->
                lastAppliedSignature = null
                _state.value = _state.value.copy(
                    activeRoom = room,
                    isLocalLanRoom = false,
                    username = cleanName,
                    isBusy = false,
                    statusMessage = "Terhubung ke room ${room.roomCode}!"
                )
                applyRoomStateToLocalPlayer(room, forceApply = true)
                observeActiveCloudRoom(room.roomCode)
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isBusy = false,
                    errorMessage = err.localizedMessage ?: "Gagal bergabung ke room"
                )
            }
        }
    }

    private fun observeActiveCloudRoom(roomCode: String) {
        val repo = safeRepository() ?: return
        activeRoomJob?.cancel()
        activeRoomJob = scope.launch {
            repo.observeRoom(roomCode)
                .catch { err ->
                    val fsErr = generateSequence(err) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (fsErr == null && err is CancellationException) throw err
                    Log.w("ListenTogether", "Room listener error", fsErr ?: err)
                    _state.value = _state.value.copy(
                        errorMessage = (fsErr ?: err).localizedMessage ?: "Koneksi sinkronisasi terputus"
                    )
                }
                .collect { room ->
                    if (room == null || !room.active) {
                        _state.value = _state.value.copy(
                            activeRoom = null,
                            isLocalLanRoom = false,
                            statusMessage = "Room telah ditutup oleh host."
                        )
                        activeRoomJob?.cancel()
                        return@collect
                    }
                    _state.value = _state.value.copy(activeRoom = room, isLocalLanRoom = false)
                    val myUid = safeFirebaseUid()
                    if (room.updatedByUid != myUid) {
                        applyRoomStateToLocalPlayer(room, forceApply = false)
                    }
                }
        }
    }

    // ==================== LOCAL WI-FI / HOTSPOT MODE (NO LOGIN) ====================

    fun createLocalLanRoom(username: String, roomName: String) {
        val cleanName = username.trim()
        if (cleanName.length < 2) {
            _state.value = _state.value.copy(errorMessage = "Masukkan username minimal 2 karakter.")
            return
        }
        scope.launch {
            _state.value = _state.value.copy(isBusy = true, errorMessage = null, statusMessage = null)
            activeRoomJob?.cancel()
            settingsDataStore.updateListenUsername(cleanName)
            val pb = playbackController.state.value
            val res = lanEngine.startHostRoom(
                username = cleanName,
                roomName = roomName,
                currentTrack = pb.currentTrack,
                isPlaying = pb.isPlaying,
                positionMs = pb.positionMs
            )
            res.onSuccess { room ->
                lastAppliedSignature = null
                _state.value = _state.value.copy(
                    activeRoom = room,
                    isLocalLanRoom = true,
                    username = cleanName,
                    isBusy = false,
                    statusMessage = "Room Wi-Fi ${room.roomCode} aktif! Bagikan kode ke teman di Wi-Fi/Hotspot yang sama."
                )
                observeLocalLanRoom(isHost = true)
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isBusy = false,
                    errorMessage = err.localizedMessage ?: "Gagal membuat room Wi-Fi lokal"
                )
            }
        }
    }

    fun joinLocalLanRoom(roomCode: String, username: String) {
        val cleanName = username.trim()
        if (cleanName.length < 2) {
            _state.value = _state.value.copy(errorMessage = "Masukkan username minimal 2 karakter.")
            return
        }
        scope.launch {
            _state.value = _state.value.copy(isBusy = true, errorMessage = null, statusMessage = null)
            activeRoomJob?.cancel()
            settingsDataStore.updateListenUsername(cleanName)
            val res = lanEngine.joinLanRoom(roomCode, cleanName)
            res.onSuccess { room ->
                lastAppliedSignature = null
                _state.value = _state.value.copy(
                    activeRoom = room,
                    isLocalLanRoom = true,
                    username = cleanName,
                    isBusy = false,
                    statusMessage = "Terhubung ke room Wi-Fi ${room.roomCode}!"
                )
                applyRoomStateToLocalPlayer(room, forceApply = true)
                observeLocalLanRoom(isHost = false)
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isBusy = false,
                    errorMessage = err.localizedMessage ?: "Gagal terhubung ke room Wi-Fi"
                )
            }
        }
    }

    private fun observeLocalLanRoom(isHost: Boolean) {
        activeRoomJob?.cancel()
        val flow = if (isHost) lanEngine.observeHostRoom() else lanEngine.observeRemoteLanRoom()
        activeRoomJob = scope.launch {
            flow.catch { err ->
                if (err is CancellationException) throw err
                Log.w("ListenTogether", "Local LAN listener error", err)
                _state.value = _state.value.copy(
                    errorMessage = err.localizedMessage ?: "Koneksi Wi-Fi ke host terputus"
                )
            }.collect { room ->
                if (room == null || !room.active) {
                    if (_state.value.isLocalLanRoom && _state.value.activeRoom != null) {
                        _state.value = _state.value.copy(
                            activeRoom = null,
                            isLocalLanRoom = false,
                            statusMessage = "Room Wi-Fi ditutup atau host terputus."
                        )
                    }
                    activeRoomJob?.cancel()
                    return@collect
                }
                _state.value = _state.value.copy(activeRoom = room, isLocalLanRoom = true)
                if (room.updatedByUid != lanEngine.localPeerId) {
                    applyRoomStateToLocalPlayer(room, forceApply = false)
                }
            }
        }
    }

    // ==================== SHARED ROOM CONTROLS ====================

    fun forceResyncNow() {
        val room = _state.value.activeRoom ?: return
        lastAppliedSignature = null
        applyRoomStateToLocalPlayer(room, forceApply = true)
        _state.value = _state.value.copy(statusMessage = "Disinkronkan dengan room ${room.roomCode}")
    }

    fun pushCurrentTrackToRoom() {
        val currentState = _state.value
        val room = currentState.activeRoom ?: return
        val pb = playbackController.state.value
        val uname = currentState.username.ifBlank { "Listener" }
        scope.launch {
            val res = if (currentState.isLocalLanRoom) {
                lanEngine.syncPlaybackState(
                    username = uname,
                    track = pb.currentTrack,
                    playing = pb.isPlaying,
                    positionMs = pb.positionMs
                )
            } else {
                val repo = safeRepository()
                if (repo == null) {
                    Result.failure(IllegalStateException("Koneksi Cloud tidak tersedia."))
                } else {
                    repo.syncPlaybackState(
                        roomCode = room.roomCode,
                        username = uname,
                        track = pb.currentTrack,
                        playing = pb.isPlaying,
                        positionMs = pb.positionMs
                    )
                }
            }
            res.onSuccess {
                _state.value = _state.value.copy(statusMessage = "Lagu saat ini disinkronkan ke semua anggota.")
            }.onFailure { err ->
                _state.value = _state.value.copy(errorMessage = err.localizedMessage ?: "Gagal menyinkronkan")
            }
        }
    }

    fun leaveCurrentRoom() {
        val currentState = _state.value
        val room = currentState.activeRoom ?: return
        scope.launch {
            _state.value = _state.value.copy(isBusy = true)
            activeRoomJob?.cancel()
            activeRoomJob = null
            if (currentState.isLocalLanRoom) {
                lanEngine.leaveOrClose()
            } else {
                safeRepository()?.leaveRoom(room.roomCode)
            }
            lastAppliedSignature = null
            _state.value = _state.value.copy(
                activeRoom = null,
                isLocalLanRoom = false,
                isBusy = false,
                statusMessage = "Keluar dari room ${room.roomCode}"
            )
        }
    }

    fun closeCurrentRoom() {
        val currentState = _state.value
        val room = currentState.activeRoom ?: return
        scope.launch {
            _state.value = _state.value.copy(isBusy = true)
            activeRoomJob?.cancel()
            activeRoomJob = null
            if (currentState.isLocalLanRoom) {
                lanEngine.leaveOrClose()
            } else {
                safeRepository()?.closeRoom(room.roomCode)
            }
            lastAppliedSignature = null
            _state.value = _state.value.copy(
                activeRoom = null,
                isLocalLanRoom = false,
                isBusy = false,
                statusMessage = "Room ${room.roomCode} telah ditutup."
            )
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(errorMessage = null, statusMessage = null)
    }

    private fun applyRoomStateToLocalPlayer(room: ListenRoom, forceApply: Boolean) {
        val track: Track = room.toSyncedTrack() ?: return
        val sig = "${room.trackId}|${room.playing}|${room.playbackUpdatedAtMs}"
        if (!forceApply && sig == lastAppliedSignature) return
        lastAppliedSignature = sig
        val targetPos = room.estimatedPositionMs()
        playbackController.applyRemoteSync(
            track = track,
            shouldPlay = room.playing,
            targetPositionMs = targetPos
        )
    }
}
