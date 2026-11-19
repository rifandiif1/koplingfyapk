package com.example.data.remote

import android.util.Log
import com.example.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Zero-login local Wi-Fi / Hotspot real-time synchronization engine for "Dengarkan Bersama".
 * Uses raw TCP JSON-line sockets (works on every Android API 26–36 device without Firebase or
 * Google Sign-In, including GitHub Actions builds without google-services.json).
 */
class LocalLanListenTogetherEngine {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val hostRoomRef = AtomicReference<ListenRoom?>(null)
    private val hostRoomFlow = MutableStateFlow<ListenRoom?>(null)

    private var remoteHostEndpoint: Pair<String, Int>? = null

    val localPeerId: String = "lan_${UUID.randomUUID().toString().replace("-", "").take(12)}"

    val isHosting: Boolean
        get() = serverSocket != null && serverSocket?.isClosed == false && hostRoomRef.get()?.active == true

    suspend fun startHostRoom(
        username: String,
        roomName: String,
        currentTrack: Track?,
        isPlaying: Boolean,
        positionMs: Long,
        preferredPort: Int = DEFAULT_PORT,
        explicitHostIp: String? = null
    ): Result<ListenRoom> = withContext(Dispatchers.IO) {
        try {
            val cleanName = username.trim().take(30)
            require(cleanName.length >= 2) { "Username minimal 2 karakter." }
            val cleanRoomName = roomName.trim().ifBlank { "Room $cleanName" }.take(60)

            stopHostServer()

            val localIp = explicitHostIp?.takeIf { it.isNotBlank() }
                ?: detectLocalIpv4Address()
                ?: return@withContext Result.failure(
                    IllegalStateException("Aktifkan Wi-Fi atau Hotspot untuk membuat room lokal.")
                )

            val socket = openServerSocket(preferredPort)
            val boundPort = socket.localPort
            val code = encodeRoomCode(localIp, boundPort)
            val nowMs = System.currentTimeMillis()

            val room = ListenRoom(
                id = code,
                roomCode = code,
                hostUid = localPeerId,
                hostUsername = cleanName,
                roomName = cleanRoomName,
                active = true,
                memberUids = listOf(localPeerId),
                memberNames = mapOf(localPeerId to cleanName),
                trackId = currentTrack?.id?.take(128).orEmpty(),
                trackTitle = currentTrack?.title?.take(200).orEmpty(),
                trackArtist = currentTrack?.artist?.take(150).orEmpty(),
                trackAlbum = currentTrack?.album?.take(150).orEmpty(),
                trackArtworkUrl = currentTrack?.artworkUrl?.take(500).orEmpty(),
                trackDurationMs = currentTrack?.durationMs?.coerceIn(0L, 86_400_000L) ?: 0L,
                trackSource = currentTrack?.source?.name.orEmpty(),
                trackSourceId = currentTrack?.sourceTrackId?.take(128).orEmpty(),
                trackStreamUrl = currentTrack?.streamUrl?.take(500).orEmpty(),
                playing = currentTrack != null && isPlaying,
                positionMs = positionMs.coerceIn(0L, 86_400_000L),
                playbackUpdatedAtMs = nowMs,
                updatedByUid = localPeerId,
                updatedByUsername = cleanName
            )

            serverSocket = socket
            remoteHostEndpoint = null
            hostRoomRef.set(room)
            hostRoomFlow.value = room

            acceptJob = ioScope.launch {
                runAcceptLoop(socket)
            }

            Log.d(TAG, "LAN Host started at $localIp:$boundPort (code=$code)")
            Result.success(room)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start LAN host room", e)
            stopHostServer()
            Result.failure(e)
        }
    }

