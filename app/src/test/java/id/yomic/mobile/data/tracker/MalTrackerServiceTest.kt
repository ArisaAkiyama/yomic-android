package id.yomic.mobile.data.tracker

import id.yomic.mobile.data.preferences.MalAuthSnapshot
import id.yomic.mobile.data.preferences.MalTokenStorage
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemoryMalTokenStorage(
    initial: MalAuthSnapshot = MalAuthSnapshot()
) : MalTokenStorage {
    var snapshot: MalAuthSnapshot = initial
    override fun getMalAuth(): MalAuthSnapshot = snapshot
    override fun saveMalAuth(accessToken: String, refreshToken: String, expiresAtEpochMs: Long, username: String) {
        snapshot = MalAuthSnapshot(accessToken, refreshToken, expiresAtEpochMs, username)
    }
    override fun updateMalUsername(username: String) {
        snapshot = snapshot.copy(username = username)
    }
    override fun clearMalAuth() {
        snapshot = MalAuthSnapshot()
    }
}

class MalTrackerServiceTest {

    @Test
    fun generatesValidPkceVerifierAndDesktopCompatibleAuthUrl() {
        val verifier = MalTrackerService.generateCodeVerifier()
        assertEquals(128, verifier.length)
        assertTrue(verifier.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~" })

        val service = MalTrackerService(OkHttpClient(), InMemoryMalTokenStorage())
        val authUrl = service.buildAuthorizationUrl(verifier)
        assertTrue(authUrl.startsWith("https://myanimelist.net/v1/oauth2/authorize?"))
        assertTrue(authUrl.contains("client_id=${MalTrackerService.CLIENT_ID}"))
        assertTrue(authUrl.contains("code_challenge=$verifier"))
        assertTrue(authUrl.contains("code_challenge_method=plain"))
        assertTrue(authUrl.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A49152%2F"))

        assertEquals("plan_to_read", MalTrackerService.toMalApiStatus("Plan to Read"))
        assertEquals("on_hold", MalTrackerService.toMalApiStatus("On Hold"))
        assertEquals("Completed", MalTrackerService.toDisplayStatus("completed"))
    }

    @Test
    fun authenticatesFetchesProfileSearchesAndUpdatesMangaStatus() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val store = InMemoryMalTokenStorage()
            val fixedNow = 1_700_000_000_000L
            val service = MalTrackerService(
                httpClient = OkHttpClient(),
                tokenStore = store,
                tokenUrl = "$baseUrl/oauth2/token",
                apiBaseUrl = "$baseUrl/v2",
                nowProvider = { fixedNow }
            )

            // 1. Authenticate
            server.enqueue(
                MockResponse().setBody(
                    """{"access_token":"acc_123","refresh_token":"ref_456","expires_in":3600}"""
                )
            )
            assertTrue(service.authenticate("code_xyz", "verifier_abc"))
            assertEquals("acc_123", store.snapshot.accessToken)
            assertEquals("ref_456", store.snapshot.refreshToken)
            assertEquals(fixedNow + 3_600_000L, store.snapshot.expiresAtEpochMs)

            // 2. Fetch profile
            server.enqueue(MockResponse().setBody("""{"id":99,"name":"ArisaReader"}"""))
            val user = service.fetchUserProfileName()
            assertEquals("ArisaReader", user)
            assertEquals("ArisaReader", store.snapshot.username)
            val profileReq = server.takeRequest() // auth
            assertEquals("/oauth2/token", profileReq.path)
            val meReq = server.takeRequest()
            assertEquals("/v2/users/@me", meReq.path)
            assertEquals("Bearer acc_123", meReq.getHeader("Authorization"))

            // 3. Search manga
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "data": [
                        {
                          "node": {
                            "id": 121496,
                            "title": "Solo Leveling",
                            "media_type": "manhwa",
                            "num_chapters": 200,
                            "mean": 8.66,
                            "main_picture": { "medium": "https://cdn.myanimelist.net/solo.jpg" }
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
            )
            val results = service.searchManga("Solo Leveling")
            assertEquals(1, results.size)
            assertEquals(121496, results[0].id)
            assertEquals("Manhwa", results[0].mediaType)
            assertEquals(200, results[0].chapters)
            assertEquals("https://cdn.myanimelist.net/solo.jpg", results[0].coverUrl)
            server.takeRequest()

            // 4. Fetch manga status
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "id": 121496,
                      "title": "Solo Leveling",
                      "num_chapters": 200,
                      "my_list_status": { "status": "reading", "score": 9, "num_chapters_read": 45 }
                    }
                    """.trimIndent()
                )
            )
            val track = service.fetchMangaStatus(121496L, "local_m1")
            assertNotNull(track)
            assertEquals("Reading", track!!.status)
            assertEquals(9, track.score)
            assertEquals(45, track.chaptersRead)
            assertEquals(200, track.totalChapters)
            server.takeRequest()

            // 5. Update manga status (PATCH)
            server.enqueue(MockResponse().setBody("""{"status":"completed","score":10,"num_chapters_read":200}"""))
            val updated = service.updateMangaStatus(121496L, "Completed", 200, 10)
            assertTrue(updated)
            val patchReq = server.takeRequest()
            assertEquals("PATCH", patchReq.method)
            assertEquals("/v2/manga/121496/my_list_status", patchReq.path)
            val patchBody = patchReq.body.readUtf8()
            assertTrue(patchBody.contains("status=completed"))
            assertTrue(patchBody.contains("num_chapters_read=200"))
            assertTrue(patchBody.contains("score=10"))

            service.disconnect()
            assertFalse(service.isConnected)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun autoRefreshesTokenWithinFiveMinuteMarginBeforeApiCall() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val now = 1_700_000_000_000L
            // Expires in 2 minutes (< 5 minute refresh margin)
            val store = InMemoryMalTokenStorage(
                MalAuthSnapshot(
                    accessToken = "old_access",
                    refreshToken = "valid_refresh",
                    expiresAtEpochMs = now + 120_000L,
                    username = "Arisa"
                )
            )
            val service = MalTrackerService(
                httpClient = OkHttpClient(),
                tokenStore = store,
                tokenUrl = "$baseUrl/oauth2/token",
                apiBaseUrl = "$baseUrl/v2",
                nowProvider = { now }
            )

            server.enqueue(
                MockResponse().setBody(
                    """{"access_token":"fresh_access_999","refresh_token":"fresh_ref_999","expires_in":7200}"""
                )
            )
            server.enqueue(MockResponse().setBody("""{"name":"ArisaRefreshed"}"""))

            val name = service.fetchUserProfileName()
            assertEquals("ArisaRefreshed", name)
            assertEquals("fresh_access_999", store.snapshot.accessToken)
            assertEquals("fresh_ref_999", store.snapshot.refreshToken)

            val refreshReq = server.takeRequest()
            assertEquals("/oauth2/token", refreshReq.path)
            assertTrue(refreshReq.body.readUtf8().contains("grant_type=refresh_token"))

            val apiReq = server.takeRequest()
            assertEquals("Bearer fresh_access_999", apiReq.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun dynamicallySwitchesHttpClientWhenProviderChanges() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val store = InMemoryMalTokenStorage(
                MalAuthSnapshot("tok", "ref", System.currentTimeMillis() + 3_600_000L, "User")
            )
            val clientA = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Doh-Id", "A").build())
            }.build()
            val clientB = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Doh-Id", "B").build())
            }.build()
            var active = clientA

            val service = MalTrackerService(
                httpClientProvider = { active },
                tokenStore = store,
                apiBaseUrl = "$baseUrl/v2"
            )

            server.enqueue(MockResponse().setBody("""{"name":"UserA"}"""))
            assertEquals("UserA", service.fetchUserProfileName())
            assertEquals("A", server.takeRequest().getHeader("X-Doh-Id"))

            active = clientB
            server.enqueue(MockResponse().setBody("""{"name":"UserB"}"""))
            assertEquals("UserB", service.fetchUserProfileName())
            assertEquals("B", server.takeRequest().getHeader("X-Doh-Id"))
        } finally {
            server.shutdown()
        }
    }
}
