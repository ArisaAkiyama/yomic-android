package id.yomic.mobile.data.service

import id.yomic.mobile.data.local.ExtensionCacheDao
import id.yomic.mobile.data.network.YomicNetworkClient
import id.yomic.mobile.data.preferences.SettingsRepository
import id.yomic.mobile.engine.js.JsExtensionManager
import id.yomic.mobile.model.ServerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Periodically checks HTTP health status of all installed JS sources (ONLINE / WARNING / OFFLINE)
 * and persists results to Room `extension_cache`. Mirrors Desktop's SourceStatusService.cs.
 */
class SourceStatusService(
    private val extensionCacheDao: ExtensionCacheDao,
    private val extensionManager: JsExtensionManager,
    private val networkClient: YomicNetworkClient,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope
) {
    private val semaphore = Semaphore(6)
    private var periodicJob: Job? = null

    fun startMonitoring(intervalKey: String) {
        periodicJob?.cancel()
        val intervalMs = parseIntervalMs(intervalKey) ?: return
        periodicJob = appScope.launch {
            while (isActive) {
                delay(intervalMs)
                checkAllInstalledSources()
            }
        }
    }

    suspend fun checkAllInstalledSources() = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getCurrentSettings()
        if (settings.isOfflineMode) return@withContext

        val installed = extensionManager.getAllSources()
        if (installed.isEmpty()) return@withContext

        val pingClient = networkClient.httpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        coroutineScope {
            installed.map { src ->
                async {
                    semaphore.withPermit {
                        val baseUrl = src.baseUrl.ifBlank { src.metadata.apiUrl }
                        if (baseUrl.isBlank()) return@withPermit
                        val now = System.currentTimeMillis()
                        val status = probeEndpoint(pingClient, baseUrl)
                        extensionCacheDao.updateServerStatus(src.pkg, status.name, now)
                    }
                }
            }.awaitAll()
        }
    }

    private fun probeEndpoint(client: okhttp3.OkHttpClient, url: String): ServerStatus {
        return try {
            val req = Request.Builder()
                .url(url)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val cfMitigated = resp.header("cf-mitigated")
                val server = resp.header("Server")
                when {
                    code in 200..399 -> ServerStatus.ONLINE
                    cfMitigated.equals("challenge", ignoreCase = true) ||
                        code == 403 || code == 429 ||
                        (code == 503 && server?.contains("cloudflare", ignoreCase = true) == true) -> ServerStatus.WARNING
                    else -> ServerStatus.OFFLINE
                }
            }
        } catch (_: Exception) {
            ServerStatus.OFFLINE
        }
    }

    companion object {
        fun parseIntervalMs(key: String): Long? = when (key.trim().lowercase()) {
            "15m" -> 15L * 60_000L
            "30m" -> 30L * 60_000L
            "1h" -> 60L * 60_000L
            "off", "0", "" -> null
            else -> 30L * 60_000L
        }
    }
}
