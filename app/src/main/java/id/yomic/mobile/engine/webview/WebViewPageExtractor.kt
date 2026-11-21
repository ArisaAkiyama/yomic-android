package id.yomic.mobile.engine.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Offscreen Android WebView extractor for JS extensions that define
 * `pageWebViewExtractScript` + `chapterReadySelector` (such as voratoon.js).
 * Mirrors Desktop's WebViewPageExtractorWindow.cs.
 */
class WebViewPageExtractor(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractPages(
        chapterUrl: String,
        extractScript: String,
        userAgent: String,
        referer: String = "",
        timeoutMs: Long = 22_000L
    ): List<String> = withContext(Dispatchers.Main) {
        val resultDeferred = CompletableDeferred<List<String>>()
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var pollRunnable: Runnable? = null

        try {
            val wv = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                if (userAgent.isNotBlank()) {
                    settings.userAgentString = userAgent
                }
            }
            webView = wv

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }

            var attempts = 0
            val maxAttempts = 24

            pollRunnable = object : Runnable {
                override fun run() {
                    if (resultDeferred.isCompleted || attempts >= maxAttempts) {
                        if (!resultDeferred.isCompleted) resultDeferred.complete(emptyList())
                        return
                    }
                    attempts++
                    wv.evaluateJavascript(extractScript) { raw ->
                        val parsed = parseJsStringArray(raw)
                        if (parsed.isNotEmpty()) {
                            resultDeferred.complete(parsed)
                        } else if (!resultDeferred.isCompleted) {
                            handler.postDelayed(this, 750L)
                        }
                    }
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pollRunnable?.let { handler.postDelayed(it, 600L) }
                }
            }

            val headers = if (referer.isNotBlank()) mapOf("Referer" to referer) else emptyMap()
            wv.loadUrl(chapterUrl, headers)

            withTimeoutOrNull(timeoutMs) { resultDeferred.await() } ?: emptyList()
        } finally {
            pollRunnable?.let { handler.removeCallbacks(it) }
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
            }
        }
    }

    private fun parseJsStringArray(raw: String?): List<String> {
        if (raw.isNullOrBlank() || raw == "null" || raw == "undefined") return emptyList()
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        // evaluateJavascript may double-encode a JSON string if script returned JSON.stringify(...)
        val arrayElement = when (element) {
            is JsonArray -> element
            is JsonPrimitive -> {
                val inner = element.contentOrNull.orEmpty()
                if (inner.startsWith("[")) runCatching { json.parseToJsonElement(inner) as? JsonArray }.getOrNull()
                else null
            }
            else -> null
        } ?: return emptyList()

        return arrayElement.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
    }
}
