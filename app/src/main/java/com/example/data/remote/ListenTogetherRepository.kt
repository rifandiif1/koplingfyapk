package com.example.data.remote

import android.content.Context
import androidx.annotation.Keep
import com.example.R
import com.example.domain.model.MusicSource
import com.example.domain.model.Track
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

@Keep
@IgnoreExtraProperties
data class ListenRoom(
    @DocumentId val id: String = "",
    val roomCode: String = "",
    val hostUid: String = "",
    val hostUsername: String = "",
    val roomName: String = "",
    val active: Boolean = true,
    val memberUids: List<String> = emptyList(),
    val memberNames: Map<String, String> = emptyMap(),
    val trackId: String = "",
    val trackTitle: String = "",
    val trackArtist: String = "",
    val trackAlbum: String = "",
    val trackArtworkUrl: String = "",
    val trackDurationMs: Long = 0L,
    val trackSource: String = "",
    val trackSourceId: String = "",
    val trackStreamUrl: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val playbackUpdatedAtMs: Long = 0L,
    val updatedByUid: String = "",
    val updatedByUsername: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toSyncedTrack(): Track? {
        if (trackId.isBlank() || trackTitle.isBlank()) return null
        val src = MusicSource.entries.firstOrNull { it.name == trackSource } ?: MusicSource.YOUTUBE_MUSIC
        return Track(
            id = trackId,
            title = trackTitle,
            artist = trackArtist.ifBlank { "Unknown Artist" },
            album = trackAlbum,
            durationMs = trackDurationMs.coerceAtLeast(0L),
            artworkUrl = trackArtworkUrl.takeIf { it.isNotBlank() },
            streamUrl = trackStreamUrl.takeIf { it.isNotBlank() },
            source = src,
            sourceTrackId = trackSourceId.ifBlank { trackId.removePrefix("ytm_") },
            isDownloadable = false,
            isLiveStream = src == MusicSource.RADIO
        )
    }

    fun estimatedPositionMs(nowEpochMs: Long = System.currentTimeMillis()): Long {
        if (!playing || playbackUpdatedAtMs <= 0L) return positionMs.coerceAtLeast(0L)
        val elapsed = (nowEpochMs - playbackUpdatedAtMs).coerceAtLeast(0L)
        val est = positionMs + elapsed
        return if (trackDurationMs > 0L) est.coerceIn(0L, trackDurationMs) else est
    }
}

class ListenTogetherRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val roomsCollection get() = db.collection("listen_rooms")

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("Silakan masuk dengan Google untuk menggunakan Dengarkan Bersama.")
    }

    private fun sanitizeUsername(raw: String): String {
        val cleaned = raw.trim().take(30)
        require(cleaned.length >= 2) { "Username minimal 2 karakter." }
        return cleaned
    }

    private fun normalizeRoomCode(raw: String): String {
        val code = raw.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
        require(code.length in 4..12) { "Kode room harus 4–12 huruf/angka." }
        return code
    }

    fun generateRoomCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(6) {
            repeat(6) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
    }

    suspend fun createRoom(
        username: String,
        roomName: String,
        currentTrack: Track?,
        isPlaying: Boolean,
        positionMs: Long
    ): Result<ListenRoom> {
        val code = generateRoomCode()
        val path = "listen_rooms/$code"
        return try {
            val uid = requireUserId()
            val validName = sanitizeUsername(username)
            val cleanRoomName = roomName.trim().ifBlank { "Room $validName" }.take(60)
            val nowMs = System.currentTimeMillis()

            val payload = mapOf(
                "roomCode" to code,
                "hostUid" to uid,
                "hostUsername" to validName,
                "roomName" to cleanRoomName,
                "active" to true,
                "memberUids" to listOf(uid),
                "memberNames" to mapOf(uid to validName),
                "trackId" to (currentTrack?.id?.take(128) ?: ""),
                "trackTitle" to (currentTrack?.title?.take(200) ?: ""),
                "trackArtist" to (currentTrack?.artist?.take(150) ?: ""),
                "trackAlbum" to (currentTrack?.album?.take(150) ?: ""),
                "trackArtworkUrl" to (currentTrack?.artworkUrl?.take(500) ?: ""),
                "trackDurationMs" to (currentTrack?.durationMs?.coerceIn(0L, 86_400_000L) ?: 0L),
                "trackSource" to (currentTrack?.source?.name ?: ""),
                "trackSourceId" to (currentTrack?.sourceTrackId?.take(128) ?: ""),
                "trackStreamUrl" to (currentTrack?.streamUrl?.take(500) ?: ""),
                "playing" to (currentTrack != null && isPlaying),
                "positionMs" to positionMs.coerceIn(0L, 86_400_000L),
                "playbackUpdatedAtMs" to nowMs,
                "updatedByUid" to uid,
                "updatedByUsername" to validName,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            roomsCollection.document(code).set(payload).await()
            val created = roomsCollection.document(code).get().await()
                .toObject(ListenRoom::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: ListenRoom(
                    id = code,
                    roomCode = code,
                    hostUid = uid,
                    hostUsername = validName,
                    roomName = cleanRoomName,
                    active = true,
                    memberUids = listOf(uid),
                    memberNames = mapOf(uid to validName)
                )
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun joinRoom(rawCode: String, username: String): Result<ListenRoom> {
        var path = "listen_rooms"
        return try {
            val uid = requireUserId()
            val validName = sanitizeUsername(username)
            val code = normalizeRoomCode(rawCode)
            val docRef = roomsCollection.document(code)
            path = docRef.path

            val snap = docRef.get().await()
            if (!snap.exists()) {
                return Result.failure(IllegalArgumentException("Room dengan kode '$code' tidak ditemukan."))
            }
            val room = snap.toObject(ListenRoom::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("Data room tidak valid."))
            if (!room.active) {
                return Result.failure(IllegalStateException("Room '$code' sudah ditutup oleh host."))
            }
            if (uid !in room.memberUids && room.memberUids.size >= 20) {
                return Result.failure(IllegalStateException("Room penuh (maksimal 20 pendengar)."))
            }

            val updatedNames = room.memberNames.toMutableMap().apply { put(uid, validName) }
            if (uid in room.memberUids) {
                docRef.update(
                    mapOf(
                        "memberNames" to updatedNames,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val updatedUids = (room.memberUids + uid).distinct()
                docRef.update(
                    mapOf(
                        "memberUids" to updatedUids,
                        "memberNames" to updatedNames,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            val refreshed = docRef.get().await()
                .toObject(ListenRoom::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: room.copy(
                    memberUids = (room.memberUids + uid).distinct(),
                    memberNames = updatedNames
                )
            Result.success(refreshed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun syncPlaybackState(
        roomCode: String,
        username: String,
        track: Track?,
        playing: Boolean,
        positionMs: Long
    ): Result<Unit> {
        val code = roomCode.trim().uppercase()
        val path = "listen_rooms/$code"
        return try {
            val uid = requireUserId()
            val validName = sanitizeUsername(username)
            val docRef = roomsCollection.document(code)
            val updateMap = mapOf(
                "trackId" to (track?.id?.take(128) ?: ""),
                "trackTitle" to (track?.title?.take(200) ?: ""),
                "trackArtist" to (track?.artist?.take(150) ?: ""),
                "trackAlbum" to (track?.album?.take(150) ?: ""),
                "trackArtworkUrl" to (track?.artworkUrl?.take(500) ?: ""),
                "trackDurationMs" to (track?.durationMs?.coerceIn(0L, 86_400_000L) ?: 0L),
                "trackSource" to (track?.source?.name ?: ""),
                "trackSourceId" to (track?.sourceTrackId?.take(128) ?: ""),
                "trackStreamUrl" to (track?.streamUrl?.take(500) ?: ""),
                "playing" to (track != null && playing),
                "positionMs" to positionMs.coerceIn(0L, 86_400_000L),
                "playbackUpdatedAtMs" to System.currentTimeMillis(),
                "updatedByUid" to uid,
                "updatedByUsername" to validName,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.update(updateMap).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun leaveRoom(roomCode: String): Result<Unit> {
        val code = roomCode.trim().uppercase()
        val path = "listen_rooms/$code"
        return try {
            val uid = requireUserId()
            val docRef = roomsCollection.document(code)
            val snap = docRef.get().await()
            if (!snap.exists()) return Result.success(Unit)
            val room = snap.toObject(ListenRoom::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.success(Unit)
            if (!room.active || uid !in room.memberUids) return Result.success(Unit)

            val remainingUids = room.memberUids.filterNot { it == uid }
            val remainingNames = room.memberNames.filterKeys { it != uid }
            docRef.update(
                mapOf(
                    "memberUids" to remainingUids,
                    "memberNames" to remainingNames,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun closeRoom(roomCode: String): Result<Unit> {
        val code = roomCode.trim().uppercase()
        val path = "listen_rooms/$code"
        return try {
            requireUserId()
            roomsCollection.document(code).update(
                mapOf(
                    "active" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    fun observeRoom(roomCode: String): Flow<ListenRoom?> = flow {
        val uid = requireUserId()
        val code = normalizeRoomCode(roomCode)
        val path = "listen_rooms/$code"
        emitAll(
            roomsCollection.document(code)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(ListenRoom::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val ex = firestoreError ?: (error as? Exception)
                    if (ex != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(ex, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    fun observeMyRooms(userId: String): Flow<List<ListenRoom>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user changed." }
        val path = "listen_rooms"
        emitAll(
            roomsCollection
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(ListenRoom::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter { it.active }
                        .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val ex = firestoreError ?: (error as? Exception)
                    if (ex != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(ex, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }
}
