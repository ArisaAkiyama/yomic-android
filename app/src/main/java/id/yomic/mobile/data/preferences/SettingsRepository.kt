package id.yomic.mobile.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import id.yomic.mobile.BuildConfig
import id.yomic.mobile.model.AppLanguage
import id.yomic.mobile.model.AppSettingsState
import id.yomic.mobile.model.ReaderFitMode
import id.yomic.mobile.model.ReaderMode
import id.yomic.mobile.model.ReadingDirection
import id.yomic.mobile.model.SourceLang
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.yomicSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "yomic_settings"
)

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val defaultExtensionRepo: String = BuildConfig.DEFAULT_EXTENSION_REPO
) {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.yomicSettingsDataStore,
        defaultExtensionRepo = BuildConfig.DEFAULT_EXTENSION_REPO
    )

    private object Keys {
        val IS_DARK_MODE = booleanPreferencesKey("is_dark_mode")
        val PURE_BLACK_DARK_MODE = booleanPreferencesKey("pure_black_dark_mode")
        val IS_INCOGNITO_MODE = booleanPreferencesKey("is_incognito_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val DEFAULT_READER_MODE = stringPreferencesKey("default_reader_mode")
        val READING_DIRECTION = stringPreferencesKey("reading_direction")
        val READER_FIT_MODE = stringPreferencesKey("reader_fit_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val VOLUME_KEYS_NAV = booleanPreferencesKey("volume_keys_nav")
        val FULLSCREEN_READER = booleanPreferencesKey("fullscreen_reader")
        val AUTO_DARK_READER = booleanPreferencesKey("auto_dark_reader")
        val SHOW_UNREAD_BADGES = booleanPreferencesKey("show_unread_badges")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val READER_DIMMER_ALPHA = floatPreferencesKey("reader_dimmer_alpha")
        val SHOW_NSFW = booleanPreferencesKey("show_nsfw")
        val BLUR_EXPLICIT_COVERS = booleanPreferencesKey("blur_explicit_covers")
        val CHECK_EXTENSION_UPDATES = booleanPreferencesKey("check_extension_updates")
        val ENABLE_IMAGE_OPTIMIZATION = booleanPreferencesKey("enable_image_optimization")
        val ENABLE_IMAGE_SHARPENING = booleanPreferencesKey("enable_image_sharpening")
        val ENABLE_AUTO_CROP_BORDERS = booleanPreferencesKey("enable_auto_crop_borders")
        val MAX_IMAGE_DIMENSION = intPreferencesKey("max_image_dimension")
        val PRELOAD_NEXT_CHAPTER = booleanPreferencesKey("preload_next_chapter")
        val DOWNLOAD_ONLY_ON_WIFI = booleanPreferencesKey("download_only_on_wifi")
        val SAVE_DOWNLOADS_AS_CBZ = booleanPreferencesKey("save_downloads_as_cbz")
        val AUTO_DELETE_AFTER_READ = booleanPreferencesKey("auto_delete_after_read")
        val AUTO_DOWNLOAD_NEXT_CHAPTER = booleanPreferencesKey("auto_download_next_chapter")
        val SKIP_FILTERED_CHAPTERS = booleanPreferencesKey("skip_filtered_chapters")
        val MAX_READER_CACHE_SIZE = stringPreferencesKey("max_reader_cache_size")
        val AUTO_SYNC_STARTUP = booleanPreferencesKey("auto_sync_startup")
        val SMART_UPDATE = booleanPreferencesKey("smart_update")
        val BACKGROUND_UPDATE_INTERVAL = stringPreferencesKey("background_update_interval")
        val IS_OFFLINE_MODE = booleanPreferencesKey("is_offline_mode")
        val DOH_PROVIDER = stringPreferencesKey("doh_provider")
        val SOURCE_STATUS_INTERVAL = stringPreferencesKey("source_status_interval")
        val CUSTOM_USER_AGENT = stringPreferencesKey("custom_user_agent")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val SECURE_SCREEN_ENABLED = booleanPreferencesKey("secure_screen_enabled")
        val AUTO_SYNC_TRACKER = booleanPreferencesKey("auto_sync_tracker")
        val MANGADEX_LANGUAGE = stringPreferencesKey("mangadex_language")
        val EXTENSION_REPOS_CONFIGURED = booleanPreferencesKey("extension_repos_configured")
        val EXTENSION_REPO_URLS = stringSetPreferencesKey("extension_repo_urls")
        val PINNED_SOURCE_IDS = stringSetPreferencesKey("pinned_source_ids")
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        val LAST_READ_ANNOUNCEMENT_ID = stringPreferencesKey("last_read_announcement_id")
        val LAST_BACKUP_EPOCH = longPreferencesKey("last_backup_epoch")
    }

    val settingsFlow: Flow<AppSettingsState> = dataStore.data
        .catch { ex ->
            if (ex is IOException) emit(emptyPreferences()) else throw ex
        }
        .map { prefs -> mapPreferencesToState(prefs) }

    val pinnedSourceIdsFlow: Flow<Set<String>> = dataStore.data
        .catch { ex ->
            if (ex is IOException) emit(emptyPreferences()) else throw ex
        }
        .map { prefs -> prefs[Keys.PINNED_SOURCE_IDS] ?: emptySet() }

    suspend fun getCurrentSettings(): AppSettingsState = settingsFlow.first()

    suspend fun saveSettings(state: AppSettingsState) {
        dataStore.edit { p ->
            p[Keys.IS_DARK_MODE] = state.isDarkMode
            p[Keys.PURE_BLACK_DARK_MODE] = state.pureBlackDarkMode
            p[Keys.IS_INCOGNITO_MODE] = state.isIncognitoMode
            p[Keys.APP_LANGUAGE] = state.appLanguage.code
            p[Keys.DEFAULT_READER_MODE] = state.defaultReaderMode.name
            p[Keys.READING_DIRECTION] = state.readingDirection.name
            p[Keys.READER_FIT_MODE] = state.readerFitMode.name
            p[Keys.KEEP_SCREEN_ON] = state.keepScreenOn
            p[Keys.VOLUME_KEYS_NAV] = state.volumeKeysNavigation
            p[Keys.FULLSCREEN_READER] = state.fullscreenReader
            p[Keys.AUTO_DARK_READER] = state.autoDarkReader
            p[Keys.SHOW_UNREAD_BADGES] = state.showUnreadBadges
            p[Keys.GRID_COLUMNS] = state.gridColumns.coerceIn(2, 4)
            p[Keys.READER_DIMMER_ALPHA] = state.readerDimmerAlpha.coerceIn(0f, 0.7f)
            p[Keys.SHOW_NSFW] = state.showNsfw
            p[Keys.BLUR_EXPLICIT_COVERS] = state.blurExplicitCovers
            p[Keys.CHECK_EXTENSION_UPDATES] = state.checkExtensionUpdates
            p[Keys.ENABLE_IMAGE_OPTIMIZATION] = state.enableImageOptimization
            p[Keys.ENABLE_IMAGE_SHARPENING] = state.enableImageSharpening
            p[Keys.ENABLE_AUTO_CROP_BORDERS] = state.enableAutoCropBorders
            p[Keys.MAX_IMAGE_DIMENSION] = state.maxImageDimension
            p[Keys.PRELOAD_NEXT_CHAPTER] = state.preloadNextChapter
            p[Keys.DOWNLOAD_ONLY_ON_WIFI] = state.downloadOnlyOnWifi
            p[Keys.SAVE_DOWNLOADS_AS_CBZ] = state.saveDownloadsAsCbz
            p[Keys.AUTO_DELETE_AFTER_READ] = state.autoDeleteAfterRead
            p[Keys.AUTO_DOWNLOAD_NEXT_CHAPTER] = state.autoDownloadNextChapter
            p[Keys.SKIP_FILTERED_CHAPTERS] = state.skipFilteredChapters
            p[Keys.MAX_READER_CACHE_SIZE] = state.maxReaderCacheSize
            p[Keys.AUTO_SYNC_STARTUP] = state.autoSyncStartup
            p[Keys.SMART_UPDATE] = state.smartUpdate
            p[Keys.BACKGROUND_UPDATE_INTERVAL] = state.backgroundUpdateInterval
            p[Keys.IS_OFFLINE_MODE] = state.isOfflineMode
            p[Keys.DOH_PROVIDER] = state.dohProvider
            p[Keys.SOURCE_STATUS_INTERVAL] = state.sourceStatusInterval
            p[Keys.CUSTOM_USER_AGENT] = state.customUserAgent
            p[Keys.APP_LOCK_ENABLED] = state.appLockEnabled
            p[Keys.SECURE_SCREEN_ENABLED] = state.secureScreenEnabled
            p[Keys.AUTO_SYNC_TRACKER] = state.autoSyncTracker
            p[Keys.MANGADEX_LANGUAGE] = state.mangaDexLanguage.name
            p[Keys.EXTENSION_REPOS_CONFIGURED] = true
            p[Keys.EXTENSION_REPO_URLS] = state.extensionRepoUrls
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            p[Keys.HAS_COMPLETED_ONBOARDING] = state.hasCompletedOnboarding
            p[Keys.LAST_READ_ANNOUNCEMENT_ID] = state.lastReadAnnouncementId
            p[Keys.LAST_BACKUP_EPOCH] = state.lastBackupEpoch
        }
    }

    suspend fun updateSettings(transform: (AppSettingsState) -> AppSettingsState): AppSettingsState {
        var updated = AppSettingsState()
        dataStore.edit { prefs ->
            val current = mapPreferencesToState(prefs)
            updated = transform(current)
            prefs[Keys.IS_DARK_MODE] = updated.isDarkMode
            prefs[Keys.PURE_BLACK_DARK_MODE] = updated.pureBlackDarkMode
            prefs[Keys.IS_INCOGNITO_MODE] = updated.isIncognitoMode
            prefs[Keys.APP_LANGUAGE] = updated.appLanguage.code
            prefs[Keys.DEFAULT_READER_MODE] = updated.defaultReaderMode.name
            prefs[Keys.READING_DIRECTION] = updated.readingDirection.name
            prefs[Keys.READER_FIT_MODE] = updated.readerFitMode.name
            prefs[Keys.KEEP_SCREEN_ON] = updated.keepScreenOn
            prefs[Keys.VOLUME_KEYS_NAV] = updated.volumeKeysNavigation
            prefs[Keys.FULLSCREEN_READER] = updated.fullscreenReader
            prefs[Keys.AUTO_DARK_READER] = updated.autoDarkReader
            prefs[Keys.SHOW_UNREAD_BADGES] = updated.showUnreadBadges
            prefs[Keys.GRID_COLUMNS] = updated.gridColumns.coerceIn(2, 4)
            prefs[Keys.READER_DIMMER_ALPHA] = updated.readerDimmerAlpha.coerceIn(0f, 0.7f)
            prefs[Keys.SHOW_NSFW] = updated.showNsfw
            prefs[Keys.BLUR_EXPLICIT_COVERS] = updated.blurExplicitCovers
            prefs[Keys.CHECK_EXTENSION_UPDATES] = updated.checkExtensionUpdates
            prefs[Keys.ENABLE_IMAGE_OPTIMIZATION] = updated.enableImageOptimization
            prefs[Keys.ENABLE_IMAGE_SHARPENING] = updated.enableImageSharpening
            prefs[Keys.ENABLE_AUTO_CROP_BORDERS] = updated.enableAutoCropBorders
            prefs[Keys.MAX_IMAGE_DIMENSION] = updated.maxImageDimension
            prefs[Keys.PRELOAD_NEXT_CHAPTER] = updated.preloadNextChapter
            prefs[Keys.DOWNLOAD_ONLY_ON_WIFI] = updated.downloadOnlyOnWifi
            prefs[Keys.SAVE_DOWNLOADS_AS_CBZ] = updated.saveDownloadsAsCbz
            prefs[Keys.AUTO_DELETE_AFTER_READ] = updated.autoDeleteAfterRead
            prefs[Keys.AUTO_DOWNLOAD_NEXT_CHAPTER] = updated.autoDownloadNextChapter
            prefs[Keys.SKIP_FILTERED_CHAPTERS] = updated.skipFilteredChapters
            prefs[Keys.MAX_READER_CACHE_SIZE] = updated.maxReaderCacheSize
            prefs[Keys.AUTO_SYNC_STARTUP] = updated.autoSyncStartup
            prefs[Keys.SMART_UPDATE] = updated.smartUpdate
            prefs[Keys.BACKGROUND_UPDATE_INTERVAL] = updated.backgroundUpdateInterval
            prefs[Keys.IS_OFFLINE_MODE] = updated.isOfflineMode
            prefs[Keys.DOH_PROVIDER] = updated.dohProvider
            prefs[Keys.SOURCE_STATUS_INTERVAL] = updated.sourceStatusInterval
            prefs[Keys.CUSTOM_USER_AGENT] = updated.customUserAgent
            prefs[Keys.APP_LOCK_ENABLED] = updated.appLockEnabled
            prefs[Keys.SECURE_SCREEN_ENABLED] = updated.secureScreenEnabled
            prefs[Keys.AUTO_SYNC_TRACKER] = updated.autoSyncTracker
            prefs[Keys.MANGADEX_LANGUAGE] = updated.mangaDexLanguage.name
            prefs[Keys.EXTENSION_REPOS_CONFIGURED] = true
            prefs[Keys.EXTENSION_REPO_URLS] = updated.extensionRepoUrls
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            prefs[Keys.HAS_COMPLETED_ONBOARDING] = updated.hasCompletedOnboarding
            prefs[Keys.LAST_READ_ANNOUNCEMENT_ID] = updated.lastReadAnnouncementId
            prefs[Keys.LAST_BACKUP_EPOCH] = updated.lastBackupEpoch
        }
        return updated
    }

    suspend fun addExtensionRepoUrl(rawUrl: String): Boolean {
        val clean = rawUrl.trim()
        if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            return false
        }
        updateSettings { s ->
            if (s.extensionRepoUrls.any { it.equals(clean, ignoreCase = true) }) s
            else s.copy(extensionRepoUrls = s.extensionRepoUrls + clean)
        }
        return true
    }

    suspend fun removeExtensionRepoUrl(url: String) {
        updateSettings { s ->
            s.copy(extensionRepoUrls = s.extensionRepoUrls.filterNot { it.equals(url.trim(), ignoreCase = true) })
        }
    }

    suspend fun resetExtensionReposToDefault() {
        val defaults = if (defaultExtensionRepo.isNotBlank()) listOf(defaultExtensionRepo) else emptyList()
        updateSettings { it.copy(extensionRepoUrls = defaults) }
    }

    suspend fun togglePinSource(sourceId: String): Boolean {
        var nowPinned = false
        dataStore.edit { prefs ->
            val current = prefs[Keys.PINNED_SOURCE_IDS] ?: emptySet()
            if (sourceId in current) {
                prefs[Keys.PINNED_SOURCE_IDS] = current - sourceId
                nowPinned = false
            } else {
                prefs[Keys.PINNED_SOURCE_IDS] = current + sourceId
                nowPinned = true
            }
        }
        return nowPinned
    }

    suspend fun setLastReadAnnouncementId(id: String) {
        if (id.isBlank()) return
        dataStore.edit { it[Keys.LAST_READ_ANNOUNCEMENT_ID] = id }
    }

    suspend fun setLastBackupEpoch(epochMs: Long) {
        dataStore.edit { it[Keys.LAST_BACKUP_EPOCH] = epochMs }
    }

    suspend fun setOnboardingCompleted() {
        dataStore.edit { it[Keys.HAS_COMPLETED_ONBOARDING] = true }
    }

    suspend fun resetAll() {
        dataStore.edit { it.clear() }
    }

    private fun mapPreferencesToState(p: Preferences): AppSettingsState {
        val defaults = AppSettingsState()
        val lang = if (p[Keys.APP_LANGUAGE] == AppLanguage.EN.code) AppLanguage.EN else AppLanguage.ID
        val isEn = lang == AppLanguage.EN

        val reposConfigured = p[Keys.EXTENSION_REPOS_CONFIGURED] == true
        val repos = if (reposConfigured) {
            (p[Keys.EXTENSION_REPO_URLS] ?: emptySet()).sorted()
        } else {
            if (defaultExtensionRepo.isNotBlank()) listOf(defaultExtensionRepo) else emptyList()
        }

        val lastBackupEpoch = p[Keys.LAST_BACKUP_EPOCH] ?: 0L
        val lastBackupLabel = if (lastBackupEpoch > 0L) {
            val formatted = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(lastBackupEpoch))
            if (isEn) "Last backup: $formatted (.yomicbackup)" else "Terakhir dicadangkan: $formatted (.yomicbackup)"
        } else {
            if (isEn) "Ready to back up (.yomicbackup)" else "Siap dicadangkan (.yomicbackup)"
        }

        return defaults.copy(
            isDarkMode = p[Keys.IS_DARK_MODE] ?: defaults.isDarkMode,
            pureBlackDarkMode = p[Keys.PURE_BLACK_DARK_MODE] ?: defaults.pureBlackDarkMode,
            isIncognitoMode = p[Keys.IS_INCOGNITO_MODE] ?: defaults.isIncognitoMode,
            appLanguage = lang,
            defaultReaderMode = p[Keys.DEFAULT_READER_MODE]?.let { runCatching { ReaderMode.valueOf(it) }.getOrNull() }
                ?: defaults.defaultReaderMode,
            readingDirection = p[Keys.READING_DIRECTION]?.let { runCatching { ReadingDirection.valueOf(it) }.getOrNull() }
                ?: defaults.readingDirection,
            readerFitMode = p[Keys.READER_FIT_MODE]?.let { runCatching { ReaderFitMode.valueOf(it) }.getOrNull() }
                ?: defaults.readerFitMode,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            volumeKeysNavigation = p[Keys.VOLUME_KEYS_NAV] ?: defaults.volumeKeysNavigation,
            fullscreenReader = p[Keys.FULLSCREEN_READER] ?: defaults.fullscreenReader,
            autoDarkReader = p[Keys.AUTO_DARK_READER] ?: defaults.autoDarkReader,
            showUnreadBadges = p[Keys.SHOW_UNREAD_BADGES] ?: defaults.showUnreadBadges,
            gridColumns = (p[Keys.GRID_COLUMNS] ?: defaults.gridColumns).coerceIn(2, 4),
            readerDimmerAlpha = (p[Keys.READER_DIMMER_ALPHA] ?: defaults.readerDimmerAlpha).coerceIn(0f, 0.7f),
            showNsfw = p[Keys.SHOW_NSFW] ?: defaults.showNsfw,
            blurExplicitCovers = p[Keys.BLUR_EXPLICIT_COVERS] ?: defaults.blurExplicitCovers,
            checkExtensionUpdates = p[Keys.CHECK_EXTENSION_UPDATES] ?: defaults.checkExtensionUpdates,
            enableImageOptimization = p[Keys.ENABLE_IMAGE_OPTIMIZATION] ?: defaults.enableImageOptimization,
            enableImageSharpening = p[Keys.ENABLE_IMAGE_SHARPENING] ?: defaults.enableImageSharpening,
            enableAutoCropBorders = p[Keys.ENABLE_AUTO_CROP_BORDERS] ?: defaults.enableAutoCropBorders,
            maxImageDimension = p[Keys.MAX_IMAGE_DIMENSION] ?: defaults.maxImageDimension,
            preloadNextChapter = p[Keys.PRELOAD_NEXT_CHAPTER] ?: defaults.preloadNextChapter,
            downloadOnlyOnWifi = p[Keys.DOWNLOAD_ONLY_ON_WIFI] ?: defaults.downloadOnlyOnWifi,
            saveDownloadsAsCbz = p[Keys.SAVE_DOWNLOADS_AS_CBZ] ?: defaults.saveDownloadsAsCbz,
            autoDeleteAfterRead = p[Keys.AUTO_DELETE_AFTER_READ] ?: defaults.autoDeleteAfterRead,
            autoDownloadNextChapter = p[Keys.AUTO_DOWNLOAD_NEXT_CHAPTER] ?: defaults.autoDownloadNextChapter,
            skipFilteredChapters = p[Keys.SKIP_FILTERED_CHAPTERS] ?: defaults.skipFilteredChapters,
            maxReaderCacheSize = p[Keys.MAX_READER_CACHE_SIZE] ?: defaults.maxReaderCacheSize,
            autoSyncStartup = p[Keys.AUTO_SYNC_STARTUP] ?: defaults.autoSyncStartup,
            smartUpdate = p[Keys.SMART_UPDATE] ?: defaults.smartUpdate,
            backgroundUpdateInterval = p[Keys.BACKGROUND_UPDATE_INTERVAL] ?: defaults.backgroundUpdateInterval,
            isOfflineMode = p[Keys.IS_OFFLINE_MODE] ?: defaults.isOfflineMode,
            dohProvider = p[Keys.DOH_PROVIDER] ?: defaults.dohProvider,
            sourceStatusInterval = p[Keys.SOURCE_STATUS_INTERVAL] ?: defaults.sourceStatusInterval,
            customUserAgent = p[Keys.CUSTOM_USER_AGENT] ?: defaults.customUserAgent,
            appLockEnabled = p[Keys.APP_LOCK_ENABLED] ?: defaults.appLockEnabled,
            secureScreenEnabled = p[Keys.SECURE_SCREEN_ENABLED] ?: defaults.secureScreenEnabled,
            autoSyncTracker = p[Keys.AUTO_SYNC_TRACKER] ?: defaults.autoSyncTracker,
            mangaDexLanguage = p[Keys.MANGADEX_LANGUAGE]?.let { runCatching { SourceLang.valueOf(it) }.getOrNull() }
                ?: defaults.mangaDexLanguage,
            extensionRepoUrls = repos,
            hasCompletedOnboarding = p[Keys.HAS_COMPLETED_ONBOARDING] ?: false,
            lastReadAnnouncementId = p[Keys.LAST_READ_ANNOUNCEMENT_ID] ?: "",
            lastBackupEpoch = lastBackupEpoch,
            lastBackupText = lastBackupLabel
        )
    }
}
