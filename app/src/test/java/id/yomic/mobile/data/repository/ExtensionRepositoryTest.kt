package id.yomic.mobile.data.repository

import id.yomic.mobile.data.service.SourceStatusService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionRepositoryTest {

    @Test
    fun expandsOfficialYomicRepoToMultiCdnMirrorsAndKeepsCustomRepoDirect() {
        val official = ExtensionRepository.expandIndexMirrorUrls(
            "https://cdn.jsdelivr.net/gh/ArisaAkiyama/extension-yomic@main/index.min.json"
        )
        assertEquals(5, official.size)
        assertTrue(official.any { it.contains("cdn.jsdelivr.net") })
        assertTrue(official.any { it.contains("fastly.jsdelivr.net") })
        assertTrue(official.any { it.contains("raw.githubusercontent.com") })

        val custom = ExtensionRepository.expandIndexMirrorUrls("https://my-repo.example.com/yomic/index.min.json")
        assertEquals(1, custom.size)
        assertTrue(custom[0].startsWith("https://my-repo.example.com/yomic/index.min.json?t="))

        val customScripts = ExtensionRepository.buildScriptDownloadUrls(
            repoUrl = "https://my-repo.example.com/yomic/index.min.json",
            langFolder = "id",
            cleanFileName = "komiku.js"
        )
        assertTrue(customScripts[0].startsWith("https://my-repo.example.com/yomic/src/id/komiku.js"))
        assertTrue(customScripts[1].startsWith("https://my-repo.example.com/yomic/komiku.js"))
    }

    @Test
    fun calculatesVersionCodeCleansNamesAndDetectsNsfw() {
        assertEquals(123, ExtensionRepository.calculateVersionCode("v1.2.3"))
        assertEquals(208, ExtensionRepository.calculateVersionCode("2.0.8"))
        assertEquals("BacaKomik", ExtensionRepository.cleanExtensionName("Yomic: BacaKomik (ID)"))

        assertTrue(ExtensionRepository.isAdultIndicator("Doujindesu", "https://doujin.desu.xxx"))
        assertTrue(ExtensionRepository.isAdultIndicator("NHentai.xxx"))
        assertFalse(ExtensionRepository.isAdultIndicator("Komiku", "https://komiku.org"))

        assertEquals(
            ExtensionRepository.generateMangaId("komiku", "/manga/solo"),
            ExtensionRepository.generateMangaId("KOMIKU", "/manga/solo")
        )
    }

    @Test
    fun sourceStatusIntervalParsesAllChoices() {
        assertEquals(15L * 60_000L, SourceStatusService.parseIntervalMs("15m"))
        assertEquals(30L * 60_000L, SourceStatusService.parseIntervalMs("30m"))
        assertEquals(60L * 60_000L, SourceStatusService.parseIntervalMs("1h"))
        assertNull(SourceStatusService.parseIntervalMs("Off"))
    }
}
