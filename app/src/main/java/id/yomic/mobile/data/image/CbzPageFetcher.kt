package id.yomic.mobile.data.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import okio.Buffer
import okio.FileSystem
import java.io.File
import java.util.zip.ZipFile

data class CbzPageData(
    val archivePath: String,
    val entryName: String
)

class CbzPageKeyer : Keyer<CbzPageData> {
    override fun key(data: CbzPageData, options: Options): String =
        "cbz://${data.archivePath}#${data.entryName}"
}

/**
 * Streams a single comic page image directly out of a `.cbz` ZIP archive into Coil 3
 * without extracting temporary files to disk.
 */
class CbzPageFetcher(
    private val data: CbzPageData
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val file = File(data.archivePath)
        require(file.exists()) { "CBZ archive not found: ${data.archivePath}" }

        val bytes = ZipFile(file).use { zip ->
            val entry = zip.getEntry(data.entryName)
                ?: throw IllegalStateException("CBZ entry not found: ${data.entryName}")
            zip.getInputStream(entry).use { it.readBytes() }
        }

        val buffer = Buffer().write(bytes)
        val mime = guessMimeType(data.entryName)
        return SourceFetchResult(
            source = ImageSource(source = buffer, fileSystem = FileSystem.SYSTEM),
            mimeType = mime,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<CbzPageData> {
        override fun create(data: CbzPageData, options: Options, imageLoader: ImageLoader): Fetcher =
            CbzPageFetcher(data)
    }

    companion object {
        fun guessMimeType(name: String): String {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".avif") -> "image/avif"
                else -> "image/jpeg"
            }
        }
    }
}
