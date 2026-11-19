package com.example.data.providers.youtube

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class YouTubePlayerConfig(
    val playerId: String,
    val playerJsUrl: String,
    val signatureTimestamp: Int,
    val fetchedAtMs: Long,
    val rawJs: String,
    val sigFunctionName: String?,
    val nFunctionExpr: String?,
    val fastSigOps: List<SigOp>
)

sealed interface SigOp {
    data object Reverse : SigOp
    data class Splice(val count: Int) : SigOp
    data class Swap(val index: Int) : SigOp
}

data class ParsedSignatureCipher(
    val url: String,
    val s: String,
    val sp: String
)

/**
 * Self-healing YouTube Player Cipher & N-Parameter Solver (Metrolist / Zemer Cipher / yt.solver architecture).
 *
 * Responsibilities:
 * 1. Fetches active `player_ias.vflset/en_US/base.js` directly from YouTube (`iframe_api` / `music.youtube.com`)
 *    and caches it for 6 hours.
 * 2. Extracts the live `signatureTimestamp` (`sts`) required by `/youtubei/v1/player` (`WEB_REMIX` & `TVHTML5`).
 * 3. Extracts signature (`sig`) and throttle (`n`) deobfuscator hooks from `base.js`.
 * 4. Solves `signatureCipher` via ultra-fast pure-Kotlin ops first (<1ms), falling back to sandboxed
 *    `CipherWebViewEngine` (Chromium V8 ES6+) whenever `base.js` uses complex/obfuscated transforms.
 * 5. Transforms the `n=` query parameter on `googlevideo.com/videoplayback` URLs to eliminate 403/throttling.
 * 6. Generates 16-character Client Playback Nonce (`cpn`) values matching official YouTube clients.
 */
