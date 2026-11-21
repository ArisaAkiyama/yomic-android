package id.yomic.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaDao {
    @Transaction
    @Query("SELECT * FROM mangas ORDER BY title COLLATE NOCASE ASC")
    fun observeAllWithRelations(): Flow<List<MangaWithRelations>>

    @Transaction
    @Query("SELECT * FROM mangas WHERE inLibrary = 1 ORDER BY title COLLATE NOCASE ASC")
    fun observeLibraryWithRelations(): Flow<List<MangaWithRelations>>

    @Transaction
    @Query("SELECT * FROM mangas WHERE id = :mangaId LIMIT 1")
    suspend fun getWithRelations(mangaId: String): MangaWithRelations?

    @Query("SELECT * FROM mangas WHERE id = :mangaId LIMIT 1")
    suspend fun getById(mangaId: String): MangaEntity?

    @Query("SELECT * FROM mangas WHERE numericSourceId = :sourceId AND url = :url LIMIT 1")
    suspend fun getBySourceAndUrl(sourceId: Long, url: String): MangaEntity?

    @Query("SELECT * FROM mangas WHERE inLibrary = 1")
    suspend fun getLibraryMangasSnapshot(): List<MangaEntity>

    @Query("SELECT * FROM mangas")
    suspend fun getAllSnapshot(): List<MangaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(manga: MangaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(mangas: List<MangaEntity>)

    @Update
    suspend fun update(manga: MangaEntity)

    @Query("UPDATE mangas SET inLibrary = :inLibrary, dateAddedEpoch = CASE WHEN :inLibrary = 1 AND dateAddedEpoch = 0 THEN :nowEpoch ELSE dateAddedEpoch END WHERE id = :mangaId")
    suspend fun setInLibrary(mangaId: String, inLibrary: Boolean, nowEpoch: Long)

    @Query("UPDATE mangas SET inLibrary = 0 WHERE id IN (:mangaIds)")
    suspend fun removeMultipleFromLibrary(mangaIds: List<String>)

    @Query("UPDATE mangas SET lastViewedEpoch = :epoch WHERE id = :mangaId")
    suspend fun updateLastViewed(mangaId: String, epoch: Long)

    @Query("UPDATE mangas SET lastUpdateEpoch = :epoch, responseETag = :eTag, responseLastModified = :lastModified WHERE id = :mangaId")
    suspend fun updateSyncMetadata(mangaId: String, epoch: Long, eTag: String?, lastModified: String?)

    @Query("DELETE FROM mangas")
    suspend fun deleteAll()
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE mangaId = :mangaId ORDER BY number DESC")
    fun observeChaptersForManga(mangaId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE mangaId = :mangaId ORDER BY number DESC")
    suspend fun getChaptersForManga(mangaId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :chapterId LIMIT 1")
    suspend fun getById(chapterId: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE mangaId = :mangaId AND ABS(number - :chapterNumber) < 0.001 LIMIT 1")
    suspend fun getByMangaAndNumber(mangaId: String, chapterNumber: Double): ChapterEntity?

    @Query("SELECT * FROM chapters")
    suspend fun getAllSnapshot(): List<ChapterEntity>

    @Query(
        """
        SELECT c.* FROM chapters c
        INNER JOIN mangas m ON c.mangaId = m.id
        WHERE m.inLibrary = 1 AND c.dateFetchEpoch > 0
        ORDER BY c.dateFetchEpoch DESC, c.number DESC
        LIMIT :limit
        """
    )
    fun observeRecentLibraryUpdates(limit: Int = 250): Flow<List<ChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chapter: ChapterEntity)

    @Query("UPDATE chapters SET isRead = :isRead, isNew = CASE WHEN :isRead = 1 THEN 0 ELSE isNew END WHERE mangaId = :mangaId AND ABS(number - :chapterNumber) < 0.001")
    suspend fun setRead(mangaId: String, chapterNumber: Double, isRead: Boolean)

    @Query("UPDATE chapters SET isBookmarked = :bookmarked WHERE mangaId = :mangaId AND ABS(number - :chapterNumber) < 0.001")
    suspend fun setBookmarked(mangaId: String, chapterNumber: Double, bookmarked: Boolean)

    @Query("UPDATE chapters SET isRead = 1, isNew = 0 WHERE mangaId = :mangaId AND number <= :chapterNumber")
    suspend fun markPreviousRead(mangaId: String, chapterNumber: Double)

    @Query("UPDATE chapters SET isRead = 1, isNew = 0 WHERE mangaId = :mangaId")
    suspend fun markAllReadForManga(mangaId: String)

    @Query("UPDATE chapters SET isRead = 1, isNew = 0 WHERE mangaId IN (:mangaIds)")
    suspend fun markAllReadForMangas(mangaIds: List<String>)

    @Query("UPDATE chapters SET lastPageRead = :page, totalPages = CASE WHEN :totalPages > 0 THEN :totalPages ELSE totalPages END WHERE mangaId = :mangaId AND ABS(number - :chapterNumber) < 0.001")
    suspend fun updatePageProgress(mangaId: String, chapterNumber: Double, page: Int, totalPages: Int)

    @Query("UPDATE chapters SET isRead = 1, isNew = 0, lastPageRead = :page, totalPages = CASE WHEN :totalPages > 0 THEN :totalPages ELSE totalPages END WHERE mangaId = :mangaId AND ABS(number - :chapterNumber) < 0.001")
    suspend fun markOpened(mangaId: String, chapterNumber: Double, page: Int, totalPages: Int)

    @Query("UPDATE chapters SET isDownloaded = :downloaded, localPath = :localPath WHERE id = :chapterId")
    suspend fun setDownloadState(chapterId: String, downloaded: Boolean, localPath: String)

    @Query("UPDATE chapters SET isDownloaded = 0, localPath = '' WHERE mangaId = :mangaId")
    suspend fun clearAllDownloadsForManga(mangaId: String)

    @Query("UPDATE chapters SET isNew = 0, dateFetchEpoch = 0 WHERE dateFetchEpoch > 0")
    suspend fun clearAllUpdateBadges()

    @Query("DELETE FROM chapters")
    suspend fun deleteAll()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllSnapshot(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultCategory(): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id = :categoryId")
    suspend fun deleteById(categoryId: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMangaToCategory(ref: MangaCategoryCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMangasToCategory(refs: List<MangaCategoryCrossRef>)

    @Query("DELETE FROM manga_category WHERE mangaId = :mangaId AND categoryId = :categoryId")
    suspend fun removeMangaFromCategory(mangaId: String, categoryId: Int)

    @Query("SELECT * FROM manga_category")
    suspend fun getAllCrossRefsSnapshot(): List<MangaCategoryCrossRef>

    @Query("SELECT categoryId FROM manga_category WHERE mangaId = :mangaId")
    suspend fun getCategoryIdsForManga(mangaId: String): List<Int>

    @Query("DELETE FROM manga_category")
    suspend fun deleteAllCrossRefs()

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_entries WHERE isRemoved = 0 ORDER BY lastReadEpoch DESC")
    fun observeActive(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history_entries")
    suspend fun getAllSnapshot(): List<HistoryEntity>

    @Query("SELECT * FROM history_entries WHERE mangaId = :mangaId LIMIT 1")
    suspend fun getByMangaId(mangaId: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: HistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<HistoryEntity>)

    @Query("UPDATE history_entries SET isRemoved = :removed WHERE mangaId = :mangaId")
    suspend fun setRemoved(mangaId: String, removed: Boolean)

    @Query("UPDATE history_entries SET timeReadMs = timeReadMs + :deltaMs WHERE mangaId = :mangaId")
    suspend fun addTimeRead(mangaId: String, deltaMs: Long)

    @Query("DELETE FROM history_entries")
    suspend fun clearAll()
}

@Dao
interface DailyReadingLogDao {
    @Query("SELECT * FROM daily_reading_logs ORDER BY dateIso ASC")
    fun observeAll(): Flow<List<DailyReadingLogEntity>>

    @Query("SELECT * FROM daily_reading_logs ORDER BY dateIso ASC")
    suspend fun getAllSnapshot(): List<DailyReadingLogEntity>

    @Query("SELECT * FROM daily_reading_logs WHERE dateIso = :dateIso LIMIT 1")
    suspend fun getByDate(dateIso: String): DailyReadingLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: DailyReadingLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(logs: List<DailyReadingLogEntity>)

    @Query("DELETE FROM daily_reading_logs")
    suspend fun deleteAll()
}

@Dao
interface MangaTrackDao {
    @Query("SELECT * FROM manga_tracks")
    fun observeAll(): Flow<List<MangaTrackEntity>>

    @Query("SELECT * FROM manga_tracks")
    suspend fun getAllSnapshot(): List<MangaTrackEntity>

    @Query("SELECT * FROM manga_tracks WHERE mangaId = :mangaId LIMIT 1")
    suspend fun getByMangaId(mangaId: String): MangaTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: MangaTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<MangaTrackEntity>)

    @Query("DELETE FROM manga_tracks WHERE mangaId = :mangaId")
    suspend fun deleteByMangaId(mangaId: String)

    @Query("DELETE FROM manga_tracks")
    suspend fun deleteAll()
}

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdEpoch DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status IN ('QUEUED', 'DOWNLOADING') ORDER BY createdEpoch ASC")
    suspend fun getPendingTasks(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<DownloadTaskEntity>)

    @Query("UPDATE download_tasks SET status = :status, speedText = :speedText, errorMessage = CASE WHEN :status = 'QUEUED' THEN NULL ELSE errorMessage END WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, speedText: String)

    @Query("UPDATE download_tasks SET progressPercent = :pct, bytesDownloaded = :bytes, totalBytes = :total, speedText = :speed, sizeText = :size, status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateProgress(
        id: String,
        pct: Int,
        bytes: Long,
        total: Long,
        speed: String,
        size: String,
        status: String,
        error: String?
    )

    @Query("UPDATE download_tasks SET status = 'PAUSED', speedText = :pausedLabel WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun pauseAllActive(pausedLabel: String)

    @Query("UPDATE download_tasks SET status = 'QUEUED', speedText = '', errorMessage = NULL WHERE status IN ('PAUSED', 'ERROR')")
    suspend fun resumeAllPaused()

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM download_tasks WHERE mangaId = :mangaId")
    suspend fun deleteByMangaId(mangaId: String)

    @Query("DELETE FROM download_tasks WHERE mangaId = :mangaId AND ABS(chapterNumber - :chapterNumber) < 0.001")
    suspend fun deleteByMangaAndChapter(mangaId: String, chapterNumber: Double)

    @Query("DELETE FROM download_tasks WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    @Query("DELETE FROM download_tasks")
    suspend fun deleteAll()
}

@Dao
interface ExtensionCacheDao {
    @Query("SELECT * FROM extension_cache ORDER BY installed DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ExtensionCacheEntity>>

    @Query("SELECT * FROM extension_cache")
    suspend fun getAllSnapshot(): List<ExtensionCacheEntity>

    @Query("SELECT * FROM extension_cache WHERE pkg = :pkg LIMIT 1")
    suspend fun getByPkg(pkg: String): ExtensionCacheEntity?

    @Query("SELECT * FROM extension_cache WHERE numericSourceId = :sourceId LIMIT 1")
    suspend fun getByNumericSourceId(sourceId: Long): ExtensionCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ExtensionCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ExtensionCacheEntity>)

    @Query("UPDATE extension_cache SET status = :status, lastCheckedEpoch = :checkedEpoch WHERE pkg = :pkg")
    suspend fun updateServerStatus(pkg: String, status: String, checkedEpoch: Long)

    @Query("UPDATE extension_cache SET installed = :installed, installedVersion = :installedVer, installedVersionCode = :installedCode, numericSourceId = :sourceId, fileSizeText = :fileSize WHERE pkg = :pkg")
    suspend fun updateInstallState(
        pkg: String,
        installed: Boolean,
        installedVer: String,
        installedCode: Int,
        sourceId: Long,
        fileSize: String
    )

    @Query("DELETE FROM extension_cache WHERE installed = 0 AND repoUrl NOT IN (:activeRepoUrls)")
    suspend fun pruneUninstalledNotInRepos(activeRepoUrls: List<String>)

    @Query("DELETE FROM extension_cache")
    suspend fun deleteAll()
}

@Dao
interface AnnouncementDao {
    @Query("SELECT * FROM announcements_cache ORDER BY orderIndex ASC")
    fun observeAll(): Flow<List<AnnouncementEntity>>

    @Query("SELECT * FROM announcements_cache ORDER BY orderIndex ASC")
    suspend fun getAllSnapshot(): List<AnnouncementEntity>

    @Transaction
    suspend fun replaceAll(items: List<AnnouncementEntity>) {
        deleteAll()
        upsertAll(items)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AnnouncementEntity>)

    @Query("DELETE FROM announcements_cache")
    suspend fun deleteAll()
}
