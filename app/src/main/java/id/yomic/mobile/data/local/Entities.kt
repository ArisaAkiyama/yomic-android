package id.yomic.mobile.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import id.yomic.mobile.model.AnnouncementItem
import id.yomic.mobile.model.CategoryItem
import id.yomic.mobile.model.ChapterItem
import id.yomic.mobile.model.DownloadQueueItem
import id.yomic.mobile.model.DownloadStatus
import id.yomic.mobile.model.ExtensionItem
import id.yomic.mobile.model.MangaItem
import id.yomic.mobile.model.MangaStatus
import id.yomic.mobile.model.MangaType
import id.yomic.mobile.model.ServerStatus
import id.yomic.mobile.model.SourceLang
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(
    tableName = "mangas",
    indices = [
        Index(value = ["numericSourceId", "url"], unique = true),
        Index(value = ["inLibrary"]),
        Index(value = ["lastViewedEpoch"]),
        Index(value = ["sourcePkg"])
    ]
)
data class MangaEntity(
    @PrimaryKey val id: String,
    val numericSourceId: Long = 0L,
    val sourcePkg: String = "",
    val sourceName: String = "",
    val lang: String = "ID",
    val url: String = "",
    val title: String = "",
    val altTitle: String = "",
    val thumbnailUrl: String = "",
    val author: String = "",
    val artist: String = "",
    val status: String = MangaStatus.ONGOING.name,
    val type: String = MangaType.MANHWA.name,
    val rating: Double = 0.0,
    val synopsis: String = "",
    val genresJson: String = "[]",
    val inLibrary: Boolean = false,
    val isExplicit: Boolean = false,
    val dateAddedEpoch: Long = 0L,
    val lastUpdateEpoch: Long = 0L,
    val lastViewedEpoch: Long = 0L,
    val initialized: Boolean = false,
    val responseETag: String? = null,
    val responseLastModified: String? = null
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["mangaId"]),
        Index(value = ["mangaId", "url"], unique = true),
        Index(value = ["dateFetchEpoch"]),
        Index(value = ["isNew"])
    ]
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val mangaId: String,
    val url: String = "",
    val title: String = "",
    val number: Double = 0.0,
    val releaseDate: String = "",
    val dateUploadEpoch: Long = 0L,
    val dateFetchEpoch: Long = 0L,
    val isRead: Boolean = false,
    val isBookmarked: Boolean = false,
    val isDownloaded: Boolean = false,
    val isNew: Boolean = false,
    val lastPageRead: Int = 1,
    val totalPages: Int = 0,
    val localPath: String = "",
    val scanlator: String = ""
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,
    val updateExcluded: Boolean = false
)

@Entity(
    tableName = "manga_category",
    primaryKeys = ["mangaId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["categoryId"])]
)
data class MangaCategoryCrossRef(
    val mangaId: String,
    val categoryId: Int
)

@Entity(
    tableName = "history_entries",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["lastReadEpoch"])]
)
data class HistoryEntity(
    @PrimaryKey val mangaId: String,
    val chapterId: String = "",
    val chapterNumber: Double = 1.0,
    val lastPageRead: Int = 1,
    val totalPages: Int = 0,
    val lastReadEpoch: Long = 0L,
    val timeReadMs: Long = 0L,
    val isRemoved: Boolean = false
)

@Entity(tableName = "daily_reading_logs")
data class DailyReadingLogEntity(
    @PrimaryKey val dateIso: String, // yyyy-MM-dd
    val chaptersRead: Int = 0,
    val secondsRead: Int = 0
)

@Entity(
    tableName = "manga_tracks",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MangaTrackEntity(
    @PrimaryKey val mangaId: String,
    val trackerName: String = "MyAnimeList",
    val remoteId: Long = 0L,
    val remoteTitle: String = "",
    val status: String = "reading",
    val score: Int = 0,
    val chaptersRead: Int = 0,
    val totalChapters: Int = 0
)

@Entity(
    tableName = "download_tasks",
    indices = [Index(value = ["mangaId"]), Index(value = ["status"]), Index(value = ["createdEpoch"])]
)
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val mangaId: String,
    val chapterId: String,
    val mangaTitle: String,
    val chapterLabel: String,
    val chapterNumber: Double,
    val sourcePkg: String,
    val sourceName: String,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedText: String = "",
    val sizeText: String = "",
    val status: String = DownloadStatus.QUEUED.name,
    val retryCount: Int = 0,
    val errorMessage: String? = null,
    val createdEpoch: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "extension_cache",
    indices = [Index(value = ["installed"]), Index(value = ["lang"])]
)
data class ExtensionCacheEntity(
    @PrimaryKey val pkg: String,
    val repoUrl: String = "",
    val name: String,
    val scriptFileName: String,
    val lang: String = "ID",
    val version: String = "1.0.0",
    val versionCode: Int = 1,
    val baseUrl: String = "",
    val iconUrl: String = "",
    val accentColorHex: Long = 0xFF2563EB,
    val isNsfw: Boolean = false,
    val isCloudflareProtected: Boolean = false,
    val installed: Boolean = false,
    val installedVersion: String = "",
    val installedVersionCode: Int = 0,
    val numericSourceId: Long = 0L,
    val fileSizeText: String = "",
    val status: String = ServerStatus.ONLINE.name,
    val lastCheckedEpoch: Long = 0L
)

