package id.yomic.mobile.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import id.yomic.mobile.data.DemoDataRepository
import id.yomic.mobile.data.preferences.MalAuthSnapshot
import id.yomic.mobile.data.repository.AttachedFeedbackScreenshot
import id.yomic.mobile.data.repository.FeedbackSubmitResult
import id.yomic.mobile.di.AppContainer
import id.yomic.mobile.model.ActivityUpdateItem
import id.yomic.mobile.model.AppSettingsState
import id.yomic.mobile.model.CategoryItem
import id.yomic.mobile.model.ChapterFilterMode
import id.yomic.mobile.model.ExtensionItem
import id.yomic.mobile.model.LibraryFilterMode
import id.yomic.mobile.model.LibrarySortBy
import id.yomic.mobile.model.MangaItem
import id.yomic.mobile.model.MangaStatus
import id.yomic.mobile.model.MangaType
import id.yomic.mobile.model.ReadingStatistics
import id.yomic.mobile.model.SourceFeedSort
import id.yomic.mobile.model.SourceLang
import id.yomic.mobile.model.SyncProgressState
import id.yomic.mobile.model.YomicUiState
import id.yomic.mobile.viewmodel.controllers.BrowseController
import id.yomic.mobile.viewmodel.controllers.ControllerContext
import id.yomic.mobile.viewmodel.controllers.DownloadController
import id.yomic.mobile.viewmodel.controllers.LibraryController
import id.yomic.mobile.viewmodel.controllers.MoreSettingsController
import id.yomic.mobile.viewmodel.controllers.ReaderHistoryController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Thin coordinator facade exposing reactive `uiState` and delegating all domain operations
 * to 5 focused feature controllers (`Library`, `Browse`, `ReaderHistory`, `Download`, `MoreSettings`).
 * Retains full backward compatibility with both live `AppContainer` and `container == null` unit tests.
 */
