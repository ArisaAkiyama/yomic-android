package id.yomic.mobile.data.repository

import androidx.work.ExistingWorkPolicy
import coil3.fetch.SourceFetchResult
import id.yomic.mobile.data.image.CbzPageData
import id.yomic.mobile.data.image.CbzPageFetcher
import id.yomic.mobile.worker.ChapterDownloadWorker
import id.yomic.mobile.worker.WorkScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadRepositoryTest {

    @Test
    fun promoteTempDownloadToFolderAtomicallyAndLoadsOffline() {
        val root = Files.createTempDirectory("yomic_dl_folder_test").toFile()
        try {
            val tmpDir = DownloadRepository.resolveChapterTmpDir(root, "id.bacakomik", "manga/1", "ch:1")
            val finalDir = DownloadRepository.resolveFinalChapterDir(root, "id.bacakomik", "manga/1", "ch:1")
            val cbzFile = DownloadRepository.resolveFinalCbzFile(root, "id.bacakomik", "manga/1", "ch:1")

            tmpDir.mkdirs()
            File(tmpDir, "010.png").writeBytes(byteArrayOf(10, 20, 30))
            File(tmpDir, "001.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(tmpDir, "002.webp").writeBytes(byteArrayOf(4, 5, 6))
            File(tmpDir, "ignored.txt").writeText("metadata")

            val promoted = DownloadRepository.promoteTempDownload(
                tmpDir = tmpDir,
                finalDir = finalDir,
                cbzFile = cbzFile,
                saveAsCbz = false
            )

            assertEquals(finalDir.absolutePath, promoted.absolutePath)
            assertTrue(finalDir.isDirectory)
            assertFalse(tmpDir.exists())
            assertFalse(cbzFile.exists())

            val localPages = ReaderRepository.loadLocalPages(promoted.absolutePath)
            assertEquals(3, localPages.size)
            assertTrue(localPages[0].isLocalFile)
            assertTrue(localPages[0].urlOrPath.endsWith("001.jpg"))
            assertTrue(localPages[1].urlOrPath.endsWith("002.webp"))
            assertTrue(localPages[2].urlOrPath.endsWith("010.png"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun promoteTempDownloadToCbzArchiveAndRoundTripsViaCbzFetcher() = runTest {
        val root = Files.createTempDirectory("yomic_dl_cbz_test").toFile()
        try {
            val tmpDir = DownloadRepository.resolveChapterTmpDir(root, "en.mangadex", "manga_42", "ch_12")
            val finalDir = DownloadRepository.resolveFinalChapterDir(root, "en.mangadex", "manga_42", "ch_12")
            val cbzFile = DownloadRepository.resolveFinalCbzFile(root, "en.mangadex", "manga_42", "ch_12")

            tmpDir.mkdirs()
            val page1Bytes = "JPEG_PAGE_1_RAW_BYTES".toByteArray(Charsets.UTF_8)
            val page2Bytes = "WEBP_PAGE_2_RAW_BYTES".toByteArray(Charsets.UTF_8)
            File(tmpDir, "001.jpg").writeBytes(page1Bytes)
            File(tmpDir, "002.webp").writeBytes(page2Bytes)

            val promoted = DownloadRepository.promoteTempDownload(
                tmpDir = tmpDir,
                finalDir = finalDir,
                cbzFile = cbzFile,
                saveAsCbz = true
            )

            assertEquals(cbzFile.absolutePath, promoted.absolutePath)
            assertTrue(cbzFile.isFile)
            assertTrue(cbzFile.length() > 0L)
            assertFalse(tmpDir.exists())
            assertFalse(finalDir.exists())

            val pages = ReaderRepository.loadLocalPages(cbzFile.absolutePath)
            assertEquals(2, pages.size)
            assertTrue(pages[0].isCbzEntry)
            assertEquals("001.jpg", pages[0].cbzEntryName)
            assertEquals("002.webp", pages[1].cbzEntryName)

            val fetched1 = CbzPageFetcher(CbzPageData(pages[0].cbzArchivePath, pages[0].cbzEntryName)).fetch() as SourceFetchResult
            assertEquals("image/jpeg", fetched1.mimeType)
            assertArrayEquals(page1Bytes, fetched1.source.source().readByteArray())

            val fetched2 = CbzPageFetcher(CbzPageData(pages[1].cbzArchivePath, pages[1].cbzEntryName)).fetch() as SourceFetchResult
            assertEquals("image/webp", fetched2.mimeType)
            assertArrayEquals(page2Bytes, fetched2.source.source().readByteArray())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun guessesImageExtensionsSanitizesPathsAndFormatsSpeedAndSize() {
        assertEquals("webp", DownloadRepository.guessImageExtension("https://cdn.example.com/1.jpg", "image/webp"))
        assertEquals("png", DownloadRepository.guessImageExtension("https://cdn.example.com/page.PNG?v=2|Referer=https://a.com", null))
        assertEquals("avif", DownloadRepository.guessImageExtension("https://cdn.example.com/page.avif|Referer=https://a.com", "application/octet-stream"))
        assertEquals("jpg", DownloadRepository.guessImageExtension("https://cdn.example.com/img?id=99", null))

        assertEquals("baca_komik_v1.2-id", DownloadRepository.sanitizeSegment("baca/komik:v1.2-id"))
        assertEquals("unknown", DownloadRepository.sanitizeSegment("   "))

        assertEquals("", DownloadRepository.formatSpeed(0.0))
        assertEquals("512 KB/s", DownloadRepository.formatSpeed(512.0 * 1024.0))
        assertEquals("2.5 MB/s", DownloadRepository.formatSpeed(2.5 * 1024.0 * 1024.0))

        assertEquals("0.0 MB", DownloadRepository.formatFileSize(0L))
        assertEquals("48 KB", DownloadRepository.formatFileSize(48L * 1024L))
        assertEquals("14.5 MB", DownloadRepository.formatFileSize((14.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun strictDownloadIntegrityRejectsPartialPageFailuresAndAborts() {
        assertTrue(ChapterDownloadWorker.shouldPromoteDownload(totalPages = 20, completedPages = 20, failedPages = 0, aborted = false))
        assertFalse(ChapterDownloadWorker.shouldPromoteDownload(totalPages = 20, completedPages = 20, failedPages = 1, aborted = false))
        assertFalse(ChapterDownloadWorker.shouldPromoteDownload(totalPages = 20, completedPages = 19, failedPages = 0, aborted = false))
        assertFalse(ChapterDownloadWorker.shouldPromoteDownload(totalPages = 20, completedPages = 20, failedPages = 0, aborted = true))
        assertFalse(ChapterDownloadWorker.shouldPromoteDownload(totalPages = 0, completedPages = 0, failedPages = 0, aborted = false))

        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, WorkScheduler.resolveDownloadWorkPolicy(replaceConstraints = false))
        assertEquals(ExistingWorkPolicy.REPLACE, WorkScheduler.resolveDownloadWorkPolicy(replaceConstraints = true))
    }
}
