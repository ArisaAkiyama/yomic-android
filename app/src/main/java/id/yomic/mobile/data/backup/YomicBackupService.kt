package id.yomic.mobile.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import id.yomic.mobile.data.local.CategoryDao
import id.yomic.mobile.data.local.CategoryEntity
import id.yomic.mobile.data.local.ChapterDao
import id.yomic.mobile.data.local.ChapterEntity
import id.yomic.mobile.data.local.DailyReadingLogDao
import id.yomic.mobile.data.local.DailyReadingLogEntity
import id.yomic.mobile.data.local.ExtensionCacheDao
import id.yomic.mobile.data.local.HistoryDao
import id.yomic.mobile.data.local.HistoryEntity
import id.yomic.mobile.data.local.LocalEntityMappers
import id.yomic.mobile.data.local.MangaCategoryCrossRef
import id.yomic.mobile.data.local.MangaDao
import id.yomic.mobile.data.local.MangaEntity
import id.yomic.mobile.data.local.MangaTrackDao
import id.yomic.mobile.data.local.MangaTrackEntity
import id.yomic.mobile.data.preferences.SettingsRepository
import id.yomic.mobile.data.repository.ExtensionRepository
import id.yomic.mobile.data.repository.LibraryRepository
import id.yomic.mobile.data.tracker.MalTrackerService
import id.yomic.mobile.engine.js.JsExtensionManager
import id.yomic.mobile.model.AppLanguage
import id.yomic.mobile.model.AppSettingsState
import id.yomic.mobile.model.MangaStatus
import id.yomic.mobile.model.MangaType
import id.yomic.mobile.model.ReaderMode
import id.yomic.mobile.model.ReadingDirection
import id.yomic.mobile.model.SourceLang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupResult(
    val success: Boolean,
    val mangaCount: Int = 0,
    val chapterCount: Int = 0,
    val categoryCount: Int = 0,
    val message: String = ""
)

data class DesktopMangaRow(
    val id: Long,
    val source: Long,
    val url: String,
    val title: String,
    val artist: String?,
    val author: String?,
    val description: String?,
    val genreCsv: String?,
    val status: Int,
    val thumbnailUrl: String?,
    val favorite: Boolean,
    val lastUpdate: Long,
    val lastViewed: Long,
    val nextUpdate: Long,
    val initialized: Boolean,
    val viewerFlags: Long,
    val chapterFlags: Long,
    val coverLastModified: Long,
    val dateAdded: Long,
    val responseETag: String?,
    val responseLastModified: String?
)

data class DesktopChapterRow(
    val id: Long,
    val mangaId: Long,
    val url: String,
    val name: String,
    val chapterNumber: Double,
    val dateUpload: Long,
    val dateFetch: Long,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val isDownloaded: Boolean,
    val isNew: Boolean,
    val scanlator: String?
)

data class DesktopCategoryRow(
    val id: Long,
    val name: String,
    val color: String,
    val sortOrder: Int,
    val isDefault: Boolean,
    val updateExcluded: Boolean
)

data class DesktopMangaCategoryRow(
    val mangaId: Long,
    val categoryId: Long
)

data class DesktopHistoryRow(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val lastRead: Long,
    val timeRead: Long
)

data class DesktopTrackRow(
    val id: Long,
    val mangaId: Long,
    val trackerName: String,
    val remoteId: Long,
    val title: String,
    val lastChapterRead: Int,
    val totalChapters: Int,
    val status: String,
    val score: Int
)

data class DesktopBackupSnapshot(
    val mangas: List<DesktopMangaRow> = emptyList(),
    val chapters: List<DesktopChapterRow> = emptyList(),
    val categories: List<DesktopCategoryRow> = emptyList(),
    val mangaCategories: List<DesktopMangaCategoryRow> = emptyList(),
    val history: List<DesktopHistoryRow> = emptyList(),
    val tracks: List<DesktopTrackRow> = emptyList()
)

interface DesktopMangaDbCodec {
    fun writeSnapshot(dbFile: File, snapshot: DesktopBackupSnapshot)
    fun readSnapshot(dbFile: File): DesktopBackupSnapshot
}

/**
 * Native Android SQLite implementation writing/reading EF Core Desktop-compatible `manga.db`.
 */
object AndroidDesktopMangaDbCodec : DesktopMangaDbCodec {

