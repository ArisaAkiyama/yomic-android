package id.yomic.mobile.engine.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsoupDomBridgeTest {

    @Test
    fun parsesHtmlAndSupportsFullDesktopDomSurface() {
        val html = """
            <html>
              <body>
                <div class="list">
                  <div class="animepost">
                    <a href="/komik/solo-leveling/" title="Solo Leveling"><h4>Solo Leveling</h4></a>
                    <img data-src="/img/cover1.webp" />
                  </div>
                  <div class="animepost">
                    <a href="https://cdn.example.com/komik/omniscient/"><h4>Omniscient Reader</h4></a>
                    <img src="https://cdn.example.com/cover2.jpg" />
                  </div>
                </div>
              </body>
            </html>
        """.trimIndent()

        val bridge = JsoupDomBridge()
        try {
            val doc = bridge.parse(html, "https://bacakomik.my/daftar-komik/")
            assertTrue(doc >= 0)

            val allJson = bridge.querySelectorAllJson(doc, "div.animepost")
            val ids = allJson.removePrefix("[").removeSuffix("]").split(",").map { it.trim().toInt() }
            assertEquals(2, ids.size)

            val firstLink = bridge.querySelector(ids[0], "a")
            assertEquals("A", bridge.tagName(firstLink))
            assertEquals("https://bacakomik.my/komik/solo-leveling/", bridge.absUrl(firstLink, "href"))
            assertEquals("Solo Leveling", bridge.text(firstLink))

            val img = bridge.nextElementSibling(firstLink)
            assertEquals("IMG", bridge.tagName(img))
            assertEquals("https://bacakomik.my/img/cover1.webp", bridge.absUrl(img, "data-src"))

            val parent = bridge.parentElement(img)
            assertEquals("DIV", bridge.tagName(parent))
            assertTrue(bridge.html(parent, outer = true).contains("animepost"))
            assertTrue(bridge.html(parent, outer = false).contains("Solo Leveling"))
        } finally {
            bridge.clear()
        }
    }
}
