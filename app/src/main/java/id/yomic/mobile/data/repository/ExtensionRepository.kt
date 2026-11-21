package id.yomic.mobile.data.repository

import id.yomic.mobile.data.local.ExtensionCacheDao
import id.yomic.mobile.data.local.ExtensionCacheEntity
import id.yomic.mobile.data.local.LocalEntityMappers
import id.yomic.mobile.data.local.LocalEntityMappers.toModel
import id.yomic.mobile.data.network.YomicNetworkClient
import id.yomic.mobile.data.preferences.SettingsRepository
import id.yomic.mobile.engine.js.JsExtensionManager
import id.yomic.mobile.engine.js.JsExtensionSource
import id.yomic.mobile.engine.js.PagedJsMangaResult
import id.yomic.mobile.engine.js.RawJsManga
import id.yomic.mobile.model.ExtensionItem
import id.yomic.mobile.model.MangaItem
import id.yomic.mobile.model.MangaStatus
import id.yomic.mobile.model.MangaType
import id.yomic.mobile.model.ServerStatus
import id.yomic.mobile.model.SourceFeedSort
import id.yomic.mobile.model.SourceLang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

@Serializable
data class RepoSourceEntryDto(
    val name: String = "",
    val lang: String = "",
    val id: String = "",
    val baseUrl: String = ""
)

@Serializable
data class RepoExtensionEntryDto(
    val name: String = "",
    val pkg: String = "",
    val apk: String = "",
    val lang: String = "id",
    val code: Int = 0,
    val versionCode: Int = 0,
    val version: String = "1.0.0",
    val nsfw: Int = 0,
    val isNsfw: Boolean = false,
    @SerialName("icon") val iconUrl: String = "",
    val sources: List<RepoSourceEntryDto> = emptyList()
) {
    val effectiveVersionCode: Int
        get() = maxOf(versionCode, code, ExtensionRepository.calculateVersionCode(version))
}

data class ExtensionActionResult(
    val success: Boolean,
    val message: String,
    val installedSource: JsExtensionSource? = null
)

data class CatalogPageResult(
    val items: List<MangaItem>,
    val page: Int,
    val totalPages: Int,
    val hasMore: Boolean,
    val availableGenres: List<String>,
    val availableFormats: List<String>
)

/**
 * Dynamic Extension Repository with ZERO bundled `.js` scripts in the APK.
 * Fetches `index.min.json` and `.js` plugins online over OkHttp + DoH (with multi-CDN mirrors),
 * validates scripts in QuickJS, and persists metadata to Room `extension_cache`.
 */