class YouTubeCipherSolver(
    context: Context?,
    httpClient: OkHttpClient
) {
    private val webViewEngine: CipherWebViewEngine? = context?.let { CipherWebViewEngine(it) }
    private val fetchClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val configMutex = Mutex()

    @Volatile
    private var cachedConfig: YouTubePlayerConfig? = null

    private val nParamCache = ConcurrentHashMap<String, String>()
    private val webUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

    private val configTtlMs = 6L * 60L * 60L * 1000L // 6 hours

    /**
     * Ensures live player config (`sts` + `base.js`) is loaded and cached.
     */
    suspend fun ensurePlayerConfig(forceRefresh: Boolean = false): YouTubePlayerConfig? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = cachedConfig
        if (!forceRefresh && existing != null && (now - existing.fetchedAtMs) < configTtlMs) {
            return@withContext existing
        }

        configMutex.withLock {
            val recheck = cachedConfig
            if (!forceRefresh && recheck != null && (System.currentTimeMillis() - recheck.fetchedAtMs) < configTtlMs) {
                return@withLock recheck
            }

            val discovered = discoverPlayerJsUrl() ?: return@withLock recheck
            val (playerId, playerJsUrl) = discovered
            val rawJs = downloadText(playerJsUrl) ?: return@withLock recheck
            val sts = extractSignatureTimestamp(rawJs) ?: return@withLock recheck
            val sigFn = extractSigFunctionName(rawJs)
            val nExpr = extractNFunctionExpression(rawJs)
            val fastOps = if (sigFn != null) extractFastSigOps(rawJs, sigFn) else emptyList()

            val config = YouTubePlayerConfig(
                playerId = playerId,
                playerJsUrl = playerJsUrl,
                signatureTimestamp = sts,
                fetchedAtMs = System.currentTimeMillis(),
                rawJs = rawJs,
                sigFunctionName = sigFn,
                nFunctionExpr = nExpr,
                fastSigOps = fastOps
            )
            cachedConfig = config
            nParamCache.clear()
            Log.d(
                "KoplingfyCipher",
                "Loaded YouTube player=$playerId sts=$sts sigFn=$sigFn nExpr=$nExpr fastOps=${fastOps.size}"
            )

            // Pre-warm headless WebView solver if we found sig or n hooks
            if (webViewEngine != null && (sigFn != null || nExpr != null)) {
                val instrumented = instrumentBaseJsForWebView(rawJs, sigFn, nExpr)
                webViewEngine.loadPlayerScript(playerId, instrumented)
            }
            config
        }
    }

    suspend fun getSignatureTimestamp(): Int? = ensurePlayerConfig()?.signatureTimestamp

    /**
     * Resolves a raw `url` or `signatureCipher` from `/youtubei/v1/player` into a ready-to-play
     * `googlevideo.com/videoplayback` URL with deciphered `sig`, transformed `n`, and `cpn`.
     */
    suspend fun resolveStreamUrl(
        directUrl: String?,
        signatureCipher: String?,
        cpn: String,
        solveNParam: Boolean
    ): String? {
        var resolvedUrl = when {
            !directUrl.isNullOrBlank() && directUrl.startsWith("http") -> directUrl
            !signatureCipher.isNullOrBlank() -> {
                val parsed = parseSignatureCipher(signatureCipher) ?: return null
                val decipheredSig = decipherSignature(parsed.s) ?: return null
                val sep = if (parsed.url.contains("?")) "&" else "?"
                "${parsed.url}${sep}${parsed.sp}=${URLEncoder.encode(decipheredSig, "UTF-8")}"
            }
            else -> return null
        }

        if (solveNParam) {
            resolvedUrl = applyNParamTransform(resolvedUrl)
        }
        if (!resolvedUrl.contains("cpn=")) {
            val sep = if (resolvedUrl.contains("?")) "&" else "?"
            resolvedUrl = "${resolvedUrl}${sep}cpn=$cpn"
        }
        return resolvedUrl
    }

    suspend fun decipherSignature(cipheredSig: String): String? {
        val cfg = ensurePlayerConfig() ?: return null
        // 1. Ultra-fast pure Kotlin path (<1ms)
        if (cfg.fastSigOps.isNotEmpty()) {
            return applyFastSigOps(cipheredSig, cfg.fastSigOps)
        }
        // 2. Full ES6+ Chromium V8 WebView execution path
        if (webViewEngine != null && cfg.sigFunctionName != null) {
            val instrumented = instrumentBaseJsForWebView(cfg.rawJs, cfg.sigFunctionName, cfg.nFunctionExpr)
            if (webViewEngine.loadPlayerScript(cfg.playerId, instrumented)) {
                return webViewEngine.decipherSignature(cipheredSig)
            }
        }
        return null
    }

    suspend fun applyNParamTransform(url: String): String {
        val match = Regex("""([?&])n=([^&]+)""").find(url) ?: return url
        val encodedN = match.groupValues[2]
        val rawN = runCatching { URLDecoder.decode(encodedN, "UTF-8") }.getOrDefault(encodedN)
        if (rawN.isBlank()) return url

        nParamCache[rawN]?.let { solved ->
            return url.replaceRange(match.range, "${match.groupValues[1]}n=${URLEncoder.encode(solved, "UTF-8")}")
        }

        val cfg = ensurePlayerConfig() ?: return url
        if (webViewEngine == null || cfg.nFunctionExpr == null) return url

        val instrumented = instrumentBaseJsForWebView(cfg.rawJs, cfg.sigFunctionName, cfg.nFunctionExpr)
        if (!webViewEngine.loadPlayerScript(cfg.playerId, instrumented)) return url

        val solved = webViewEngine.transformNParameter(rawN) ?: return url
        nParamCache[rawN] = solved
        return url.replaceRange(match.range, "${match.groupValues[1]}n=${URLEncoder.encode(solved, "UTF-8")}")
    }

    private fun discoverPlayerJsUrl(): Pair<String, String>? {
        // 1. Fast ~1KB iframe_api endpoint (contains active player hash)
        downloadText("https://www.youtube.com/iframe_api")?.let { iframeJs ->
            val idMatch = Regex("""\\?/s\\?/player\\?/([a-zA-Z0-9_-]{6,16})\\?/""").find(iframeJs)
            if (idMatch != null) {
                val id = idMatch.groupValues[1]
                return id to "https://www.youtube.com/s/player/$id/player_ias.vflset/en_US/base.js"
            }
        }
        // 2. Fallback: scrape YouTube embed page for exact jsUrl
        downloadText("https://www.youtube.com/embed/dQw4w9WgXcQ")?.let { html ->
            val pathMatch = Regex("""(/s/player/([a-zA-Z0-9_-]{6,16})/[^"'\\]*base\.js)""").find(html)
            if (pathMatch != null) {
                val path = pathMatch.groupValues[1]
                val id = pathMatch.groupValues[2]
                return id to "https://www.youtube.com$path"
            }
        }
        return null
    }

    private fun downloadText(url: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", webUa)
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()
            fetchClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    companion object {
        private const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private val secureRandom = SecureRandom()

        fun generateCpn(): String {
            val chars = CharArray(16)
            for (i in chars.indices) {
                chars[i] = CPN_ALPHABET[secureRandom.nextInt(CPN_ALPHABET.length)]
            }
            return String(chars)
        }

        fun parseSignatureCipher(cipher: String): ParsedSignatureCipher? {
            if (cipher.isBlank()) return null
            val params = mutableMapOf<String, String>()
            for (part in cipher.split("&")) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq)
                val value = runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }
                    .getOrDefault(part.substring(eq + 1))
                params[key] = value
            }
            val url = params["url"]?.takeIf { it.startsWith("http") } ?: return null
            val s = params["s"]?.takeIf { it.isNotBlank() } ?: return null
            val sp = params["sp"]?.takeIf { it.isNotBlank() } ?: "sig"
            return ParsedSignatureCipher(url = url, s = s, sp = sp)
        }

        fun extractSignatureTimestamp(baseJs: String): Int? {
            val match = Regex("""(?:signatureTimestamp|sts)\s*[:=]\s*(\d{5,})""").find(baseJs)
            return match?.groupValues?.get(1)?.toIntOrNull()
        }

        fun extractSigFunctionName(baseJs: String): String? {
            val patterns = listOf(
                Regex("""\b[cs]\s*&&\s*[a-zA-Z0-9$]+\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
                Regex("""\b[a-zA-Z0-9$]+\s*&&\s*[a-zA-Z0-9$]+\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
                Regex("""\bm=([a-zA-Z0-9$]{2,})\(decodeURIComponent\(h\.s\)\)"""),
                Regex("""(["'])signature\1\s*,\s*([a-zA-Z0-9$]+)\("""),
                Regex("""\.sig\|\|([a-zA-Z0-9$]+)\(""")
            )
            for (pattern in patterns) {
                val m = pattern.find(baseJs)
                if (m != null) {
                    val candidate = m.groupValues.lastOrNull { it.isNotBlank() && it != "\"" && it != "'" }
                    if (!candidate.isNullOrBlank()) return candidate
                }
            }
            return null
        }

        fun extractNFunctionExpression(baseJs: String): String? {
            val patterns = listOf(
                Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]+(?:\[\d+\])?)\([a-zA-Z0-9$]+\)"""),
                Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]+)\(b\)"""),
                Regex("""\b([a-zA-Z0-9$]+)=function\([a-zA-Z0-9$]+\)\{var [a-zA-Z0-9$]+=[a-zA-Z0-9$]+\.split\(""\),""")
            )
            for (pattern in patterns) {
                val m = pattern.find(baseJs)
                if (m != null) {
                    val expr = m.groupValues[1]
                    if (expr.isNotBlank()) return expr
                }
            }
            return null
        }

        /**
         * Injects `window.__koplingfySig` and `window.__koplingfyN` inside YouTube's top-level
         * `_yt_player` IIFE closure so all obfuscated helper objects & lookup tables remain in scope.
         */
        fun instrumentBaseJsForWebView(
            baseJs: String,
            sigFunctionName: String?,
            nFunctionExpr: String?
        ): String {
            val hooks = buildString {
                if (!sigFunctionName.isNullOrBlank()) {
                    append(";try{window.__koplingfySig=").append(sigFunctionName).append(";}catch(_e){}")
                }
                if (!nFunctionExpr.isNullOrBlank()) {
                    append(";try{window.__koplingfyN=").append(nFunctionExpr).append(";}catch(_e){}")
                }
            }
            if (hooks.isBlank()) return baseJs

            val closingRegex = Regex("""\}\)\(_yt_player\);?\s*$""")
            return if (closingRegex.containsMatchIn(baseJs)) {
                baseJs.replace(closingRegex) { m -> "$hooks\n${m.value}" }
            } else {
                "$baseJs\n$hooks"
            }
        }

        /**
         * Pure-Kotlin fast signature deobfuscation extractor for standard helper-object ciphers
         * (`reverse`, `splice`, `swap`). Avoids WebView latency when applicable.
         */
        fun extractFastSigOps(baseJs: String, fnName: String): List<SigOp> {
            val escapedFn = Regex.escape(fnName)
            val fnRegex = Regex("""(?:var\s+$escapedFn=|function\s+$escapedFn|\b$escapedFn=function)\s*\([a-zA-Z0-9$]+\)\s*\{([^}]{1,500})\}""")
            val fnBody = fnRegex.find(baseJs)?.groupValues?.get(1) ?: return emptyList()
            val helperCallMatch = Regex("""([a-zA-Z0-9$]+)\.([a-zA-Z0-9$]+)\([a-zA-Z0-9$]+,(\d+)\)""").findAll(fnBody).toList()
            if (helperCallMatch.isEmpty()) return emptyList()

            val helperObjName = helperCallMatch.first().groupValues[1]
            val escapedHelper = Regex.escape(helperObjName)
            val helperBlock = Regex("""var\s+$escapedHelper\s*=\s*\{([\s\S]{1,1200}?)\};""").find(baseJs)?.groupValues?.get(1)
                ?: return emptyList()

            val methodKinds = mutableMapOf<String, String>()
            val methodRegex = Regex("""([a-zA-Z0-9$]+)\s*:\s*function\s*\([^)]*\)\s*\{([^}]*)\}""")
            for (m in methodRegex.findAll(helperBlock)) {
                val methodName = m.groupValues[1]
                val body = m.groupValues[2]
                when {
                    body.contains("reverse") -> methodKinds[methodName] = "reverse"
                    body.contains("splice") -> methodKinds[methodName] = "splice"
                    body.contains("%") || body.contains("length") -> methodKinds[methodName] = "swap"
                }
            }

            val ops = mutableListOf<SigOp>()
            for (call in helperCallMatch) {
                val obj = call.groupValues[1]
                if (obj != helperObjName) return emptyList()
                val method = call.groupValues[2]
                val arg = call.groupValues[3].toIntOrNull() ?: return emptyList()
                when (methodKinds[method]) {
                    "reverse" -> ops.add(SigOp.Reverse)
                    "splice" -> ops.add(SigOp.Splice(arg))
                    "swap" -> ops.add(SigOp.Swap(arg))
                    else -> return emptyList()
                }
            }
            return ops
        }

        fun applyFastSigOps(sig: String, ops: List<SigOp>): String {
            val chars = sig.toMutableList()
            for (op in ops) {
                when (op) {
                    SigOp.Reverse -> chars.reverse()
                    is SigOp.Splice -> {
                        val n = op.count.coerceIn(0, chars.size)
                        repeat(n) { if (chars.isNotEmpty()) chars.removeAt(0) }
                    }
                    is SigOp.Swap -> {
                        if (chars.isNotEmpty()) {
                            val idx = op.index % chars.size
                            val tmp = chars[0]
                            chars[0] = chars[idx]
                            chars[idx] = tmp
                        }
                    }
                }
            }
            return chars.joinToString("")
        }
    }
}
