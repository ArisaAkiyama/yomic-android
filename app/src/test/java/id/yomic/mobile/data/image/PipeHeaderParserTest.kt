package id.yomic.mobile.data.image

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PipeHeaderParserTest {

    @Test
    fun parsesPlainUrlWithoutPipe() {
        val parsed = PipeHeaderParser.parse("https://cdn.example.com/ch1/001.jpg")
        assertEquals("https://cdn.example.com/ch1/001.jpg", parsed.cleanUrl)
        assertNull(parsed.referer)
        assertFalse(parsed.omitReferer)
        assertFalse(parsed.requiresAesDecryption)
    }

    @Test
    fun parsesRefererUserAgentOriginAndAesParams() {
        val url = "https://cdn.example.com/img.webp|Referer=https://komiku.org/|UserAgent=Yomic/2.1|Origin=https://komiku.org|AesKey=00112233445566778899aabbccddeeff|AesIv=ffeeddccbbaa99887766554433221100"
        val parsed = PipeHeaderParser.parse(url)
        assertEquals("https://cdn.example.com/img.webp", parsed.cleanUrl)
        assertEquals("https://komiku.org/", parsed.referer)
        assertEquals("Yomic/2.1", parsed.userAgent)
        assertEquals("https://komiku.org", parsed.origin)
        assertTrue(parsed.requiresAesDecryption)
        assertEquals("00112233445566778899aabbccddeeff", parsed.aesKeyHex)
        assertEquals("ffeeddccbbaa99887766554433221100", parsed.aesIvHex)
    }

    @Test
    fun parsesEncodedPipesAndRefererNone() {
        val encoded = "https://cdn.example.com/page.png%7CReferer=none%7CUser-Agent=CustomUA%2F1.0"
        val parsed = PipeHeaderParser.parse(encoded)
        assertEquals("https://cdn.example.com/page.png", parsed.cleanUrl)
        assertTrue(parsed.omitReferer)
        assertNull(parsed.referer)
        assertEquals("CustomUA/1.0", parsed.userAgent)
    }

    @Test
    fun interceptorStripsPipeInjectsHeadersAndDecryptsAesBody() {
        val server = MockWebServer()
        server.start()
        try {
            val plainPayload = "FAKE_WEBP_IMAGE_BYTES_1234567890".toByteArray(Charsets.UTF_8)
            val keyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
            val ivHex = "0f0e0d0c0b0a09080706050403020100"
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(AesImageDecryptor.hexToBytes(keyHex)!!, "AES"),
                IvParameterSpec(AesImageDecryptor.hexToBytes(ivHex)!!)
            )
            val encrypted = cipher.doFinal(plainPayload)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "image/webp")
                    .setBody(Buffer().write(encrypted))
            )

            val client = OkHttpClient.Builder()
                .addInterceptor(PipeHeaderOkHttpInterceptor { "DefaultUA/1.0" })
                .build()

            val targetUrl = server.url("/manga/page1.webp").toString()
            val pipedUrl = "$targetUrl%7CReferer=https://reader.example.com/%7CUserAgent=YomicTest/2.1%7CAesKey=$keyHex%7CAesIv=$ivHex"

            val response = client.newCall(Request.Builder().url(pipedUrl).build()).execute()
            assertEquals(200, response.code)
            assertArrayEquals(plainPayload, response.body!!.bytes())

            val recorded = server.takeRequest()
            assertEquals("/manga/page1.webp", recorded.path)
            assertEquals("https://reader.example.com/", recorded.getHeader("Referer"))
            assertEquals("YomicTest/2.1", recorded.getHeader("User-Agent"))
        } finally {
            server.shutdown()
        }
    }
}
