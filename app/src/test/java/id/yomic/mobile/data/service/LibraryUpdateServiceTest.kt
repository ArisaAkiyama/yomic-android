package id.yomic.mobile.data.service

import id.yomic.mobile.data.local.CategoryEntity
import id.yomic.mobile.data.local.ChapterEntity
import id.yomic.mobile.data.local.MangaEntity
import id.yomic.mobile.data.local.MangaWithRelations
import id.yomic.mobile.model.MangaStatus
import id.yomic.mobile.worker.WorkScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUpdateServiceTest {

    @Test
    fun smartUpdateSkipsCompletedUnreadAndExcludedCategories() {
        val ongoing = MangaEntity(id = "m1", title = "Ongoing", status = MangaStatus.ONGOING.name, inLibrary = true)
        val completed = MangaEntity(id = "m2", title = "Completed", status = MangaStatus.COMPLETED.name, inLibrary = true)
        val readCh = ChapterEntity(id = "c1", mangaId = "m1", isRead = true)
        val unreadCh = ChapterEntity(id = "c2", mangaId = "m1", isRead = false)
        val normalCat = CategoryEntity(id = 1, name = "Favorit", updateExcluded = false)
        val excludedCat = CategoryEntity(id = 2, name = "Arsip", updateExcluded = true)

        assertTrue(LibraryUpdateService.shouldSkipForSmartUpdate(true, completed, listOf(readCh), listOf(normalCat)))
        assertTrue(LibraryUpdateService.shouldSkipForSmartUpdate(true, ongoing, listOf(readCh, unreadCh), listOf(normalCat)))
        assertTrue(LibraryUpdateService.shouldSkipForSmartUpdate(true, ongoing, listOf(readCh), listOf(excludedCat)))
        assertFalse(LibraryUpdateService.shouldSkipForSmartUpdate(true, ongoing, listOf(readCh), listOf(normalCat)))
        assertFalse(LibraryUpdateService.shouldSkipForSmartUpdate(false, completed, listOf(unreadCh), listOf(excludedCat)))
    }

    @Test
    fun groupsSameDayChaptersIntoSingleUpdateRowWithExtraCount() {
        val m1 = MangaEntity(id = "m1", title = "Solo Leveling", sourceName = "Komiku", lang = "ID", inLibrary = true)
        val day1T1 = 1_760_000_100_000L
        val day1T2 = 1_760_000_200_000L
        val day0T1 = day1T1 - 2 * 86_400_000L

        val chapters = listOf(
            ChapterEntity(id = "c12", mangaId = "m1", title = "Ch 12", number = 12.0, dateFetchEpoch = day1T2, isNew = true, isRead = false),
            ChapterEntity(id = "c11", mangaId = "m1", title = "Ch 11", number = 11.0, dateFetchEpoch = day1T1, isNew = true, isRead = false),
            ChapterEntity(id = "c10", mangaId = "m1", title = "Ch 10", number = 10.0, dateFetchEpoch = day0T1, isNew = false, isRead = true)
        )
        val relMap = mapOf(
            "m1" to MangaWithRelations(manga = m1, chapters = chapters, categories = emptyList(), history = null, track = null)
        )

        val grouped = LibraryUpdateService.groupChaptersIntoUpdates(chapters, relMap, isEn = false)
        assertEquals(2, grouped.size)
        assertEquals(12.0, grouped[0].chapterNumber, 0.001)
        assertEquals(1, grouped[0].extraChaptersCount)
        assertTrue(grouped[0].isNew)

        assertEquals(10.0, grouped[1].chapterNumber, 0.001)
        assertEquals(0, grouped[1].extraChaptersCount)
        assertFalse(grouped[1].isNew)
    }

    @Test
    fun workSchedulerParsesAllBackgroundIntervals() {
        assertNull(WorkScheduler.parseUpdateIntervalHours("0"))
        assertNull(WorkScheduler.parseUpdateIntervalHours("Manual"))
        assertEquals(6L, WorkScheduler.parseUpdateIntervalHours("6h"))
        assertEquals(12L, WorkScheduler.parseUpdateIntervalHours("12h"))
        assertEquals(24L, WorkScheduler.parseUpdateIntervalHours("24h"))
    }
}