@Entity(tableName = "announcements_cache")
data class AnnouncementEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val date: String,
    val type: String = "info",
    val url: String = "",
    val orderIndex: Int = 0
)

data class MangaWithRelations(
    @Embedded val manga: MangaEntity,
    @Relation(parentColumn = "id", entityColumn = "mangaId")
    val chapters: List<ChapterEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = MangaCategoryCrossRef::class,
            parentColumn = "mangaId",
            entityColumn = "categoryId"
        )
    )
    val categories: List<CategoryEntity>,
    @Relation(parentColumn = "id", entityColumn = "mangaId")
    val history: HistoryEntity?,
    @Relation(parentColumn = "id", entityColumn = "mangaId")
    val track: MangaTrackEntity?
)

object LocalEntityMappers {
    private val json = Json { ignoreUnknownKeys = true }

    private val coverPalettes: List<Triple<Long, Long, Long>> = listOf(
        Triple(0xFF172554L, 0xFF2563EBL, 0xFF60A5FAL),
        Triple(0xFF1E1B4BL, 0xFF6366F1L, 0xFFA5B4FCL),
        Triple(0xFF064E3BL, 0xFF10B981L, 0xFF6EE7B7L),
        Triple(0xFF4C0519L, 0xFFE11D48L, 0xFFFDA4AFL),
        Triple(0xFF451A03L, 0xFFD97706L, 0xFFFCD34DL),
        Triple(0xFF3B0764L, 0xFF9333EAL, 0xFFD8B4FEL),
        Triple(0xFF083344L, 0xFF0891B2L, 0xFF67E8F9L),
        Triple(0xFF1F2937L, 0xFF4B5563L, 0xFF9CA3AFL)
    )

    fun deriveCoverPalette(seed: String): Triple<Long, Long, Long> {
        var h = 2166136261L
        for (c in seed) {
            h = (h xor c.code.toLong()) * 16777619L
        }
        val idx = ((h and 0x7FFFFFFFL) % coverPalettes.size).toInt()
        return coverPalettes[idx]
    }

    fun encodeGenres(genres: List<String>): String =
        runCatching { json.encodeToString(genres) }.getOrDefault("[]")

