package id.yomic.mobile.data.repository

import id.yomic.mobile.data.local.AnnouncementEntity
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementRepositoryTest {

    @Test
    fun extractsCommitShaFromAtomXmlAndComputesReadState() {
        val sha = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"
        val atom = """
            <feed>
              <entry>
                <id>tag:github.com,2008:Grit::Commit/$sha</id>
              </entry>
            </feed>
        """.trimIndent()
        assertEquals(sha, AnnouncementRepository.extractCommitSha(atom))

        val items = listOf(
            AnnouncementEntity("ann-3", "Latest", "Body 3", "2026-11-21", "update", "", 0),
            AnnouncementEntity("ann-2", "Older", "Body 2", "2026-11-18", "info", "", 1),
            AnnouncementEntity("ann-1", "Oldest", "Body 1", "2026-11-10", "warning", "", 2)
        )

        val unreadBefore = AnnouncementRepository.mapWithReadState(items, lastReadId = "ann-2")
        assertTrue(unreadBefore[0].isUnread)
        assertFalse(unreadBefore[1].isUnread)
        assertFalse(unreadBefore[2].isUnread)

        val allRead = AnnouncementRepository.mapWithReadState(items, lastReadId = "ann-3")
        assertTrue(allRead.none { it.isUnread })
    }

    @Test
    fun fetchesCommitShaThenBypassesCdnToLoadAnnouncementsJson() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("").toString().trimEnd('/')
            val sha = "deadbeef0123456789abcdef0123456789abcdef"

            server.enqueue(
                MockResponse().setBody(
                    """<feed><entry><link href="https://github.com/ArisaAkiyama/yomic/commit/$sha"/></entry></feed>"""
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    [
                      {
                        "id": "rel-2.2",
                        "title": "Yomic Android Native",
                        "body": "Dynamic JS extensions + CBZ offline downloads.",
                        "date": "2026-11-21",
                        "type": "update",
                        "url": "https://github.com/ArisaAkiyama/yomic"
                      }
                    ]
                    """.trimIndent()
                )
            )

            val repo = AnnouncementRepository(
                httpClient = OkHttpClient(),
                announcementDao = null,
                settingsRepository = null,
                commitsAtomUrl = "$base/commits/master.atom",
                rawCommitTemplate = "$base/raw/%s/announcements.json",
                rawFallbackUrl = "$base/raw/master/announcements.json"
            )

            val result = repo.refreshAnnouncements(force = true)
            assertEquals(1, result.size)
            assertEquals("rel-2.2", result[0].id)
            assertEquals("Update", result[0].typeLabel)
            assertEquals("https://github.com/ArisaAkiyama/yomic", result[0].url)

            val req1 = server.takeRequest()
            assertTrue(req1.path!!.startsWith("/commits/master.atom"))
            val req2 = server.takeRequest()
            assertEquals("/raw/$sha/announcements.json", req2.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun dynamicallySwitchesHttpClientWhenProviderChanges() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("").toString().trimEnd('/')
            val clientA = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Doh-Tag", "A").build())
            }.build()
            val clientB = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Doh-Tag", "B").build())
            }.build()
            var active = clientA

            val repo = AnnouncementRepository(
                httpClientProvider = { active },
                announcementDao = null,
                settingsRepository = null,
                commitsAtomUrl = "$base/commits.atom",
                rawCommitTemplate = "$base/raw/%s/announcements.json",
                rawFallbackUrl = "$base/raw/master/announcements.json"
            )

            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""[{"id":"1","title":"A","body":"B","date":"2026-11-21","type":"info"}]"""))
            assertEquals(1, repo.refreshAnnouncements(force = true).size)
            assertEquals("A", server.takeRequest().getHeader("X-Doh-Tag"))
            assertEquals("A", server.takeRequest().getHeader("X-Doh-Tag"))

            active = clientB
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""[{"id":"2","title":"C","body":"D","date":"2026-11-21","type":"info"}]"""))
            assertEquals(1, repo.refreshAnnouncements(force = true).size)
            assertEquals("B", server.takeRequest().getHeader("X-Doh-Tag"))
            assertEquals("B", server.takeRequest().getHeader("X-Doh-Tag"))
        } finally {
            server.shutdown()
        }
    }
}
