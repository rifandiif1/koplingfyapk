package com.example.data.providers.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Sandboxed headless Android WebView V8 engine (Zemer Cipher / Metrolist `CipherWebView` pattern)
 * for executing live YouTube `player_ias.vflset/en_US/base.js` signature (`sig`) and throttle (`n`)
 * deobfuscation functions with full modern ES6+ support and zero outbound network traffic.
 */
class CipherWebViewEngine(
    private val context: Context
) {
    private val mutex = Mutex()
    private var webView: WebView? = null
    private var loadedPlayerId: String? = null
    private var hasSigFunction: Boolean = false
    private var hasNFunction: Boolean = false

    suspend fun loadPlayerScript(
        playerId: String,
        instrumentedBaseJs: String
    ): Boolean = mutex.withLock {
        if (loadedPlayerId == playerId && (hasSigFunction || hasNFunction)) {
            return true
        }
        val ready = withTimeoutOrNull(8_000L) {
            withContext(Dispatchers.Main.immediate) {
                runCatching {
                    val wv = getOrCreateWebView() ?: return@runCatching false
                    val loadFinished = CompletableDeferred<Boolean>()
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            // Keep the sandbox strictly offline so base.js never triggers network calls
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!loadFinished.isCompleted) loadFinished.complete(true)
                        }
                    }

                    val html = buildSandboxHtml(instrumentedBaseJs)
                    wv.loadDataWithBaseURL(
                        "https://www.youtube.com/",
                        html,
                        "text/html",
                        "utf-8",
                        null
                    )
                    loadFinished.await()

                    val probeResult = evaluateRawOnMain(
                        wv,
                        "(function(){return JSON.stringify({sig:typeof window.__koplingfySig==='function',n:typeof window.__koplingfyN==='function'});})()"
                    )
                    val parsed = probeResult?.let { runCatching { JSONObject(it) }.getOrNull() }
                    hasSigFunction = parsed?.optBoolean("sig", false) == true
                    hasNFunction = parsed?.optBoolean("n", false) == true
                    loadedPlayerId = playerId
                    Log.d(
                        "KoplingfyCipher",
                        "CipherWebView ready for player=$playerId (sig=$hasSigFunction, n=$hasNFunction)"
                    )
                    hasSigFunction || hasNFunction
                }.onFailure { err ->
                    Log.w("KoplingfyCipher", "CipherWebView load failed: ${err.message}")
                }.getOrDefault(false)
            }
        }
        ready == true
    }

    suspend fun decipherSignature(cipheredSig: String): String? = mutex.withLock {
        if (!hasSigFunction || cipheredSig.isBlank()) return null
        val quoted = JSONObject.quote(cipheredSig)
        val js = "(function(){try{return String(window.__koplingfySig($quoted));}catch(e){return '';}})()"
        evaluateStringLocked(js)?.takeIf { it.isNotBlank() && it != cipheredSig }
    }

    suspend fun transformNParameter(nValue: String): String? = mutex.withLock {
        if (!hasNFunction || nValue.isBlank()) return null
        val quoted = JSONObject.quote(nValue)
        val js = "(function(){try{return String(window.__koplingfyN($quoted));}catch(e){return '';}})()"
        evaluateStringLocked(js)?.takeIf { it.isNotBlank() && !it.startsWith("enhanced_except_") }
    }

    private suspend fun evaluateStringLocked(js: String): String? {
        val wv = webView ?: return null
        return withTimeoutOrNull(4_000L) {
            withContext(Dispatchers.Main.immediate) {
                evaluateRawOnMain(wv, js)
            }
        }
    }

    private suspend fun evaluateRawOnMain(wv: WebView, js: String): String? {
        val deferred = CompletableDeferred<String?>()
        wv.evaluateJavascript(js) { raw ->
            val decoded = decodeJsStringResult(raw)
            if (!deferred.isCompleted) deferred.complete(decoded)
        }
        return deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(): WebView? {
        webView?.let { return it }
        return runCatching {
            WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                settings.blockNetworkLoads = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                webView = this
            }
        }.onFailure { err ->
            Log.w("KoplingfyCipher", "WebView unavailable on this environment: ${err.message}")
        }.getOrNull()
    }

    private fun buildSandboxHtml(instrumentedBaseJs: String): String {
        val safeScript = instrumentedBaseJs.replace("</script>", "<\\/script>", ignoreCase = true)
        return """
            <!doctype html>
            <html>
            <head><meta charset="utf-8"></head>
            <body>
            <script>
            window.ytcfg = {
                d: function(){ return {}; },
                get: function(){ return undefined; },
                set: function(){}
            };
            window.yt = window.yt || {};
            window.ytplayer = window.ytplayer || { config: {} };
            window.matchMedia = window.matchMedia || function(){ return { matches: false, addListener: function(){}, removeListener: function(){} }; };
            </script>
            <script>
            $safeScript
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun decodeJsStringResult(raw: String?): String? {
        if (raw == null || raw == "null" || raw == "undefined") return null
        return runCatching {
            // evaluateJavascript returns a JSON-encoded string literal; unwrap safely via JSONObject
            JSONObject("{\"v\":$raw}").optString("v").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