    suspend fun joinLanRoom(rawCode: String, username: String): Result<ListenRoom> = withContext(Dispatchers.IO) {
        try {
            val cleanName = username.trim().take(30)
            require(cleanName.length >= 2) { "Username minimal 2 karakter." }
            val endpoint = decodeRoomCode(rawCode)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Kode room lokal tidak valid. Pastikan kode diawali huruf 'W' atau format IP:Port.")
                )

            stopHostServer()

            val req = JSONObject().apply {
                put("action", "join")
                put("uid", localPeerId)
                put("username", cleanName)
            }
            val resp = sendTcpJson(endpoint.first, endpoint.second, req)
                ?: return@withContext Result.failure(
                    IllegalStateException("Tidak dapat terhubung ke Host (${endpoint.first}). Pastikan berada di Wi-Fi atau Hotspot yang sama.")
                )

            if (!resp.optBoolean("ok", false)) {
                val msg = resp.optString("error", "Gagal bergabung ke room lokal.")
                return@withContext Result.failure(IllegalStateException(msg))
            }

            val roomJson = resp.optJSONObject("room")
                ?: return@withContext Result.failure(IllegalStateException("Respons room lokal tidak valid."))
            val room = roomFromJson(roomJson)
            if (!room.active) {
                return@withContext Result.failure(IllegalStateException("Room lokal sudah ditutup oleh host."))
            }
            remoteHostEndpoint = endpoint
            Result.success(room)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to join LAN room", e)
            Result.failure(e)
        }
    }

    fun observeHostRoom(): Flow<ListenRoom?> = hostRoomFlow.asStateFlow()

    fun observeRemoteLanRoom(): Flow<ListenRoom?> = flow {
        val endpoint = remoteHostEndpoint ?: return@flow
        var consecutiveFailures = 0
        while (true) {
            val req = JSONObject().apply {
                put("action", "state")
                put("uid", localPeerId)
            }
            val resp = withContext(Dispatchers.IO) {
                runCatching { sendTcpJson(endpoint.first, endpoint.second, req) }.getOrNull()
            }
            if (resp != null && resp.optBoolean("ok", false)) {
                consecutiveFailures = 0
                val roomObj = resp.optJSONObject("room")
                if (roomObj != null) {
                    val room = roomFromJson(roomObj)
                    emit(room)
                    if (!room.active) break
                }
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= 5) {
                    emit(null)
                    break
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    suspend fun syncPlaybackState(
        username: String,
        track: Track?,
        playing: Boolean,
        positionMs: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanName = username.trim().ifBlank { "Listener" }.take(30)
            val nowMs = System.currentTimeMillis()
            if (isHosting) {
                val current = hostRoomRef.get() ?: return@withContext Result.success(Unit)
                val updated = current.copy(
                    trackId = track?.id?.take(128).orEmpty(),
                    trackTitle = track?.title?.take(200).orEmpty(),
                    trackArtist = track?.artist?.take(150).orEmpty(),
                    trackAlbum = track?.album?.take(150).orEmpty(),
                    trackArtworkUrl = track?.artworkUrl?.take(500).orEmpty(),
                    trackDurationMs = track?.durationMs?.coerceIn(0L, 86_400_000L) ?: 0L,
                    trackSource = track?.source?.name.orEmpty(),
                    trackSourceId = track?.sourceTrackId?.take(128).orEmpty(),
                    trackStreamUrl = track?.streamUrl?.take(500).orEmpty(),
                    playing = track != null && playing,
                    positionMs = positionMs.coerceIn(0L, 86_400_000L),
                    playbackUpdatedAtMs = nowMs,
                    updatedByUid = localPeerId,
                    updatedByUsername = cleanName
                )
                hostRoomRef.set(updated)
                hostRoomFlow.value = updated
                return@withContext Result.success(Unit)
            }

            val endpoint = remoteHostEndpoint ?: return@withContext Result.success(Unit)
            val req = JSONObject().apply {
                put("action", "sync")
                put("uid", localPeerId)
                put("username", cleanName)
                put("trackId", track?.id?.take(128).orEmpty())
                put("trackTitle", track?.title?.take(200).orEmpty())
                put("trackArtist", track?.artist?.take(150).orEmpty())
                put("trackAlbum", track?.album?.take(150).orEmpty())
                put("trackArtworkUrl", track?.artworkUrl?.take(500).orEmpty())
                put("trackDurationMs", track?.durationMs?.coerceIn(0L, 86_400_000L) ?: 0L)
                put("trackSource", track?.source?.name.orEmpty())
                put("trackSourceId", track?.sourceTrackId?.take(128).orEmpty())
                put("trackStreamUrl", track?.streamUrl?.take(500).orEmpty())
                put("playing", track != null && playing)
                put("positionMs", positionMs.coerceIn(0L, 86_400_000L))
                put("playbackUpdatedAtMs", nowMs)
            }
            val resp = sendTcpJson(endpoint.first, endpoint.second, req)
            if (resp?.optBoolean("ok", false) == true) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Host lokal tidak merespons sinkronisasi."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun leaveOrClose() = withContext(Dispatchers.IO) {
        val endpoint = remoteHostEndpoint
        remoteHostEndpoint = null
        if (endpoint != null) {
            runCatching {
                sendTcpJson(
                    endpoint.first,
                    endpoint.second,
                    JSONObject().apply {
                        put("action", "leave")
                        put("uid", localPeerId)
                    }
                )
            }
        }
        stopHostServer()
    }

    fun stopHostServer() {
        hostRoomRef.get()?.let { existing ->
            val closed = existing.copy(active = false)
            hostRoomRef.set(closed)
            hostRoomFlow.value = null
        }
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun openServerSocket(preferredPort: Int): ServerSocket {
        if (preferredPort > 0) {
            runCatching {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(preferredPort))
                }
            }
        }
        return ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
    }

    private suspend fun runAcceptLoop(socket: ServerSocket) {
        while (ioScope.isActive && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            ioScope.launch {
                handleClientSocket(client)
            }
        }
    }

    private fun handleClientSocket(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8), true)
            val line = reader.readLine() ?: return
            val req = JSONObject(line)
            val response = processHostRequest(req)
            writer.println(response.toString())
        } catch (e: Exception) {
            Log.d(TAG, "LAN client request ignored: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    @Synchronized
    private fun processHostRequest(req: JSONObject): JSONObject {
        val current = hostRoomRef.get()
            ?: return JSONObject().put("ok", false).put("error", "Room lokal sudah ditutup.")
        val action = req.optString("action")
        val uid = req.optString("uid").take(64)

        return when (action) {
            "join" -> {
                if (!current.active) {
                    return JSONObject().put("ok", false).put("error", "Room lokal sudah ditutup.")
                }
                if (uid.isBlank()) {
                    return JSONObject().put("ok", false).put("error", "ID pendengar tidak valid.")
                }
                if (uid !in current.memberUids && current.memberUids.size >= 20) {
                    return JSONObject().put("ok", false).put("error", "Room penuh (maksimal 20 pendengar).")
                }
                val uname = req.optString("username", "Listener").trim().ifBlank { "Listener" }.take(30)
                val updatedUids = (current.memberUids + uid).distinct()
                val updatedNames = current.memberNames.toMutableMap().apply { put(uid, uname) }
                val updated = current.copy(memberUids = updatedUids, memberNames = updatedNames)
                hostRoomRef.set(updated)
                hostRoomFlow.value = updated
                JSONObject().put("ok", true).put("room", roomToJson(updated))
            }

            "state" -> {
                JSONObject().put("ok", true).put("room", roomToJson(current))
            }

            "sync" -> {
                if (!current.active) {
                    return JSONObject().put("ok", false).put("error", "Room lokal sudah ditutup.")
                }
                val uname = req.optString("username", "Listener").trim().ifBlank { "Listener" }.take(30)
                val updated = current.copy(
                    trackId = req.optString("trackId", ""),
                    trackTitle = req.optString("trackTitle", ""),
                    trackArtist = req.optString("trackArtist", ""),
                    trackAlbum = req.optString("trackAlbum", ""),
                    trackArtworkUrl = req.optString("trackArtworkUrl", ""),
                    trackDurationMs = req.optLong("trackDurationMs", 0L).coerceAtLeast(0L),
                    trackSource = req.optString("trackSource", ""),
                    trackSourceId = req.optString("trackSourceId", ""),
                    trackStreamUrl = req.optString("trackStreamUrl", ""),
                    playing = req.optBoolean("playing", false),
                    positionMs = req.optLong("positionMs", 0L).coerceAtLeast(0L),
                    playbackUpdatedAtMs = req.optLong("playbackUpdatedAtMs", System.currentTimeMillis()),
                    updatedByUid = uid.ifBlank { current.updatedByUid },
                    updatedByUsername = uname
                )
                hostRoomRef.set(updated)
                hostRoomFlow.value = updated
                JSONObject().put("ok", true).put("room", roomToJson(updated))
            }

            "leave" -> {
                val updated = current.copy(
                    memberUids = current.memberUids.filterNot { it == uid },
                    memberNames = current.memberNames.filterKeys { it != uid }
                )
                hostRoomRef.set(updated)
                hostRoomFlow.value = updated
                JSONObject().put("ok", true)
            }

            else -> JSONObject().put("ok", false).put("error", "Aksi tidak dikenal.")
        }
    }

    private fun sendTcpJson(host: String, port: Int, payload: JSONObject): JSONObject? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            writer.println(payload.toString())
            val respLine = reader.readLine() ?: return null
            return JSONObject(respLine)
        }
    }

    companion object {
        private const val TAG = "KoplingfyLanSync"
        private const val DEFAULT_PORT = 48721
        private const val SOCKET_TIMEOUT_MS = 2_500
        private const val POLL_INTERVAL_MS = 1_200L

        fun detectLocalIpv4Address(): String? {
            return runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                val candidates = mutableListOf<Pair<Int, String>>()
                for (ni in interfaces) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val name = ni.name.lowercase()
                    val priority = when {
                        name.startsWith("wlan") -> 1
                        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> 2
                        name.startsWith("eth") -> 3
                        else -> 4
                    }
                    for (addr in ni.inetAddresses.toList()) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                candidates.add(priority to ip)
                            }
                        }
                    }
                }
                candidates.minByOrNull { it.first }?.second
            }.getOrNull()
        }

        /**
         * Encodes an IPv4 address + TCP port into a compact alphanumeric room code prefixed with 'W'
         * (e.g. "W2F8K9P1M4A") so friends can join on the same Wi-Fi/Hotspot without typing an IP.
         */
        fun encodeRoomCode(ipv4: String, port: Int): String {
            val parts = ipv4.trim().split(".")
            require(parts.size == 4) { "Invalid IPv4 address: $ipv4" }
            require(port in 1..65535) { "Invalid port: $port" }
            val b0 = parts[0].toInt() and 0xFF
            val b1 = parts[1].toInt() and 0xFF
            val b2 = parts[2].toInt() and 0xFF
            val b3 = parts[3].toInt() and 0xFF
            val packed = (b0.toLong() shl 40) or
                (b1.toLong() shl 32) or
                (b2.toLong() shl 24) or
                (b3.toLong() shl 16) or
                (port.toLong() and 0xFFFFL)
            return "W" + packed.toString(36).uppercase()
        }

        fun decodeRoomCode(raw: String): Pair<String, Int>? {
            val trimmed = raw.trim()
            if (trimmed.contains('.') && trimmed.contains(':')) {
                val host = trimmed.substringBefore(':').trim()
                val port = trimmed.substringAfter(':').trim().toIntOrNull() ?: return null
                val octets = host.split(".")
                if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 } && port in 1..65535) {
                    return host to port
                }
                return null
            }
            val cleaned = trimmed.uppercase().replace(Regex("[^A-Z0-9]"), "")
            if (!cleaned.startsWith("W") || cleaned.length !in 6..12) return null
            val packed = cleaned.drop(1).toLongOrNull(36) ?: return null
            if (packed <= 0L) return null
            val b0 = ((packed ushr 40) and 0xFFL).toInt()
            val b1 = ((packed ushr 32) and 0xFFL).toInt()
            val b2 = ((packed ushr 24) and 0xFFL).toInt()
            val b3 = ((packed ushr 16) and 0xFFL).toInt()
            val port = (packed and 0xFFFFL).toInt()
            if (port !in 1..65535) return null
            if (b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0) return null
            return "$b0.$b1.$b2.$b3" to port
        }

        fun roomToJson(room: ListenRoom): JSONObject = JSONObject().apply {
            put("id", room.id)
            put("roomCode", room.roomCode)
            put("hostUid", room.hostUid)
            put("hostUsername", room.hostUsername)
            put("roomName", room.roomName)
            put("active", room.active)
            put("memberUids", JSONArray(room.memberUids))
            put("memberNames", JSONObject(room.memberNames))
            put("trackId", room.trackId)
            put("trackTitle", room.trackTitle)
            put("trackArtist", room.trackArtist)
            put("trackAlbum", room.trackAlbum)
            put("trackArtworkUrl", room.trackArtworkUrl)
            put("trackDurationMs", room.trackDurationMs)
            put("trackSource", room.trackSource)
            put("trackSourceId", room.trackSourceId)
            put("trackStreamUrl", room.trackStreamUrl)
            put("playing", room.playing)
            put("positionMs", room.positionMs)
            put("playbackUpdatedAtMs", room.playbackUpdatedAtMs)
            put("updatedByUid", room.updatedByUid)
            put("updatedByUsername", room.updatedByUsername)
        }

        fun roomFromJson(obj: JSONObject): ListenRoom {
            val uidsArr = obj.optJSONArray("memberUids")
            val uids = buildList {
                if (uidsArr != null) {
                    for (i in 0 until uidsArr.length()) {
                        val v = uidsArr.optString(i)
                        if (v.isNotBlank()) add(v)
                    }
                }
            }
            val namesObj = obj.optJSONObject("memberNames")
            val names = buildMap {
                if (namesObj != null) {
                    val keys = namesObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, namesObj.optString(k, "Listener"))
                    }
                }
            }
            return ListenRoom(
                id = obj.optString("id", obj.optString("roomCode")),
                roomCode = obj.optString("roomCode"),
                hostUid = obj.optString("hostUid"),
                hostUsername = obj.optString("hostUsername"),
                roomName = obj.optString("roomName"),
                active = obj.optBoolean("active", true),
                memberUids = uids,
                memberNames = names,
                trackId = obj.optString("trackId"),
                trackTitle = obj.optString("trackTitle"),
                trackArtist = obj.optString("trackArtist"),
                trackAlbum = obj.optString("trackAlbum"),
                trackArtworkUrl = obj.optString("trackArtworkUrl"),
                trackDurationMs = obj.optLong("trackDurationMs", 0L),
                trackSource = obj.optString("trackSource"),
                trackSourceId = obj.optString("trackSourceId"),
                trackStreamUrl = obj.optString("trackStreamUrl"),
                playing = obj.optBoolean("playing", false),
                positionMs = obj.optLong("positionMs", 0L),
                playbackUpdatedAtMs = obj.optLong("playbackUpdatedAtMs", 0L),
                updatedByUid = obj.optString("updatedByUid"),
                updatedByUsername = obj.optString("updatedByUsername")
            )
        }
    }
}
