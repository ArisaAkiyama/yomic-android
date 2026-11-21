package id.yomic.mobile.viewmodel.controllers

import android.net.Uri
import id.yomic.mobile.data.DemoDataRepository
import id.yomic.mobile.data.repository.AttachedFeedbackScreenshot
import id.yomic.mobile.data.repository.FeedbackRepository
import id.yomic.mobile.data.repository.FeedbackSubmitResult
import id.yomic.mobile.model.AppSettingsState
import id.yomic.mobile.model.YomicUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns More & Settings backend operations:
 * Cache/Cookie maintenance, MyAnimeList OAuth2 & Search/Sync,
 * `.yomicbackup` SAF Export/Import, Announcements, Feedback Webhook,
 * GitHub App Updater, and Factory Reset.
 */
internal class MoreSettingsController(
    private val ctx: ControllerContext,
    private val createInitialState: () -> YomicUiState
) {
    private var malSearchJob: Job? = null
    private var malLoginJob: Job? = null

    fun refreshCacheSizeLabel() {
        val c = ctx.container ?: return
        ctx.scope.launch {
            val sizeText = withContext(Dispatchers.IO) { c.imagePipeline.computeCacheSizeText() }
            ctx.state.update { it.copy(settings = it.settings.copy(cacheSizeText = sizeText)) }
        }
    }

    fun clearImageCache() {
        val c = ctx.container
        if (c == null) {
            ctx.updateSettings(
                { it.copy(cacheSizeText = "0.0 MB") },
                ctx.l("Cache gambar berhasil dibersihkan.", "Image cache cleared.")
            )
            return
        }
        ctx.scope.launch {
            val nextSize = withContext(Dispatchers.IO) {
                c.imagePipeline.clearAllCaches()
                c.imagePipeline.computeCacheSizeText()
            }
            ctx.state.update {
                it.copy(
                    settings = it.settings.copy(cacheSizeText = nextSize),
                    snackbarMessage = ctx.l("Cache gambar berhasil dibersihkan.", "Image cache cleared.")
                )
            }
        }
    }

    fun clearWebViewCookies() {
        val c = ctx.container
        ctx.scope.launch {
            withContext(Dispatchers.IO) {
                c?.networkClient?.clearAllCookies()
            }
            ctx.showMessage(ctx.l("Cookie WebView berhasil dibersihkan.", "WebView cookies cleared."))
        }
    }

    fun connectMyAnimeList(onOpenAuthUrl: (String) -> Unit) {
        val c = ctx.container
        if (c == null) {
            ctx.updateSettings(
                { it.copy(isMalConnected = true, malConnectedUser = it.malConnectedUser.ifBlank { "YomicReader" }) },
                ctx.l("Terhubung ke MyAnimeList.", "Connected to MyAnimeList.")
            )
            return
        }
        if (ctx.state.value.settings.isOfflineMode) {
            ctx.showMessage(ctx.l("Mode Offline aktif.", "Offline mode is active."))
            return
        }
        malLoginJob?.cancel()
        malLoginJob = ctx.scope.launch {
            ctx.state.update { it.copy(isConnectingMal = true) }
            val result = c.malTrackerService.startLoopbackOAuthFlow(onAuthUrlReady = onOpenAuthUrl)
            ctx.state.update {
                it.copy(
                    isConnectingMal = false,
                    snackbarMessage = if (result.success) {
                        ctx.l("Terhubung ke MyAnimeList (@${result.username}).", "Connected to MyAnimeList (@${result.username}).")
                    } else {
                        result.error ?: ctx.l("Login MyAnimeList dibatalkan.", "MyAnimeList login cancelled.")
                    }
                )
            }
        }
    }

    fun disconnectMyAnimeList() {
        val c = ctx.container
        if (c == null) {
            ctx.updateSettings(
                { it.copy(isMalConnected = false) },
                ctx.l("Koneksi MyAnimeList diputus.", "Disconnected from MyAnimeList.")
            )
            return
        }
        malLoginJob?.cancel()
        c.malTrackerService.disconnect()
        ctx.state.update {
            it.copy(
                isConnectingMal = false,
                malSearchResults = emptyList(),
                snackbarMessage = ctx.l("Koneksi MyAnimeList diputus.", "Disconnected from MyAnimeList.")
            )
        }
    }

    fun searchMalCatalog(query: String) {
        val trimmed = query.trim()
        val c = ctx.container
        if (c == null) {
            ctx.state.update {
                it.copy(
                    malSearchResults = DemoDataRepository.searchMalCatalog(trimmed),
                    isSearchingMal = false
                )
            }
            return
        }
        if (trimmed.isBlank()) {
            ctx.state.update { it.copy(malSearchResults = emptyList(), isSearchingMal = false) }
            return
        }
        malSearchJob?.cancel()
        malSearchJob = ctx.scope.launch {
            ctx.state.update { it.copy(isSearchingMal = true) }
            val results = c.malTrackerService.searchManga(trimmed)
            ctx.state.update { it.copy(malSearchResults = results, isSearchingMal = false) }
        }
    }

    fun updateMalTracking(
        mangaId: String,
        status: String,
        chaptersRead: Int,
        score: Int,
        malId: Int = 0,
        malTitle: String = ""
    ) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                val synced = c.malTrackerService.saveAndSyncTrack(
                    mangaId = mangaId,
                    malId = malId,
                    malTitle = malTitle,
                    status = status,
                    chaptersRead = chaptersRead,
                    score = score
                )
                ctx.showMessage(
                    if (synced) ctx.l("Pelacakan MyAnimeList tersinkronisasi.", "MyAnimeList tracking synced.")
                    else ctx.l("Pelacakan disimpan lokal (gagal sinkronisasi ke MAL).", "Saved locally (failed to push to MAL).")
                )
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id != mangaId) m else m.copy(
                        malTracked = true,
                        malId = if (malId > 0) malId else m.malId,
                        malTitle = malTitle.ifBlank { m.malTitle.ifBlank { m.title } },
                        malStatus = status,
                        malChaptersRead = chaptersRead,
                        malScore = score
                    )
                },
                snackbarMessage = ctx.l("Pelacakan MyAnimeList disimpan.", "MyAnimeList tracking saved.")
            )
        }
    }

    fun unbindMalTracking(mangaId: String) {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                c.malTrackerService.unbindTrack(mangaId)
                ctx.showMessage(ctx.l("Tautan MyAnimeList dilepas.", "MyAnimeList link removed."))
            }
            return
        }
        ctx.state.update { state ->
            state.copy(
                mangaList = state.mangaList.map { m ->
                    if (m.id != mangaId) m else m.copy(malTracked = false, malId = 0, malTitle = "", malScore = 0)
                },
                snackbarMessage = ctx.l("Tautan MyAnimeList dilepas.", "MyAnimeList link removed.")
            )
        }
    }

    fun markAnnouncementsRead() {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch { c.announcementRepository.markAllRead() }
            return
        }
        ctx.state.update { state ->
            state.copy(announcements = state.announcements.map { it.copy(isUnread = false) })
        }
    }

    fun refreshAnnouncements() {
        if (ctx.state.value.isRefreshingAnnouncements) return
        val c = ctx.container
        if (c != null) {
            if (ctx.state.value.settings.isOfflineMode) {
                ctx.showMessage(ctx.l("Mode Offline aktif.", "Offline mode is active."))
                return
            }
            ctx.scope.launch {
                ctx.state.update { it.copy(isRefreshingAnnouncements = true) }
                val list = c.announcementRepository.refreshAnnouncements(force = true)
                ctx.state.update {
                    it.copy(
                        isRefreshingAnnouncements = false,
                        snackbarMessage = if (list.isNotEmpty()) {
                            ctx.l("Pengumuman diperbarui (${list.size}).", "Announcements refreshed (${list.size}).")
                        } else {
                            ctx.l("Tidak dapat memuat pengumuman terbaru.", "Could not load latest announcements.")
                        }
                    )
                }
            }
            return
        }
        ctx.scope.launch {
            ctx.state.update { it.copy(isRefreshingAnnouncements = true) }
            delay(750L)
            ctx.state.update {
                it.copy(
                    isRefreshingAnnouncements = false,
                    snackbarMessage = ctx.l("Pengumuman diperbarui.", "Announcements refreshed.")
                )
            }
        }
    }

    fun exportBackup(uri: Uri) {
        val c = ctx.container ?: return
        ctx.scope.launch {
            val res = c.backupService.exportBackupToUri(uri)
            ctx.showMessage(res.message)
        }
    }

    fun importBackup(uri: Uri) {
        val c = ctx.container ?: return
        ctx.scope.launch {
            val res = c.backupService.importBackupFromUri(uri)
            ctx.showMessage(res.message)
        }
    }

    fun submitFeedback(
        categoryLabel: String,
        message: String,
        screenshot: AttachedFeedbackScreenshot?,
        onComplete: (FeedbackSubmitResult) -> Unit
    ) {
        val c = ctx.container
        if (c == null) {
            val ok = message.isNotBlank()
            val res = FeedbackSubmitResult(
                success = ok,
                message = if (ok) ctx.l("Terima kasih! Masukan Anda berhasil dikirim.", "Thank you! Your feedback has been sent.")
                else ctx.l("Mohon isi deskripsi masukan terlebih dahulu.", "Please enter a description first.")
            )
            ctx.showMessage(res.message)
            onComplete(res)
            return
        }
        ctx.scope.launch {
            val res = c.feedbackRepository.submitFeedback(
                category = FeedbackRepository.mapCategoryKey(categoryLabel),
                categoryLabel = categoryLabel,
                message = message,
                language = ctx.state.value.settings.appLanguage.code,
                screenshot = screenshot
            )
            ctx.showMessage(res.message)
            onComplete(res)
        }
    }

    fun checkAppUpdate() {
        if (ctx.state.value.isCheckingAppUpdate) return
        val c = ctx.container
        if (c == null) {
            ctx.scope.launch {
                ctx.state.update { it.copy(isCheckingAppUpdate = true) }
                delay(500L)
                ctx.state.update {
                    it.copy(
                        isCheckingAppUpdate = false,
                        snackbarMessage = ctx.l("Yomic sudah versi terbaru.", "Yomic is up to date.")
                    )
                }
            }
            return
        }
        if (ctx.state.value.settings.isOfflineMode) {
            ctx.showMessage(ctx.l("Mode Offline aktif.", "Offline mode is active."))
            return
        }
        ctx.scope.launch {
            ctx.state.update { it.copy(isCheckingAppUpdate = true) }
            val info = c.appUpdateService.checkForUpdates()
            ctx.state.update {
                it.copy(
                    isCheckingAppUpdate = false,
                    appUpdateInfo = info,
                    snackbarMessage = if (info.isUpdateAvailable) {
                        ctx.l("Pembaruan tersedia: ${info.latestVersion}", "Update available: ${info.latestVersion}")
                    } else {
                        ctx.l("Yomic sudah versi terbaru (v${info.currentVersion}).", "You are on the latest version (v${info.currentVersion}).")
                    }
                )
            }
        }
    }

    fun resetAppDemo() {
        val c = ctx.container
        if (c != null) {
            ctx.scope.launch {
                withContext(Dispatchers.IO) {
                    c.malTrackerService.disconnect()
                    c.downloadRepository.deleteAllDownloadsOnDevice()
                    c.jsExtensionManager.clearAllPlugins()
                    c.database.mangaTrackDao().deleteAll()
                    c.database.historyDao().clearAll()
                    c.database.dailyReadingLogDao().deleteAll()
                    c.database.categoryDao().deleteAllCrossRefs()
                    c.database.chapterDao().deleteAll()
                    c.database.mangaDao().deleteAll()
                    c.database.categoryDao().deleteAll()
                    c.database.extensionCacheDao().deleteAll()
                    c.database.announcementDao().deleteAll()
                    c.imagePipeline.clearAllCaches()
                    c.networkClient.clearAllCookies()
                    c.settingsRepository.resetAll()
                }
                refreshCacheSizeLabel()
                ctx.state.update {
                    it.copy(
                        appUpdateInfo = null,
                        malSearchResults = emptyList(),
                        globalSearchGroups = emptyList(),
                        catalogItems = emptyList(),
                        readerPages = emptyList(),
                        snackbarMessage = ctx.l("Seluruh data & pengaturan aplikasi telah diatur ulang.", "All app data and settings have been reset.")
                    )
                }
            }
            return
        }
        val lang = ctx.state.value.settings.appLanguage
        val dark = ctx.state.value.settings.isDarkMode
        ctx.state.value = createInitialState().copy(
            settings = AppSettingsState(isDarkMode = dark, appLanguage = lang),
            snackbarMessage = ctx.l("Data demo aplikasi berhasil diatur ulang.", "Demo application data has been reset.")
        )
    }

    fun onCleared() {
        malLoginJob?.cancel()
        malSearchJob?.cancel()
        ctx.container?.malTrackerService?.cancelPendingLogin()
    }
}
