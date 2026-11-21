package id.yomic.mobile.viewmodel.controllers

import id.yomic.mobile.model.GlobalSearchSourceGroup
import id.yomic.mobile.model.MangaStatus
import id.yomic.mobile.model.MangaType
import id.yomic.mobile.model.SourceFeedSort
import id.yomic.mobile.model.SourceLang
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Owns Browse search, parallel Global Search, Extension Repositories,
 * Extension install/update/uninstall, Source pinning, and Source Catalog pagination/filters.
 */
internal class BrowseController(
    private val ctx: ControllerContext
) {
    private var catalogLoadJob: Job? = null
    private var catalogSearchDebounceJob: Job? = null
    private var globalSearchJob: Job? = null

    fun addExtensionRepoUrl(rawUrl: String) {
        val clean = rawUrl.trim()
        if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            ctx.showMessage(ctx.l("URL repositori harus diawali http:// atau https://", "Repository URL must start with http:// or https://"))
            return
        }
        val c = ctx.container
        if (c == null) {
            ctx.updateSettings(
                { s ->
                    if (s.extensionRepoUrls.any { it.equals(clean, ignoreCase = true) }) s
                    else s.copy(extensionRepoUrls = s.extensionRepoUrls + clean)
                },
                ctx.l("Repositori ekstensi ditambahkan.", "Extension repository added.")
            )
            return
        }
        ctx.scope.launch {
            ctx.state.update { it.copy(isRefreshingRepo = true) }
            val (_, msg) = c.extensionRepository.validateAndAddRepoUrl(clean)
            ctx.state.update { it.copy(isRefreshingRepo = false, snackbarMessage = msg) }
        }
    }

    fun removeExtensionRepoUrl(url: String) {
        val c = ctx.container
        if (c == null) {
            ctx.updateSettings(
                { s -> s.copy(extensionRepoUrls = s.extensionRepoUrls.filterNot { it.equals(url, ignoreCase = true) }) },
                ctx.l("Repositori ekstensi dihapus.", "Extension repository removed.")
            )
            return
        }
        ctx.scope.launch {
            c.extensionRepository.removeRepoUrl(url)
            ctx.showMessage(ctx.l("Repositori ekstensi dihapus.", "Extension repository removed."))
        }
    }

    fun resetExtensionReposToDefault() {
        val c = ctx.container ?: return
        ctx.scope.launch {
            ctx.state.update { it.copy(isRefreshingRepo = true) }
            c.settingsRepository.resetExtensionReposToDefault()
            c.extensionRepository.refreshCatalogFromRepos(forceOnline = true)
            ctx.state.update {
                it.copy(
                    isRefreshingRepo = false,
                    snackbarMessage = ctx.l("Repositori ekstensi dikembalikan ke bawaan.", "Extension repositories reset to default.")
                )
            }
        }
    }

    fun setBrowseSearch(query: String) {
        ctx.state.update { it.copy(browseSearchQuery = query) }
        val c = ctx.container ?: return
        globalSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2 || ctx.state.value.settings.isOfflineMode) {
            ctx.state.update { it.copy(globalSearchGroups = emptyList(), isGlobalSearching = false) }
            return
        }
        globalSearchJob = ctx.scope.launch {
            delay(450L)
            val state = ctx.state.value
            val targets = state.extensions
                .filter { it.installed }
                .filter { state.settings.showNsfw || !it.isNsfw }
                .filter { state.browseLangFilter == SourceLang.ALL || it.lang == state.browseLangFilter || it.lang == SourceLang.ALL }
                .sortedBy { it.name.lowercase() }

            if (targets.isEmpty()) {
                ctx.state.update { it.copy(globalSearchGroups = emptyList(), isGlobalSearching = false) }
                return@launch
            }

            ctx.state.update {
                it.copy(
                    isGlobalSearching = true,
                    globalSearchGroups = targets.map { src -> GlobalSearchSourceGroup(source = src, results = emptyList(), isLoading = true) }
                )
            }

            val sem = Semaphore(4)
            coroutineScope {
                targets.map { src ->
                    async {
                        sem.withPermit {
                            val result = runCatching { c.extensionRepository.searchSingleSource(src.id, trimmed) }
                            val libraryMap = ctx.state.value.mangaList.associateBy { it.id }
                            val items = result.getOrDefault(emptyList()).map { m ->
                                val existing = libraryMap[m.id]
                                if (existing != null) m.copy(inLibrary = existing.inLibrary, isFavorite = existing.isFavorite) else m
                            }
                            val errMsg = result.exceptionOrNull()?.message
                            ctx.state.update { st ->
                                st.copy(
                                    globalSearchGroups = st.globalSearchGroups.map { grp ->
                                        if (grp.source.id == src.id) {
                                            grp.copy(results = items, isLoading = false, error = errMsg)
                                        } else grp
                                    }
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
            ctx.state.update { it.copy(isGlobalSearching = false) }
        }
    }

    fun setBrowseLangFilter(lang: SourceLang) {
        ctx.state.update { it.copy(browseLangFilter = lang) }
        val q = ctx.state.value.browseSearchQuery
        if (ctx.container != null && q.trim().length >= 2) {
            setBrowseSearch(q)
        }
    }

    fun togglePinSource(sourceId: String) {
        val ext = ctx.state.value.extensions.find { it.id == sourceId }
        val wasPinned = sourceId in ctx.state.value.pinnedSourceIds
        ctx.state.update { state ->
            val next = if (wasPinned) state.pinnedSourceIds - sourceId else state.pinnedSourceIds + sourceId
            state.copy(
                pinnedSourceIds = next,
                snackbarMessage = ext?.let {
                    if (wasPinned) ctx.l("${it.name} dilepas dari sematan.", "${it.name} unpinned.")
                    else ctx.l("${it.name} disematkan ke atas.", "${it.name} pinned to top.")
                }
            )
        }
        ctx.container?.let { c ->
            ctx.scope.launch { c.settingsRepository.togglePinSource(sourceId) }
        }
    }

    fun setExtSearch(query: String) {
        ctx.state.update { it.copy(extSearchQuery = query) }
    }

    fun setExtLangFilter(lang: SourceLang) {
        ctx.state.update { it.copy(extLangFilter = lang) }
    }

    fun refreshExtensionRepo() {
        if (ctx.state.value.isRefreshingRepo) return
        val c = ctx.container
        if (c == null) {
            ctx.scope.launch {
                ctx.state.update { it.copy(isRefreshingRepo = true) }
                delay(900L)
                ctx.state.update {
                    it.copy(
                        isRefreshingRepo = false,
                        snackbarMessage = ctx.l("Repositori ekstensi diperbarui.", "Extension repository refreshed.")
                    )
                }
            }
            return
        }
        ctx.scope.launch {
            ctx.state.update { it.copy(isRefreshingRepo = true) }
            val (ok, count) = c.extensionRepository.refreshCatalogFromRepos(forceOnline = true)
            launch { c.sourceStatusService.checkAllInstalledSources() }
            ctx.state.update {
                it.copy(
                    isRefreshingRepo = false,
                    snackbarMessage = if (ok) {
                        ctx.l("Katalog diperbarui ($count ekstensi).", "Catalog refreshed ($count extensions).")
                    } else {
                        ctx.l("Gagal terhubung ke repositori ekstensi.", "Failed to reach extension repository.")
                    }
                )
            }
        }
    }

    fun installOrUpdateExtension(extId: String) {
        if (ctx.state.value.installingExtensions.containsKey(extId)) return
        val ext = ctx.state.value.extensions.find { it.id == extId } ?: return
        val c = ctx.container
        if (c == null) {
            ctx.scope.launch {
                for (pct in listOf(18, 42, 68, 91, 100)) {
                    ctx.state.update { it.copy(installingExtensions = it.installingExtensions + (extId to pct)) }
                    delay(180L)
                }
                ctx.state.update { state ->
                    state.copy(
                        installingExtensions = state.installingExtensions - extId,
                        extensions = state.extensions.map { item ->
                            if (item.id == extId) item.copy(installed = true, hasUpdate = false) else item
                        },
                        snackbarMessage = ctx.l("${ext.name} berhasil dipasang.", "${ext.name} installed successfully.")
                    )
                }
            }
            return
        }
        ctx.scope.launch {
            ctx.state.update { it.copy(installingExtensions = it.installingExtensions + (extId to 5)) }
            val result = c.extensionRepository.installOrUpdateExtension(extId) { pct ->
                ctx.state.update { it.copy(installingExtensions = it.installingExtensions + (extId to pct)) }
            }
            ctx.state.update {
                it.copy(
                    installingExtensions = it.installingExtensions - extId,
                    snackbarMessage = result.message
                )
            }
            if (result.success) {
                launch { c.sourceStatusService.checkAllInstalledSources() }
            }
        }
    }

    fun uninstallExtension(extId: String) {
        val ext = ctx.state.value.extensions.find { it.id == extId } ?: return
        val c = ctx.container
        if (c == null) {
            ctx.state.update { state ->
                state.copy(
                    extensions = state.extensions.map { item ->
                        if (item.id == extId) item.copy(installed = false, hasUpdate = false) else item
                    },
                    snackbarMessage = ctx.l("${ext.name} dihapus.", "${ext.name} uninstalled.")
                )
            }
            return
        }
        ctx.scope.launch {
            val res = c.extensionRepository.uninstallExtension(extId)
            ctx.showMessage(res.message)
        }
    }

    fun openSourceCatalog(sourceId: String) {
        ctx.state.update {
            it.copy(
                catalogSourceId = sourceId,
                catalogItems = emptyList(),
                catalogPage = 1,
                catalogHasMore = false,
                catalogError = null,
                catalogSearchQuery = "",
                catalogSort = SourceFeedSort.POPULAR,
                catalogStatusFilter = null,
                catalogTypeFilter = null,
                catalogGenreFilter = null
            )
        }
        loadCatalogPage(sourceId = sourceId, page = 1)
    }

    fun loadCatalogPage(sourceId: String = ctx.state.value.catalogSourceId, page: Int = 1) {
        val c = ctx.container ?: return
        if (sourceId.isBlank() || ctx.state.value.settings.isOfflineMode) return
        if (page == 1) {
            catalogLoadJob?.cancel()
        } else if (ctx.state.value.catalogIsLoading || ctx.state.value.catalogIsLoadingMore || !ctx.state.value.catalogHasMore) {
            return
        }
        catalogLoadJob = ctx.scope.launch {
            val snap = ctx.state.value
            ctx.state.update {
                it.copy(
                    catalogSourceId = sourceId,
                    catalogIsLoading = page == 1,
                    catalogIsLoadingMore = page > 1,
                    catalogError = null
                )
            }
            val result = runCatching {
                c.extensionRepository.fetchCatalogPage(
                    sourcePkg = sourceId,
                    page = page,
                    query = snap.catalogSearchQuery,
                    sort = snap.catalogSort,
                    status = snap.catalogStatusFilter,
                    type = snap.catalogTypeFilter,
                    genre = snap.catalogGenreFilter
                )
            }
            result.fold(
                onSuccess = { fetched ->
                    val existingById = ctx.state.value.mangaList.associateBy { it.id }
                    val hydrated = fetched.items.map { item ->
                        val ex = existingById[item.id]
                        if (ex != null) item.copy(inLibrary = ex.inLibrary, isFavorite = ex.isFavorite) else item
                    }
                    ctx.state.update { st ->
                        val combined = if (page == 1) {
                            hydrated
                        } else {
                            val seen = st.catalogItems.map { it.id }.toHashSet()
                            st.catalogItems + hydrated.filter { seen.add(it.id) }
                        }
                        st.copy(
                            catalogItems = combined,
                            catalogPage = fetched.page,
                            catalogHasMore = fetched.hasMore && hydrated.isNotEmpty(),
                            catalogIsLoading = false,
                            catalogIsLoadingMore = false,
                            catalogError = null,
                            catalogAvailableGenres = fetched.availableGenres,
                            catalogAvailableFormats = fetched.availableFormats
                        )
                    }
                },
                onFailure = { err ->
                    ctx.state.update {
                        it.copy(
                            catalogIsLoading = false,
                            catalogIsLoadingMore = false,
                            catalogError = err.message ?: ctx.l("Gagal memuat katalog.", "Failed to load catalog.")
                        )
                    }
                }
            )
        }
    }

    fun loadNextCatalogPage() {
        val st = ctx.state.value
        if (st.catalogHasMore && !st.catalogIsLoading && !st.catalogIsLoadingMore) {
            loadCatalogPage(st.catalogSourceId, st.catalogPage + 1)
        }
    }

    fun setCatalogSearch(query: String) {
        ctx.state.update { it.copy(catalogSearchQuery = query) }
        if (ctx.container != null && ctx.state.value.catalogSourceId.isNotBlank()) {
            catalogSearchDebounceJob?.cancel()
            catalogSearchDebounceJob = ctx.scope.launch {
                delay(450L)
                loadCatalogPage(ctx.state.value.catalogSourceId, page = 1)
            }
        }
    }

    fun setCatalogSort(sort: SourceFeedSort) {
        ctx.state.update { it.copy(catalogSort = sort) }
        if (ctx.container != null && ctx.state.value.catalogSourceId.isNotBlank()) {
            loadCatalogPage(ctx.state.value.catalogSourceId, page = 1)
        }
    }

    fun setCatalogStatusFilter(status: MangaStatus?) {
        ctx.state.update { it.copy(catalogStatusFilter = status) }
        if (ctx.container != null && ctx.state.value.catalogSourceId.isNotBlank()) {
            loadCatalogPage(ctx.state.value.catalogSourceId, page = 1)
        }
    }

    fun setCatalogTypeFilter(type: MangaType?) {
        ctx.state.update { it.copy(catalogTypeFilter = type) }
        if (ctx.container != null && ctx.state.value.catalogSourceId.isNotBlank()) {
            loadCatalogPage(ctx.state.value.catalogSourceId, page = 1)
        }
    }

    fun setCatalogGenreFilter(genre: String?) {
        ctx.state.update { it.copy(catalogGenreFilter = genre) }
        if (ctx.container != null && ctx.state.value.catalogSourceId.isNotBlank()) {
            loadCatalogPage(ctx.state.value.catalogSourceId, page = 1)
        }
    }

    fun switchMangaDexLanguage(lang: SourceLang) {
        ctx.updateSettings({ it.copy(mangaDexLanguage = lang) })
        ctx.container?.jsExtensionManager?.updateMangaDexLanguage(if (lang == SourceLang.ID) "id" else "en")
        if (ctx.container != null && ctx.state.value.catalogSourceId.contains("mangadex", ignoreCase = true)) {
            loadCatalogPage(ctx.state.value.catalogSourceId, page = 1)
        }
    }

    fun resetCatalogFilters() {
        ctx.state.update {
            it.copy(
                catalogSearchQuery = "",
                catalogSort = SourceFeedSort.POPULAR,
                catalogStatusFilter = null,
                catalogTypeFilter = null,
                catalogGenreFilter = null
            )
        }
        if (ctx.container != null && ctx.state.value.catalogSourceId.isNotBlank()) {
            loadCatalogPage(ctx.state.value.catalogSourceId, page = 1)
        }
    }
}
