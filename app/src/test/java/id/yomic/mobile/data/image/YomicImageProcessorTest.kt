package id.yomic.mobile.data.image

import org.junit.Assert.assertEquals
import org.junit.Test

class YomicImageProcessorTest {

    @Test
    fun downscalesStandardMangaPageProportionallyByLargestEdge() {
        val (w, h) = YomicImageProcessor.computeTargetDimensions(width = 2400, height = 3600, maxDim = 2160)
        assertEquals(1440, w)
        assertEquals(2160, h)
    }

    @Test
    fun preservesWebtoonLongStripWidthInsteadOfCrushingToBlurryThumbnail() {
        // Standard 800x5000 webtoon strip should NOT be shrunk horizontally
        val (w1, h1) = YomicImageProcessor.computeTargetDimensions(width = 800, height = 5000, maxDim = 2160)
        assertEquals(800, w1)
        assertEquals(5000, h1)

        // Wide high-res 2880x9000 webtoon strip caps width to 2160 (2160x6750)
        val (w2, h2) = YomicImageProcessor.computeTargetDimensions(width = 2880, height = 9000, maxDim = 2160)
        assertEquals(2160, w2)
        assertEquals(6750, h2)

        // Extreme 800x12000 strip clamps only against safe GPU texture height (8192), never crushing to 144px wide
        val (w3, h3) = YomicImageProcessor.computeTargetDimensions(width = 800, height = 12000, maxDim = 2160)
        assertEquals(546, w3)
        assertEquals(8192, h3)
    }

    @Test
    fun respectsOriginalResolutionSettingWhileGuardingGpuTextureLimit() {
        val (w1, h1) = YomicImageProcessor.computeTargetDimensions(width = 3000, height = 4500, maxDim = 0)
        assertEquals(3000, w1)
        assertEquals(4500, h1)

        val (w2, h2) = YomicImageProcessor.computeTargetDimensions(width = 1000, height = 16384, maxDim = 0)
        assertEquals(500, w2)
        assertEquals(8192, h2)
    }
}
