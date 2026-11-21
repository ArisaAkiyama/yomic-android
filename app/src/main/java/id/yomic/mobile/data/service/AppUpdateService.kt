package id.yomic.mobile.data.service

import id.yomic.mobile.BuildConfig
import id.yomic.mobile.model.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * Checks GitHub Releases for newer Yomic Android APK builds (1:1 parity with Desktop UpdateService.cs).
 */
class AppUpdateService(
    private val httpClientProvider: () -> OkHttpClient,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val flavorHint: String = if (BuildConfig.DEFAULT_EXTENSION_REPO.isNotBlank()) "github" else "neutral",
    private val latestReleaseUrl: String = GITHUB_LATEST_RELEASE_URL
) {
    constructor(
        httpClient: OkHttpClient,
        currentVersion: String = BuildConfig.VERSION_NAME,
        flavorHint: String = if (BuildConfig.DEFAULT_EXTENSION_REPO.isNotBlank()) "github" else "neutral",
        latestReleaseUrl: String = GITHUB_LATEST_RELEASE_URL
    ) : this(
        httpClientProvider = { httpClient },
        currentVersion = currentVersion,
        flavorHint = flavorHint,
        latestReleaseUrl = latestReleaseUrl
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForUpdates(): AppUpdateInfo = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(latestReleaseUrl)
                .header("User-Agent", "Yomic-Android-App")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()

            httpClientProvider().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext AppUpdateInfo(
                        isUpdateAvailable = false,
                        currentVersion = currentVersion,
                        latestVersion = currentVersion
                    )
                }
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val tagName = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val htmlUrl = root["html_url"]?.jsonPrimitive?.content.orEmpty()
                val body = root["body"]?.jsonPrimitive?.content.orEmpty()
                val publishedAt = root["published_at"]?.jsonPrimitive?.content.orEmpty()

                var bestApkUrl = ""
                var fallbackApkUrl = ""
                val assets = root["assets"]?.jsonArray
                if (assets != null) {
                    for (el in assets) {
                        val asset = el.jsonObject
                        val name = asset["name"]?.jsonPrimitive?.content.orEmpty()
                        val dlUrl = asset["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                        if (dlUrl.isNotBlank() && name.endsWith(".apk", ignoreCase = true)) {
                            if (fallbackApkUrl.isBlank()) fallbackApkUrl = dlUrl
                            if (name.lowercase(Locale.US).contains(flavorHint.lowercase(Locale.US))) {
                                bestApkUrl = dlUrl
                                break
                            }
                        }
                    }
                }

                val downloadUrl = bestApkUrl.ifBlank { fallbackApkUrl }.ifBlank { htmlUrl }
                val isNewer = tagName.isNotBlank() && compareVersions(tagName, currentVersion) > 0

                AppUpdateInfo(
                    isUpdateAvailable = isNewer,
                    currentVersion = currentVersion,
                    latestVersion = tagName.ifBlank { currentVersion },
                    downloadUrl = downloadUrl,
                    releaseNotes = body,
                    publishedAt = publishedAt
                )
            }
        } catch (_: Exception) {
            AppUpdateInfo(
                isUpdateAvailable = false,
                currentVersion = currentVersion,
                latestVersion = currentVersion
            )
        }
    }

    companion object {
        const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/ArisaAkiyama/yomic/releases/latest"

        /**
         * Compares two SemVer strings (e.g. `v2.10.0` vs `2.2.1` -> > 0).
         */
        fun compareVersions(latest: String, current: String): Int {
            val lParts = parseNumericParts(latest)
            val cParts = parseNumericParts(current)
            val maxLen = maxOf(lParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val lv = lParts.getOrElse(i) { 0 }
                val cv = cParts.getOrElse(i) { 0 }
                if (lv != cv) return lv.compareTo(cv)
            }
            return 0
        }

        private fun parseNumericParts(version: String): List<Int> {
            val cleaned = version.trim()
                .trimStart('v', 'V')
                .substringBefore('-')
                .substringBefore('+')
            if (cleaned.isBlank()) return listOf(0)
            return cleaned.split('.').map { seg ->
                seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
        }
    }
}
