package id.yomic.mobile.data.repository

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackRepositoryTest {

    @Test
    fun treatsGoogleAppsScriptRedirectToGoogleUserContentAsSuccess() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://script.googleusercontent.com/macros/echo?user_content_key=abc")
            )

            val repo = FeedbackRepository(
                baseHttpClient = OkHttpClient(),
                webhookUrl = server.url("/exec").toString()
            )
            val res = repo.submitFeedback(
                category = "bug",
                categoryLabel = "Laporan Bug",
                message = "Test bug report dari Android Native",
                language = "id",
                appVersion = "2.1.0",
                osVersion = "Android 15",
                screenshot = AttachedFeedbackScreenshot("shot.jpg", "image/jpeg", "RkFLRQ==")
            )

            assertTrue(res.success)
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            val body = req.body.readUtf8()
            assertTrue(body.contains(""""targetOwnerEmail":"info.yomic@gmail.com""""))
            assertTrue(body.contains(""""spreadsheetName":"Yomic - Masukan Pengguna""""))
            assertTrue(body.contains(""""hasScreenshot":true"""))
            assertTrue(body.contains(""""screenshotBase64":"RkFLRQ==""""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rejectsEmptyMessageAndUnexpectedServerErrors() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val repo = FeedbackRepository(
                baseHttpClient = OkHttpClient(),
                webhookUrl = server.url("/exec").toString()
            )

            val emptyRes = repo.submitFeedback("bug", "Bug", "   ", "en")
            assertFalse(emptyRes.success)
            assertEquals(0, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Error"))
            val errRes = repo.submitFeedback("general", "General", "Valid message", "en")
            assertFalse(errRes.success)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun dynamicallySwitchesHttpClientWhenProviderChanges() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val clientA = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Doh-Epoch", "A").build())
            }.build()
            val clientB = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Doh-Epoch", "B").build())
            }.build()
            var activeClient = clientA

            val repo = FeedbackRepository(
                httpClientProvider = { activeClient },
                webhookUrl = server.url("/exec").toString()
            )

            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://script.googleusercontent.com/ok1"))
            assertTrue(repo.submitFeedback("bug", "Bug", "Msg 1", "id").success)
            assertEquals("A", server.takeRequest().getHeader("X-Doh-Epoch"))

            activeClient = clientB
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://script.googleusercontent.com/ok2"))
            assertTrue(repo.submitFeedback("bug", "Bug", "Msg 2", "id").success)
            assertEquals("B", server.takeRequest().getHeader("X-Doh-Epoch"))
        } finally {
            server.shutdown()
        }
    }
}
