package id.yomic.mobile.data.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Bidirectional OkHttp CookieJar synced with Android WebView CookieManager.
 * Any Cloudflare `cf_clearance` or login cookie acquired in WebView is immediately
 * available to OkHttp JS `fetch()` requests and Coil image requests.
 */
class WebViewCookieJar(
    private val useAndroidWebViewManager: Boolean = true
) : CookieJar {

    private val memoryStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private fun cookieManagerOrNull(): CookieManager? {
        if (!useAndroidWebViewManager) return null
        return runCatching { CookieManager.getInstance() }.getOrNull()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val cm = cookieManagerOrNull()
        if (cm != null) {
            val urlString = url.toString()
            cookies.forEach { cookie ->
                cm.setCookie(urlString, cookie.toString())
            }
            cm.flush()
        } else {
            val bucket = memoryStore.getOrPut(url.host) { mutableListOf() }
            synchronized(bucket) {
                cookies.forEach { incoming ->
                    bucket.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                    bucket.add(incoming)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cm = cookieManagerOrNull()
        if (cm != null) {
            val raw = cm.getCookie(url.toString()).orEmpty()
            if (raw.isBlank()) return emptyList()
            return raw.split(";")
                .mapNotNull { part ->
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) null else Cookie.parse(url, trimmed)
                }
        }

        val now = System.currentTimeMillis()
        val matching = mutableListOf<Cookie>()
        memoryStore.values.forEach { bucket ->
            synchronized(bucket) {
                bucket.removeAll { it.expiresAt < now }
                bucket.filterTo(matching) { it.matches(url) }
            }
        }
        return matching
    }

    fun getCookieHeader(url: String): String {
        val cm = cookieManagerOrNull()
        if (cm != null) {
            return cm.getCookie(url).orEmpty()
        }
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        val cookies = loadForRequest(httpUrl)
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun clearAllCookies() {
        memoryStore.clear()
        val cm = cookieManagerOrNull() ?: return
        runCatching {
            cm.removeAllCookies(null)
            cm.flush()
        }
    }
}
