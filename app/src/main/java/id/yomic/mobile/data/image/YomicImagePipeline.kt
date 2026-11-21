package id.yomic.mobile.data.image

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import id.yomic.mobile.data.network.YomicNetworkClient
import okhttp3.Call
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.Locale

/**
 * Manages the Coil 3 ImageLoader backed by YomicNetworkClient (DoH + Cookies + Pipe Headers + AES Decrypt)
 * and dynamic disk cache limits configured from AppSettingsState.
 */
class YomicImagePipeline(
    private val context: Context,
    private val networkClient: YomicNetworkClient
) {
    private val cacheDir: File = File(context.cacheDir, "yomic_image_cache").apply { mkdirs() }
    private val coversDir: File = File(context.filesDir, "covers").apply { mkdirs() }

    @Volatile
    private var maxDiskBytes: Long = parseCacheSizeBytes("500 MB")

    @Volatile
    var imageLoader: ImageLoader = buildImageLoader(maxDiskBytes)
        private set

    @OptIn(DelicateCoilApi::class)
    @Synchronized
    fun updateCacheLimit(limitLabel: String) {
        val nextBytes = parseCacheSizeBytes(limitLabel)
        if (nextBytes == maxDiskBytes) return
        maxDiskBytes = nextBytes
        val previous = imageLoader
        val next = buildImageLoader(nextBytes)
        imageLoader = next
        runCatching { SingletonImageLoader.setUnsafe(next) }
        runCatching { previous.memoryCache?.clear() }
    }

    fun computeCacheSizeBytes(): Long =
        directorySizeBytes(cacheDir) + directorySizeBytes(coversDir)

    fun computeCacheSizeText(): String = formatBytes(computeCacheSizeBytes())

    fun clearAllCaches() {
        runCatching { imageLoader.memoryCache?.clear() }
        runCatching { imageLoader.diskCache?.clear() }
        cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        coversDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun buildImageLoader(diskLimitBytes: Long): ImageLoader =
        ImageLoader.Builder(context.applicationContext)
            .components {
                add(CbzPageKeyer())
                add(CbzPageFetcher.Factory())
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            Call.Factory { request -> networkClient.imageClient.newCall(request) }
                        }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context.applicationContext, 0.22)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.toOkioPath())
                    .maxSizeBytes(diskLimitBytes)
                    .build()
            }
            .build()

    companion object {
        fun parseCacheSizeBytes(label: String): Long {
            val clean = label.trim().uppercase(Locale.US)
            return when {
                clean.startsWith("250") -> 250L * 1024L * 1024L
                clean.startsWith("500") -> 500L * 1024L * 1024L
                clean.startsWith("1") && clean.contains("GB") -> 1024L * 1024L * 1024L
                clean.startsWith("2") && clean.contains("GB") -> 2L * 1024L * 1024L * 1024L
                else -> 500L * 1024L * 1024L
            }
        }

        fun directorySizeBytes(dir: File): Long {
            if (!dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0.0 MB"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format(Locale.US, "%.2f GB", mb / 1024.0)
            } else {
                String.format(Locale.US, "%.1f MB", mb)
            }
        }
    }
}