    override fun writeSnapshot(dbFile: File, snapshot: DesktopBackupSnapshot) {
        if (dbFile.exists()) dbFile.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            // Android's SQLiteDatabase forbids execSQL() for PRAGMAs that return a row.
            db.rawQuery("PRAGMA journal_mode=DELETE;", null).use { it.moveToFirst() }
            db.beginTransaction()
            try {
                for (ddl in YomicBackupService.DESKTOP_SCHEMA_DDL) {
                    db.execSQL(ddl)
                }

                for (cat in snapshot.categories) {
                    db.insertOrThrow("Categories", null, ContentValues().apply {
                        put("Id", cat.id)
                        put("Name", cat.name)
                        put("Color", cat.color)
                        put("SortOrder", cat.sortOrder)
                        put("IsDefault", if (cat.isDefault) 1 else 0)
                        put("UpdateExcluded", if (cat.updateExcluded) 1 else 0)
                    })
                }

                for (m in snapshot.mangas) {
                    db.insertOrThrow("Mangas", null, ContentValues().apply {
                        put("Id", m.id)
                        put("Source", m.source)
                        put("Url", m.url)
                        put("Title", m.title)
                        put("Artist", m.artist)
                        put("Author", m.author)
                        put("Description", m.description)
                        put("Genre", m.genreCsv)
                        put("Status", m.status)
                        put("ThumbnailUrl", m.thumbnailUrl)
                        put("Favorite", if (m.favorite) 1 else 0)
                        put("LastUpdate", m.lastUpdate)
                        put("LastViewed", m.lastViewed)
                        put("NextUpdate", m.nextUpdate)
                        put("Initialized", if (m.initialized) 1 else 0)
                        put("ViewerFlags", m.viewerFlags)
                        put("ChapterFlags", m.chapterFlags)
                        put("CoverLastModified", m.coverLastModified)
                        put("DateAdded", m.dateAdded)
                        put("ResponseETag", m.responseETag)
                        put("ResponseLastModified", m.responseLastModified)
                    })
                }

                for (ch in snapshot.chapters) {
                    db.insertOrThrow("Chapters", null, ContentValues().apply {
                        put("Id", ch.id)
                        put("MangaId", ch.mangaId)
                        put("Url", ch.url)
                        put("Name", ch.name)
                        put("ChapterNumber", ch.chapterNumber)
                        put("DateUpload", ch.dateUpload)
                        put("DateFetch", ch.dateFetch)
                        put("Read", if (ch.read) 1 else 0)
                        put("Bookmark", if (ch.bookmark) 1 else 0)
                        put("LastPageRead", ch.lastPageRead)
                        put("IsDownloaded", if (ch.isDownloaded) 1 else 0)
                        put("IsNew", if (ch.isNew) 1 else 0)
                        put("Scanlator", ch.scanlator)
                    })
                }

                for (ref in snapshot.mangaCategories) {
                    db.insertWithOnConflict("MangaCategory", null, ContentValues().apply {
                        put("MangaId", ref.mangaId)
                        put("CategoryId", ref.categoryId)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }

                for (h in snapshot.history) {
                    db.insertOrThrow("History", null, ContentValues().apply {
                        put("Id", h.id)
                        put("ChapterId", h.chapterId)
                        put("MangaId", h.mangaId)
                        put("LastRead", h.lastRead)
                        put("TimeRead", h.timeRead)
                    })
                }

                for (t in snapshot.tracks) {
                    db.insertOrThrow("Tracks", null, ContentValues().apply {
                        put("Id", t.id)
                        put("MangaId", t.mangaId)
                        put("TrackerName", t.trackerName)
                        put("RemoteId", t.remoteId)
                        put("Title", t.title)
                        put("LastChapterRead", t.lastChapterRead)
                        put("TotalChapters", t.totalChapters)
                        put("Status", t.status)
                        put("Score", t.score)
                    })
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    override fun readSnapshot(dbFile: File): DesktopBackupSnapshot {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val tables = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) tables.add(c.getString(0).lowercase(Locale.US))
            }

            val categories = mutableListOf<DesktopCategoryRow>()
            if ("categories" in tables) {
                db.rawQuery("SELECT * FROM Categories", null).use { c ->
                    while (c.moveToNext()) {
                        categories.add(
                            DesktopCategoryRow(
                                id = c.getLongOrDefault("Id", 0L),
                                name = c.getStringOrEmpty("Name"),
                                color = c.getStringOrEmpty("Color").ifBlank { "#FFFFFF" },
                                sortOrder = c.getIntOrDefault("SortOrder", 0),
                                isDefault = c.getIntOrDefault("IsDefault", 0) != 0,
                                updateExcluded = c.getIntOrDefault("UpdateExcluded", 0) != 0
                            )
                        )
                    }
                }
            }

            val mangas = mutableListOf<DesktopMangaRow>()
            if ("mangas" in tables) {
                db.rawQuery("SELECT * FROM Mangas", null).use { c ->
                    while (c.moveToNext()) {
                        mangas.add(
                            DesktopMangaRow(
                                id = c.getLongOrDefault("Id", 0L),
                                source = c.getLongOrDefault("Source", 0L),
                                url = c.getStringOrEmpty("Url"),
                                title = c.getStringOrEmpty("Title"),
                                artist = c.getStringOrNull("Artist"),
                                author = c.getStringOrNull("Author"),
                                description = c.getStringOrNull("Description"),
                                genreCsv = c.getStringOrNull("Genre"),
                                status = c.getIntOrDefault("Status", 1),
                                thumbnailUrl = c.getStringOrNull("ThumbnailUrl"),
                                favorite = c.getIntOrDefault("Favorite", 0) != 0,
                                lastUpdate = c.getLongOrDefault("LastUpdate", 0L),
                                lastViewed = c.getLongOrDefault("LastViewed", 0L),
                                nextUpdate = c.getLongOrDefault("NextUpdate", 0L),
                                initialized = c.getIntOrDefault("Initialized", 0) != 0,
                                viewerFlags = c.getLongOrDefault("ViewerFlags", 0L),
                                chapterFlags = c.getLongOrDefault("ChapterFlags", 0L),
                                coverLastModified = c.getLongOrDefault("CoverLastModified", 0L),
                                dateAdded = c.getLongOrDefault("DateAdded", 0L),
                                responseETag = c.getStringOrNull("ResponseETag"),
                                responseLastModified = c.getStringOrNull("ResponseLastModified")
                            )
                        )
                    }
                }
            }

            val chapters = mutableListOf<DesktopChapterRow>()
            if ("chapters" in tables) {
                db.rawQuery("SELECT * FROM Chapters", null).use { c ->
                    while (c.moveToNext()) {
                        chapters.add(
                            DesktopChapterRow(
                                id = c.getLongOrDefault("Id", 0L),
                                mangaId = c.getLongOrDefault("MangaId", 0L),
                                url = c.getStringOrEmpty("Url"),
                                name = c.getStringOrEmpty("Name"),
                                chapterNumber = c.getDoubleOrDefault("ChapterNumber", 0.0),
                                dateUpload = c.getLongOrDefault("DateUpload", 0L),
                                dateFetch = c.getLongOrDefault("DateFetch", 0L),
                                read = c.getIntOrDefault("Read", 0) != 0,
                                bookmark = c.getIntOrDefault("Bookmark", 0) != 0,
                                lastPageRead = c.getLongOrDefault("LastPageRead", 1L),
                                isDownloaded = c.getIntOrDefault("IsDownloaded", 0) != 0,
                                isNew = c.getIntOrDefault("IsNew", 0) != 0,
                                scanlator = c.getStringOrNull("Scanlator")
                            )
                        )
                    }
                }
            }

            val refs = mutableListOf<DesktopMangaCategoryRow>()
            if ("mangacategory" in tables) {
                db.rawQuery("SELECT * FROM MangaCategory", null).use { c ->
                    while (c.moveToNext()) {
                        refs.add(
                            DesktopMangaCategoryRow(
                                mangaId = c.getLongOrDefault("MangaId", 0L),
                                categoryId = c.getLongOrDefault("CategoryId", 0L)
                            )
                        )
                    }
                }
            }

            val history = mutableListOf<DesktopHistoryRow>()
            if ("history" in tables) {
                db.rawQuery("SELECT * FROM History", null).use { c ->
                    while (c.moveToNext()) {
                        history.add(
                            DesktopHistoryRow(
                                id = c.getLongOrDefault("Id", 0L),
                                chapterId = c.getLongOrDefault("ChapterId", 0L),
                                mangaId = c.getLongOrDefault("MangaId", 0L),
                                lastRead = c.getLongOrDefault("LastRead", 0L),
                                timeRead = c.getLongOrDefault("TimeRead", 0L)
                            )
                        )
                    }
                }
            }

            val tracks = mutableListOf<DesktopTrackRow>()
            if ("tracks" in tables) {
                db.rawQuery("SELECT * FROM Tracks", null).use { c ->
                    while (c.moveToNext()) {
                        tracks.add(
                            DesktopTrackRow(
                                id = c.getLongOrDefault("Id", 0L),
                                mangaId = c.getLongOrDefault("MangaId", 0L),
                                trackerName = c.getStringOrEmpty("TrackerName").ifBlank { "MyAnimeList" },
                                remoteId = c.getLongOrDefault("RemoteId", 0L),
                                title = c.getStringOrEmpty("Title"),
                                lastChapterRead = c.getIntOrDefault("LastChapterRead", 0),
                                totalChapters = c.getIntOrDefault("TotalChapters", 0),
                                status = c.getStringOrEmpty("Status").ifBlank { "reading" },
                                score = c.getIntOrDefault("Score", 0)
                            )
                        )
                    }
                }
            }

            return DesktopBackupSnapshot(
                mangas = mangas,
                chapters = chapters,
                categories = categories,
                mangaCategories = refs,
                history = history,
                tracks = tracks
            )
        } finally {
            db.close()
        }
    }

    private fun android.database.Cursor.col(name: String): Int = getColumnIndex(name)
    private fun android.database.Cursor.getStringOrEmpty(name: String): String {
        val i = col(name)
        return if (i >= 0 && !isNull(i)) getString(i) else ""
    }
    private fun android.database.Cursor.getStringOrNull(name: String): String? {
        val i = col(name)
        return if (i >= 0 && !isNull(i)) getString(i) else null
    }
    private fun android.database.Cursor.getLongOrDefault(name: String, default: Long): Long {
        val i = col(name)
        return if (i >= 0 && !isNull(i)) getLong(i) else default
    }
    private fun android.database.Cursor.getIntOrDefault(name: String, default: Int): Int {
        val i = col(name)
        return if (i >= 0 && !isNull(i)) getInt(i) else default
    }
    private fun android.database.Cursor.getDoubleOrDefault(name: String, default: Double): Double {
        val i = col(name)
        return if (i >= 0 && !isNull(i)) getDouble(i) else default
    }
}

/**
 * Bidirectional `.yomicbackup` Backup & Restore Service using Android SAF (zero storage permissions).
 * 100% compatible with Yomic Windows Desktop (`BackupService.cs` + EF Core `MangaDbContext.cs`):
 * - Exports ZIP containing Desktop-schema SQLite `manga.db` + sanitized `settings.json` (OAuth tokens stripped).
 * - Imports `.yomicbackup` archives created on either Yomic Windows Desktop or Yomic Android Mobile.
 */
class YomicBackupService(
    private val context: Context?,
    private val cacheDir: File,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val categoryDao: CategoryDao,
    private val historyDao: HistoryDao,
    private val trackDao: MangaTrackDao,
    private val extensionCacheDao: ExtensionCacheDao,
    private val extensionManager: JsExtensionManager?,
    private val settingsRepository: SettingsRepository,
    private val dbCodec: DesktopMangaDbCodec = AndroidDesktopMangaDbCodec,
    private val dailyReadingLogDao: DailyReadingLogDao? = null
) {

    suspend fun exportBackupToUri(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext BackupResult(false, message = "Context tidak tersedia.")
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                val res = exportBackupToStream(out)
                if (res.success) {
                    settingsRepository.setLastBackupEpoch(System.currentTimeMillis())
                }
                res
            } ?: BackupResult(false, message = "Gagal membuka lokasi penyimpanan cadangan.")
        } catch (e: Exception) {
            BackupResult(false, message = "Gagal mengekspor cadangan: ${e.message}")
        }
    }

