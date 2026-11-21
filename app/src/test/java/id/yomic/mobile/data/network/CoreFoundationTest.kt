package id.yomic.mobile.data.network

import id.yomic.mobile.data.image.YomicImagePipeline
import id.yomic.mobile.data.local.LocalEntityMappers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreFoundationTest {

    @Test
    fun dohProviderMapsAllSettingsValuesCorrectly() {
        assertEquals(DohProvider.CLOUDFLARE, DohProvider.fromSettingsKey("Cloudflare (1.1.1.1)"))
        assertEquals(DohProvider.GOOGLE, DohProvider.fromSettingsKey("Google (8.8.8.8)"))
        assertEquals(DohProvider.QUAD9, DohProvider.fromSettingsKey("Quad9 (9.9.9.9)"))
        assertEquals(DohProvider.ADGUARD, DohProvider.fromSettingsKey("AdGuard DNS"))
        assertEquals(DohProvider.SYSTEM, DohProvider.fromSettingsKey("System Default"))
        assertEquals(DohProvider.CLOUDFLARE, DohProvider.fromSettingsKey("unknown"))
    }

    @Test
    fun cloudflareChallengeDetectionHandlesMarkers() {
        assertTrue(YomicNetworkClient.isCloudflareChallenge(403, "cloudflare", "challenge", ""))
        assertTrue(YomicNetworkClient.isCloudflareChallenge(503, "cloudflare", null, "<title>Just a moment...</title>"))
        assertFalse(YomicNetworkClient.isCloudflareChallenge(200, "cloudflare", null, "<html>OK</html>"))
        assertFalse(YomicNetworkClient.isCloudflareChallenge(404, "nginx", null, "Not Found"))
    }

    @Test
    fun cacheLimitParsingAndByteFormattingWork() {
        assertEquals(250L * 1024L * 1024L, YomicImagePipeline.parseCacheSizeBytes("250 MB"))
        assertEquals(500L * 1024L * 1024L, YomicImagePipeline.parseCacheSizeBytes("500 MB"))
        assertEquals(1024L * 1024L * 1024L, YomicImagePipeline.parseCacheSizeBytes("1 GB"))
        assertEquals(2L * 1024L * 1024L * 1024L, YomicImagePipeline.parseCacheSizeBytes("2 GB"))
        assertEquals("0.0 MB", YomicImagePipeline.formatBytes(0L))
        assertEquals("1.5 MB", YomicImagePipeline.formatBytes((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun localEntityMapperRoundTripsGenresAndDeterministicPalette() {
        val genres = listOf("Action", "Fantasy", "Isekai")
        val encoded = LocalEntityMappers.encodeGenres(genres)
        assertEquals(genres, LocalEntityMappers.decodeGenres(encoded))

        val p1 = LocalEntityMappers.deriveCoverPalette("Solo Leveling")
        val p2 = LocalEntityMappers.deriveCoverPalette("Solo Leveling")
        assertEquals(p1, p2)
    }
}
