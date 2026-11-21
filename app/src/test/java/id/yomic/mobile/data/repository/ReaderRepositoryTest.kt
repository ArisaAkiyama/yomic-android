package id.yomic.mobile.data.repository

import coil3.fetch.SourceFetchResult
import id.yomic.mobile.data.image.CbzPageData
import id.yomic.mobile.data.image.CbzPageFetcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReaderRepositoryTest {

    @Test
    fun naturalCompareOrdersPagesNumerically() {
        val names = listOf("page_10.jpg", "page_2.jpg", "page_1.jpg", "page_20.webp", "page_3.png")
        val sorted = names.sortedWith { a, b -> ReaderRepository.naturalCompare(a, b) }
        assertEquals(listOf("page_1.jpg", "page_2.jpg", "page_3.png", "page_10.jpg", "page_20.webp"), sorted)
    }

    @Test
    fun loadsLocalDirectoryAndCbzArchivePagesAndStreamsBytes() = runTest {
        val tempDir = Files.createTempDirectory("yomic_reader_test").toFile()
        try {
            // 1. Folder test
            val chDir = File(tempDir, "ch1").apply { mkdirs() }
            File(chDir, "10.jpg").writeBytes(byteArrayOf(10))
            File(chDir, "2.jpg").writeBytes(byteArrayOf(2))
            File(chDir, "1.jpg").writeBytes(byteArrayOf(1))
            File(chDir, "meta.txt").writeText("ignored")

            val dirPages = ReaderRepository.loadLocalPages(chDir.absolutePath)
            assertEquals(3, dirPages.size)
            assertTrue(dirPages[0].urlOrPath.endsWith("1.jpg"))
            assertTrue(dirPages[1].urlOrPath.endsWith("2.jpg"))
            assertTrue(dirPages[2].urlOrPath.endsWith("10.jpg"))

            // 2. CBZ test + CbzPageFetcher streaming test
            val cbzFile = File(tempDir, "chapter_2.cbz")
            val page2Bytes = "WEBP_PAGE_2_CONTENT".toByteArray(Charsets.UTF_8)
            ZipOutputStream(cbzFile.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("010.webp"))
                zos.write("WEBP_PAGE_10".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("002.webp"))
                zos.write(page2Bytes)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("ComicInfo.xml"))
                zos.write("<ComicInfo/>".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val cbzPages = ReaderRepository.loadLocalPages(cbzFile.absolutePath)
            assertEquals(2, cbzPages.size)
            assertTrue(cbzPages[0].isCbzEntry)
            assertEquals("002.webp", cbzPages[0].cbzEntryName)
            assertEquals("010.webp", cbzPages[1].cbzEntryName)

            val fetched = CbzPageFetcher(CbzPageData(cbzPages[0].cbzArchivePath, cbzPages[0].cbzEntryName)).fetch()
            assertTrue(fetched is SourceFetchResult)
            val result = fetched as SourceFetchResult
            assertEquals("image/webp", result.mimeType)
            assertArrayEquals(page2Bytes, result.source.source().readByteArray())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