    suspend fun importBackupFromUri(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext BackupResult(false, message = "Context tidak tersedia.")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                importBackupFromStream(input)
            } ?: BackupResult(false, message = "Gagal membaca berkas cadangan.")
        } catch (e: Exception) {
            BackupResult(false, message = "Gagal memulihkan cadangan: ${e.message}")
        }
    }

    suspend fun exportBackupToStream(outputStream: OutputStream): BackupResult = withContext(Dispatchers.IO) {
        val tempDb = File(cacheDir, "yomic_export_${System.currentTimeMillis()}.db")
        try {
            val mangas = mangaDao.getAllSnapshot()
            val chapters = chapterDao.getAllSnapshot()
            val categories = categoryDao.getAllSnapshot()
            val crossRefs = categoryDao.getAllCrossRefsSnapshot()
            val historyEntries = historyDao.getAllSnapshot().filter { !it.isRemoved }
            val tracks = trackDao.getAllSnapshot()
            val settings = settingsRepository.getCurrentSettings()

            val snapshot = buildDesktopSnapshot(
                mangas = mangas,
                chapters = chapters,
                categories = categories,
                crossRefs = crossRefs,
                historyEntries = historyEntries,
                tracks = tracks
            )

            tempDb.parentFile?.mkdirs()
            dbCodec.writeSnapshot(tempDb, snapshot)

            val sanitizedSettings = buildSanitizedSettingsJson(settings)
            val dailyLogs = dailyReadingLogDao?.getAllSnapshot().orEmpty()

            ZipOutputStream(outputStream.buffered()).use { zos ->
                zos.putNextEntry(ZipEntry("manga.db"))
                tempDb.inputStream().buffered().use { it.copyTo(zos) }
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("settings.json"))
                zos.write(sanitizedSettings.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                if (dailyLogs.isNotEmpty()) {
                    val logsJson = encodeDailyReadingLogsJson(dailyLogs)
                    zos.putNextEntry(ZipEntry(MOBILE_READING_LOGS_ENTRY))
                    zos.write(logsJson.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }

            BackupResult(
                success = true,
                mangaCount = snapshot.mangas.size,
                chapterCount = snapshot.chapters.size,
                categoryCount = snapshot.categories.size,
                message = "Cadangan diekspor (${snapshot.mangas.size} komik, ${snapshot.chapters.size} bab)."
            )
        } catch (e: Exception) {
            BackupResult(false, message = "Gagal membuat cadangan: ${e.message}")
        } finally {
            runCatching { tempDb.delete() }
        }
    }

    suspend fun importBackupFromStream(inputStream: InputStream): BackupResult = withContext(Dispatchers.IO) {
        val tempDb = File(cacheDir, "yomic_import_${System.currentTimeMillis()}.db")
        var settingsJsonRaw: String? = null
        var mobileReadingLogsRaw: String? = null
        var hasDb = false
        try {
            tempDb.parentFile?.mkdirs()
            ZipInputStream(inputStream.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase(Locale.US).substringAfterLast('/')
                    when (name) {
                        "manga.db" -> {
                            tempDb.outputStream().buffered().use { out -> zis.copyTo(out) }
                            hasDb = true
                        }
                        "settings.json" -> {
                            settingsJsonRaw = zis.readBytes().toString(Charsets.UTF_8)
                        }
                        MOBILE_READING_LOGS_ENTRY -> {
                            mobileReadingLogsRaw = zis.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (!hasDb && settingsJsonRaw == null) {
                return@withContext BackupResult(false, message = "Berkas .yomicbackup tidak valid (tidak ditemukan manga.db atau settings.json).")
            }

            var importedMangas = 0
            var importedChapters = 0
            var importedCategories = 0

            if (hasDb && tempDb.exists() && tempDb.length() > 0L) {
                val snapshot = dbCodec.readSnapshot(tempDb)
                val cachedExts = extensionCacheDao.getAllSnapshot()
                val extByNumericId = cachedExts
                    .filter { it.numericSourceId != 0L }
                    .associateBy { it.numericSourceId }

                // 1. Restore / merge Categories (map desktop categoryId -> mobile categoryId)
                val existingCats = categoryDao.getAllSnapshot()
                val existingCatByName = existingCats.associateBy { it.name.lowercase(Locale.US) }.toMutableMap()
                val catIdMap = mutableMapOf<Long, Int>()

                for (dCat in snapshot.categories) {
                    val existing = existingCatByName[dCat.name.lowercase(Locale.US)]
                    if (existing != null) {
                        catIdMap[dCat.id] = existing.id
                    } else {
                        val insertedId = categoryDao.insert(
                            CategoryEntity(
                                name = dCat.name.ifBlank { "Favorit" },
                                sortOrder = dCat.sortOrder,
                                isDefault = dCat.isDefault,
                                updateExcluded = dCat.updateExcluded
                            )
                        ).toInt()
                        catIdMap[dCat.id] = insertedId
                        existingCatByName[dCat.name.lowercase(Locale.US)] = CategoryEntity(
                            id = insertedId,
                            name = dCat.name,
                            sortOrder = dCat.sortOrder,
                            isDefault = dCat.isDefault,
                            updateExcluded = dCat.updateExcluded
                        )
                        importedCategories++
                    }
                }

                // 2. Restore Mangas (map desktop mangaId -> mobile string mangaId)
                val mangaIdMap = mutableMapOf<Long, String>()
                val mangaEntities = mutableListOf<MangaEntity>()

                for (dm in snapshot.mangas) {
                    val existingBySourceUrl = if (dm.source != 0L && dm.url.isNotBlank()) {
                        mangaDao.getBySourceAndUrl(dm.source, dm.url)
                    } else null

                    val liveSrc = extensionManager?.getSourceByNumericId(dm.source)
                    val cachedSrc = extByNumericId[dm.source]
                    val resolvedPkg = existingBySourceUrl?.sourcePkg?.takeIf { it.isNotBlank() }
                        ?: liveSrc?.pkg
                        ?: cachedSrc?.pkg
                        ?: "src_${dm.source}"
                    val resolvedName = existingBySourceUrl?.sourceName?.takeIf { it.isNotBlank() }
                        ?: liveSrc?.name
                        ?: cachedSrc?.name
                        ?: resolvedPkg
                    val resolvedLang = existingBySourceUrl?.lang?.takeIf { it.isNotBlank() }
                        ?: liveSrc?.language?.uppercase(Locale.US)
                        ?: cachedSrc?.lang
                        ?: "ID"

                    val mobileId = existingBySourceUrl?.id
                        ?: ExtensionRepository.generateMangaId(resolvedPkg, dm.url.ifBlank { dm.title })
                    mangaIdMap[dm.id] = mobileId

                    val genresList = dm.genreCsv
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()

                    mangaEntities.add(
                        MangaEntity(
                            id = mobileId,
                            numericSourceId = dm.source,
                            sourcePkg = resolvedPkg,
                            sourceName = resolvedName,
                            lang = resolvedLang,
                            url = dm.url,
                            title = dm.title,
                            altTitle = existingBySourceUrl?.altTitle.orEmpty(),
                            thumbnailUrl = dm.thumbnailUrl.orEmpty().ifBlank { existingBySourceUrl?.thumbnailUrl.orEmpty() },
                            author = dm.author.orEmpty().ifBlank { existingBySourceUrl?.author.orEmpty() },
                            artist = dm.artist.orEmpty().ifBlank { existingBySourceUrl?.artist.orEmpty() },
                            status = mapDesktopStatusToMobile(dm.status).name,
                            type = existingBySourceUrl?.type ?: inferTypeFromGenres(genresList).name,
                            rating = existingBySourceUrl?.rating ?: 0.0,
                            synopsis = dm.description.orEmpty().ifBlank { existingBySourceUrl?.synopsis.orEmpty() },
                            genresJson = LocalEntityMappers.encodeGenres(genresList),
                            inLibrary = dm.favorite || (existingBySourceUrl?.inLibrary == true),
                            isExplicit = existingBySourceUrl?.isExplicit ?: (cachedSrc?.isNsfw == true),
                            dateAddedEpoch = if (dm.dateAdded > 0L) dm.dateAdded else (existingBySourceUrl?.dateAddedEpoch ?: 0L),
                            lastUpdateEpoch = maxOf(dm.lastUpdate, existingBySourceUrl?.lastUpdateEpoch ?: 0L),
                            lastViewedEpoch = maxOf(dm.lastViewed, existingBySourceUrl?.lastViewedEpoch ?: 0L),
                            initialized = dm.initialized || (existingBySourceUrl?.initialized == true),
                            responseETag = dm.responseETag ?: existingBySourceUrl?.responseETag,
                            responseLastModified = dm.responseLastModified ?: existingBySourceUrl?.responseLastModified
                        )
                    )
                }
                if (mangaEntities.isNotEmpty()) {
                    mangaDao.upsertAll(mangaEntities)
                    importedMangas = mangaEntities.size
                }

                // 3. Restore Chapters (map desktop chapterId -> mobile ChapterEntity)
                val chapterIdMap = mutableMapOf<Long, Pair<String, Double>>()
                val chapterEntities = mutableListOf<ChapterEntity>()
                for (dc in snapshot.chapters) {
                    val mobileMangaId = mangaIdMap[dc.mangaId] ?: continue
                    val chId = LibraryRepository.generateChapterId(mobileMangaId, dc.url.ifBlank { "ch_${dc.chapterNumber}" })
                    val existingCh = chapterDao.getById(chId)
                    chapterIdMap[dc.id] = chId to dc.chapterNumber
                    chapterEntities.add(
                        ChapterEntity(
                            id = chId,
                            mangaId = mobileMangaId,
                            url = dc.url,
                            title = dc.name,
                            number = dc.chapterNumber,
                            releaseDate = existingCh?.releaseDate.orEmpty(),
                            dateUploadEpoch = dc.dateUpload,
                            dateFetchEpoch = dc.dateFetch,
                            isRead = dc.read || (existingCh?.isRead == true),
                            isBookmarked = dc.bookmark || (existingCh?.isBookmarked == true),
                            isDownloaded = existingCh?.isDownloaded == true, // Local files are device-specific
                            isNew = dc.isNew,
                            lastPageRead = maxOf(dc.lastPageRead.toInt().coerceAtLeast(1), existingCh?.lastPageRead ?: 1),
                            totalPages = existingCh?.totalPages ?: 0,
                            localPath = existingCh?.localPath.orEmpty(),
                            scanlator = dc.scanlator.orEmpty()
                        )
                    )
                }
                if (chapterEntities.isNotEmpty()) {
                    chapterDao.upsertAll(chapterEntities)
                    importedChapters = chapterEntities.size
                }

                // 4. Restore Manga <-> Category cross-refs
                val crossRefs = snapshot.mangaCategories.mapNotNull { ref ->
                    val mId = mangaIdMap[ref.mangaId] ?: return@mapNotNull null
                    val cId = catIdMap[ref.categoryId] ?: return@mapNotNull null
                    MangaCategoryCrossRef(mangaId = mId, categoryId = cId)
                }
                if (crossRefs.isNotEmpty()) {
                    categoryDao.addMangasToCategory(crossRefs)
                }

                // 5. Restore History (keep latest entry per manga)
                val latestHistoryByManga = snapshot.history
                    .groupBy { it.mangaId }
                    .mapNotNull { (dMangaId, entries) ->
                        val mobileMangaId = mangaIdMap[dMangaId] ?: return@mapNotNull null
                        val latest = entries.maxByOrNull { it.lastRead } ?: return@mapNotNull null
                        val chPair = chapterIdMap[latest.chapterId]
                        HistoryEntity(
                            mangaId = mobileMangaId,
                            chapterId = chPair?.first.orEmpty(),
                            chapterNumber = chPair?.second ?: 1.0,
                            lastPageRead = 1,
                            totalPages = 0,
                            lastReadEpoch = latest.lastRead,
                            timeReadMs = latest.timeRead,
                            isRemoved = false
                        )
                    }
                if (latestHistoryByManga.isNotEmpty()) {
                    historyDao.upsertAll(latestHistoryByManga)
                }

                // 6. Restore MAL Tracks
                val restoredTracks = snapshot.tracks.mapNotNull { dt ->
                    val mobileMangaId = mangaIdMap[dt.mangaId] ?: return@mapNotNull null
                    MangaTrackEntity(
                        mangaId = mobileMangaId,
                        trackerName = dt.trackerName.ifBlank { "MyAnimeList" },
                        remoteId = dt.remoteId,
                        remoteTitle = dt.title,
                        status = MalTrackerService.toDisplayStatus(dt.status),
                        score = dt.score.coerceIn(0, 10),
                        chaptersRead = dt.lastChapterRead.coerceAtLeast(0),
                        totalChapters = dt.totalChapters.coerceAtLeast(0)
                    )
                }
                if (restoredTracks.isNotEmpty()) {
                    trackDao.upsertAll(restoredTracks)
                }
            }

            // 7. Restore portable settings (never importing any OAuth token)
            if (!settingsJsonRaw.isNullOrBlank()) {
                val current = settingsRepository.getCurrentSettings()
                val merged = parsePortableSettingsJson(settingsJsonRaw!!, current)
                settingsRepository.saveSettings(merged)
            }

            // 8. Non-destructively merge optional mobile reading insights logs (14-day chart & streak)
            if (!mobileReadingLogsRaw.isNullOrBlank() && dailyReadingLogDao != null) {
                val incomingLogs = parseDailyReadingLogsJson(mobileReadingLogsRaw!!)
                if (incomingLogs.isNotEmpty()) {
                    val existingByDate = dailyReadingLogDao.getAllSnapshot().associateBy { it.dateIso }
                    val mergedLogs = incomingLogs.map { inc ->
                        val prev = existingByDate[inc.dateIso]
                        DailyReadingLogEntity(
                            dateIso = inc.dateIso,
                            chaptersRead = maxOf(inc.chaptersRead, prev?.chaptersRead ?: 0),
                            secondsRead = maxOf(inc.secondsRead, prev?.secondsRead ?: 0)
                        )
                    }
                    dailyReadingLogDao.upsertAll(mergedLogs)
                }
            }

            BackupResult(
                success = true,
                mangaCount = importedMangas,
                chapterCount = importedChapters,
                categoryCount = importedCategories,
                message = "Cadangan dipulihkan ($importedMangas komik, $importedChapters bab)."
            )
        } catch (e: Exception) {
            BackupResult(false, message = "Gagal memulihkan cadangan: ${e.message}")
        } finally {
            runCatching { tempDb.delete() }
        }
    }

    companion object {
        const val MOBILE_READING_LOGS_ENTRY = "mobile_reading_logs.json"

        private val prettyJson = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        fun encodeDailyReadingLogsJson(logs: List<DailyReadingLogEntity>): String {
            val arr = buildJsonArray {
                logs.forEach { log ->
                    add(
                        buildJsonObject {
                            put("dateIso", log.dateIso)
                            put("chaptersRead", log.chaptersRead)
                            put("secondsRead", log.secondsRead)
                        }
                    )
                }
            }
            return prettyJson.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), arr)
        }

        fun parseDailyReadingLogsJson(rawJson: String): List<DailyReadingLogEntity> {
            return runCatching {
                prettyJson.parseToJsonElement(rawJson).jsonArray.mapNotNull { el ->
                    val obj = el.jsonObject
                    val dateIso = obj["dateIso"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (dateIso.isBlank()) return@mapNotNull null
                    DailyReadingLogEntity(
                        dateIso = dateIso,
                        chaptersRead = (obj["chaptersRead"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0),
                        secondsRead = (obj["secondsRead"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
                    )
                }
            }.getOrDefault(emptyList())
        }

        val DESKTOP_SCHEMA_DDL: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS Mangas (
                Id INTEGER PRIMARY KEY AUTOINCREMENT,
                Source INTEGER NOT NULL,
                Url TEXT NOT NULL,
                Title TEXT NOT NULL,
                Artist TEXT,
                Author TEXT,
                Description TEXT,
                Genre TEXT,
                Status INTEGER NOT NULL,
                ThumbnailUrl TEXT,
                Favorite INTEGER NOT NULL,
                LastUpdate INTEGER NOT NULL,
                LastViewed INTEGER NOT NULL,
                NextUpdate INTEGER NOT NULL,
                Initialized INTEGER NOT NULL,
                ViewerFlags INTEGER NOT NULL,
                ChapterFlags INTEGER NOT NULL,
                CoverLastModified INTEGER NOT NULL,
                DateAdded INTEGER NOT NULL,
                ResponseETag TEXT,
                ResponseLastModified TEXT
            );
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS Chapters (
                Id INTEGER PRIMARY KEY AUTOINCREMENT,
                MangaId INTEGER NOT NULL,
                Url TEXT NOT NULL,
                Name TEXT NOT NULL,
                ChapterNumber REAL NOT NULL,
                DateUpload INTEGER NOT NULL,
                DateFetch INTEGER NOT NULL,
                Read INTEGER NOT NULL,
                Bookmark INTEGER NOT NULL,
                LastPageRead INTEGER NOT NULL,
                IsDownloaded INTEGER NOT NULL,
                IsNew INTEGER NOT NULL,
                Scanlator TEXT,
                FOREIGN KEY (MangaId) REFERENCES Mangas(Id) ON DELETE CASCADE
            );
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS Categories (
                Id INTEGER PRIMARY KEY AUTOINCREMENT,
                Name TEXT NOT NULL,
                Color TEXT NOT NULL,
                SortOrder INTEGER NOT NULL,
                IsDefault INTEGER NOT NULL,
                UpdateExcluded INTEGER NOT NULL
            );
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS MangaCategory (
                MangaId INTEGER NOT NULL,
                CategoryId INTEGER NOT NULL,
                PRIMARY KEY (MangaId, CategoryId),
                FOREIGN KEY (MangaId) REFERENCES Mangas(Id) ON DELETE CASCADE,
                FOREIGN KEY (CategoryId) REFERENCES Categories(Id) ON DELETE CASCADE
            );
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS History (
                Id INTEGER PRIMARY KEY AUTOINCREMENT,
                ChapterId INTEGER NOT NULL,
                MangaId INTEGER NOT NULL,
                LastRead INTEGER NOT NULL,
                TimeRead INTEGER NOT NULL,
                FOREIGN KEY (ChapterId) REFERENCES Chapters(Id) ON DELETE CASCADE
            );
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS Tracks (
                Id INTEGER PRIMARY KEY AUTOINCREMENT,
                MangaId INTEGER NOT NULL,
                TrackerName TEXT NOT NULL,
                RemoteId INTEGER NOT NULL,
                Title TEXT NOT NULL,
                LastChapterRead INTEGER NOT NULL,
                TotalChapters INTEGER NOT NULL,
                Status TEXT NOT NULL,
                Score INTEGER NOT NULL,
                FOREIGN KEY (MangaId) REFERENCES Mangas(Id) ON DELETE CASCADE
            );
            """.trimIndent()
        )

        fun defaultBackupFileName(nowMs: Long = System.currentTimeMillis()): String {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(nowMs))
            return "YomicBackup_$stamp.yomicbackup"
        }

        fun mapMobileStatusToDesktop(statusName: String): Int = when (statusName.uppercase(Locale.US)) {
            MangaStatus.COMPLETED.name -> 2
            MangaStatus.HIATUS.name -> 6
            else -> 1
        }

        fun mapDesktopStatusToMobile(code: Int): MangaStatus = when (code) {
            2, 4 -> MangaStatus.COMPLETED
            6 -> MangaStatus.HIATUS
            else -> MangaStatus.ONGOING
        }

        private fun inferTypeFromGenres(genres: List<String>): MangaType {
            val joined = genres.joinToString(" ").lowercase(Locale.US)
            return when {
                joined.contains("manga") -> MangaType.MANGA
                joined.contains("manhua") -> MangaType.MANHUA
                else -> MangaType.MANHWA
            }
        }

        fun buildDesktopSnapshot(
            mangas: List<MangaEntity>,
            chapters: List<ChapterEntity>,
            categories: List<CategoryEntity>,
            crossRefs: List<MangaCategoryCrossRef>,
            historyEntries: List<HistoryEntity>,
            tracks: List<MangaTrackEntity>
        ): DesktopBackupSnapshot {
            val mangaRowIdMap = mutableMapOf<String, Long>()
            val desktopMangas = mangas.mapIndexed { idx, m ->
                val dId = (idx + 1).toLong()
                mangaRowIdMap[m.id] = dId
                val genres = LocalEntityMappers.decodeGenres(m.genresJson)
                DesktopMangaRow(
                    id = dId,
                    source = m.numericSourceId,
                    url = m.url,
                    title = m.title,
                    artist = m.artist.ifBlank { null },
                    author = m.author.ifBlank { null },
                    description = m.synopsis.ifBlank { null },
                    genreCsv = genres.joinToString(","),
                    status = mapMobileStatusToDesktop(m.status),
                    thumbnailUrl = m.thumbnailUrl.ifBlank { null },
                    favorite = m.inLibrary,
                    lastUpdate = m.lastUpdateEpoch,
                    lastViewed = m.lastViewedEpoch,
                    nextUpdate = 0L,
                    initialized = m.initialized,
                    viewerFlags = 0L,
                    chapterFlags = 0L,
                    coverLastModified = 0L,
                    dateAdded = m.dateAddedEpoch,
                    responseETag = m.responseETag,
                    responseLastModified = m.responseLastModified
                )
            }

            val chapterRowIdMap = mutableMapOf<String, Long>()
            val desktopChapters = chapters.mapIndexedNotNull { idx, ch ->
                val dMangaId = mangaRowIdMap[ch.mangaId] ?: return@mapIndexedNotNull null
                val dChId = (idx + 1).toLong()
                chapterRowIdMap[ch.id] = dChId
                DesktopChapterRow(
                    id = dChId,
                    mangaId = dMangaId,
                    url = ch.url,
                    name = ch.title.ifBlank { "Chapter ${ch.number}" },
                    chapterNumber = ch.number,
                    dateUpload = ch.dateUploadEpoch,
                    dateFetch = ch.dateFetchEpoch,
                    read = ch.isRead,
                    bookmark = ch.isBookmarked,
                    lastPageRead = ch.lastPageRead.toLong(),
                    isDownloaded = ch.isDownloaded,
                    isNew = ch.isNew,
                    scanlator = ch.scanlator.ifBlank { null }
                )
            }

            val desktopCategories = categories.map { cat ->
                DesktopCategoryRow(
                    id = cat.id.toLong(),
                    name = cat.name,
                    color = "#FFFFFF",
                    sortOrder = cat.sortOrder,
                    isDefault = cat.isDefault,
                    updateExcluded = cat.updateExcluded
                )
            }

            val validCatIds = categories.map { it.id }.toSet()
            val desktopRefs = crossRefs.mapNotNull { ref ->
                val dMangaId = mangaRowIdMap[ref.mangaId] ?: return@mapNotNull null
                if (ref.categoryId !in validCatIds) return@mapNotNull null
                DesktopMangaCategoryRow(mangaId = dMangaId, categoryId = ref.categoryId.toLong())
            }

            val desktopHistory = historyEntries.mapIndexedNotNull { idx, h ->
                val dMangaId = mangaRowIdMap[h.mangaId] ?: return@mapIndexedNotNull null
                val dChId = chapterRowIdMap[h.chapterId] ?: return@mapIndexedNotNull null
                DesktopHistoryRow(
                    id = (idx + 1).toLong(),
                    chapterId = dChId,
                    mangaId = dMangaId,
                    lastRead = h.lastReadEpoch,
                    timeRead = h.timeReadMs
                )
            }

            val desktopTracks = tracks.mapIndexedNotNull { idx, t ->
                val dMangaId = mangaRowIdMap[t.mangaId] ?: return@mapIndexedNotNull null
                DesktopTrackRow(
                    id = (idx + 1).toLong(),
                    mangaId = dMangaId,
                    trackerName = t.trackerName,
                    remoteId = t.remoteId,
                    title = t.remoteTitle,
                    lastChapterRead = t.chaptersRead,
                    totalChapters = t.totalChapters,
                    status = MalTrackerService.toMalApiStatus(t.status),
                    score = t.score
                )
            }

            return DesktopBackupSnapshot(
                mangas = desktopMangas,
                chapters = desktopChapters,
                categories = desktopCategories,
                mangaCategories = desktopRefs,
                history = desktopHistory,
                tracks = desktopTracks
            )
        }

        /**
         * Produces Desktop-compatible `settings.json` with sensitive OAuth tokens stripped
         * (mirroring `BackupService.CreateSanitizedSettingsJson` in Yomic Desktop).
         */
        fun buildSanitizedSettingsJson(settings: AppSettingsState): String {
            val obj = buildJsonObject {
                put("Theme", if (settings.isDarkMode) "Dark" else "Light")
                put("PureBlackDarkMode", settings.pureBlackDarkMode)
                put("AppLanguage", settings.appLanguage.code)
                put("ReaderMode", settings.defaultReaderMode.name)
                put("ReadingDirection", settings.readingDirection.name)
                put("ShowNsfw", settings.showNsfw)
                put("BlurExplicitCovers", settings.blurExplicitCovers)
                put("SmartUpdate", settings.smartUpdate)
                put("AutoSyncStartup", settings.autoSyncStartup)
                put("BackgroundUpdateInterval", settings.backgroundUpdateInterval)
                put("AutoSyncTracker", settings.autoSyncTracker)
                put("DohProvider", settings.dohProvider)
                put("SaveDownloadsAsCbz", settings.saveDownloadsAsCbz)
                put("DownloadOnlyOnWifi", settings.downloadOnlyOnWifi)
                put("AutoDeleteAfterRead", settings.autoDeleteAfterRead)
                put("AutoDownloadNextChapter", settings.autoDownloadNextChapter)
                put("PreloadNextChapter", settings.preloadNextChapter)
                put("MangaDexLanguage", settings.mangaDexLanguage.name)
                put("ExtensionRepoUrls", buildJsonArray {
                    settings.extensionRepoUrls.forEach { add(JsonPrimitive(it)) }
                })
                // Explicitly strip OAuth credentials identical to Desktop BackupService.cs
                put("MalAccessToken", "")
                put("MalRefreshToken", "")
                put("MalTokenExpiresAt", 0)
            }
            return prettyJson.encodeToString(JsonObject.serializer(), obj)
        }

        /**
         * Applies safe portable preferences from a Desktop or Mobile `settings.json`,
         * ignoring any OAuth credential fields.
         */
        fun parsePortableSettingsJson(rawJson: String, base: AppSettingsState): AppSettingsState {
            return runCatching {
                val obj = prettyJson.parseToJsonElement(rawJson).jsonObject
                var next = base
                obj["Theme"]?.jsonPrimitive?.content?.let { theme ->
                    next = next.copy(isDarkMode = !theme.equals("Light", ignoreCase = true))
                }
                obj["PureBlackDarkMode"]?.jsonPrimitive?.booleanOrNull?.let { next = next.copy(pureBlackDarkMode = it) }
                obj["AppLanguage"]?.jsonPrimitive?.content?.let { code ->
                    next = next.copy(appLanguage = if (code.equals("en", ignoreCase = true)) AppLanguage.EN else AppLanguage.ID)
                }
                obj["ReaderMode"]?.jsonPrimitive?.content?.let { mode ->
                    runCatching { ReaderMode.valueOf(mode.uppercase(Locale.US)) }.getOrNull()?.let {
                        next = next.copy(defaultReaderMode = it)
                    }
                }
                obj["ReadingDirection"]?.jsonPrimitive?.content?.let { dir ->
                    runCatching { ReadingDirection.valueOf(dir.uppercase(Locale.US)) }.getOrNull()?.let {
                        next = next.copy(readingDirection = it)
                    }
                }
                obj["ShowNsfw"]?.jsonPrimitive?.booleanOrNull?.let { next = next.copy(showNsfw = it) }
                obj["BlurExplicitCovers"]?.jsonPrimitive?.booleanOrNull?.let { next = next.copy(blurExplicitCovers = it) }
                obj["SmartUpdate"]?.jsonPrimitive?.booleanOrNull?.let { next = next.copy(smartUpdate = it) }
                obj["AutoSyncStartup"]?.jsonPrimitive?.booleanOrNull?.let { next = next.copy(autoSyncStartup = it) }
                obj["BackgroundUpdateInterval"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                    next = next.copy(backgroundUpdateInterval = it)
                }
                obj["AutoSyncTracker"]?.jsonPrimitive?.booleanOrNull?.let { next = next.copy(autoSyncTracker = it) }
                obj["DohProvider"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                    next = next.copy(dohProvider = it)
                }
                obj["SaveDownloadsAsCbz"]?.jsonPrimitive?.booleanOrNull?.let { next = next.copy(saveDownloadsAsCbz = it) }
                obj["DownloadOnlyOnWifi"]?.jsonPrimitive?.booleanOrNull?.let { next = next.copy(downloadOnlyOnWifi = it) }
                obj["AutoDeleteAfterRead"]?.jsonPrimitive?.booleanOrNull?.let { next = next.copy(autoDeleteAfterRead = it) }
                obj["AutoDownloadNextChapter"]?.jsonPrimitive?.booleanOrNull?.let { next = next.copy(autoDownloadNextChapter = it) }
                obj["PreloadNextChapter"]?.jsonPrimitive?.booleanOrNull?.let { next = next.copy(preloadNextChapter = it) }
                obj["MangaDexLanguage"]?.jsonPrimitive?.content?.let { lang ->
                    runCatching { SourceLang.valueOf(lang.uppercase(Locale.US)) }.getOrNull()?.let {
                        if (it == SourceLang.ID || it == SourceLang.EN) next = next.copy(mangaDexLanguage = it)
                    }
                }
                obj["ExtensionRepoUrls"]?.jsonArray?.mapNotNull { el ->
                    el.jsonPrimitive.content.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                }?.takeIf { it.isNotEmpty() }?.let { repos ->
                    next = next.copy(extensionRepoUrls = (next.extensionRepoUrls + repos).distinct())
                }
                next
            }.getOrDefault(base)
        }
    }
}
