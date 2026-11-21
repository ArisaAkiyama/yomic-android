package id.yomic.mobile.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import id.yomic.mobile.data.local.CategoryDao
import id.yomic.mobile.data.local.CategoryEntity
import id.yomic.mobile.data.local.ChapterDao
import id.yomic.mobile.data.local.ChapterEntity
import id.yomic.mobile.data.local.DailyReadingLogDao
import id.yomic.mobile.data.local.DailyReadingLogEntity
import id.yomic.mobile.data.local.ExtensionCacheDao
import id.yomic.mobile.data.local.ExtensionCacheEntity
import id.yomic.mobile.data.local.HistoryDao
import id.yomic.mobile.data.local.HistoryEntity
import id.yomic.mobile.data.local.MangaCategoryCrossRef
import id.yomic.mobile.data.local.MangaDao
import id.yomic.mobile.data.local.MangaEntity
import id.yomic.mobile.data.local.MangaTrackDao
import id.yomic.mobile.data.local.MangaTrackEntity
import id.yomic.mobile.data.local.MangaWithRelations
import id.yomic.mobile.data.preferences.SettingsRepository
import id.yomic.mobile.model.AppLanguage
import id.yomic.mobile.model.AppSettingsState
import id.yomic.mobile.model.MangaStatus
import id.yomic.mobile.model.ReaderMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs

class YomicBackupServiceTest {

    @Test
    fun desktopSchemaContainsAllSixEfCoreTablesAndColumns() {
        val ddl = YomicBackupService.DESKTOP_SCHEMA_DDL.joinToString("\n")
        for (table in listOf("Mangas", "Chapters", "Categories", "MangaCategory", "History", "Tracks")) {
            assertTrue("Missing table $table", ddl.contains("CREATE TABLE IF NOT EXISTS $table"))
        }
        for (col in listOf("ResponseETag", "ResponseLastModified", "ChapterNumber", "LastPageRead", "TrackerName", "RemoteId", "UpdateExcluded")) {
            assertTrue("Missing column $col", ddl.contains(col))
        }
    }

    @Test
    fun sanitizesOAuthTokensInSettingsJsonAndRoundTripsPortableSettings() {
        val original = AppSettingsState(
            isDarkMode = false,
            pureBlackDarkMode = true,
            appLanguage = AppLanguage.EN,
            defaultReaderMode = ReaderMode.SINGLE,
            showNsfw = true,
            smartUpdate = false,
            saveDownloadsAsCbz = true,
            dohProvider = "Quad9 (9.9.9.9)",
            isMalConnected = true,
            malConnectedUser = "SecretUser",
            extensionRepoUrls = listOf("https://example.com/index.min.json")
        )

        val jsonText = YomicBackupService.buildSanitizedSettingsJson(original)
        assertTrue(jsonText.contains(""""MalAccessToken": """""))
        assertTrue(jsonText.contains(""""MalRefreshToken": """""))
        assertTrue(jsonText.contains(""""MalTokenExpiresAt": 0"""))
        assertFalse(jsonText.contains("SecretUser"))

        val restored = YomicBackupService.parsePortableSettingsJson(jsonText, AppSettingsState())
        assertEquals(false, restored.isDarkMode)
        assertEquals(true, restored.pureBlackDarkMode)
        assertEquals(AppLanguage.EN, restored.appLanguage)
        assertEquals(ReaderMode.SINGLE, restored.defaultReaderMode)
        assertEquals(true, restored.showNsfw)
        assertEquals(false, restored.smartUpdate)
        assertEquals(true, restored.saveDownloadsAsCbz)
        assertEquals("Quad9 (9.9.9.9)", restored.dohProvider)
        assertTrue(restored.extensionRepoUrls.contains("https://example.com/index.min.json"))
    }

