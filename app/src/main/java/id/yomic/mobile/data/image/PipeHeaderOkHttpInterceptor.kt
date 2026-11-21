package id.yomic.mobile.data.image

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * OkHttp interceptor for Coil 3 & Download Worker image requests:
 * 1. Strips pipe metadata (`|Referer=...|UserAgent=...|AesKey=...|AesIv=...`) from the URL.
 * 2. Applies Referer, Origin, User-Agent, Accept, and Sec-Fetch-* headers matching Yomic Desktop.
 * 3. Transparently decrypts AES-256-CBC responses when `AesKey` & `AesIv` are supplied.
 */
class PipeHeaderOkHttpInterceptor(
    private val defaultUserAgentProvider: () -> String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val parsed = PipeHeaderParser.parse(original.url.toString())
        val targetHttpUrl = parsed.cleanUrl.toHttpUrlOrNull() ?: original.url

        val reqBuilder = original.newBuilder().url(targetHttpUrl)

        // Referer
        if (parsed.omitReferer) {
            reqBuilder.removeHeader("Referer")
        } else if (!parsed.referer.isNullOrBlank()) {
            reqBuilder.header("Referer", parsed.referer)
        } else if (original.header("Referer").isNullOrBlank()) {
            val defaultRef = "${targetHttpUrl.scheme}://${targetHttpUrl.host}/"
            reqBuilder.header("Referer", defaultRef)
        }

        // Origin
        if (!parsed.origin.isNullOrBlank()) {
            reqBuilder.header("Origin", parsed.origin)
        }

        // User-Agent
        val ua = parsed.userAgent?.takeIf { it.isNotBlank() } ?: defaultUserAgentProvider()
        reqBuilder.header("User-Agent", ua)

        // Sec-Fetch headers (matching WebResourceImageProxy.cs)
        if (parsed.cleanUrl.contains("mangamillion", ignoreCase = true)) {
            reqBuilder.header("Sec-Fetch-Dest", "empty")
            reqBuilder.header("Sec-Fetch-Mode", "cors")
            reqBuilder.header("Sec-Fetch-Site", "same-site")
            if (parsed.origin.isNullOrBlank()) {
                reqBuilder.header("Origin", "https://mangamillion.shueisha.co.jp")
            }
        } else {
            reqBuilder.header("Sec-Fetch-Dest", "image")
            reqBuilder.header("Sec-Fetch-Mode", "no-cors")
            reqBuilder.header("Sec-Fetch-Site", "cross-site")
        }

        if (original.header("Accept").isNullOrBlank()) {
            reqBuilder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        }

        parsed.extraHeaders.forEach { (k, v) ->
            reqBuilder.header(k, v)
        }

        val response = chain.proceed(reqBuilder.build())
        if (!response.isSuccessful || !parsed.requiresAesDecryption) {
            return response
        }

        val rawBody = response.body ?: return response
        val encrypted = rawBody.bytes()
        val decrypted = AesImageDecryptor.decryptAesCbc(
            encryptedBytes = encrypted,
            hexKey = parsed.aesKeyHex.orEmpty(),
            hexIv = parsed.aesIvHex.orEmpty()
        )
        val mediaType = rawBody.contentType()
        return response.newBuilder()
            .body(decrypted.toResponseBody(mediaType))
            .build()
    }
}