class YomicViewModel(
    autoStartTicker: Boolean,
    private val container: AppContainer? = null
) : ViewModel() {

    constructor() : this(autoStartTicker = true, container = null)
    constructor(autoStartTicker: Boolean) : this(autoStartTicker = autoStartTicker, container = null)

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<YomicUiState> = _uiState.asStateFlow()

    private val ctx = ControllerContext(
        scope = viewModelScope,
        state = _uiState,
        container = container
    )

    private val library = LibraryController(ctx)
    private val browse = BrowseController(ctx)
    private val downloads = DownloadController(ctx)
    private val readerHistory = ReaderHistoryController(ctx, downloads)
    private val moreSettings = MoreSettingsController(ctx, ::createInitialState)

    init {
        if (container != null) {
            observeCorePersistence(container)
            moreSettings.refreshCacheSizeLabel()
        } else if (autoStartTicker) {
            downloads.startDemoTicker()
        }
    }

    private fun observeCorePersistence(c: AppContainer) {
        viewModelScope.launch {
            combine(
                c.settingsRepository.settingsFlow,
                c.settingsRepository.pinnedSourceIdsFlow,
                c.secureTokenStore.malAuthFlow,
                c.extensionRepository.observeExtensions()
            ) { settings, pinned, malAuth, extensions ->
                listOf(settings, pinned, malAuth, extensions)
            }.collect { tuple ->
                val persistedSettings = tuple[0] as AppSettingsState
                @Suppress("UNCHECKED_CAST")
                val pinned = tuple[1] as Set<String>
                val malAuth = tuple[2] as MalAuthSnapshot
                @Suppress("UNCHECKED_CAST")
                val extensions = tuple[3] as List<ExtensionItem>

                _uiState.update { state ->
                    state.copy(
                        extensions = extensions,
                        pinnedSourceIds = pinned,
                        isSettingsLoaded = true,
                        settings = persistedSettings.copy(
                            isMalConnected = malAuth.isConnected,
                            malConnectedUser = malAuth.username,
                            cacheSizeText = state.settings.cacheSizeText
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(
                c.libraryRepository.observeAllManga { ctx.isEn() },
                c.libraryRepository.observeCategories(),
                c.libraryUpdateService.observeActivityUpdates { ctx.isEn() },
                c.libraryUpdateService.syncProgress
            ) { mangas, cats, updates, syncProg ->
                listOf(mangas, cats, updates, syncProg)
            }.collect { tuple ->
                @Suppress("UNCHECKED_CAST")
                val mangas = tuple[0] as List<MangaItem>
                @Suppress("UNCHECKED_CAST")
                val cats = tuple[1] as List<CategoryItem>
                @Suppress("UNCHECKED_CAST")
                val updates = tuple[2] as List<ActivityUpdateItem>
                val syncProg = tuple[3] as SyncProgressState

                _uiState.update { state ->
                    val inLibIds = mangas.filter { it.inLibrary }.map { it.id }.toSet()
                    state.copy(
                        mangaList = mangas,
                        categories = cats,
                        activityUpdates = updates,
                        syncProgress = syncProg,
                        catalogItems = state.catalogItems.map { item ->
                            item.copy(inLibrary = item.id in inLibIds, isFavorite = item.id in inLibIds)
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(
                c.historyRepository.observeDailyActivity14Days { ctx.isEn() },
                c.historyRepository.observeAllDailyLogs()
            ) { daily14, allLogs -> daily14 to allLogs }
                .collect { (daily14, allLogs) ->
                    readerHistory.latestDailyLogs = allLogs
                    _uiState.update { it.copy(baseDailyActivity = daily14) }
                }
        }
        viewModelScope.launch {
            c.downloadRepository.observeDownloads().collect { dlList ->
                _uiState.update { it.copy(downloads = dlList) }
            }
        }
        viewModelScope.launch {
            c.announcementRepository.observeAnnouncements().collect { items ->
                _uiState.update { it.copy(announcements = items) }
            }
        }
    }

    private fun createInitialState(): YomicUiState = if (container != null) {
        YomicUiState(
            mangaList = emptyList(),
            categories = emptyList(),
            activityUpdates = emptyList(),
            extensions = emptyList(),
            downloads = emptyList(),
            announcements = emptyList(),
            baseDailyActivity = emptyList(),
            isSettingsLoaded = false
        )
    } else {
        YomicUiState(
            mangaList = DemoDataRepository.createMangaList(),
            categories = DemoDataRepository.createCategories(),
            activityUpdates = DemoDataRepository.createInitialUpdates(),
            extensions = DemoDataRepository.createExtensions(),
            downloads = DemoDataRepository.createInitialDownloads(),
            announcements = DemoDataRepository.createAnnouncements(),
            baseDailyActivity = DemoDataRepository.createBaseDailyActivity(),
            isSettingsLoaded = true
        )
    }

    // =====================================================================
    // MESSAGING & SETTINGS
    // =====================================================================

    fun showMessage(message: String) = ctx.showMessage(message)
    fun clearMessage() = ctx.clearMessage()
    fun updateSettings(transform: (AppSettingsState) -> AppSettingsState, toast: String? = null) =
        ctx.updateSettings(transform, toast)

    fun refreshCacheSizeLabel() = moreSettings.refreshCacheSizeLabel()
    fun clearImageCache() = moreSettings.clearImageCache()
    fun clearWebViewCookies() = moreSettings.clearWebViewCookies()
    fun connectMyAnimeList(onOpenAuthUrl: (String) -> Unit) = moreSettings.connectMyAnimeList(onOpenAuthUrl)
    fun disconnectMyAnimeList() = moreSettings.disconnectMyAnimeList()
    fun searchMalCatalog(query: String) = moreSettings.searchMalCatalog(query)
    fun updateMalTracking(
        mangaId: String,
        status: String,
        chaptersRead: Int,
        score: Int,
        malId: Int = 0,
        malTitle: String = ""
    ) = moreSettings.updateMalTracking(mangaId, status, chaptersRead, score, malId, malTitle)
    fun unbindMalTracking(mangaId: String) = moreSettings.unbindMalTracking(mangaId)
    fun markAnnouncementsRead() = moreSettings.markAnnouncementsRead()
    fun refreshAnnouncements() = moreSettings.refreshAnnouncements()
    fun exportBackup(uri: Uri) = moreSettings.exportBackup(uri)
    fun importBackup(uri: Uri) = moreSettings.importBackup(uri)
    fun submitFeedback(
        categoryLabel: String,
        message: String,
        screenshot: AttachedFeedbackScreenshot?,
        onComplete: (FeedbackSubmitResult) -> Unit
    ) = moreSettings.submitFeedback(categoryLabel, message, screenshot, onComplete)
    fun checkAppUpdate() = moreSettings.checkAppUpdate()
    fun resetAppDemo() = moreSettings.resetAppDemo()

    // =====================================================================
    // LIBRARY, UPDATES & DETAIL
    // =====================================================================

    fun setLibrarySearch(query: String) = library.setLibrarySearch(query)
    fun setLibraryCategoryFilter(categoryId: Int?) = library.setLibraryCategoryFilter(categoryId)
    fun setLibraryFilterMode(mode: LibraryFilterMode) = library.setLibraryFilterMode(mode)
    fun setLibrarySourceFilter(sourceId: String?) = library.setLibrarySourceFilter(sourceId)
    fun setLibrarySort(sort: LibrarySortBy) = library.setLibrarySort(sort)
    fun toggleLibraryViewMode() = library.toggleLibraryViewMode()
    fun resetLibraryFilters() = library.resetLibraryFilters()
    fun addCategory(rawName: String) = library.addCategory(rawName)
    fun deleteCategory(categoryId: Int) = library.deleteCategory(categoryId)
    fun toggleMangaCategory(mangaId: String, categoryId: Int) = library.toggleMangaCategory(mangaId, categoryId)
    fun triggerLibrarySync() = library.triggerLibrarySync()
    fun clearAllUpdates() = library.clearAllUpdates()
    fun registerExternalMangaForDetail(manga: MangaItem) = library.registerExternalMangaForDetail(manga)
    fun loadMangaDetail(mangaId: String, forceRefresh: Boolean = false) = library.loadMangaDetail(mangaId, forceRefresh)
    fun toggleInLibrary(mangaId: String) = library.toggleInLibrary(mangaId)
    fun setDetailChapterFilter(filter: ChapterFilterMode) = library.setDetailChapterFilter(filter)
    fun toggleDetailChapterOrder() = library.toggleDetailChapterOrder()
    fun toggleChapterBookmark(mangaId: String, chapterNumber: Double) = library.toggleChapterBookmark(mangaId, chapterNumber)
    fun toggleChapterRead(mangaId: String, chapterNumber: Double) = library.toggleChapterRead(mangaId, chapterNumber)
    fun markPreviousChaptersRead(mangaId: String, chapterNumber: Double) = library.markPreviousChaptersRead(mangaId, chapterNumber)
    fun markAllChaptersRead(mangaId: String) = library.markAllChaptersRead(mangaId)
    fun markMultipleRead(mangaIds: Set<String>) = library.markMultipleRead(mangaIds)
    fun removeMultipleFromLibrary(mangaIds: Set<String>) = library.removeMultipleFromLibrary(mangaIds)
    fun assignCategoryToMultiple(mangaIds: Set<String>, categoryId: Int) = library.assignCategoryToMultiple(mangaIds, categoryId)
    fun getFilteredLibraryManga(state: YomicUiState = _uiState.value): List<MangaItem> = library.getFilteredLibraryManga(state)

    // =====================================================================
    // BROWSE, EXTENSIONS & CATALOG
    // =====================================================================

    fun addExtensionRepoUrl(rawUrl: String) = browse.addExtensionRepoUrl(rawUrl)
    fun removeExtensionRepoUrl(url: String) = browse.removeExtensionRepoUrl(url)
    fun resetExtensionReposToDefault() = browse.resetExtensionReposToDefault()
    fun setBrowseSearch(query: String) = browse.setBrowseSearch(query)
    fun setBrowseLangFilter(lang: SourceLang) = browse.setBrowseLangFilter(lang)
    fun togglePinSource(sourceId: String) = browse.togglePinSource(sourceId)
    fun setExtSearch(query: String) = browse.setExtSearch(query)
    fun setExtLangFilter(lang: SourceLang) = browse.setExtLangFilter(lang)
    fun refreshExtensionRepo() = browse.refreshExtensionRepo()
    fun installOrUpdateExtension(extId: String) = browse.installOrUpdateExtension(extId)
    fun uninstallExtension(extId: String) = browse.uninstallExtension(extId)
    fun openSourceCatalog(sourceId: String) = browse.openSourceCatalog(sourceId)
    fun loadCatalogPage(sourceId: String = _uiState.value.catalogSourceId, page: Int = 1) = browse.loadCatalogPage(sourceId, page)
    fun loadNextCatalogPage() = browse.loadNextCatalogPage()
    fun setCatalogSearch(query: String) = browse.setCatalogSearch(query)
    fun setCatalogSort(sort: SourceFeedSort) = browse.setCatalogSort(sort)
    fun setCatalogStatusFilter(status: MangaStatus?) = browse.setCatalogStatusFilter(status)
    fun setCatalogTypeFilter(type: MangaType?) = browse.setCatalogTypeFilter(type)
    fun setCatalogGenreFilter(genre: String?) = browse.setCatalogGenreFilter(genre)
    fun switchMangaDexLanguage(lang: SourceLang) = browse.switchMangaDexLanguage(lang)
    fun resetCatalogFilters() = browse.resetCatalogFilters()

    // =====================================================================
    // READER, HISTORY & INSIGHTS
    // =====================================================================

    fun loadReaderPages(mangaId: String, chapterNumber: Double, forceReload: Boolean = false) =
        readerHistory.loadReaderPages(mangaId, chapterNumber, forceReload)
    fun solveCloudflareAndReloadReader(challengeUrl: String, mangaId: String, chapterNumber: Double) =
        readerHistory.solveCloudflareAndReloadReader(challengeUrl, mangaId, chapterNumber)
    fun onChapterFinishedReading(mangaId: String, chapterNumber: Double) =
        readerHistory.onChapterFinishedReading(mangaId, chapterNumber)
    fun setHistorySearch(query: String) = readerHistory.setHistorySearch(query)
    fun toggleHistoryViewMode() = readerHistory.toggleHistoryViewMode()
    fun removeHistoryItem(mangaId: String) = readerHistory.removeHistoryItem(mangaId)
    fun restoreHistoryItem(mangaId: String) = readerHistory.restoreHistoryItem(mangaId)
    fun clearAllHistory() = readerHistory.clearAllHistory()
    fun updateReadingPage(mangaId: String, chapterNumber: Double, page: Int, totalPages: Int) =
        readerHistory.updateReadingPage(mangaId, chapterNumber, page, totalPages)
    fun recordChapterOpened(mangaId: String, chapterNumber: Double, page: Int, totalPages: Int) =
        readerHistory.recordChapterOpened(mangaId, chapterNumber, page, totalPages)
    fun recordReadingSeconds(mangaId: String, deltaSeconds: Int) =
        readerHistory.recordReadingSeconds(mangaId, deltaSeconds)
    fun computeReadingStatistics(state: YomicUiState = _uiState.value): ReadingStatistics =
        readerHistory.computeReadingStatistics(state)

    // =====================================================================
    // DOWNLOADS QUEUE
    // =====================================================================

    fun downloadUnreadForMultiple(mangaIds: Set<String>) = downloads.downloadUnreadForMultiple(mangaIds)
    fun enqueueChapterDownload(mangaId: String, chapterNumber: Double) = downloads.enqueueChapterDownload(mangaId, chapterNumber)
    fun enqueueUnreadDownloads(mangaId: String) = downloads.enqueueUnreadDownloads(mangaId)
    fun removeSingleChapterDownload(mangaId: String, chapterNumber: Double) = downloads.removeSingleChapterDownload(mangaId, chapterNumber)
    fun deleteAllChapterDownloads(mangaId: String) = downloads.deleteAllChapterDownloads(mangaId)
    fun toggleDownloadPause(dlId: String) = downloads.toggleDownloadPause(dlId)
    fun pauseAllDownloads() = downloads.pauseAllDownloads()
    fun resumeAllDownloads() = downloads.resumeAllDownloads()
    fun removeDownloadEntry(dlId: String) = downloads.removeDownloadEntry(dlId)
    fun clearCompletedDownloads() = downloads.clearCompletedDownloads()

    override fun onCleared() {
        super.onCleared()
        moreSettings.onCleared()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return YomicViewModel(autoStartTicker = true, container = container) as T
                }
            }
    }
}