    @Test
    fun exportsAndImportsYomicBackupZipWithRealSqlite3DatabaseAndReadingLogs() = runTest {
        val tempDir = Files.createTempDirectory("yomic_backup_sqlite_test").toFile()
        try {
            val codec = JdbcDesktopMangaDbCodec()

            // Source DAOs populated with sample library data
            val srcMangaDao = FakeBackupMangaDao()
            val srcChapterDao = FakeBackupChapterDao()
            val srcCatDao = FakeBackupCategoryDao()
            val srcHistoryDao = FakeBackupHistoryDao()
            val srcTrackDao = FakeBackupTrackDao()
            val srcExtDao = FakeBackupExtDao()
            val srcDailyLogDao = FakeBackupDailyLogDao().apply {
                items["2026-11-20"] = DailyReadingLogEntity("2026-11-20", chaptersRead = 4, secondsRead = 900)
                items["2026-11-21"] = DailyReadingLogEntity("2026-11-21", chaptersRead = 2, secondsRead = 300)
            }

            srcExtDao.items["id.bacakomik"] = ExtensionCacheEntity(
                pkg = "id.bacakomik",
                name = "BacaKomik",
                scriptFileName = "bacakomik.js",
                lang = "ID",
                numericSourceId = 778899L,
                installed = true
            )
            srcCatDao.cats[1] = CategoryEntity(id = 1, name = "Favorit", sortOrder = 0, isDefault = true)
            srcCatDao.cats[2] = CategoryEntity(id = 2, name = "Aksi", sortOrder = 1, isDefault = false)
            srcMangaDao.items["id.bacakomik_abc"] = MangaEntity(
                id = "id.bacakomik_abc",
                numericSourceId = 778899L,
                sourcePkg = "id.bacakomik",
                sourceName = "BacaKomik",
                lang = "ID",
                url = "https://bacakomik.my/komik/solo-leveling/",
                title = "Solo Leveling",
                author = "Chugong",
                status = MangaStatus.COMPLETED.name,
                genresJson = """["Action","Fantasy"]""",
                inLibrary = true,
                dateAddedEpoch = 1700000000000L,
                lastViewedEpoch = 1700005000000L,
                initialized = true,
                responseETag = "\"etag-1\""
            )
            srcChapterDao.items["ch_1"] = ChapterEntity(
                id = "ch_1",
                mangaId = "id.bacakomik_abc",
                url = "https://bacakomik.my/solo-leveling-ch-1/",
                title = "Chapter 1",
                number = 1.0,
                isRead = true,
                isBookmarked = true,
                lastPageRead = 14
            )
            srcCatDao.refs.add(MangaCategoryCrossRef("id.bacakomik_abc", 2))
            srcHistoryDao.items["id.bacakomik_abc"] = HistoryEntity(
                mangaId = "id.bacakomik_abc",
                chapterId = "ch_1",
                chapterNumber = 1.0,
                lastReadEpoch = 1700005000000L,
                timeReadMs = 240_000L
            )
            srcTrackDao.items["id.bacakomik_abc"] = MangaTrackEntity(
                mangaId = "id.bacakomik_abc",
                remoteId = 121496L,
                remoteTitle = "Solo Leveling",
                status = "Reading",
                score = 9,
                chaptersRead = 1,
                totalChapters = 200
            )

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val srcSettingsRepo = SettingsRepository(
                PreferenceDataStoreFactory.create(scope = scope) { File(tempDir, "src.preferences_pb") },
                defaultExtensionRepo = ""
            )
            srcSettingsRepo.saveSettings(AppSettingsState(appLanguage = AppLanguage.EN, saveDownloadsAsCbz = true))

            val exporter = YomicBackupService(
                context = null,
                cacheDir = tempDir,
                mangaDao = srcMangaDao,
                chapterDao = srcChapterDao,
                categoryDao = srcCatDao,
                historyDao = srcHistoryDao,
                trackDao = srcTrackDao,
                extensionCacheDao = srcExtDao,
                extensionManager = null,
                settingsRepository = srcSettingsRepo,
                dbCodec = codec,
                dailyReadingLogDao = srcDailyLogDao
            )

            val zipBytes = ByteArrayOutputStream()
            val exportRes = exporter.exportBackupToStream(zipBytes)
            assertTrue(exportRes.success)
            assertEquals(1, exportRes.mangaCount)
            assertEquals(1, exportRes.chapterCount)
            assertEquals(2, exportRes.categoryCount)

            // Confirm ZIP contains real SQLite 3 binary manga.db + settings.json + mobile_reading_logs.json
            val entryNames = mutableListOf<String>()
            var extractedDbBytes = ByteArray(0)
            ZipInputStream(ByteArrayInputStream(zipBytes.toByteArray())).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    entryNames.add(e.name)
                    if (e.name == "manga.db") {
                        extractedDbBytes = zis.readBytes()
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
            assertTrue("manga.db" in entryNames)
            assertTrue("settings.json" in entryNames)
            assertTrue(YomicBackupService.MOBILE_READING_LOGS_ENTRY in entryNames)
            val magicHeader = extractedDbBytes.copyOfRange(0, 16).toString(Charsets.US_ASCII)
            assertEquals("SQLite format 3\u0000", magicHeader)

            // Run real SQLite PRAGMA foreign_key_check against extracted manga.db
            val inspectedDb = File(tempDir, "inspected.db").apply { writeBytes(extractedDbBytes) }
            DriverManager.getConnection("jdbc:sqlite:${inspectedDb.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA foreign_key_check;").use { rs ->
                        assertFalse("Foreign key violation found in exported manga.db", rs.next())
                    }
                    st.executeQuery("SELECT COUNT(*) FROM Mangas").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }

            // Import into a destination DB that already has a higher reading log for 2026-11-21
            val dstMangaDao = FakeBackupMangaDao()
            val dstChapterDao = FakeBackupChapterDao()
            val dstCatDao = FakeBackupCategoryDao().apply {
                cats[1] = CategoryEntity(id = 1, name = "Favorit", sortOrder = 0, isDefault = true)
            }
            val dstHistoryDao = FakeBackupHistoryDao()
            val dstTrackDao = FakeBackupTrackDao()
            val dstDailyLogDao = FakeBackupDailyLogDao().apply {
                items["2026-11-21"] = DailyReadingLogEntity("2026-11-21", chaptersRead = 5, secondsRead = 120)
            }
            val dstSettingsRepo = SettingsRepository(
                PreferenceDataStoreFactory.create(scope = scope) { File(tempDir, "dst.preferences_pb") },
                defaultExtensionRepo = ""
            )

            val importer = YomicBackupService(
                context = null,
                cacheDir = tempDir,
                mangaDao = dstMangaDao,
                chapterDao = dstChapterDao,
                categoryDao = dstCatDao,
                historyDao = dstHistoryDao,
                trackDao = dstTrackDao,
                extensionCacheDao = srcExtDao,
                extensionManager = null,
                settingsRepository = dstSettingsRepo,
                dbCodec = codec,
                dailyReadingLogDao = dstDailyLogDao
            )

            val importRes = importer.importBackupFromStream(ByteArrayInputStream(zipBytes.toByteArray()))
            assertTrue(importRes.success)
            assertEquals(1, importRes.mangaCount)
            assertEquals(1, importRes.chapterCount)

            // Verify non-destructive max merge of mobile reading logs
            assertEquals(4, dstDailyLogDao.items["2026-11-20"]?.chaptersRead)
            assertEquals(900, dstDailyLogDao.items["2026-11-20"]?.secondsRead)
            assertEquals(5, dstDailyLogDao.items["2026-11-21"]?.chaptersRead)
            assertEquals(300, dstDailyLogDao.items["2026-11-21"]?.secondsRead)

            val restoredManga = dstMangaDao.items.values.first()
            assertEquals("Solo Leveling", restoredManga.title)
            assertEquals("id.bacakomik", restoredManga.sourcePkg)
            assertEquals(MangaStatus.COMPLETED.name, restoredManga.status)
            assertTrue(restoredManga.inLibrary)
            assertEquals("\"etag-1\"", restoredManga.responseETag)

            val restoredCh = dstChapterDao.items.values.first()
            assertTrue(restoredCh.isRead)
            assertTrue(restoredCh.isBookmarked)
            assertEquals(14, restoredCh.lastPageRead)

            assertTrue(dstCatDao.cats.values.any { it.name == "Aksi" })
            assertEquals(1, dstCatDao.refs.size)
            assertEquals(1, dstHistoryDao.items.size)
            assertEquals(121496L, dstTrackDao.items.values.first().remoteId)
            assertEquals("Reading", dstTrackDao.items.values.first().status)

            val restoredSettings = dstSettingsRepo.getCurrentSettings()
            assertEquals(AppLanguage.EN, restoredSettings.appLanguage)
            assertTrue(restoredSettings.saveDownloadsAsCbz)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun importsAuthenticWindowsDesktopBackupZipWithRealSqlite3Database() = runTest {
        val tempDir = Files.createTempDirectory("yomic_desktop_import_test").toFile()
        try {
            val codec = JdbcDesktopMangaDbCodec()
            val desktopDbFile = File(tempDir, "desktop_manga.db")

            // Simulate a real Yomic Windows Desktop backup (ONLY manga.db + Desktop settings.json, NO mobile logs)
            DriverManager.getConnection("jdbc:sqlite:${desktopDbFile.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("PRAGMA foreign_keys = ON;")
                    for (ddl in YomicBackupService.DESKTOP_SCHEMA_DDL) {
                        st.execute(ddl)
                    }
                    st.execute(
                        "INSERT INTO Categories (Id, Name, Color, SortOrder, IsDefault, UpdateExcluded) VALUES (1, 'Favorit', '#FFFFFF', 0, 1, 0)"
                    )
                    st.execute(
                        "INSERT INTO Categories (Id, Name, Color, SortOrder, IsDefault, UpdateExcluded) VALUES (2, 'Hiatus Watch', '#F59E0B', 1, 0, 1)"
                    )
                    st.execute(
                        """
                        INSERT INTO Mangas (Id, Source, Url, Title, Artist, Author, Description, Genre, Status, ThumbnailUrl, Favorite, LastUpdate, LastViewed, NextUpdate, Initialized, ViewerFlags, ChapterFlags, CoverLastModified, DateAdded, ResponseETag, ResponseLastModified)
                        VALUES (10, 551122, 'https://komiku.id/manga/vagabond/', 'Vagabond', 'Inoue Takehiko', 'Inoue Takehiko', 'Samurai epic.', 'Action,Historical,Manga', 6, 'https://cdn.komiku.id/vagabond.jpg', 1, 1700010000000, 1700020000000, 0, 1, 0, 0, 0, 1700000000000, '"w-etag"', 'Mon, 01 Jan 2026 00:00:00 GMT')
                        """.trimIndent()
                    )
                    st.execute(
                        """
                        INSERT INTO Chapters (Id, MangaId, Url, Name, ChapterNumber, DateUpload, DateFetch, Read, Bookmark, LastPageRead, IsDownloaded, IsNew, Scanlator)
                        VALUES (100, 10, 'https://komiku.id/vagabond-ch-327/', 'Chapter 327', 327.0, 1700009000000, 1700010000000, 1, 1, 22, 1, 0, 'Komiku')
                        """.trimIndent()
                    )
                    st.execute("INSERT INTO MangaCategory (MangaId, CategoryId) VALUES (10, 2)")
                    st.execute("INSERT INTO History (Id, ChapterId, MangaId, LastRead, TimeRead) VALUES (1, 100, 10, 1700020000000, 360000)")
                    st.execute(
                        "INSERT INTO Tracks (Id, MangaId, TrackerName, RemoteId, Title, LastChapterRead, TotalChapters, Status, Score) VALUES (1, 10, 'MyAnimeList', 656, 'Vagabond', 327, 327, 'on_hold', 10)"
                    )
                }
            }

            val zipOut = ByteArrayOutputStream()
            ZipOutputStream(zipOut).use { zos ->
                zos.putNextEntry(ZipEntry("manga.db"))
                desktopDbFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("settings.json"))
                zos.write(
                    """{"Theme":"Dark","AppLanguage":"id","SmartUpdate":true,"MalAccessToken":"SHOULD_BE_IGNORED"}""".toByteArray(Charsets.UTF_8)
                )
                zos.closeEntry()
            }

            val dstMangaDao = FakeBackupMangaDao()
            val dstChapterDao = FakeBackupChapterDao()
            val dstCatDao = FakeBackupCategoryDao().apply {
                cats[1] = CategoryEntity(id = 1, name = "Favorit", sortOrder = 0, isDefault = true)
            }
            val dstHistoryDao = FakeBackupHistoryDao()
            val dstTrackDao = FakeBackupTrackDao()
            val extDao = FakeBackupExtDao().apply {
                items["id.komiku"] = ExtensionCacheEntity(
                    pkg = "id.komiku",
                    name = "Komiku",
                    scriptFileName = "komiku.js",
                    lang = "ID",
                    numericSourceId = 551122L,
                    installed = true
                )
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val settingsRepo = SettingsRepository(
                PreferenceDataStoreFactory.create(scope = scope) { File(tempDir, "desktop_dst.preferences_pb") },
                defaultExtensionRepo = ""
            )

            val importer = YomicBackupService(
                context = null,
                cacheDir = tempDir,
                mangaDao = dstMangaDao,
                chapterDao = dstChapterDao,
                categoryDao = dstCatDao,
                historyDao = dstHistoryDao,
                trackDao = dstTrackDao,
                extensionCacheDao = extDao,
                extensionManager = null,
                settingsRepository = settingsRepo,
                dbCodec = codec
            )

            val res = importer.importBackupFromStream(ByteArrayInputStream(zipOut.toByteArray()))
            assertTrue(res.success)
            assertEquals(1, res.mangaCount)
            assertEquals(1, res.chapterCount)

            val m = dstMangaDao.items.values.first()
            assertEquals("Vagabond", m.title)
            assertEquals("id.komiku", m.sourcePkg)
            assertEquals(MangaStatus.HIATUS.name, m.status)
            assertEquals("\"w-etag\"", m.responseETag)

            val ch = dstChapterDao.items.values.first()
            assertEquals(327.0, ch.number, 0.001)
            assertFalse(ch.isDownloaded) // Device-specific files must NOT be marked downloaded on new device!
            assertTrue(ch.isRead)
            assertEquals(22, ch.lastPageRead)

            val track = dstTrackDao.items.values.first()
            assertEquals(656L, track.remoteId)
            assertEquals("On Hold", track.status)
            assertEquals(10, track.score)
            assertTrue(dstCatDao.cats.values.any { it.name == "Hiatus Watch" && it.updateExcluded })
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

/**
 * Genuine SQLite 3 JDBC codec executing real `YomicBackupService.DESKTOP_SCHEMA_DDL`
 * SQL statements, foreign key checks, and table queries on disk during JVM unit tests.
 */
private class JdbcDesktopMangaDbCodec : DesktopMangaDbCodec {

    override fun writeSnapshot(dbFile: File, snapshot: DesktopBackupSnapshot) {
        if (dbFile.exists()) dbFile.delete()
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=DELETE;")
                st.execute("PRAGMA foreign_keys=ON;")
                for (ddl in YomicBackupService.DESKTOP_SCHEMA_DDL) {
                    st.execute(ddl)
                }
            }

            conn.prepareStatement(
                "INSERT INTO Categories (Id, Name, Color, SortOrder, IsDefault, UpdateExcluded) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                for (c in snapshot.categories) {
                    ps.setLong(1, c.id)
                    ps.setString(2, c.name)
                    ps.setString(3, c.color)
                    ps.setInt(4, c.sortOrder)
                    ps.setInt(5, if (c.isDefault) 1 else 0)
                    ps.setInt(6, if (c.updateExcluded) 1 else 0)
                    ps.executeUpdate()
                }
            }

            conn.prepareStatement(
                """
                INSERT INTO Mangas (
                    Id, Source, Url, Title, Artist, Author, Description, Genre, Status, ThumbnailUrl,
                    Favorite, LastUpdate, LastViewed, NextUpdate, Initialized, ViewerFlags, ChapterFlags,
                    CoverLastModified, DateAdded, ResponseETag, ResponseLastModified
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                for (m in snapshot.mangas) {
                    ps.setLong(1, m.id)
                    ps.setLong(2, m.source)
                    ps.setString(3, m.url)
                    ps.setString(4, m.title)
                    ps.setString(5, m.artist)
                    ps.setString(6, m.author)
                    ps.setString(7, m.description)
                    ps.setString(8, m.genreCsv)
                    ps.setInt(9, m.status)
                    ps.setString(10, m.thumbnailUrl)
                    ps.setInt(11, if (m.favorite) 1 else 0)
                    ps.setLong(12, m.lastUpdate)
                    ps.setLong(13, m.lastViewed)
                    ps.setLong(14, m.nextUpdate)
                    ps.setInt(15, if (m.initialized) 1 else 0)
                    ps.setLong(16, m.viewerFlags)
                    ps.setLong(17, m.chapterFlags)
                    ps.setLong(18, m.coverLastModified)
                    ps.setLong(19, m.dateAdded)
                    ps.setString(20, m.responseETag)
                    ps.setString(21, m.responseLastModified)
                    ps.executeUpdate()
                }
            }

            conn.prepareStatement(
                """
                INSERT INTO Chapters (
                    Id, MangaId, Url, Name, ChapterNumber, DateUpload, DateFetch,
                    Read, Bookmark, LastPageRead, IsDownloaded, IsNew, Scanlator
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                for (ch in snapshot.chapters) {
                    ps.setLong(1, ch.id)
                    ps.setLong(2, ch.mangaId)
                    ps.setString(3, ch.url)
                    ps.setString(4, ch.name)
                    ps.setDouble(5, ch.chapterNumber)
                    ps.setLong(6, ch.dateUpload)
                    ps.setLong(7, ch.dateFetch)
                    ps.setInt(8, if (ch.read) 1 else 0)
                    ps.setInt(9, if (ch.bookmark) 1 else 0)
                    ps.setLong(10, ch.lastPageRead)
                    ps.setInt(11, if (ch.isDownloaded) 1 else 0)
                    ps.setInt(12, if (ch.isNew) 1 else 0)
                    ps.setString(13, ch.scanlator)
                    ps.executeUpdate()
                }
            }

            conn.prepareStatement("INSERT OR IGNORE INTO MangaCategory (MangaId, CategoryId) VALUES (?, ?)").use { ps ->
                for (ref in snapshot.mangaCategories) {
                    ps.setLong(1, ref.mangaId)
                    ps.setLong(2, ref.categoryId)
                    ps.executeUpdate()
                }
            }

            conn.prepareStatement(
                "INSERT INTO History (Id, ChapterId, MangaId, LastRead, TimeRead) VALUES (?, ?, ?, ?, ?)"
            ).use { ps ->
                for (h in snapshot.history) {
                    ps.setLong(1, h.id)
                    ps.setLong(2, h.chapterId)
                    ps.setLong(3, h.mangaId)
                    ps.setLong(4, h.lastRead)
                    ps.setLong(5, h.timeRead)
                    ps.executeUpdate()
                }
            }

            conn.prepareStatement(
                """
                INSERT INTO Tracks (
                    Id, MangaId, TrackerName, RemoteId, Title, LastChapterRead, TotalChapters, Status, Score
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                for (t in snapshot.tracks) {
                    ps.setLong(1, t.id)
                    ps.setLong(2, t.mangaId)
                    ps.setString(3, t.trackerName)
                    ps.setLong(4, t.remoteId)
                    ps.setString(5, t.title)
                    ps.setInt(6, t.lastChapterRead)
                    ps.setInt(7, t.totalChapters)
                    ps.setString(8, t.status)
                    ps.setInt(9, t.score)
                    ps.executeUpdate()
                }
            }

            conn.commit()
        }
    }

    override fun readSnapshot(dbFile: File): DesktopBackupSnapshot {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val categories = mutableListOf<DesktopCategoryRow>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT Id, Name, Color, SortOrder, IsDefault, UpdateExcluded FROM Categories").use { rs ->
                    while (rs.next()) {
                        categories.add(
                            DesktopCategoryRow(
                                id = rs.getLong("Id"),
                                name = rs.getString("Name").orEmpty(),
                                color = rs.getString("Color").orEmpty().ifBlank { "#FFFFFF" },
                                sortOrder = rs.getInt("SortOrder"),
                                isDefault = rs.getInt("IsDefault") != 0,
                                updateExcluded = rs.getInt("UpdateExcluded") != 0
                            )
                        )
                    }
                }
            }

            val mangas = mutableListOf<DesktopMangaRow>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM Mangas").use { rs ->
                    while (rs.next()) {
                        mangas.add(
                            DesktopMangaRow(
                                id = rs.getLong("Id"),
                                source = rs.getLong("Source"),
                                url = rs.getString("Url").orEmpty(),
                                title = rs.getString("Title").orEmpty(),
                                artist = rs.getString("Artist"),
                                author = rs.getString("Author"),
                                description = rs.getString("Description"),
                                genreCsv = rs.getString("Genre"),
                                status = rs.getInt("Status"),
                                thumbnailUrl = rs.getString("ThumbnailUrl"),
                                favorite = rs.getInt("Favorite") != 0,
                                lastUpdate = rs.getLong("LastUpdate"),
                                lastViewed = rs.getLong("LastViewed"),
                                nextUpdate = rs.getLong("NextUpdate"),
                                initialized = rs.getInt("Initialized") != 0,
                                viewerFlags = rs.getLong("ViewerFlags"),
                                chapterFlags = rs.getLong("ChapterFlags"),
                                coverLastModified = rs.getLong("CoverLastModified"),
                                dateAdded = rs.getLong("DateAdded"),
                                responseETag = rs.getString("ResponseETag"),
                                responseLastModified = rs.getString("ResponseLastModified")
                            )
                        )
                    }
                }
            }

            val chapters = mutableListOf<DesktopChapterRow>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM Chapters").use { rs ->
                    while (rs.next()) {
                        chapters.add(
                            DesktopChapterRow(
                                id = rs.getLong("Id"),
                                mangaId = rs.getLong("MangaId"),
                                url = rs.getString("Url").orEmpty(),
                                name = rs.getString("Name").orEmpty(),
                                chapterNumber = rs.getDouble("ChapterNumber"),
                                dateUpload = rs.getLong("DateUpload"),
                                dateFetch = rs.getLong("DateFetch"),
                                read = rs.getInt("Read") != 0,
                                bookmark = rs.getInt("Bookmark") != 0,
                                lastPageRead = rs.getLong("LastPageRead"),
                                isDownloaded = rs.getInt("IsDownloaded") != 0,
                                isNew = rs.getInt("IsNew") != 0,
                                scanlator = rs.getString("Scanlator")
                            )
                        )
                    }
                }
            }

            val refs = mutableListOf<DesktopMangaCategoryRow>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT MangaId, CategoryId FROM MangaCategory").use { rs ->
                    while (rs.next()) {
                        refs.add(DesktopMangaCategoryRow(rs.getLong("MangaId"), rs.getLong("CategoryId")))
                    }
                }
            }

            val history = mutableListOf<DesktopHistoryRow>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT Id, ChapterId, MangaId, LastRead, TimeRead FROM History").use { rs ->
                    while (rs.next()) {
                        history.add(
                            DesktopHistoryRow(
                                id = rs.getLong("Id"),
                                chapterId = rs.getLong("ChapterId"),
                                mangaId = rs.getLong("MangaId"),
                                lastRead = rs.getLong("LastRead"),
                                timeRead = rs.getLong("TimeRead")
                            )
                        )
                    }
                }
            }

            val tracks = mutableListOf<DesktopTrackRow>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM Tracks").use { rs ->
                    while (rs.next()) {
                        tracks.add(
                            DesktopTrackRow(
                                id = rs.getLong("Id"),
                                mangaId = rs.getLong("MangaId"),
                                trackerName = rs.getString("TrackerName").orEmpty(),
                                remoteId = rs.getLong("RemoteId"),
                                title = rs.getString("Title").orEmpty(),
                                lastChapterRead = rs.getInt("LastChapterRead"),
                                totalChapters = rs.getInt("TotalChapters"),
                                status = rs.getString("Status").orEmpty(),
                                score = rs.getInt("Score")
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
        }
    }
}

private class FakeBackupMangaDao : MangaDao {
    val items = linkedMapOf<String, MangaEntity>()
    override fun observeAllWithRelations(): Flow<List<MangaWithRelations>> = flowOf(emptyList())
    override fun observeLibraryWithRelations(): Flow<List<MangaWithRelations>> = flowOf(emptyList())
    override suspend fun getWithRelations(mangaId: String): MangaWithRelations? = null
    override suspend fun getById(mangaId: String): MangaEntity? = items[mangaId]
    override suspend fun getBySourceAndUrl(sourceId: Long, url: String): MangaEntity? =
        items.values.firstOrNull { it.numericSourceId == sourceId && it.url == url }
    override suspend fun getLibraryMangasSnapshot(): List<MangaEntity> = items.values.filter { it.inLibrary }
    override suspend fun getAllSnapshot(): List<MangaEntity> = items.values.toList()
    override suspend fun upsert(manga: MangaEntity) { items[manga.id] = manga }
    override suspend fun upsertAll(mangas: List<MangaEntity>) { mangas.forEach { items[it.id] = it } }
    override suspend fun update(manga: MangaEntity) { items[manga.id] = manga }
    override suspend fun setInLibrary(mangaId: String, inLibrary: Boolean, nowEpoch: Long) {}
    override suspend fun removeMultipleFromLibrary(mangaIds: List<String>) {}
    override suspend fun updateLastViewed(mangaId: String, epoch: Long) {}
    override suspend fun updateSyncMetadata(mangaId: String, epoch: Long, eTag: String?, lastModified: String?) {}
    override suspend fun deleteAll() { items.clear() }
}

private class FakeBackupChapterDao : ChapterDao {
    val items = linkedMapOf<String, ChapterEntity>()
    override fun observeChaptersForManga(mangaId: String): Flow<List<ChapterEntity>> = flowOf(emptyList())
    override suspend fun getChaptersForManga(mangaId: String): List<ChapterEntity> = items.values.filter { it.mangaId == mangaId }
    override suspend fun getById(chapterId: String): ChapterEntity? = items[chapterId]
    override suspend fun getByMangaAndNumber(mangaId: String, chapterNumber: Double): ChapterEntity? =
        items.values.firstOrNull { it.mangaId == mangaId && abs(it.number - chapterNumber) < 0.001 }
    override suspend fun getAllSnapshot(): List<ChapterEntity> = items.values.toList()
    override fun observeRecentLibraryUpdates(limit: Int): Flow<List<ChapterEntity>> = flowOf(emptyList())
    override suspend fun upsertAll(chapters: List<ChapterEntity>) { chapters.forEach { items[it.id] = it } }
    override suspend fun upsert(chapter: ChapterEntity) { items[chapter.id] = chapter }
    override suspend fun setRead(mangaId: String, chapterNumber: Double, isRead: Boolean) {}
    override suspend fun setBookmarked(mangaId: String, chapterNumber: Double, bookmarked: Boolean) {}
    override suspend fun markPreviousRead(mangaId: String, chapterNumber: Double) {}
    override suspend fun markAllReadForManga(mangaId: String) {}
    override suspend fun markAllReadForMangas(mangaIds: List<String>) {}
    override suspend fun updatePageProgress(mangaId: String, chapterNumber: Double, page: Int, totalPages: Int) {}
    override suspend fun markOpened(mangaId: String, chapterNumber: Double, page: Int, totalPages: Int) {}
    override suspend fun setDownloadState(chapterId: String, downloaded: Boolean, localPath: String) {}
    override suspend fun clearAllDownloadsForManga(mangaId: String) {}
    override suspend fun clearAllUpdateBadges() {}
    override suspend fun deleteAll() { items.clear() }
}

private class FakeBackupCategoryDao : CategoryDao {
    val cats = linkedMapOf<Int, CategoryEntity>()
    val refs = mutableListOf<MangaCategoryCrossRef>()
    private var nextId = 10
    override fun observeAll(): Flow<List<CategoryEntity>> = flowOf(cats.values.toList())
    override suspend fun getAllSnapshot(): List<CategoryEntity> = cats.values.toList()
    override suspend fun getDefaultCategory(): CategoryEntity? = cats.values.firstOrNull { it.isDefault }
    override suspend fun insert(category: CategoryEntity): Long {
        val id = if (category.id > 0) category.id else nextId++
        cats[id] = category.copy(id = id)
        return id.toLong()
    }
    override suspend fun upsertAll(categories: List<CategoryEntity>) { categories.forEach { cats[it.id] = it } }
    override suspend fun deleteById(categoryId: Int) { cats.remove(categoryId) }
    override suspend fun addMangaToCategory(ref: MangaCategoryCrossRef) { refs.add(ref) }
    override suspend fun addMangasToCategory(refs: List<MangaCategoryCrossRef>) { this.refs.addAll(refs) }
    override suspend fun removeMangaFromCategory(mangaId: String, categoryId: Int) {}
    override suspend fun getAllCrossRefsSnapshot(): List<MangaCategoryCrossRef> = refs.toList()
    override suspend fun getCategoryIdsForManga(mangaId: String): List<Int> = refs.filter { it.mangaId == mangaId }.map { it.categoryId }
    override suspend fun deleteAllCrossRefs() { refs.clear() }
    override suspend fun deleteAll() { cats.clear() }
}

private class FakeBackupHistoryDao : HistoryDao {
    val items = linkedMapOf<String, HistoryEntity>()
    override fun observeActive(): Flow<List<HistoryEntity>> = flowOf(items.values.toList())
    override suspend fun getAllSnapshot(): List<HistoryEntity> = items.values.toList()
    override suspend fun getByMangaId(mangaId: String): HistoryEntity? = items[mangaId]
    override suspend fun upsert(entry: HistoryEntity) { items[entry.mangaId] = entry }
    override suspend fun upsertAll(entries: List<HistoryEntity>) { entries.forEach { items[it.mangaId] = it } }
    override suspend fun setRemoved(mangaId: String, removed: Boolean) {}
    override suspend fun addTimeRead(mangaId: String, deltaMs: Long) {}
    override suspend fun clearAll() { items.clear() }
}

private class FakeBackupTrackDao : MangaTrackDao {
    val items = linkedMapOf<String, MangaTrackEntity>()
    override fun observeAll(): Flow<List<MangaTrackEntity>> = flowOf(items.values.toList())
    override suspend fun getAllSnapshot(): List<MangaTrackEntity> = items.values.toList()
    override suspend fun getByMangaId(mangaId: String): MangaTrackEntity? = items[mangaId]
    override suspend fun upsert(track: MangaTrackEntity) { items[track.mangaId] = track }
    override suspend fun upsertAll(tracks: List<MangaTrackEntity>) { tracks.forEach { items[it.mangaId] = it } }
    override suspend fun deleteByMangaId(mangaId: String) { items.remove(mangaId) }
    override suspend fun deleteAll() { items.clear() }
}

private class FakeBackupExtDao : ExtensionCacheDao {
    val items = linkedMapOf<String, ExtensionCacheEntity>()
    override fun observeAll(): Flow<List<ExtensionCacheEntity>> = flowOf(items.values.toList())
    override suspend fun getAllSnapshot(): List<ExtensionCacheEntity> = items.values.toList()
    override suspend fun getByPkg(pkg: String): ExtensionCacheEntity? = items[pkg]
    override suspend fun getByNumericSourceId(sourceId: Long): ExtensionCacheEntity? =
        items.values.firstOrNull { it.numericSourceId == sourceId }
    override suspend fun upsert(item: ExtensionCacheEntity) { items[item.pkg] = item }
    override suspend fun upsertAll(items: List<ExtensionCacheEntity>) { items.forEach { this.items[it.pkg] = it } }
    override suspend fun updateServerStatus(pkg: String, status: String, checkedEpoch: Long) {}
    override suspend fun updateInstallState(pkg: String, installed: Boolean, installedVer: String, installedCode: Int, sourceId: Long, fileSize: String) {}
    override suspend fun pruneUninstalledNotInRepos(activeRepoUrls: List<String>) {}
    override suspend fun deleteAll() { items.clear() }
}

private class FakeBackupDailyLogDao : DailyReadingLogDao {
    val items = linkedMapOf<String, DailyReadingLogEntity>()
    override fun observeAll(): Flow<List<DailyReadingLogEntity>> = flowOf(items.values.toList())
    override suspend fun getAllSnapshot(): List<DailyReadingLogEntity> = items.values.toList()
    override suspend fun getByDate(dateIso: String): DailyReadingLogEntity? = items[dateIso]
    override suspend fun upsert(log: DailyReadingLogEntity) { items[log.dateIso] = log }
    override suspend fun upsertAll(logs: List<DailyReadingLogEntity>) { logs.forEach { items[it.dateIso] = it } }
    override suspend fun deleteAll() { items.clear() }
}
