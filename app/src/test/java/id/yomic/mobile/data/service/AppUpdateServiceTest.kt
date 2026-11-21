package id.yomic.mobile.data.service

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateServiceTest {

    @Test
    fun comparesSemanticVersionsAccurately() {
        assertTrue(AppUpdateService.compareVersions("v2.2.0", "2.1.0") > 0)
        assertTrue(AppUpdateService.compareVersions("v2.10.0", "2.2.0") > 0)
        assertEquals(0, AppUpdateService.compareVersions("v2.1.0", "2.1.0"))
        assertTrue(AppUpdateService.compareVersions("2.0.9", "2.1.0") < 0)
        assertTrue(AppUpdateService.compareVersions("v2.1.1-beta.1", "2.1.0") > 0)
    }

    @Test
    fun parsesGitHubReleaseAndPrefersFlavorMatchingApkAsset() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "tag_name": "v2.3.0",
                      "html_url": "https://github.com/ArisaAkiyama/yomic/releases/tag/v2.3.0",
                      "published_at": "2026-11-21T08:00:00Z",
                      "body": "Android Native full backend release.",
                      "assets": [
                        {
                          "name": "YomicSetup-2.3.0.exe",
                          "browser_download_url": "https://example.com/YomicSetup.exe"
                        },
                        {
                          "name": "yomic-mobile-neutral-release.apk",
                          "browser_download_url": "https://example.com/yomic-neutral.apk"
                        },
                        {
                          "name": "yomic-mobile-github-release.apk",
                          "browser_download_url": "https://example.com/yomic-github.apk"
                        }
                      ]
                    }
                    """.trimIndent()
                )
            )

            val service = AppUpdateService(
                httpClient = OkHttpClient(),
                currentVersion = "2.1.0",
                flavorHint = "github",
                latestReleaseUrl = server.url("/releases/latest").toString()
            )
            val info = service.checkForUpdates()
            assertTrue(info.isUpdateAvailable)
            assertEquals("v2.3.0", info.latestVersion)
            assertEquals("https://example.com/yomic-github.apk", info.downloadUrl)
            assertTrue(info.releaseNotes.contains("Android Native"))

            // When already on 2.3.0 -> no update available
            server.enqueue(
                MockResponse().setBody(
                    """{"tag_name":"v2.3.0","html_url":"https://github.com/ArisaAkiyama/yomic/releases/tag/v2.3.0","assets":[]}"""
                )
            )
            val upToDateService = AppUpdateService(
                httpClient = OkHttpClient(),
                currentVersion = "2.3.0",
                flavorHint = "neutral",
                latestReleaseUrl = server.url("/releases/latest").toString()
            )
            val upToDate = upToDateService.checkForUpdates()
            assertFalse(upToDate.isUpdateAvailable)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun dynamicallySwitchesHttpClientWhenProviderChanges() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val clientA = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Client-Tag", "A").build())
            }.build()
            val clientB = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Client-Tag", "B").build())
            }.build()
            var activeClient = clientA

            val service = AppUpdateService(
                httpClientProvider = { activeClient },
                currentVersion = "2.1.0",
                latestReleaseUrl = server.url("/releases/latest").toString()
            )

            server.enqueue(MockResponse().setBody("""{"tag_name":"v2.1.0","html_url":"https://example.com","assets":[]}"""))
            service.checkForUpdates()
            assertEquals("A", server.takeRequest().getHeader("X-Client-Tag"))

            activeClient = clientB
            server.enqueue(MockResponse().setBody("""{"tag_name":"v2.2.0","html_url":"https://example.com","assets":[]}"""))
            assertTrue(service.checkForUpdates().isUpdateAvailable)
            assertEquals("B", server.takeRequest().getHeader("X-Client-Tag"))
        } finally {
            server.shutdown()
        }
    }
}
