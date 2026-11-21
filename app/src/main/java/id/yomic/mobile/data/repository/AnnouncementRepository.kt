package id.yomic.mobile.data.repository

import id.yomic.mobile.data.local.AnnouncementDao
import id.yomic.mobile.data.local.AnnouncementEntity
import id.yomic.mobile.data.local.LocalEntityMappers.toModel
import id.yomic.mobile.data.preferences.SettingsRepository
import id.yomic.mobile.model.AnnouncementItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 1:1 Android port of Yomic Desktop's AnnouncementService.cs:
 * 1. Fetches GitHub Atom commit feed (zero REST API rate limit) to extract latest 40-char commit SHA.
 * 2. Fetches `raw.githubusercontent.com/{sha}/announcements.json` to bypass Fastly 5-minute CDN caching.
 * 3. Caches announcements in Room (`announcements_cache`) and tracks read status via `lastReadAnnouncementId`.
 */
class AnnouncementRepository(
    private val httpClientProvider: () -> OkHttpClient,
    private val announcementDao: AnnouncementDao?,
    private val settingsRepository: SettingsRepository?,
    private val commitsAtomUrl: String = DEFAULT_COMMITS_ATOM_URL,
    private val rawCommitTemplate: String = DEFAULT_RAW_COMMIT_TEMPLATE,
    private val rawFallbackUrl: String = DEFAULT_RAW_FALLBACK_URL
) {
    constructor(
        httpClient: OkHttpClient,
        announcementDao: AnnouncementDao?,
        settingsRepository: SettingsRepository?,
        commitsAtomUrl: String = DEFAULT_COMMITS_ATOM_URL,
        rawCommitTemplate: String = DEFAULT_RAW_COMMIT_TEMPLATE,
        rawFallbackUrl: String = DEFAULT_RAW_FALLBACK_URL
    ) : this(
        httpClientProvider = { httpClient },
        announcementDao = announcementDao,
        settingsRepository = settingsRepository,
        commitsAtomUrl = commitsAtomUrl,
        rawCommitTemplate = rawCommitTemplate,
        rawFallbackUrl = rawFallbackUrl
    )
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastFetchedSha: String? = null

    fun observeAnnouncements(): Flow<List<AnnouncementItem>> {
        val dao = announcementDao ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        val lastReadFlow = settingsRepository?.settingsFlow?.map { it.lastReadAnnouncementId }
            ?: kotlinx.coroutines.flow.flowOf("")
        return combine(dao.observeAll(), lastReadFlow) { entities, lastReadId ->
            mapWithReadState(entities, lastReadId)
        }
    }

    suspend fun refreshAnnouncements(force: Boolean = false): List<AnnouncementItem> = withContext(Dispatchers.IO) {
        try {
            val latestSha = fetchLatestCommitSha()
            val cached = announcementDao?.getAllSnapshot().orEmpty()
            val lastReadId = settingsRepository?.getCurrentSettings()?.lastReadAnnouncementId.orEmpty()

            if (!force && !latestSha.isNullOrBlank() && latestSha.equals(lastFetchedSha, ignoreCase = true) && cached.isNotEmpty()) {
                return@withContext mapWithReadState(cached, lastReadId)
            }

            val targetUrl = if (!latestSha.isNullOrBlank()) {
                lastFetchedSha = latestSha
                String.format(rawCommitTemplate, latestSha)
            } else {
                "$rawFallbackUrl?t=${System.currentTimeMillis()}"
            }

            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Yomic-Android-App")
                .header("Pragma", "no-cache")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .get()
                .build()

            val body = httpClientProvider().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext mapWithReadState(cached, lastReadId)
                resp.body?.string().orEmpty()
            }

            val parsed = parseAnnouncementsJson(body)
            if (parsed.isNotEmpty()) {
                announcementDao?.replaceAll(parsed)
                return@withContext mapWithReadState(parsed, lastReadId)
            }
            mapWithReadState(cached, lastReadId)
        } catch (_: Exception) {
            val cached = announcementDao?.getAllSnapshot().orEmpty()
            val lastReadId = runCatching { settingsRepository?.getCurrentSettings()?.lastReadAnnouncementId }.getOrNull().orEmpty()
            mapWithReadState(cached, lastReadId)
        }
    }

    suspend fun markAllRead() = withContext(Dispatchers.IO) {
        val topId = announcementDao?.getAllSnapshot()?.firstOrNull()?.id.orEmpty()
        if (topId.isNotBlank()) {
            settingsRepository?.setLastReadAnnouncementId(topId)
        }
    }

    internal fun fetchLatestCommitSha(): String? {
        return try {
            val sep = if (commitsAtomUrl.contains('?')) '&' else '?'
            val url = "${commitsAtomUrl}${sep}t=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Yomic-Android-App")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .get()
                .build()
            val xml = httpClientProvider().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty()
            }
            extractCommitSha(xml)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_COMMITS_ATOM_URL = "https://github.com/ArisaAkiyama/yomic/commits/master.atom"
        const val DEFAULT_RAW_COMMIT_TEMPLATE = "https://raw.githubusercontent.com/ArisaAkiyama/yomic/%s/announcements.json"
        const val DEFAULT_RAW_FALLBACK_URL = "https://raw.githubusercontent.com/ArisaAkiyama/yomic/master/announcements.json"

        private val COMMIT_UPPER_REGEX = Regex("""Commit/([a-f0-9]{40})""", RegexOption.IGNORE_CASE)
        private val COMMIT_LOWER_REGEX = Regex("""/commit/([a-f0-9]{40})""", RegexOption.IGNORE_CASE)

        fun extractCommitSha(atomXml: String): String? {
            if (atomXml.isBlank()) return null
            return COMMIT_UPPER_REGEX.find(atomXml)?.groupValues?.getOrNull(1)
                ?: COMMIT_LOWER_REGEX.find(atomXml)?.groupValues?.getOrNull(1)
        }

        fun parseAnnouncementsJson(rawJson: String): List<AnnouncementEntity> {
            if (rawJson.isBlank()) return emptyList()
            val parser = Json { ignoreUnknownKeys = true }
            return runCatching {
                val array = parser.parseToJsonElement(rawJson).jsonArray
                array.mapIndexedNotNull { idx, el ->
                    val obj = el.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (id.isBlank()) return@mapIndexedNotNull null
                    AnnouncementEntity(
                        id = id,
                        title = obj["title"]?.jsonPrimitive?.content.orEmpty(),
                        body = obj["body"]?.jsonPrimitive?.content.orEmpty(),
                        date = obj["date"]?.jsonPrimitive?.content.orEmpty(),
                        type = obj["type"]?.jsonPrimitive?.content ?: "info",
                        url = obj["url"]?.jsonPrimitive?.content.orEmpty(),
                        orderIndex = idx
                    )
                }
            }.getOrDefault(emptyList())
        }

        fun mapWithReadState(entities: List<AnnouncementEntity>, lastReadId: String): List<AnnouncementItem> {
            if (entities.isEmpty()) return emptyList()
            val readIndex = if (lastReadId.isBlank()) {
                -1
            } else {
                entities.indexOfFirst { it.id.equals(lastReadId, ignoreCase = true) }
            }
            return entities.mapIndexed { idx, entity ->
                val base = entity.toModel(lastReadId)
                val unread = when {
                    lastReadId.isBlank() -> idx == 0
                    readIndex == -1 -> true
                    else -> idx < readIndex
                }
                base.copy(isUnread = unread)
            }
        }
    }
}
