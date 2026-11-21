package id.yomic.mobile.data.network

import android.content.Context
import id.yomic.mobile.data.image.PipeHeaderOkHttpInterceptor
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Centralized OkHttp network manager with dynamic DNS-over-HTTPS (DoH),
 * WebView-synced CookieJar, configurable User-Agent, and Cloudflare detection.
 */
class YomicNetworkClient(
    private val context: Context? = null,
    initialDohProvider: String = DohProvider.CLOUDFLARE.settingsKey,
    initialUserAgent: String = DEFAULT_USER_AGENT,
    useAndroidCookieManager: Boolean = context != null
) {
    val cookieJar = WebViewCookieJar(useAndroidWebViewManager = useAndroidCookieManager)

    @Volatile
    var currentDohProvider: DohProvider = DohProvider.fromSettingsKey(initialDohProvider)
        private set

    @Volatile
    var currentUserAgent: String = initialUserAgent.ifBlank { DEFAULT_USER_AGENT }
        private set

    @Volatile
    var bypassUserAgent: String = ""
        private set

    private val bootstrapClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    var httpClient: OkHttpClient = buildHttpClient(currentDohProvider)
        private set

    @Volatile
    var imageClient: OkHttpClient = buildImageClient(httpClient)
        private set

    @Synchronized
    fun reconfigure(dohProviderKey: String, userAgent: String) {
        val nextDoh = DohProvider.fromSettingsKey(dohProviderKey)
        val nextUa = userAgent.ifBlank { DEFAULT_USER_AGENT }
        val dohChanged = nextDoh != currentDohProvider
        currentUserAgent = nextUa
        if (dohChanged) {
            currentDohProvider = nextDoh
            val rebuilt = buildHttpClient(nextDoh)
            httpClient = rebuilt
            imageClient = buildImageClient(rebuilt)
        }
    }

    fun effectiveUserAgent(): String = bypassUserAgent.ifBlank { currentUserAgent }

    fun updateBypassUserAgent(ua: String) {
        if (ua.isNotBlank()) {
            bypassUserAgent = ua
        }
    }

    fun clearAllCookies() {
        bypassUserAgent = ""
        cookieJar.clearAllCookies()
    }

    suspend fun solveCloudflareInteractive(targetUrl: String): CloudflareSolveResult {
        val ctx = context ?: return CloudflareSolveResult(solved = false)
        val result = CloudflareSolverBridge.requestInteractiveSolve(
            context = ctx,
            targetUrl = targetUrl,
            userAgent = effectiveUserAgent()
        )
        if (result.solved && result.userAgent.isNotBlank()) {
            updateBypassUserAgent(result.userAgent)
        }
        return result
    }

    private fun buildHttpClient(doh: DohProvider): OkHttpClient {
        val dns = doh.createDns(bootstrapClient)
        return OkHttpClient.Builder()
            .dns(dns)
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(DefaultHeadersInterceptor())
            .build()
    }

    private fun buildImageClient(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(PipeHeaderOkHttpInterceptor { effectiveUserAgent() })
            .build()

    private inner class DefaultHeadersInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val builder = req.newBuilder()
            if (req.header("User-Agent").isNullOrBlank()) {
                builder.header("User-Agent", effectiveUserAgent())
            }
            if (req.header("Accept-Language").isNullOrBlank()) {
                builder.header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            }
            return chain.proceed(builder.build())
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun isCloudflareChallenge(statusCode: Int, serverHeader: String?, cfMitigated: String?, bodySnippet: String?): Boolean {
            if (cfMitigated.equals("challenge", ignoreCase = true)) return true
            if (statusCode != 403 && statusCode != 503) return false
            val isCfServer = serverHeader?.contains("cloudflare", ignoreCase = true) == true
            val lowBody = bodySnippet?.lowercase().orEmpty()
            return (isCfServer && (lowBody.contains("cf-browser-verification") || lowBody.contains("just a moment") || lowBody.contains("turnstile"))) ||
                lowBody.contains("challenge-platform")
        }
    }
}
