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
    val myActiveRooms: List<ListenRoom> = emptyList(),
    val username: String = "",
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

/**
 * Auth-gated real-time synchronization controller for "Dengarkan Bersama".
 * Never runs any Firestore listener or write unless Firebase.auth.currentUser != null.
 */
class ListenTogetherController(
    private val repositoryProvider: () -> ListenTogetherRepository,
    private val playbackController: PlaybackController,
    private val settingsDataStore: SettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ListenTogetherUiState())
    val state: StateFlow<ListenTogetherUiState> = _state.asStateFlow()

    private var activeRoomJob: Job? = null
    private var myRoomsJob: Job? = null
    private var lastAppliedSignature: String? = null

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
            val room = _state.value.activeRoom
            val uid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
            if (room != null && room.active && !uid.isNullOrBlank()) {
                val uname = _state.value.username.ifBlank { room.memberNames[uid] ?: "Listener" }
                scope.launch {
                    repositoryProvider().syncPlaybackState(
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

    fun updateUsernameDraft(name: String) {
        _state.value = _state.value.copy(username = name.take(30), errorMessage = null)
    }

    fun startObservingUserRooms(userId: String) {
        if (userId.isBlank() || Firebase.auth.currentUser?.uid != userId) return
        myRoomsJob?.cancel()
        myRoomsJob = scope.launch {
            repositoryProvider().observeMyRooms(userId)
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
        _state.value = _state.value.copy(
            activeRoom = null,
            myActiveRooms = emptyList(),
            isBusy = false
        )
    }

    fun createRoom(username: String, roomName: String) {
        val cleanName = username.trim()
        if (cleanName.length < 2) {
            _state.value = _state.value.copy(errorMessage = "Masukkan username minimal 2 karakter.")
            return
        }
        scope.launch {
            _state.value = _state.value.copy(isBusy = true, errorMessage = null, statusMessage = null)
            settingsDataStore.updateListenUsername(cleanName)
            val pb = playbackController.state.value
            val res = repositoryProvider().createRoom(
                username = cleanName,
                roomName = roomName,
                currentTrack = pb.currentTrack,
                isPlaying = pb.isPlaying,
                positionMs = pb.positionMs
            )
            res.onSuccess { room ->
                _state.value = _state.value.copy(
                    activeRoom = room,
                    username = cleanName,
                    isBusy = false,
                    statusMessage = "Room ${room.roomCode} dibuat! Bagikan kode ke temanmu."
                )
                observeActiveRoom(room.roomCode)
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isBusy = false,
                    errorMessage = err.localizedMessage ?: "Gagal membuat room"
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
        scope.launch {
            _state.value = _state.value.copy(isBusy = true, errorMessage = null, statusMessage = null)
            settingsDataStore.updateListenUsername(cleanName)
            val res = repositoryProvider().joinRoom(cleanCode, cleanName)
            res.onSuccess { room ->
                lastAppliedSignature = null
                _state.value = _state.value.copy(
                    activeRoom = room,
                    username = cleanName,
                    isBusy = false,
                    statusMessage = "Terhubung ke room ${room.roomCode}!"
                )
                applyRoomStateToLocalPlayer(room, forceApply = true)
                observeActiveRoom(room.roomCode)
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isBusy = false,
                    errorMessage = err.localizedMessage ?: "Gagal bergabung ke room"
                )
            }
        }
    }

    private fun observeActiveRoom(roomCode: String) {
        activeRoomJob?.cancel()
        activeRoomJob = scope.launch {
            repositoryProvider().observeRoom(roomCode)
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
                            statusMessage = "Room telah ditutup oleh host."
                        )
                        activeRoomJob?.cancel()
                        return@collect
                    }
                    _state.value = _state.value.copy(activeRoom = room)
                    val myUid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
                    // Apply updates originating from other participants
                    if (room.updatedByUid != myUid) {
                        applyRoomStateToLocalPlayer(room, forceApply = false)
                    }
                }
        }
    }

    fun forceResyncNow() {
        val room = _state.value.activeRoom ?: return
        lastAppliedSignature = null
        applyRoomStateToLocalPlayer(room, forceApply = true)
        _state.value = _state.value.copy(statusMessage = "Disinkronkan dengan room ${room.roomCode}")
    }

    fun pushCurrentTrackToRoom() {
        val room = _state.value.activeRoom ?: return
        val pb = playbackController.state.value
        val uname = _state.value.username.ifBlank { "Listener" }
        scope.launch {
            repositoryProvider().syncPlaybackState(
                roomCode = room.roomCode,
                username = uname,
                track = pb.currentTrack,
                playing = pb.isPlaying,
                positionMs = pb.positionMs
            ).onSuccess {
                _state.value = _state.value.copy(statusMessage = "Lagu saat ini disinkronkan ke semua anggota.")
            }.onFailure { err ->
                _state.value = _state.value.copy(errorMessage = err.localizedMessage ?: "Gagal menyinkronkan")
            }
        }
    }

    fun leaveCurrentRoom() {
        val room = _state.value.activeRoom ?: return
        scope.launch {
            _state.value = _state.value.copy(isBusy = true)
            activeRoomJob?.cancel()
            activeRoomJob = null
            repositoryProvider().leaveRoom(room.roomCode)
            lastAppliedSignature = null
            _state.value = _state.value.copy(
                activeRoom = null,
                isBusy = false,
                statusMessage = "Keluar dari room ${room.roomCode}"
            )
        }
    }

    fun closeCurrentRoom() {
        val room = _state.value.activeRoom ?: return
        scope.launch {
            _state.value = _state.value.copy(isBusy = true)
            activeRoomJob?.cancel()
            activeRoomJob = null
            repositoryProvider().closeRoom(room.roomCode)
            lastAppliedSignature = null
            _state.value = _state.value.copy(
                activeRoom = null,
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
