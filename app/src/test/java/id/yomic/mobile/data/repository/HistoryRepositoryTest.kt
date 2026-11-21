package id.yomic.mobile.data.repository

import id.yomic.mobile.data.local.DailyReadingLogEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class HistoryRepositoryTest {

    @Test
    fun buildsExact14DayWindowFillingGapsAndRealMinutes() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 21, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayMs = cal.timeInMillis
        val logs = listOf(
            DailyReadingLogEntity(dateIso = "2026-11-21", chaptersRead = 3, secondsRead = 600), // 10 min
            DailyReadingLogEntity(dateIso = "2026-11-19", chaptersRead = 2, secondsRead = 0)    // fallback 5 min
        )

        val window = HistoryRepository.build14DayWindow(logs, todayMs, isEn = false)
        assertEquals(14, window.size)
        assertEquals(3, window.last().chaptersRead)
        assertEquals(10, window.last().minutesRead)
        assertEquals(0, window[12].chaptersRead)
        assertEquals(2, window[11].chaptersRead)
        assertEquals(5, window[11].minutesRead)
    }

    @Test
    fun computesCurrentLongestStreakActiveDaysAndTotalMinutes() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 21, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayMs = cal.timeInMillis

        // 4-day streak earlier (Nov 10..13), gap on Nov 14..18, 2-day current streak (Nov 20..21)
        val logs = listOf(
            DailyReadingLogEntity("2026-11-10", 2, 300),
            DailyReadingLogEntity("2026-11-11", 1, 180),
            DailyReadingLogEntity("2026-11-12", 4, 480),
            DailyReadingLogEntity("2026-11-13", 1, 120),
            DailyReadingLogEntity("2026-11-20", 2, 240),
            DailyReadingLogEntity("2026-11-21", 3, 360)
        )

        val summary = HistoryRepository.computeStreakStats(logs, todayMs)
        assertEquals(2, summary.currentStreakDays)
        assertEquals(4, summary.longestStreakDays)
        assertEquals(6, summary.activeDaysCount)
        assertEquals(28, summary.totalReadingMinutes) // (300+180+480+120+240+360)/60 = 28

        // If last read was 3 days ago, current streak should reset to 0 while keeping longest
        val brokenSummary = HistoryRepository.computeStreakStats(logs.take(4), todayMs)
        assertEquals(0, brokenSummary.currentStreakDays)
        assertEquals(4, brokenSummary.longestStreakDays)
    }
}
