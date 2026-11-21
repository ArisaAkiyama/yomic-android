package id.yomic.mobile.engine.js

import id.yomic.mobile.data.network.DohProvider
import id.yomic.mobile.data.network.YomicNetworkClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class QuickJsExtensionEngineTest {

    @Test
    fun generateStableIdMatchesDesktopMd5LittleEndianContract() {
        val expectedBytes = MessageDigest.getInstance("MD5").digest("JS_BacaKomik_id".toByteArray(Charsets.UTF_8))
        val expected = ByteBuffer.wrap(expectedBytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        assertEquals(expected, JsExtensionSource.generateStableId("BacaKomik", "id"))
    }

    @Test
    fun parseChapterNumberHandlesCommonFormats() {
        assertEquals(12.5, JsExtensionSource.parseChapterNumber("Chapter 12.5 - Bonus"), 0.001)
        assertEquals(108.0, JsExtensionSource.parseChapterNumber("Ch. 108"), 0.001)
        assertEquals(4.0, JsExtensionSource.parseChapterNumber("Bab 4 Prolog"), 0.001)
        assertEquals(-1.0, JsExtensionSource.parseChapterNumber("Prologue"), 0.001)
    }

    @Test
    fun executesFullYomicJsExtensionOverQuickJsWithHtmlFetchAtobLangAnd304Cache() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val sampleScript = """
                var source = {
                    name: "MockKomik",
                    baseUrl: "$baseUrl",
                    language: "id",
                    selectedLanguage: "en",
                    version: "1.2.3",
                    genres: ["Action", "Fantasy"],
                    formats: ["Manhwa", "Manga"],
                    getPopularManga: function(page) {
                        let res = fetch(this.baseUrl + "/popular?page=" + page + "&lang=" + this.selectedLanguage);
                        let doc = Html.parse(res.body, this.baseUrl);
                        let items = [];
                        for (let el of doc.querySelectorAll(".card")) {
                            let a = el.querySelector("a");
                            let img = el.querySelector("img");
                            items.push({
                                title: a.text(),
                                url: a.attr("href"),
                                thumbnailUrl: img.absUrl("src"),
                                status: 1
                            });
                        }
                        return { items: items, totalPages: 3 };
                    },
                    getChapterList: function(mangaUrl) {
                        let res = fetch(this.baseUrl + mangaUrl);
                        if (res.status === 304) return [];
                        let data = JSON.parse(res.body);
                        return data.chapters;
                    },
                    getPageList: function(chapterUrl) {
                        let res = fetch(this.baseUrl + chapterUrl, {
                            method: "POST",
                            headers: { "X-Custom": "Yomic" },
                            body: JSON.stringify({ ch: chapterUrl })
                        });
                        let decoded = atob(res.body.trim());
                        return [decoded + "|Referer=" + this.baseUrl + "/"];
                    }
                };
            """.trimIndent()

            val networkClient = YomicNetworkClient(
                context = null,
                initialDohProvider = DohProvider.SYSTEM.settingsKey,
                useAndroidCookieManager = false
            )

            val src = JsExtensionSource.loadFromCode(
                pkg = "mockkomik",
                scriptFileName = "mockkomik.js",
                scriptCode = sampleScript,
                networkClient = networkClient
            )

            assertEquals("MockKomik", src.name)
            assertEquals("1.2.3", src.version)
            assertEquals(listOf("Action", "Fantasy"), src.genres)
            assertEquals(listOf("Manhwa", "Manga"), src.formats)

            // 1. Test Popular Manga + selectedLanguage injection + Html.parse
            src.selectedLanguage = "id"
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """<div class="card"><a href="/series/alpha">Alpha Knight</a><img src="/covers/alpha.jpg"/></div>"""
                )
            )
            val popular = src.getPopularManga(1)
            assertEquals(1, popular.items.size)
            assertEquals("Alpha Knight", popular.items[0].title)
            assertEquals("$baseUrl/covers/alpha.jpg", popular.items[0].thumbnailUrl)
            assertEquals(3, popular.totalPages)
            assertTrue(popular.hasNextPage)
            assertEquals("/popular?page=1&lang=id", server.takeRequest().path)

            // 2. Test Chapter List + ETag extraction
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("ETag", "\"v42\"")
                    .setBody("""{"chapters":[{"name":"Chapter 10","url":"/ch/10","chapterNumber":10}]}""")
            )
            val chCache1 = src.getLatestChaptersWithCache("/series/alpha", null, null)
            assertFalse(chCache1.isNotModified)
            assertEquals("\"v42\"", chCache1.eTag)
            assertEquals(1, chCache1.chapters.size)
            assertEquals(10.0, chCache1.chapters[0].chapterNumber, 0.001)
            server.takeRequest()

            // 3. Test HTTP 304 Not Modified conditional caching
            server.enqueue(MockResponse().setResponseCode(304))
            val chCache2 = src.getLatestChaptersWithCache("/series/alpha", "\"v42\"", null)
            assertTrue(chCache2.isNotModified)
            assertEquals("\"v42\"", server.takeRequest().getHeader("If-None-Match"))

            // 4. Test POST fetch + atob() polyfill
            // Base64("https://cdn.mock/p1.webp") = "aHR0cHM6Ly9jZG4ubW9jay9wMS53ZWJw"
            server.enqueue(MockResponse().setResponseCode(200).setBody("aHR0cHM6Ly9jZG4ubW9jay9wMS53ZWJw"))
            val pageRes = src.getPageListWithStatus("/ch/10")
            assertEquals(1, pageRes.pages.size)
            assertEquals("https://cdn.mock/p1.webp|Referer=$baseUrl/", pageRes.pages[0])
            val postReq = server.takeRequest()
            assertEquals("POST", postReq.method)
            assertEquals("Yomic", postReq.getHeader("X-Custom"))

            // 5. Verify warm QuickJS runtime was created ONCE and reused across all 4 method calls!
            assertEquals(1, src.runtimeCreationCount)
            src.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun warmQuickJsPoolSerializesConcurrentCallsAndResetsVerificationFlag() = runTest {
        val script = """
            var source = {
                name: "WarmPoolSource",
                baseUrl: "https://example.com",
                language: "id",
                version: "1.0.0",
                getPageList: function(url) {
                    if (url === "/cf") {
                        this.lastRequiresVerification = true;
                        return [];
                    }
                    return ["https://cdn.example.com/" + url + ".jpg"];
                }
            };
        """.trimIndent()
        val net = YomicNetworkClient(context = null, initialDohProvider = DohProvider.SYSTEM.settingsKey, useAndroidCookieManager = false)
        val src = JsExtensionSource.loadFromCode("warmpool", "warmpool.js", script, net)
        try {
            // 1. Trigger verification flag on call 1
            val cfRes = src.getPageListWithStatus("/cf")
            assertTrue(cfRes.requiresVerification)
            assertEquals(1, src.runtimeCreationCount)

            // 2. Subsequent call reuses same VM AND resets lastRequiresVerification to false
            val okRes = src.getPageListWithStatus("page1")
            assertFalse(okRes.requiresVerification)
            assertEquals(listOf("https://cdn.example.com/page1.jpg"), okRes.pages)
            assertEquals(1, src.runtimeCreationCount)

            // 3. After closeSafely(), next call transparently rebuilds warm VM (self-healing)
            src.closeSafely()
            val afterClose = src.getPageListWithStatus("page2")
            assertEquals(listOf("https://cdn.example.com/page2.jpg"), afterClose.pages)
            assertEquals(2, src.runtimeCreationCount)

            // 4. Concurrent invocations + closeSafely() serialize cleanly without JNI race
            val concurrent = listOf(
                async { src.getPageListWithStatus("pA") },
                async { src.getPageListWithStatus("pB") },
                async { src.closeSafely(); src.getPageListWithStatus("pC") }
            ).awaitAll()
            assertTrue(concurrent.all { it.pages.size == 1 })
        } finally {
            src.closeSafely()
        }
    }

    @Test
    fun loadsRealDesktopBacaKomikExtensionMetadataInQuickJs() = runTest {
        val candidate = File("../src/YomicDesktop/Plugins/Catalog/bacakomik.js")
        if (!candidate.exists()) return@runTest

        val networkClient = YomicNetworkClient(
            context = null,
            initialDohProvider = DohProvider.SYSTEM.settingsKey,
            useAndroidCookieManager = false
        )
        val src = JsExtensionSource.loadFromCode(
            pkg = "bacakomik",
            scriptFileName = "bacakomik.js",
            scriptCode = candidate.readText(Charsets.UTF_8),
            networkClient = networkClient
        )
        assertEquals("BacaKomik", src.name)
        assertEquals("id", src.language)
        assertTrue(src.genres.contains("Action"))
        assertTrue(src.metadata.supportsMangaList)
        assertNotNull(src.id)
    }
}
