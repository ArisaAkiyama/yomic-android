package id.yomic.mobile.data.repository

import android.content.Context
import coil3.request.ImageRequest
import id.yomic.mobile.data.image.CbzPageData
import id.yomic.mobile.data.image.YomicImagePipeline
import id.yomic.mobile.data.local.ChapterDao
import id.yomic.mobile.data.local.MangaDao
import id.yomic.mobile.engine.js.JsExtensionManager
import id.yomic.mobile.model.ReaderPageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

sealed class ReaderLoadResult {
    data class Success(val pages: List<ReaderPageItem>) : ReaderLoadResult()
    data class RequiresCloudflare(val challengeUrl: String) : ReaderLoadResult()
    data class Error(val message: String) : ReaderLoadResult()
}

/**
 * Loads chapter page lists offline-first (folder images or `.cbz` archives)
 * and falls back to online JS `getPageList` (+ WebView extractor / Cloudflare signal).
 * Also preloads upcoming chapter pages into Coil's disk cache when enabled.
 */
class ReaderRepository(
    private val context: Context?,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val extensionManager: JsExtensionManager,
    private val imagePipeline: YomicImagePipeline?
) {

    suspend fun loadChapterPages(mangaId: String, chapterNumber: Double): ReaderLoadResult =
        withContext(Dispatchers.IO) {
            val manga = mangaDao.getById(mangaId)
                ?: return@withContext ReaderLoadResult.Error("Komik tidak ditemukan.")
            val chapter = chapterDao.getByMangaAndNumber(mangaId, chapterNumber)
                ?: return@withContext ReaderLoadResult.Error("Bab $chapterNumber tidak ditemukan.")

            // 1. Offline-first: read downloaded folder or CBZ archive
            if (chapter.isDownloaded && chapter.localPath.isNotBlank()) {
                val localPages = loadLocalPages(chapter.localPath)
                if (localPages.isNotEmpty()) {
                    chapterDao.updatePageProgress(mangaId, chapterNumber, chapter.lastPageRead, localPages.size)
                    return@withContext ReaderLoadResult.Success(localPages)
                }
            }

            // 2. Online JS fetch
            val source = extensionManager.getSource(manga.sourcePkg)
                ?: extensionManager.getSourceByNumericId(manga.numericSourceId)
                ?: return@withContext ReaderLoadResult.Error(
                    "Ekstensi '${manga.sourceName.ifBlank { manga.sourcePkg }}' belum terpasang."
                )

            if (chapter.url.isBlank()) {
                return@withContext ReaderLoadResult.Error("URL bab kosong.")
            }

            return@withContext try {
                val res = source.getPageListWithStatus(chapter.url)
                if (res.pages.isNotEmpty()) {
                    val baseReferer = source.baseUrl.trimEnd('/').takeIf { it.isNotBlank() }?.plus("/") ?: ""
                    val items = res.pages.mapIndexed { idx, rawUrl ->
                        val withReferer = if (baseReferer.isNotEmpty() && !rawUrl.contains("|Referer=", ignoreCase = true)) {
                            "$rawUrl|Referer=$baseReferer"
                        } else rawUrl
                        ReaderPageItem(
                            index = idx + 1,
                            urlOrPath = withReferer,
                            isLocalFile = false,
                            referer = baseReferer,
                            userAgent = source.metadata.userAgent.orEmpty()
                        )
                    }
                    chapterDao.updatePageProgress(mangaId, chapterNumber, chapter.lastPageRead, items.size)
                    ReaderLoadResult.Success(items)
                } else if (res.requiresVerification || source.metadata.isCloudflareProtected) {
                    val absUrl = resolveAbsoluteUrl(source.baseUrl, chapter.url)
                    ReaderLoadResult.RequiresCloudflare(absUrl)
                } else {
                    ReaderLoadResult.Error("Daftar halaman kosong dari ${source.name}.")
                }
            } catch (e: Exception) {
                ReaderLoadResult.Error(e.message ?: "Gagal memuat halaman bab.")
            }
        }

    suspend fun preloadNextChapter(mangaId: String, nextChapterNumber: Double) = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext
        val pipeline = imagePipeline ?: return@withContext
        val result = runCatching { loadChapterPages(mangaId, nextChapterNumber) }.getOrNull()
        if (result is ReaderLoadResult.Success) {
            result.pages.take(3).forEach { page ->
                val data: Any = when {
                    page.isCbzEntry -> CbzPageData(page.cbzArchivePath, page.cbzEntryName)
                    page.isLocalFile -> File(page.urlOrPath)
                    else -> page.urlOrPath
                }
                val req = ImageRequest.Builder(ctx)
                    .data(data)
                    .build()
                pipeline.imageLoader.enqueue(req)
            }
        }
    }

    companion object {
        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "avif", "gif", "bmp")

        fun isImageFileName(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in imageExtensions
        }

        fun loadLocalPages(localPath: String): List<ReaderPageItem> {
            val target = File(localPath)
            if (!target.exists()) return emptyList()

            if (target.isFile && target.extension.equals("cbz", ignoreCase = true)) {
                return runCatching {
                    ZipFile(target).use { zip ->
                        val entries = zip.entries().toList()
                            .filter { !it.isDirectory && isImageFileName(it.name) }
                            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                        entries.mapIndexed { idx, entry ->
                            ReaderPageItem(
                                index = idx + 1,
                                urlOrPath = "${target.absolutePath}#${entry.name}",
                                isLocalFile = true,
                                isCbzEntry = true,
                                cbzArchivePath = target.absolutePath,
                                cbzEntryName = entry.name
                            )
                        }
                    }
                }.getOrDefault(emptyList())
            }

            if (target.isDirectory) {
                val files = target.listFiles()
                    ?.filter { it.isFile && isImageFileName(it.name) }
                    ?.sortedWith { a, b -> naturalCompare(a.name, b.name) }
                    ?: return emptyList()
                return files.mapIndexed { idx, f ->
                    ReaderPageItem(
                        index = idx + 1,
                        urlOrPath = f.absolutePath,
                        isLocalFile = true
                    )
                }
            }
            return emptyList()
        }

        fun resolveAbsoluteUrl(baseUrl: String, relativeOrAbs: String): String {
            if (relativeOrAbs.startsWith("http://", ignoreCase = true) ||
                relativeOrAbs.startsWith("https://", ignoreCase = true)
            ) {
                return relativeOrAbs
            }
            return baseUrl.trimEnd('/') + "/" + relativeOrAbs.trimStart('/')
        }

        /**
         * Natural numeric ordering so "2.jpg" precedes "10.jpg" and "page_002.webp" precedes "page_010.webp".
         */
        fun naturalCompare(a: String, b: String): Int {
            var ia = 0
            var ib = 0
            val la = a.length
            val lb = b.length
            while (ia < la && ib < lb) {
                val ca = a[ia]
                val cb = b[ib]
                if (ca.isDigit() && cb.isDigit()) {
                    var na = 0L
                    while (ia < la && a[ia].isDigit()) {
                        na = na * 10 + (a[ia] - '0')
                        ia++
                    }
                    var nb = 0L
                    while (ib < lb && b[ib].isDigit()) {
                        nb = nb * 10 + (b[ib] - '0')
                        ib++
                    }
                    if (na != nb) return na.compareTo(nb)
                } else {
                    val diff = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (diff != 0) return diff
                    ia++
                    ib++
                }
            }
            return (la - ia).compareTo(lb - ib)
        }
    }
}