    fun decodeGenres(raw: String): List<String> =
        if (raw.isBlank()) emptyList()
        else runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    fun formatRelativeTime(epochMs: Long, isEn: Boolean = false): String {
        if (epochMs <= 0L) return ""
        val diffMs = (System.currentTimeMillis() - epochMs).coerceAtLeast(0L)
        val mins = diffMs / 60_000L
        val hours = diffMs / 3_600_000L
        val days = diffMs / 86_400_000L
        return when {
            mins < 2L -> if (isEn) "Just now" else "Baru saja"
            mins < 60L -> if (isEn) "${mins}m ago" else "$mins mnt lalu"
            hours < 24L -> if (isEn) "${hours}h ago" else "$hours jam lalu"
            days == 1L -> if (isEn) "Yesterday" else "Kemarin"
            days < 7L -> if (isEn) "${days}d ago" else "$days hari lalu"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(epochMs))
        }
    }

    fun CategoryEntity.toModel(): CategoryItem = CategoryItem(
        id = id,
        name = name,
        sortOrder = sortOrder,
        isDefault = isDefault,
        updateExcluded = updateExcluded
    )

    fun ChapterEntity.toModel(): ChapterItem = ChapterItem(
        id = id,
        number = number,
        title = title,
        releaseDate = releaseDate.ifBlank {
            if (dateUploadEpoch > 0L) {
                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(dateUploadEpoch))
            } else ""
        },
        isRead = isRead,
        isBookmarked = isBookmarked,
        isDownloaded = isDownloaded,
        totalPages = totalPages,
        lastPageRead = lastPageRead,
        scanlator = scanlator,
        url = url,
        dateUploadEpoch = dateUploadEpoch,
        dateFetchEpoch = dateFetchEpoch,
        isNew = isNew,
        localPath = localPath
    )

    fun MangaWithRelations.toModel(isEn: Boolean = false): MangaItem {
        val sortedChapters = chapters.sortedByDescending { it.number }.map { it.toModel() }
        val lastEpoch = maxOf(manga.lastViewedEpoch, history?.takeIf { !it.isRemoved }?.lastReadEpoch ?: 0L)
        return MangaItem(
            id = manga.id,
            title = manga.title,
            altTitle = manga.altTitle,
            author = manga.author.ifBlank { "-" },
            artist = manga.artist,
            status = runCatching { MangaStatus.valueOf(manga.status) }.getOrDefault(MangaStatus.ONGOING),
            type = runCatching { MangaType.valueOf(manga.type) }.getOrDefault(MangaType.MANHWA),
            sourceId = manga.sourcePkg,
            sourceName = manga.sourceName,
            lang = runCatching { SourceLang.valueOf(manga.lang.uppercase()) }.getOrDefault(SourceLang.ID),
            rating = manga.rating,
            lastReadChapter = history?.chapterNumber ?: 1.0,
            lastReadPage = history?.lastPageRead ?: 1,
            lastReadTotalPages = (history?.totalPages ?: 0).coerceAtLeast(1),
            lastReadTimeAgo = formatRelativeTime(lastEpoch, isEn),
            lastViewedEpoch = if (history?.isRemoved == true) 0L else lastEpoch,
            lastUpdateEpoch = manga.lastUpdateEpoch,
            inLibrary = manga.inLibrary,
            isFavorite = manga.inLibrary,
            isExplicit = manga.isExplicit,
            categoryIds = categories.map { it.id },
            malTracked = track != null && track.remoteId > 0L,
            malId = track?.remoteId?.toInt() ?: 0,
            malTitle = track?.remoteTitle.orEmpty(),
            malStatus = track?.status?.ifBlank { "Reading" } ?: "Reading",
            malScore = track?.score ?: 0,
            malChaptersRead = track?.chaptersRead ?: 0,
            synopsis = manga.synopsis,
            genres = decodeGenres(manga.genresJson),
            coverPalette = deriveCoverPalette(manga.title.ifBlank { manga.id }),
            chapters = sortedChapters,
            url = manga.url,
            thumbnailUrl = manga.thumbnailUrl,
            numericSourceId = manga.numericSourceId,
            initialized = manga.initialized,
            responseETag = manga.responseETag,
            responseLastModified = manga.responseLastModified
        )
    }

    fun ExtensionCacheEntity.toModel(): ExtensionItem {
        val hasUpd = installed && versionCode > installedVersionCode && installedVersionCode > 0
        val displayVer = when {
            hasUpd && installedVersion.isNotBlank() -> "v${installedVersion.trimStart('v', 'V')} → v${version.trimStart('v', 'V')}"
            installed && installedVersion.isNotBlank() -> "v${installedVersion.trimStart('v', 'V')}"
            else -> "v${version.trimStart('v', 'V')}"
        }
        return ExtensionItem(
            id = pkg,
            name = name,
            version = displayVer,
            lang = runCatching { SourceLang.valueOf(lang.uppercase()) }.getOrDefault(SourceLang.ID),
            baseUrl = baseUrl,
            installed = installed,
            hasUpdate = hasUpd,
            isCloudflareProtected = isCloudflareProtected,
            accentColorHex = accentColorHex,
            isNsfw = isNsfw,
            status = runCatching { ServerStatus.valueOf(status) }.getOrDefault(ServerStatus.ONLINE),
            pkg = pkg,
            scriptFileName = scriptFileName,
            versionCode = versionCode,
            iconUrl = iconUrl,
            numericSourceId = numericSourceId,
            fileSizeText = fileSizeText,
            repoUrl = repoUrl
        )
    }

    fun DownloadTaskEntity.toModel(): DownloadQueueItem = DownloadQueueItem(
        id = id,
        mangaId = mangaId,
        mangaTitle = mangaTitle,
        chapterLabel = chapterLabel,
        chapterNumber = chapterNumber,
        sourceName = sourceName,
        progressPercent = progressPercent,
        speedText = speedText,
        sizeText = sizeText,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED),
        chapterId = chapterId,
        errorMessage = errorMessage
    )

    fun AnnouncementEntity.toModel(lastReadId: String): AnnouncementItem = AnnouncementItem(
        id = id,
        title = title,
        date = date,
        content = body,
        typeLabel = when (type.lowercase()) {
            "update" -> "Update"
            "warning" -> "Peringatan"
            "maintenance" -> "Maintenance"
            else -> "Info"
        },
        isUnread = id.isNotBlank() && !id.equals(lastReadId, ignoreCase = true),
        url = url
    )
}
