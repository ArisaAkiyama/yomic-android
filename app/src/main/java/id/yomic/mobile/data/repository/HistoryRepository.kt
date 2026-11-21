package id.yomic.mobile.data.repository

import id.yomic.mobile.data.local.ChapterDao
import id.yomic.mobile.data.local.DailyReadingLogDao
import id.yomic.mobile.data.local.DailyReadingLogEntity
import id.yomic.mobile.data.local.HistoryDao
import id.yomic.mobile.data.local.HistoryEntity
import id.yomic.mobile.data.local.MangaDao
import id.yomic.mobile.data.preferences.SettingsRepository
import id.yomic.mobile.model.DailyActivityStatItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class StreakAndDurationSummary(
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val activeDaysCount: Int = 0,
    val totalReadingMinutes: Int = 0
)

/**
 * Persists reading history, reading session duration, and daily reading logs in Room.
 * Strictly honors `isIncognitoMode` (writes zero history/log records while Incognito is active).
 */
class HistoryRepository(
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val historyDao: HistoryDao,
    private val dailyLogDao: DailyReadingLogDao,
    private val settingsRepository: SettingsRepository
) {

    fun observeDailyActivity14Days(isEn: () -> Boolean): Flow<List<DailyActivityStatItem>> =
        dailyLogDao.observeAll().map { logs ->
            build14DayWindow(logs = logs, todayEpochMs = System.currentTimeMillis(), isEn = isEn())
        }

    fun observeAllDailyLogs(): Flow<List<DailyReadingLogEntity>> = dailyLogDao.observeAll()

    suspend fun recordChapterOpened(
        mangaId: String,
        chapterNumber: Double,
        page: Int,
        totalPages: Int
    ) = withContext(Dispatchers.IO) {
        if (settingsRepository.getCurrentSettings().isIncognitoMode) return@withContext

        val now = System.currentTimeMillis()
        val ch = chapterDao.getByMangaAndNumber(mangaId, chapterNumber)
        val wasAlreadyRead = ch?.isRead == true
        val prevHistory = historyDao.getByMangaId(mangaId)
        val isDifferentChapter = prevHistory == null || prevHistory.chapterId != (ch?.id ?: "")

        chapterDao.markOpened(mangaId, chapterNumber, page, totalPages)
        mangaDao.updateLastViewed(mangaId, now)

        historyDao.upsert(
            HistoryEntity(
                mangaId = mangaId,
                chapterId = ch?.id ?: prevHistory?.chapterId.orEmpty(),
                chapterNumber = chapterNumber,
                lastPageRead = page.coerceAtLeast(1),
                totalPages = totalPages.coerceAtLeast(ch?.totalPages ?: 0),
                lastReadEpoch = now,
                timeReadMs = prevHistory?.timeReadMs ?: 0L,
                isRemoved = false
            )
        )

        if (!wasAlreadyRead || isDifferentChapter) {
            val todayIso = formatIsoDate(now)
            val prevLog = dailyLogDao.getByDate(todayIso)
            dailyLogDao.upsert(
                DailyReadingLogEntity(
                    dateIso = todayIso,
                    chaptersRead = (prevLog?.chaptersRead ?: 0) + 1,
                    secondsRead = prevLog?.secondsRead ?: 0
                )
            )
        }
    }

    suspend fun updateReadingPage(
        mangaId: String,
        chapterNumber: Double,
        page: Int,
        totalPages: Int
    ) = withContext(Dispatchers.IO) {
        if (settingsRepository.getCurrentSettings().isIncognitoMode) return@withContext

        val now = System.currentTimeMillis()
        chapterDao.updatePageProgress(mangaId, chapterNumber, page, totalPages)
        mangaDao.updateLastViewed(mangaId, now)

        val prev = historyDao.getByMangaId(mangaId)
        val ch = chapterDao.getByMangaAndNumber(mangaId, chapterNumber)
        historyDao.upsert(
            HistoryEntity(
                mangaId = mangaId,
                chapterId = ch?.id ?: prev?.chapterId.orEmpty(),
                chapterNumber = chapterNumber,
                lastPageRead = page.coerceAtLeast(1),
                totalPages = totalPages.coerceAtLeast(prev?.totalPages ?: 0),
                lastReadEpoch = now,
                timeReadMs = prev?.timeReadMs ?: 0L,
                isRemoved = false
            )
        )
    }

    suspend fun recordReadingDuration(mangaId: String, deltaSeconds: Int) = withContext(Dispatchers.IO) {
        if (deltaSeconds <= 0) return@withContext
        if (settingsRepository.getCurrentSettings().isIncognitoMode) return@withContext

        val now = System.currentTimeMillis()
        historyDao.addTimeRead(mangaId, deltaSeconds * 1000L)

        val todayIso = formatIsoDate(now)
        val prevLog = dailyLogDao.getByDate(todayIso)
        dailyLogDao.upsert(
            DailyReadingLogEntity(
                dateIso = todayIso,
                chaptersRead = prevLog?.chaptersRead ?: 0,
                secondsRead = (prevLog?.secondsRead ?: 0) + deltaSeconds
            )
        )
    }

    suspend fun removeHistoryItem(mangaId: String) = withContext(Dispatchers.IO) {
        historyDao.setRemoved(mangaId, removed = true)
    }

    suspend fun restoreHistoryItem(mangaId: String) = withContext(Dispatchers.IO) {
        historyDao.setRemoved(mangaId, removed = false)
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        historyDao.clearAll()
        val allMangas = mangaDao.getAllSnapshot()
        for (m in allMangas) {
            if (m.lastViewedEpoch > 0L) {
                mangaDao.updateLastViewed(m.id, 0L)
            }
        }
    }

    companion object {
        private val idDays = listOf("Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab")
        private val enDays = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

        fun formatIsoDate(epochMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMs))

        fun build14DayWindow(
            logs: List<DailyReadingLogEntity>,
            todayEpochMs: Long,
            isEn: Boolean
        ): List<DailyActivityStatItem> {
            val byDate = logs.associateBy { it.dateIso }
            val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val fullFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val cal = Calendar.getInstance().apply {
                timeInMillis = todayEpochMs
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -13)
            }

            return List(14) {
                val date = cal.time
                val iso = isoFmt.format(date)
                val dayIdx = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
                val label = if (isEn) enDays[dayIdx] else idDays[dayIdx]
                val log = byDate[iso]
                val chapters = log?.chaptersRead ?: 0
                val minutes = when {
                    log != null && log.secondsRead > 0 -> (log.secondsRead / 60f).roundToInt().coerceAtLeast(1)
                    chapters > 0 -> (chapters * 2.5f).roundToInt().coerceAtLeast(1)
                    else -> 0
                }
                cal.add(Calendar.DAY_OF_YEAR, 1)
                DailyActivityStatItem(
                    dateLabel = label,
                    fullDate = fullFmt.format(date),
                    chaptersRead = chapters,
                    minutesRead = minutes
                )
            }
        }

        fun computeStreakStats(
            logs: List<DailyReadingLogEntity>,
            todayEpochMs: Long
        ): StreakAndDurationSummary {
            val activeLogs = logs.filter { it.chaptersRead > 0 || it.secondsRead >= 30 }
            if (activeLogs.isEmpty()) return StreakAndDurationSummary()

            val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val activeDateSet = activeLogs.map { it.dateIso }.toHashSet()
            val sortedDates = activeDateSet.sorted()

            // Longest consecutive streak across all recorded dates
            var longest = 0
            var currentRun = 0
            var prevDayEpoch: Long? = null
            for (iso in sortedDates) {
                val parsed = runCatching { isoFmt.parse(iso)?.time }.getOrNull() ?: continue
                if (prevDayEpoch != null) {
                    val diffDays = ((parsed - prevDayEpoch) / 86_400_000.0).roundToInt()
                    currentRun = if (diffDays == 1) currentRun + 1 else 1
                } else {
                    currentRun = 1
                }
                if (currentRun > longest) longest = currentRun
                prevDayEpoch = parsed
            }

            // Current streak: counts backwards from today (or yesterday if user hasn't read yet today)
            val cal = Calendar.getInstance().apply {
                timeInMillis = todayEpochMs
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val todayIso = isoFmt.format(cal.time)
            if (todayIso !in activeDateSet) {
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }
            var curStreak = 0
            while (isoFmt.format(cal.time) in activeDateSet) {
                curStreak++
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }

            val totalMinutes = activeLogs.sumOf { log ->
                when {
                    log.secondsRead > 0 -> (log.secondsRead / 60f).roundToInt().coerceAtLeast(1)
                    log.chaptersRead > 0 -> (log.chaptersRead * 2.5f).roundToInt().coerceAtLeast(1)
                    else -> 0
                }
            }

            return StreakAndDurationSummary(
                currentStreakDays = curStreak,
                longestStreakDays = longest.coerceAtLeast(curStreak),
                activeDaysCount = activeDateSet.size,
                totalReadingMinutes = totalMinutes
            )
        }
    }
}