class ExtensionRepository(
    private val extensionCacheDao: ExtensionCacheDao,
    private val extensionManager: JsExtensionManager,
    private val networkClient: YomicNetworkClient,
    private val settingsRepository: SettingsRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun observeExtensions(): Flow<List<ExtensionItem>> =
        extensionCacheDao.observeAll().map { entities -> entities.map { it.toModel() } }

    suspend fun initializeInstalledAndCacheIfEmpty() {
        extensionManager.loadAllInstalled()
        reconcileInstalledPluginsIntoCache()
        val cached = extensionCacheDao.getAllSnapshot()
        val settings = settingsRepository.getCurrentSettings()
        if (cached.isEmpty() && !settings.isOfflineMode && settings.extensionRepoUrls.isNotEmpty()) {
            refreshCatalogFromRepos(forceOnline = true)
        }
    }

    suspend fun refreshCatalogFromRepos(forceOnline: Boolean = true): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getCurrentSettings()
        if (settings.isOfflineMode) {
            reconcileInstalledPluginsIntoCache()
            return@withContext false to extensionCacheDao.getAllSnapshot().size
        }

        val repoUrls = settings.extensionRepoUrls
        if (repoUrls.isEmpty()) {
            reconcileInstalledPluginsIntoCache()
            return@withContext true to extensionCacheDao.getAllSnapshot().size
        }

        val installedMap = extensionManager.getAllSources().associateBy { it.pkg.lowercase() }
        val existingCache = extensionCacheDao.getAllSnapshot().associateBy { it.pkg.lowercase() }
        val merged = LinkedHashMap<String, ExtensionCacheEntity>()
        var anySucceeded = false

        for (repoUrl in repoUrls) {
            val fetchedEntries = fetchEntriesFromRepo(repoUrl)
            if (fetchedEntries != null) {
                anySucceeded = true
                for (dto in fetchedEntries) {
                    val entity = mapDtoToCacheEntity(
                        dto = dto,
                        repoUrl = repoUrl,
                        existing = existingCache[resolvePkg(dto)],
                        installedSrc = installedMap[resolvePkg(dto)]
                    ) ?: continue
                    val prev = merged[entity.pkg]
                    if (prev == null || entity.versionCode >= prev.versionCode) {
                        merged[entity.pkg] = entity
                    }
                }
            }
        }

        if (anySucceeded) {
            // Preserve installed entries even if repo temporarily omits them
            for ((pkg, prev) in existingCache) {
                if (prev.installed && !merged.containsKey(pkg)) {
                    merged[pkg] = prev
                }
            }
            extensionCacheDao.upsertAll(merged.values.toList())
            extensionCacheDao.pruneUninstalledNotInRepos(repoUrls)
        }

        reconcileInstalledPluginsIntoCache()
        anySucceeded to extensionCacheDao.getAllSnapshot().size
    }

    suspend fun validateAndAddRepoUrl(rawUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val clean = rawUrl.trim()
        if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            return@withContext false to "URL repositori harus diawali http:// atau https://"
        }
        val entries = fetchEntriesFromRepo(clean)
        if (entries.isNullOrEmpty()) {
            return@withContext false to "Gagal memuat katalog JSON valid dari URL tersebut."
        }
        settingsRepository.addExtensionRepoUrl(clean)
        refreshCatalogFromRepos(forceOnline = true)
        true to "Repositori ditambahkan (${entries.size} ekstensi ditemukan)."
    }

    suspend fun removeRepoUrl(repoUrl: String) = withContext(Dispatchers.IO) {
        settingsRepository.removeExtensionRepoUrl(repoUrl)
        val remaining = settingsRepository.getCurrentSettings().extensionRepoUrls
        if (remaining.isEmpty()) {
            extensionCacheDao.pruneUninstalledNotInRepos(listOf("__none__"))
        } else {
            extensionCacheDao.pruneUninstalledNotInRepos(remaining)
        }
    }

    suspend fun installOrUpdateExtension(
        pkgOrId: String,
        onProgress: (Int) -> Unit = {}
    ): ExtensionActionResult = withContext(Dispatchers.IO) {
        val normalizedKey = pkgOrId.trim().lowercase()
        val entry = extensionCacheDao.getByPkg(normalizedKey)
            ?: return@withContext ExtensionActionResult(false, "Ekstensi '$pkgOrId' tidak ditemukan di katalog.")

        val settings = settingsRepository.getCurrentSettings()
        if (settings.isOfflineMode) {
            return@withContext ExtensionActionResult(false, "Nonaktifkan Mode Offline untuk memasang ekstensi.")
        }

        onProgress(15)
        val scriptFileName = File(entry.scriptFileName.ifBlank { "${entry.pkg}.js" }).name
        val langFolder = if (entry.lang.equals("EN", ignoreCase = true)) "en" else "id"
        val candidateUrls = buildScriptDownloadUrls(entry.repoUrl, langFolder, scriptFileName)

        var jsContent: String? = null
        val stepSize = (50 / candidateUrls.size.coerceAtLeast(1)).coerceAtLeast(5)
        var pct = 20

        for (url in candidateUrls) {
            val text = downloadTextOrNull(url)
            if (!text.isNullOrBlank() && !text.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) &&
                !text.trimStart().startsWith("<html", ignoreCase = true)
            ) {
                jsContent = text
                break
            }
            pct = (pct + stepSize).coerceAtMost(68)
            onProgress(pct)
        }

        if (jsContent.isNullOrBlank()) {
            return@withContext ExtensionActionResult(false, "Gagal mengunduh skrip $scriptFileName dari repositori.")
        }

        onProgress(78)
        return@withContext try {
            val installedSource = extensionManager.installScript(entry.pkg, scriptFileName, jsContent)
            onProgress(92)
            val kb = jsContent.toByteArray(Charsets.UTF_8).size / 1024.0
            val fileSize = String.format(Locale.US, "%.1f KB", kb)
            val installedVer = installedSource.version.ifBlank { entry.version }
            val installedCode = maxOf(calculateVersionCode(installedVer), entry.versionCode)

            extensionCacheDao.upsert(
                entry.copy(
                    installed = true,
                    installedVersion = installedVer,
                    installedVersionCode = installedCode,
                    numericSourceId = installedSource.id,
                    baseUrl = installedSource.baseUrl.ifBlank { entry.baseUrl },
                    iconUrl = entry.iconUrl.ifBlank { installedSource.metadata.iconUrl },
                    isCloudflareProtected = installedSource.metadata.isCloudflareProtected || installedSource.metadata.requiresProxy,
                    fileSizeText = fileSize
                )
            )
            onProgress(100)
            ExtensionActionResult(
                success = true,
                message = "${entry.name} berhasil dipasang.",
                installedSource = installedSource
            )
        } catch (e: Exception) {
            ExtensionActionResult(false, "Skrip $scriptFileName tidak valid: ${e.message}")
        }
    }

    suspend fun uninstallExtension(pkgOrId: String): ExtensionActionResult = withContext(Dispatchers.IO) {
        val normalizedKey = pkgOrId.trim().lowercase()
        val entry = extensionCacheDao.getByPkg(normalizedKey)
        extensionManager.uninstallScript(normalizedKey, entry?.scriptFileName)
        if (entry != null) {
            extensionCacheDao.updateInstallState(
                pkg = entry.pkg,
                installed = false,
                installedVer = "",
                installedCode = 0,
                sourceId = 0L,
                fileSize = ""
            )
        }
        ExtensionActionResult(true, "${entry?.name ?: pkgOrId} berhasil dihapus.")
    }

    suspend fun fetchCatalogPage(
        sourcePkg: String,
        page: Int,
        query: String,
        sort: SourceFeedSort,
        status: MangaStatus?,
        type: MangaType?,
        genre: String?
    ): CatalogPageResult {
        val src = extensionManager.getSource(sourcePkg)
            ?: throw IllegalStateException("Ekstensi '$sourcePkg' belum terpasang.")

        val paged: PagedJsMangaResult = when {
            query.isNotBlank() -> src.getSearchManga(query.trim(), page)
            status != null || type != null || !genre.isNullOrBlank() -> {
                val statusInt = when (status) {
                    MangaStatus.ONGOING -> 1
                    MangaStatus.COMPLETED -> 2
                    MangaStatus.HIATUS -> 6
                    null -> 0
                }
                val genresArg = genre?.takeIf { it.isNotBlank() }?.let { listOf(it) }
                val formatsArg = type?.let { listOf(it.name.lowercase().replaceFirstChar(Char::uppercase)) }
                val sortArg = if (sort == SourceFeedSort.LATEST) "latest" else "popular"
                src.getMangaList(page, statusInt, genresArg, formatsArg, sortArg)
            }
            sort == SourceFeedSort.LATEST -> src.getLatestUpdates(page)
            else -> src.getPopularManga(page)
        }

        val extCache = extensionCacheDao.getByPkg(src.pkg)
        val langEnum = normalizeSourceLang(src.language)
        val items = paged.items.map { raw ->
            rawToMangaItem(raw = raw, source = src, lang = langEnum, explicitSource = extCache?.isNsfw == true || src.metadata.isNsfw)
        }

        return CatalogPageResult(
            items = items,
            page = page,
            totalPages = paged.totalPages,
            hasMore = paged.hasNextPage,
            availableGenres = src.genres,
            availableFormats = src.formats
        )
    }

    suspend fun searchSingleSource(sourcePkg: String, query: String): List<MangaItem> {
        val src = extensionManager.getSource(sourcePkg) ?: return emptyList()
        val res = src.getSearchManga(query.trim(), 1)
        val extCache = extensionCacheDao.getByPkg(src.pkg)
        val langEnum = normalizeSourceLang(src.language)
        return res.items.map { raw ->
            rawToMangaItem(raw = raw, source = src, lang = langEnum, explicitSource = extCache?.isNsfw == true || src.metadata.isNsfw)
        }
    }

    private suspend fun reconcileInstalledPluginsIntoCache() {
        val installedSources = extensionManager.getAllSources()
        val cachedMap = extensionCacheDao.getAllSnapshot().associateBy { it.pkg.lowercase() }
        val toUpsert = mutableListOf<ExtensionCacheEntity>()

        for (src in installedSources) {
            val key = src.pkg.lowercase()
            val prev = cachedMap[key]
            val verCode = calculateVersionCode(src.version)
            if (prev != null) {
                toUpsert.add(
                    prev.copy(
                        installed = true,
                        installedVersion = src.version,
                        installedVersionCode = maxOf(verCode, prev.installedVersionCode),
                        numericSourceId = src.id,
                        baseUrl = src.baseUrl.ifBlank { prev.baseUrl },
                        isCloudflareProtected = src.metadata.isCloudflareProtected || src.metadata.requiresProxy
                    )
                )
            } else {
                val cleanName = cleanExtensionName(src.name.ifBlank { src.pkg })
                toUpsert.add(
                    ExtensionCacheEntity(
                        pkg = key,
                        repoUrl = "local",
                        name = cleanName,
                        scriptFileName = src.scriptFileName,
                        lang = normalizeLangCode(src.language),
                        version = src.version,
                        versionCode = verCode,
                        baseUrl = src.baseUrl,
                        iconUrl = src.metadata.iconUrl,
                        accentColorHex = pickAccentColorHex(cleanName),
                        isNsfw = src.metadata.isNsfw || isAdultIndicator(cleanName, src.baseUrl),
                        isCloudflareProtected = src.metadata.isCloudflareProtected || src.metadata.requiresProxy,
                        installed = true,
                        installedVersion = src.version,
                        installedVersionCode = verCode,
                        numericSourceId = src.id,
                        status = ServerStatus.ONLINE.name
                    )
                )
            }
        }

        // Mark uninstalled if file is gone
        val installedKeys = installedSources.map { it.pkg.lowercase() }.toSet()
        for ((pkg, prev) in cachedMap) {
            if (prev.installed && pkg !in installedKeys) {
                toUpsert.add(prev.copy(installed = false, installedVersion = "", installedVersionCode = 0))
            }
        }

        if (toUpsert.isNotEmpty()) {
            extensionCacheDao.upsertAll(toUpsert)
        }
    }

    private fun fetchEntriesFromRepo(repoUrl: String): List<RepoExtensionEntryDto>? {
        val urls = expandIndexMirrorUrls(repoUrl)
        for (url in urls) {
            val body = downloadTextOrNull(url) ?: continue
            val parsed = runCatching { json.decodeFromString<List<RepoExtensionEntryDto>>(body) }.getOrNull()
            if (!parsed.isNullOrEmpty()) {
                return parsed
            }
        }
        return null
    }

    private fun downloadTextOrNull(url: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            networkClient.httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun mapDtoToCacheEntity(
        dto: RepoExtensionEntryDto,
        repoUrl: String,
        existing: ExtensionCacheEntity?,
        installedSrc: JsExtensionSource?
    ): ExtensionCacheEntity? {
        val pkg = resolvePkg(dto)
        if (pkg.isBlank()) return null
        val cleanName = cleanExtensionName(dto.name).ifBlank { pkg }
        val scriptFile = dto.apk.takeIf { it.isNotBlank() }?.let { File(it).name } ?: "$pkg.js"
        val baseUrl = installedSrc?.baseUrl?.takeIf { it.isNotBlank() }
            ?: dto.sources.firstOrNull()?.baseUrl.orEmpty()
        val isNsfw = dto.nsfw == 1 || dto.isNsfw || installedSrc?.metadata?.isNsfw == true ||
            isAdultIndicator(dto.name, cleanName, pkg, scriptFile, baseUrl)
        val iconUrl = resolveIconUrl(dto, baseUrl, installedSrc)
        val installed = installedSrc != null
        val installedVer = installedSrc?.version ?: existing?.installedVersion.orEmpty()
        val installedCode = installedSrc?.let { calculateVersionCode(it.version) } ?: existing?.installedVersionCode ?: 0

        return ExtensionCacheEntity(
            pkg = pkg,
            repoUrl = repoUrl,
            name = cleanName,
            scriptFileName = scriptFile,
            lang = normalizeLangCode(dto.lang),
            version = dto.version.ifBlank { "1.0.0" },
            versionCode = dto.effectiveVersionCode,
            baseUrl = baseUrl,
            iconUrl = iconUrl,
            accentColorHex = pickAccentColorHex(cleanName),
            isNsfw = isNsfw,
            isCloudflareProtected = installedSrc?.metadata?.isCloudflareProtected == true ||
                installedSrc?.metadata?.requiresProxy == true ||
                existing?.isCloudflareProtected == true,
            installed = installed,
            installedVersion = installedVer,
            installedVersionCode = installedCode,
            numericSourceId = installedSrc?.id ?: existing?.numericSourceId ?: 0L,
            fileSizeText = existing?.fileSizeText.orEmpty(),
            status = existing?.status ?: ServerStatus.ONLINE.name,
            lastCheckedEpoch = existing?.lastCheckedEpoch ?: 0L
        )
    }

    private fun resolvePkg(dto: RepoExtensionEntryDto): String {
        val raw = dto.pkg.trim().ifBlank {
            File(dto.apk).nameWithoutExtension.ifBlank { cleanExtensionName(dto.name).replace(" ", "") }
        }
        return raw.substringAfterLast('.').lowercase(Locale.US)
    }

    private fun resolveIconUrl(dto: RepoExtensionEntryDto, baseUrl: String, installed: JsExtensionSource?): String {
        if (dto.iconUrl.isNotBlank()) return dto.iconUrl
        if (!installed?.metadata?.iconUrl.isNullOrBlank()) return installed!!.metadata.iconUrl
        if (baseUrl.isNotBlank()) {
            val host = runCatching { URI(baseUrl).host }.getOrNull()
            if (!host.isNullOrBlank()) {
                return "https://www.google.com/s2/favicons?domain=$host&sz=128"
            }
        }
        return ""
    }

    companion object {
        private const val OFFICIAL_OWNER = "ArisaAkiyama"
        private const val OFFICIAL_REPO = "extension-yomic"

        private val accentPalette = longArrayOf(
            0xFF2563EBL, 0xFF059669L, 0xFF7C3AEDL, 0xFFDC2626L, 0xFFD97706L,
            0xFF0891B2L, 0xFF4F46E5L, 0xFFDB2777L, 0xFF0D9488L, 0xFFEA580CL
        )

        fun isOfficialYomicRepo(url: String): Boolean =
            url.contains("$OFFICIAL_OWNER/$OFFICIAL_REPO", ignoreCase = true)

        fun expandIndexMirrorUrls(repoUrl: String): List<String> {
            val cb = System.currentTimeMillis() / 1000L
            if (isOfficialYomicRepo(repoUrl)) {
                return listOf(
                    "https://cdn.jsdelivr.net/gh/$OFFICIAL_OWNER/$OFFICIAL_REPO@main/index.min.json?t=$cb",
                    "https://cdn.jsdelivr.net/gh/$OFFICIAL_OWNER/$OFFICIAL_REPO@repo/index.min.json?t=$cb",
                    "https://fastly.jsdelivr.net/gh/$OFFICIAL_OWNER/$OFFICIAL_REPO@main/index.min.json?t=$cb",
                    "https://raw.githubusercontent.com/$OFFICIAL_OWNER/$OFFICIAL_REPO/main/index.min.json?t=$cb",
                    "https://raw.githubusercontent.com/$OFFICIAL_OWNER/$OFFICIAL_REPO/repo/index.min.json?t=$cb"
                )
            }
            val sep = if (repoUrl.contains('?')) '&' else '?'
            return listOf("${repoUrl.trim()}${sep}t=$cb")
        }

        fun buildScriptDownloadUrls(repoUrl: String, langFolder: String, cleanFileName: String): List<String> {
            val cb = System.currentTimeMillis() / 1000L
            if (repoUrl.isBlank() || isOfficialYomicRepo(repoUrl)) {
                return listOf(
                    "https://cdn.jsdelivr.net/gh/$OFFICIAL_OWNER/$OFFICIAL_REPO@main/src/$langFolder/$cleanFileName?t=$cb",
                    "https://fastly.jsdelivr.net/gh/$OFFICIAL_OWNER/$OFFICIAL_REPO@main/src/$langFolder/$cleanFileName?t=$cb",
                    "https://raw.githubusercontent.com/$OFFICIAL_OWNER/$OFFICIAL_REPO/main/src/$langFolder/$cleanFileName?t=$cb",
                    "https://cdn.jsdelivr.net/gh/$OFFICIAL_OWNER/$OFFICIAL_REPO@main/$cleanFileName?t=$cb",
                    "https://raw.githubusercontent.com/$OFFICIAL_OWNER/$OFFICIAL_REPO/main/$cleanFileName?t=$cb"
                )
            }
            val base = repoUrl.substringBefore('?').substringBeforeLast('/').trimEnd('/')
            return listOf(
                "$base/src/$langFolder/$cleanFileName?t=$cb",
                "$base/$cleanFileName?t=$cb"
            )
        }

        fun cleanExtensionName(rawName: String): String {
            if (rawName.isBlank()) return ""
            return rawName
                .replace("Yomic:", "", ignoreCase = true)
                .replace(Regex("""\s*\([A-Za-z]+\)\s*$"""), "")
                .trim()
        }

        fun calculateVersionCode(version: String): Int {
            val clean = version.trimStart('v', 'V').trim()
            val parts = clean.split('.')
            if (parts.size >= 2) {
                val major = parts[0].toIntOrNull()
                val minor = parts[1].toIntOrNull()
                if (major != null && minor != null) {
                    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    return (major * 100) + (minor * 10) + patch
                }
            }
            return 1
        }

        fun normalizeLangCode(rawLang: String?): String {
            val up = rawLang?.trim()?.uppercase(Locale.US).orEmpty()
            return when (up) {
                "", "ID", "IN" -> "ID"
                "EN" -> "EN"
                else -> "ALL"
            }
        }

        fun normalizeSourceLang(rawLang: String?): SourceLang = when (normalizeLangCode(rawLang)) {
            "EN" -> SourceLang.EN
            "ALL" -> SourceLang.ALL
            else -> SourceLang.ID
        }

        fun pickAccentColorHex(key: String): Long {
            if (key.isEmpty()) return accentPalette[0]
            var hash = 2166136261L
            for (c in key) {
                hash = (hash xor c.code.toLong()) * 16777619L
            }
            val idx = ((hash and 0x7FFFFFFFL) % accentPalette.size).toInt()
            return accentPalette[idx]
        }

        fun isAdultIndicator(vararg candidates: String?): Boolean {
            val markers = listOf(
                "18+", "dewasa", "hentai", "doujin", "smut", "crot", "porn", "xxx",
                "ecchi", "eromanga", "nhentai", "nekopoi", "mangapoi", "kumapoi",
                "kumopoi", "mangasusu", "r-18", "r18", "mature", "adult", "guro", "yaoi", "yuri"
            )
            return candidates.any { candidate ->
                val low = candidate?.lowercase(Locale.US).orEmpty()
                low.isNotEmpty() && markers.any { low.contains(it) }
            }
        }

        fun generateMangaId(sourcePkg: String, mangaUrl: String): String {
            val digest = MessageDigest.getInstance("MD5")
                .digest("${sourcePkg.lowercase()}|$mangaUrl".toByteArray(Charsets.UTF_8))
            val hex = digest.take(8).joinToString("") { "%02x".format(it) }
            return "${sourcePkg.lowercase()}_$hex"
        }

        fun rawToMangaItem(
            raw: RawJsManga,
            source: JsExtensionSource,
            lang: SourceLang,
            explicitSource: Boolean
        ): MangaItem {
            val mappedStatus = when (raw.status) {
                2, 4 -> MangaStatus.COMPLETED
                6 -> MangaStatus.HIATUS
                else -> MangaStatus.ONGOING
            }
            val mappedType = when {
                raw.type.contains("manga", ignoreCase = true) -> MangaType.MANGA
                raw.type.contains("manhua", ignoreCase = true) -> MangaType.MANHUA
                else -> MangaType.MANHWA
            }
            return MangaItem(
                id = generateMangaId(source.pkg, raw.url),
                title = raw.title,
                author = raw.author.ifBlank { "-" },
                artist = raw.artist,
                status = mappedStatus,
                type = mappedType,
                sourceId = source.pkg,
                sourceName = source.name.ifBlank { source.pkg },
                lang = lang,
                rating = 0.0,
                inLibrary = false,
                isFavorite = false,
                isExplicit = explicitSource,
                synopsis = raw.description,
                genres = raw.genres,
                coverPalette = LocalEntityMappers.deriveCoverPalette(raw.title),
                chapters = emptyList(),
                url = raw.url,
                thumbnailUrl = raw.thumbnailUrl,
                numericSourceId = source.id,
                initialized = false
            )
        }
    }
}
