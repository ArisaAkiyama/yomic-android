# Changelog

All notable changes to **Yomic Mobile** will be documented in this file.

## [1.0.0] - 2026-11-21

Update Details：
◆ Add complete native Android architecture powered by Kotlin 2.0, Jetpack Compose, and Material 3 (minSdk 24 / targetSdk 35) with unidirectional StateFlow data flow (YomicViewModel & modular feature controllers).
◆ Add ergonomic 5-tab navigation shell (YomicNavHost) unifying Library, Updates, Browse (Sources & Extensions 2-Tab Hub), History, and More with signature Oceanic Light, Antigravity Carbon Dark, Deep-Ocean Reader Dark (#0A0A0A), and AMOLED Pure Black themes.
◆ Add instant runtime bilingual UI localization (YomicStrings) supporting seamless Indonesian (ID) and English (EN) switching without restarting the application.
◆ Add native QuickJS JavaScript extension engine (QuickJsExtensionEngine & JsExtensionSource) paired with Jsoup DOM wrappers (JsoupDomBridge) for sandboxed execution of 102+ Yomic comic sources from ArisaAkiyama/extension-yomic.
◆ Add headless & interactive WebView chapter page extractor (WebViewPageExtractor) with custom per-source extraction hooks (chapterReadySelector & pageWebViewExtractScript) to resolve dynamic SPA / Next.js hydration and client-side security checks.
◆ Add multi-layer network resilience engine (YomicNetworkClient) featuring OkHttp DNS-over-HTTPS (DoHProvider supporting Cloudflare 1.1.1.1, Google 8.8.8.8, and AdGuard), persistent WebViewCookieJar synchronization, and interactive Cloudflare challenge solver (CloudflareSolverActivity).
◆ Add custom Coil 3 image pipeline (YomicImagePipeline) with PipeHeaderOkHttpInterceptor for per-image Referer, Origin, and User-Agent header injection using Yomic pipe syntax (url|Key=Value).
◆ Add on-the-fly AES-256-CBC and XOR stream image decryption (AesImageDecryptor) for protected comic CDNs directly in memory.
◆ Add direct CBZ comic archive page streaming (CbzPageFetcher) and smart bitmap post-processing (YomicImageProcessor) supporting automatic whitespace/black border cropping, smart downscaling, and unsharp-mask sharpening.
◆ Add immersive fullscreen comic reader (ReaderScreen & RealComicPageView) supporting Webtoon vertical scroll, Single Page, and Dual Page modes (LTR / RTL), 3-zone tap navigation, double-tap & pinch-to-zoom, configurable auto-scroll, volume key page turning, and keep-screen-awake control.
◆ Add Room SQLite local database (YomicDatabase, Entities, and Daos) and DataStore preferences (SettingsRepository) storing library entries, chapters, categories, reading history, download queue, and user preferences.
◆ Add WorkManager background job orchestration for queued multi-chapter offline downloads (ChapterDownloadWorker supporting folder and .cbz archive output, auto-download next chapter, and auto-delete after read) and scheduled library update checks (LibraryUpdateWorker).
◆ Add Reading Insights analytics dashboard (ReadingInsightsScreen) tracking library completion rate, total chapters read, estimated reading hours, 14-day activity chart, top genre breakdown, and daily reading streak.
◆ Add MyAnimeList (MAL) OAuth2 PKCE tracker integration (MalTrackerService & SecureTokenStore) to bind manga series and automatically synchronize chapter reading progress.
◆ Add cross-platform backup and restore service (YomicBackupService) compatible with Yomic v2 Windows Desktop (.yomicbackup archives) with automatic credential sanitization.
◆ Add biometric application lock (SecurityController via androidx.biometric) supporting fingerprint and face authentication alongside incognito / secure screen protection (FLAG_SECURE).
◆ Add in-app GitHub Releases update checker (AppUpdateService), live project announcements feed (AnnouncementRepository), and user feedback submission service (FeedbackRepository).
