package id.yomic.mobile.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CloudflareSolveResult(
    val solved: Boolean,
    val cookies: String = "",
    val userAgent: String = ""
)

object CloudflareSolverBridge {
    private val mutex = Mutex()
    @Volatile
    private var pendingDeferred: CompletableDeferred<CloudflareSolveResult>? = null

    suspend fun requestInteractiveSolve(
        context: Context,
        targetUrl: String,
        userAgent: String
    ): CloudflareSolveResult = mutex.withLock {
        val deferred = CompletableDeferred<CloudflareSolveResult>()
        pendingDeferred = deferred
        val intent = Intent(context, CloudflareSolverActivity::class.java).apply {
            putExtra(CloudflareSolverActivity.EXTRA_URL, targetUrl)
            putExtra(CloudflareSolverActivity.EXTRA_USER_AGENT, userAgent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        try {
            deferred.await()
        } finally {
            pendingDeferred = null
        }
    }

    internal fun complete(result: CloudflareSolveResult) {
        pendingDeferred?.complete(result)
    }
}

/**
 * Interactive WebView activity that lets the user pass Cloudflare / Turnstile verification.
 * Automatically captures `cf_clearance` cookies into Android CookieManager (synced to OkHttp).
 */
class CloudflareSolverActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var targetUrl: String = ""
    private var completed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val ua = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty()
        if (targetUrl.isBlank()) {
            finishWithResult(CloudflareSolveResult(solved = false))
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF121214.toInt())
        }

        val header = TextView(this).apply {
            text = "Verifikasi Cloudflare — Selesaikan cek lalu halaman akan otomatis tertutup"
            setTextColor(0xFFE4E4E7.toInt())
            textSize = 13f
            setPadding(32, 48, 32, 24)
        }

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                10
            )
        }

        val wv = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            if (ua.isNotBlank()) {
                settings.userAgentString = ua
            }

            val currentWebView = this
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(currentWebView, true)
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.isVisible = newProgress < 100
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val currentUrl = url ?: targetUrl
                    val cookies = CookieManager.getInstance().getCookie(currentUrl).orEmpty()
                    val title = view?.title.orEmpty()
                    val isChallenge = isCloudflareTitle(title)
                    if (!isChallenge && (cookies.contains("cf_clearance") || cookies.contains("__cf_bm"))) {
                        CookieManager.getInstance().flush()
                        finishWithResult(
                            CloudflareSolveResult(
                                solved = true,
                                cookies = cookies,
                                userAgent = settings.userAgentString
                            )
                        )
                    }
                }
            }
        }

        webView = wv
        root.addView(header)
        root.addView(progress)
        root.addView(wv)
        setContentView(root)

        wv.loadUrl(targetUrl)
    }

    override fun onDestroy() {
        if (!completed) {
            val cookies = runCatching { CookieManager.getInstance().getCookie(targetUrl).orEmpty() }.getOrDefault("")
            val ua = webView?.settings?.userAgentString.orEmpty()
            finishWithResult(
                CloudflareSolveResult(
                    solved = cookies.contains("cf_clearance"),
                    cookies = cookies,
                    userAgent = ua
                )
            )
        }
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun finishWithResult(result: CloudflareSolveResult) {
        if (completed) return
        completed = true
        CloudflareSolverBridge.complete(result)
        if (!isFinishing) {
            finish()
        }
    }

    private fun isCloudflareTitle(title: String): Boolean {
        val low = title.lowercase()
        return low.contains("just a moment") ||
            low.contains("attention required") ||
            low.contains("checking your browser") ||
            low.contains("ddos-guard")
    }

    companion object {
        const val EXTRA_URL = "extra_cf_url"
        const val EXTRA_USER_AGENT = "extra_cf_ua"
    }
}
