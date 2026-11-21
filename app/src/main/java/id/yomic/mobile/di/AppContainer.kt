package id.yomic.mobile.di

import android.content.Context
import id.yomic.mobile.data.backup.YomicBackupService
import id.yomic.mobile.data.image.YomicImagePipeline
import id.yomic.mobile.data.local.YomicDatabase
import id.yomic.mobile.data.network.YomicNetworkClient
import id.yomic.mobile.data.preferences.SecureTokenStore
import id.yomic.mobile.data.preferences.SettingsRepository
import id.yomic.mobile.data.repository.AnnouncementRepository
import id.yomic.mobile.data.repository.DownloadRepository
import id.yomic.mobile.data.repository.ExtensionRepository
import id.yomic.mobile.data.repository.FeedbackRepository
import id.yomic.mobile.data.repository.HistoryRepository
import id.yomic.mobile.data.repository.LibraryRepository
import id.yomic.mobile.data.repository.ReaderRepository
import id.yomic.mobile.data.service.AppUpdateService
import id.yomic.mobile.data.service.LibraryUpdateService
import id.yomic.mobile.data.service.SourceStatusService
import id.yomic.mobile.data.tracker.MalTrackerService
import id.yomic.mobile.engine.js.JsExtensionManager
import id.yomic.mobile.engine.webview.WebViewPageExtractor
import id.yomic.mobile.model.SourceLang
import id.yomic.mobile.worker.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Lightweight manual Dependency Injection container for Yomic Mobile.
 * Owned by YomicApplication and injected into YomicViewModel and background workers.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val pluginsDir: File = File(appContext.filesDir, "plugins").apply { mkdirs() }
    val downloadsDir: File = File(appContext.filesDir, "downloads").apply { mkdirs() }

    val database: YomicDatabase by lazy { YomicDatabase.create(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val secureTokenStore: SecureTokenStore by lazy { SecureTokenStore(appContext) }

    val networkClient: YomicNetworkClient by lazy { YomicNetworkClient(context = appContext) }
    val imagePipeline: YomicImagePipeline by lazy { YomicImagePipeline(appContext, networkClient) }
    val webViewPageExtractor: WebViewPageExtractor by lazy { WebViewPageExtractor(appContext) }

    val jsExtensionManager: JsExtensionManager by lazy {
        JsExtensionManager(
            pluginsDir = pluginsDir,
            networkClient = networkClient,
            webViewExtractor = webViewPageExtractor
        )
    }

    val extensionRepository: ExtensionRepository by lazy {
        ExtensionRepository(
            extensionCacheDao = database.extensionCacheDao(),
            extensionManager = jsExtensionManager,
            networkClient = networkClient,
            settingsRepository = settingsRepository
        )
    }

    val sourceStatusService: SourceStatusService by lazy {
        SourceStatusService(
            extensionCacheDao = database.extensionCacheDao(),
            extensionManager = jsExtensionManager,
            networkClient = networkClient,
            settingsRepository = settingsRepository,
            appScope = appScope
        )
    }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(
            mangaDao = database.mangaDao(),
            chapterDao = database.chapterDao(),
            categoryDao = database.categoryDao(),
            extensionManager = jsExtensionManager
        )
    }

    val readerRepository: ReaderRepository by lazy {
        ReaderRepository(
            context = appContext,
            mangaDao = database.mangaDao(),
            chapterDao = database.chapterDao(),
            extensionManager = jsExtensionManager,
            imagePipeline = imagePipeline
        )
    }

    val libraryUpdateService: LibraryUpdateService by lazy {
        LibraryUpdateService(
            mangaDao = database.mangaDao(),
            chapterDao = database.chapterDao(),
            extensionManager = jsExtensionManager,
            settingsRepository = settingsRepository
        )
    }

    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(
            mangaDao = database.mangaDao(),
            chapterDao = database.chapterDao(),
            historyDao = database.historyDao(),
            dailyLogDao = database.dailyReadingLogDao(),
            settingsRepository = settingsRepository
        )
    }

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(
            context = appContext,
            downloadsDir = downloadsDir,
            mangaDao = database.mangaDao(),
            chapterDao = database.chapterDao(),
            downloadTaskDao = database.downloadTaskDao(),
            settingsRepository = settingsRepository
        )
    }

    val malTrackerService: MalTrackerService by lazy {
        MalTrackerService(
            httpClientProvider = { networkClient.httpClient },
            tokenStore = secureTokenStore,
            mangaTrackDao = database.mangaTrackDao()
        )
    }

    val backupService: YomicBackupService by lazy {
        YomicBackupService(
            context = appContext,
            cacheDir = appContext.cacheDir,
            mangaDao = database.mangaDao(),
            chapterDao = database.chapterDao(),
            categoryDao = database.categoryDao(),
            historyDao = database.historyDao(),
            trackDao = database.mangaTrackDao(),
            extensionCacheDao = database.extensionCacheDao(),
            extensionManager = jsExtensionManager,
            settingsRepository = settingsRepository,
            dailyReadingLogDao = database.dailyReadingLogDao()
        )
    }

    val announcementRepository: AnnouncementRepository by lazy {
        AnnouncementRepository(
            httpClientProvider = { networkClient.httpClient },
            announcementDao = database.announcementDao(),
            settingsRepository = settingsRepository
        )
    }

    val feedbackRepository: FeedbackRepository by lazy {
        FeedbackRepository(httpClientProvider = { networkClient.httpClient })
    }

    val appUpdateService: AppUpdateService by lazy {
        AppUpdateService(httpClientProvider = { networkClient.httpClient })
    }

    init {
        appScope.launch {
            settingsRepository.settingsFlow
                .map { Triple(it.dohProvider, it.customUserAgent, it.maxReaderCacheSize) }
                .distinctUntilChanged()
                .collect { (doh, ua, cacheLimit) ->
                    networkClient.reconfigure(doh, ua)
                    imagePipeline.updateCacheLimit(cacheLimit)
                }
        }
        appScope.launch {
            settingsRepository.settingsFlow
                .map { it.mangaDexLanguage }
                .distinctUntilChanged()
                .collect { mdLang ->
                    jsExtensionManager.updateMangaDexLanguage(
                        if (mdLang == SourceLang.ID) "id" else "en"
                    )
                }
        }
        appScope.launch {
            settingsRepository.settingsFlow
                .map { it.backgroundUpdateInterval }
                .distinctUntilChanged()
                .collect { interval ->
                    WorkScheduler.scheduleLibraryUpdates(appContext, interval)
                }
        }
        appScope.launch {
            var initialized = false
            settingsRepository.settingsFlow
                .map { it.downloadOnlyOnWifi }
                .distinctUntilChanged()
                .collect { onlyOnWifi ->
                    if (initialized) {
                        downloadRepository.reapplyWifiConstraintIfPending(onlyOnWifi)
                    } else {
                        initialized = true
                    }
                }
        }
        appScope.launch {
            extensionRepository.initializeInstalledAndCacheIfEmpty()
            launch { libraryUpdateService.triggerStartupSyncIfEligible() }
            launch {
                if (!settingsRepository.getCurrentSettings().isOfflineMode) {
                    announcementRepository.refreshAnnouncements(force = false)
                }
            }
            settingsRepository.settingsFlow
                .map { it.sourceStatusInterval }
                .distinctUntilChanged()
                .collect { interval ->
                    sourceStatusService.startMonitoring(interval)
                }
        }
    }
}
