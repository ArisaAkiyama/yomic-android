package id.yomic.mobile.data.image

import java.net.URLDecoder

data class ParsedPipeUrl(
    val cleanUrl: String,
    val referer: String? = null,
    val omitReferer: Boolean = false,
    val userAgent: String? = null,
    val origin: String? = null,
    val aesKeyHex: String? = null,
    val aesIvHex: String? = null,
    val extraHeaders: Map<String, String> = emptyMap()
) {
    val requiresAesDecryption: Boolean
        get() = !aesKeyHex.isNullOrBlank() && !aesIvHex.isNullOrBlank()
}

/**
 * Parses Yomic extension pipe-delimited metadata URLs, e.g.:
 *   https://cdn.example.com/page1.jpg|Referer=https://site.com/|UserAgent=...|AesKey=...|AesIv=...
 * Also handles URLs where Coil/URI encoding turned `|` into `%7C`.
 */
object PipeHeaderParser {

    fun parse(rawUrl: String): ParsedPipeUrl {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ParsedPipeUrl(cleanUrl = "")

        val normalized = if (!trimmed.contains('|') && trimmed.contains("%7C", ignoreCase = true)) {
            trimmed.replace("%7C", "|", ignoreCase = true)
        } else {
            trimmed
        }

        if (!normalized.contains('|')) {
            return ParsedPipeUrl(cleanUrl = normalized)
        }

        val parts = normalized.split('|').filter { it.isNotBlank() }
        val cleanUrl = parts.first().trim()

        var referer: String? = null
        var omitReferer = false
        var userAgent: String? = null
        var origin: String? = null
        var aesKeyHex: String? = null
        var aesIvHex: String? = null
        val extras = mutableMapOf<String, String>()

        for (i in 1 until parts.size) {
            val pair = parts[i].split('=', limit = 2)
            if (pair.size != 2) continue
            val key = decodeIfEncoded(pair[0].trim())
            val value = decodeIfEncoded(pair[1].trim())
            if (key.isEmpty()) continue

            when {
                key.equals("Referer", ignoreCase = true) -> {
                    if (value.equals("none", ignoreCase = true) || value.equals("null", ignoreCase = true)) {
                        omitReferer = true
                        referer = null
                    } else if (value.isNotEmpty()) {
                        referer = value
                    }
                }
                key.equals("User-Agent", ignoreCase = true) || key.equals("UserAgent", ignoreCase = true) -> {
                    if (value.isNotEmpty()) userAgent = value
                }
                key.equals("Origin", ignoreCase = true) -> {
                    if (value.isNotEmpty()) origin = value
                }
                key.equals("AesKey", ignoreCase = true) -> {
                    if (value.isNotEmpty()) aesKeyHex = value
                }
                key.equals("AesIv", ignoreCase = true) -> {
                    if (value.isNotEmpty()) aesIvHex = value
                }
                else -> {
                    extras[key] = value
                }
            }
        }

        return ParsedPipeUrl(
            cleanUrl = cleanUrl,
            referer = referer,
            omitReferer = omitReferer,
            userAgent = userAgent,
            origin = origin,
            aesKeyHex = aesKeyHex,
            aesIvHex = aesIvHex,
            extraHeaders = extras
        )
    }

    private fun decodeIfEncoded(input: String): String {
        if (!input.contains('%')) return input
        return runCatching { URLDecoder.decode(input, "UTF-8") }.getOrDefault(input)
    }
}
