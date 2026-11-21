package id.yomic.mobile.engine.js

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/**
 * Per-invocation Jsoup DOM handle table exposed to QuickJS via primitive Int/String bridges.
 * Provides 100% behavioral parity with Desktop's AngleSharp JsDomWrappers.cs,
 * including uppercase `tagName` and absolute URL resolution.
 */
class JsoupDomBridge {
    private val nodes = ArrayList<Element>(256)
    private val baseUris = ArrayList<String>(256)

    fun parse(html: String, baseUri: String): Int {
        val doc = Jsoup.parse(html, baseUri)
        return register(doc, baseUri)
    }

    fun querySelector(handleId: Int, selector: String): Int {
        val el = getOrNull(handleId) ?: return -1
        if (selector.isBlank()) return -1
        val found = runCatching { el.selectFirst(selector) }.getOrNull() ?: return -1
        return register(found, baseUris[handleId])
    }

    fun querySelectorAllJson(handleId: Int, selector: String): String {
        val el = getOrNull(handleId) ?: return "[]"
        if (selector.isBlank()) return "[]"
        val matched = runCatching { el.select(selector) }.getOrNull() ?: return "[]"
        if (matched.isEmpty()) return "[]"
        val baseUri = baseUris[handleId]
        val ids = IntArray(matched.size) { idx -> register(matched[idx], baseUri) }
        return Json.encodeToString(ids)
    }

    fun text(handleId: Int): String {
        val el = getOrNull(handleId) ?: return ""
        return el.text().trim()
    }

    fun attr(handleId: Int, name: String): String {
        val el = getOrNull(handleId) ?: return ""
        return el.attr(name)
    }

    fun absUrl(handleId: Int, attrName: String): String {
        val el = getOrNull(handleId) ?: return ""
        val raw = el.attr(attrName)
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return raw
        }
        val jsoupAbs = el.absUrl(attrName)
        if (jsoupAbs.isNotEmpty()) return jsoupAbs

        val base = baseUris.getOrNull(handleId).orEmpty()
        if (base.isNotEmpty()) {
            val resolved = runCatching { URI(base).resolve(raw).toString() }.getOrNull()
            if (!resolved.isNullOrEmpty()) return resolved
        }
        return raw
    }

    fun html(handleId: Int, outer: Boolean): String {
        val el = getOrNull(handleId) ?: return ""
        return if (outer) el.outerHtml() else el.html()
    }

    fun tagName(handleId: Int): String {
        val el = getOrNull(handleId) ?: return ""
        // AngleSharp IElement.TagName is always uppercase (e.g. "IMG", "DIV")
        return el.tagName().uppercase(Locale.US)
    }

    fun parentElement(handleId: Int): Int {
        val el = getOrNull(handleId) ?: return -1
        val parent = el.parent() ?: return -1
        return register(parent, baseUris[handleId])
    }

    fun nextElementSibling(handleId: Int): Int {
        val el = getOrNull(handleId) ?: return -1
        val sibling = el.nextElementSibling() ?: return -1
        return register(sibling, baseUris[handleId])
    }

    fun clear() {
        nodes.clear()
        baseUris.clear()
    }

    private fun register(el: Element, baseUri: String): Int {
        val id = nodes.size
        nodes.add(el)
        baseUris.add(baseUri)
        return id
    }

    private fun getOrNull(handleId: Int): Element? =
        if (handleId in 0 until nodes.size) nodes[handleId] else null
}
