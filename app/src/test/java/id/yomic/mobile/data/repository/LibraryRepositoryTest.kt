package id.yomic.mobile.data.repository

import id.yomic.mobile.data.local.ChapterEntity
import id.yomic.mobile.engine.js.RawJsChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryTest {

    @Test
    fun sanitizeTitleRemovesKomikAndMangaPrefixes() {
        assertEquals("Solo Leveling", LibraryRepository.sanitizeTitle("Komik Solo Leveling"))
        assertEquals("One Piece", LibraryRepository.sanitizeTitle("Manga One Piece"))
        assertEquals("Omniscient Reader", LibraryRepository.sanitizeTitle("  Omniscient Reader  "))
    }

    @Test
    fun mergeChaptersPreservesReadBookmarkDownloadAndFlagsNewReleases() {
        val existing = listOf(
            ChapterEntity(
                id = "ch_m1_1",
                mangaId = "m1",
                url = "/ch-1",
                title = "Chapter 1",
                number = 1.0,
                isRead = true,
                isBookmarked = true,
                isDownloaded = true,
                isNew = false,
                lastPageRead = 14,
                totalPages = 14,
                localPath = "/downloads/m1/ch1.cbz"
            ),
            ChapterEntity(
                id = "ch_m1_2",
                mangaId = "m1",
                url = "/ch-2",
                title = "Chapter 2",
                number = 2.0,
                isRead = false,
                isBookmarked = false,
                isDownloaded = false,
                isNew = false,
                lastPageRead = 5,
                totalPages = 16
            )
        )

        val incoming = listOf(
            RawJsChapter(name = "Chapter 3 - Awakening", url = "/ch-3", chapterNumber = 3.0),
            RawJsChapter(name = "Chapter 2 (Revised)", url = "/ch-2", chapterNumber = 2.0),
            RawJsChapter(name = "Chapter 1", url = "/ch-1", chapterNumber = 1.0)
        )

        val now = 1_750_000_000_000L
        val (merged, newCount) = LibraryRepository.mergeChapters(
            mangaId = "m1",
            existing = existing,
            incoming = incoming,
            markNewAsUpdates = true,
            nowEpoch = now
        )

        assertEquals(1, newCount)
        assertEquals(3, merged.size)

        val ch3 = merged.first { it.url == "/ch-3" }
        assertTrue(ch3.isNew)
        assertEquals(now, ch3.dateFetchEpoch)
        assertFalse(ch3.isRead)

        val ch2 = merged.first { it.url == "/ch-2" }
        assertEquals("Chapter 2 (Revised)", ch2.title)
        assertEquals(5, ch2.lastPageRead)
        assertFalse(ch2.isNew)

        val ch1 = merged.first { it.url == "/ch-1" }
        assertTrue(ch1.isRead)
        assertTrue(ch1.isBookmarked)
        assertTrue(ch1.isDownloaded)
        assertEquals("/downloads/m1/ch1.cbz", ch1.localPath)
        assertEquals(14, ch1.lastPageRead)
    }

    @Test
    fun initialChapterMergeDoesNotMarkAllChaptersAsNewUpdates() {
        val incoming = listOf(
            RawJsChapter(name = "Chapter 2", url = "/ch-2", chapterNumber = 2.0),
            RawJsChapter(name = "Chapter 1", url = "/ch-1", chapterNumber = 1.0)
        )
        val (merged, newCount) = LibraryRepository.mergeChapters(
            mangaId = "m2",
            existing = emptyList(),
            incoming = incoming,
            markNewAsUpdates = false,
            nowEpoch = 1_750_000_000_000L
        )
        assertEquals(2, newCount)
        assertTrue(merged.all { !it.isNew && it.dateFetchEpoch == 0L })
    }
}
