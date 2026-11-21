package id.yomic.mobile.engine.js

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import id.yomic.mobile.data.network.YomicNetworkClient
import id.yomic.mobile.engine.webview.WebViewPageExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class JsSourceMetadata(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val apiUrl: String,
    val language: String,
    val version: String,
    val description: String,
    val author: String,
    val iconUrl: String,
    val iconBackground: String,
    val iconForeground: String,
    val isNsfw: Boolean,
    val isHasMorePages: Boolean,
    val requiresProxy: Boolean,
    val isCloudflareProtected: Boolean,
    val chapterReadySelector: String,
    val pageWebViewExtractScript: String?,
    val userAgent: String?,
    val genres: List<String>,
    val formats: List<String>,
    val supportsMangaList: Boolean,
    val supportsLatestChapters: Boolean
)

data class RawJsManga(
    val title: String,
    val url: String,
    val thumbnailUrl: String,
    val status: Int = 0,
    val author: String = "",
    val artist: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val type: String = ""
)

data class PagedJsMangaResult(
    val items: List<RawJsManga>,
    val totalPages: Int,
    val hasNextPage: Boolean
)

data class RawJsChapter(
    val name: String,
    val url: String,
    val chapterNumber: Double,
    val dateUploadEpoch: Long = 0L,
    val scanlator: String = ""
)

data class ChapterListCacheResult(
    val isNotModified: Boolean = false,
    val isPartial: Boolean = false,
    val chapters: List<RawJsChapter> = emptyList(),
    val eTag: String? = null,
    val lastModified: String? = null
)

data class PageListResult(
    val pages: List<String>,
    val requiresVerification: Boolean = false
)

/**
 * Executes a single `ArisaAkiyama/extension-yomic` JavaScript source using QuickJS + Jsoup + OkHttp.
 * 100% API parity with Yomic Desktop's `JsMangaSource.cs`.
 */
class JsExtensionSource private constructor(
    val pkg: String,
    val scriptFileName: String,
    private val scriptCode: String,
    val metadata: JsSourceMetadata,
    private val networkClient: YomicNetworkClient,
    private val webViewExtractor: WebViewPageExtractor? = null
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val runtimeMutex = Mutex()
    private val domBridge = JsoupDomBridge()
    private val runtimeCreations = AtomicInteger(0)
    private val closeRequested = AtomicBoolean(false)

    @Volatile
    private var activeCacheCtx: CacheContext? = null

    @Volatile
    private var activeHttpCall: okhttp3.Call? = null

    private var warmDispatcher: ExecutorCoroutineDispatcher? = null
    private var warmQuickJs: QuickJs? = null

    internal val runtimeCreationCount: Int
        get() = runtimeCreations.get()

    @Volatile
    var selectedLanguage: String = "en"

    val id: Long get() = metadata.id
    val name: String get() = metadata.name
    val baseUrl: String get() = metadata.baseUrl
    val language: String get() = metadata.language
    val version: String get() = metadata.version
    val genres: List<String> get() = metadata.genres
    val formats: List<String> get() = metadata.formats

    private data class CacheContext(
        val ifNoneMatch: String? = null,
        val ifModifiedSince: String? = null,
        var extractedETag: String? = null,
        var extractedLastModified: String? = null,
        var received304: Boolean = false
    )

    @Serializable
    private data class FetchResponseDto(
        val status: Int,
        val body: String
    )

    suspend fun getPopularManga(page: Int): PagedJsMangaResult {
        val resp = invokeMethod("getPopularManga", listOf(JsonPrimitive(page)))
        return parsePagedMangaList(resp.result, page)
    }

    suspend fun getLatestUpdates(page: Int): PagedJsMangaResult {
        val resp = invokeMethod("getLatestUpdates", listOf(JsonPrimitive(page)))
        return parsePagedMangaList(resp.result, page)
    }

    suspend fun getSearchManga(query: String, page: Int): PagedJsMangaResult {
        val primary = invokeMethod("getSearchManga", listOf(JsonPrimitive(query), JsonPrimitive(page)))
        if (!primary.missing) {
            return parsePagedMangaList(primary.result, page)
        }
        val fallback = invokeMethod("searchManga", listOf(JsonPrimitive(query), JsonPrimitive(page)))
        return parsePagedMangaList(fallback.result, page)
    }

    suspend fun getMangaList(
        page: Int,
        status: Int = 0,
        genres: List<String>? = null,
        formats: List<String>? = null,
        sort: String? = null
    ): PagedJsMangaResult {
        if (metadata.supportsMangaList) {
            val args = listOf<JsonElement>(
                JsonPrimitive(page),
                JsonPrimitive(status),
                genres?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull,
                formats?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull,
                sort?.let(::JsonPrimitive) ?: JsonNull
            )
            val resp = invokeMethod("getMangaList", args)
            if (!resp.missing) {
                return parsePagedMangaList(resp.result, page)
            }
        }
        return if (sort.equals("latest", ignoreCase = true)) {
            getLatestUpdates(page)
        } else {
            getPopularManga(page)
        }
    }

    suspend fun getMangaDetails(mangaUrl: String): RawJsManga? {
        val resp = invokeMethod("getMangaDetails", listOf(JsonPrimitive(mangaUrl)))
        val obj = resp.result as? JsonObject ?: return null
        val thumb = appendSourceUserAgent(obj.str("thumbnailUrl"))
        val genreList = parseStringList(obj["genre"] ?: obj["genres"])
        return RawJsManga(
            title = obj.str("title"),
            url = obj.str("url").ifBlank { mangaUrl },
            thumbnailUrl = thumb,
            status = obj.int("status", 0),
            author = obj.str("author"),
            artist = obj.str("artist"),
            description = obj.str("description"),
            genres = genreList,
            type = obj.str("type")
        )
    }

    suspend fun getChapterList(mangaUrl: String): List<RawJsChapter> {
        val resp = invokeMethod("getChapterList", listOf(JsonPrimitive(mangaUrl)))
        return parseChapterList(resp.result)
    }

    suspend fun getLatestChaptersWithCache(
        mangaUrl: String,
        eTag: String?,
        lastModified: String?
    ): ChapterListCacheResult {
        val cacheCtx = CacheContext(ifNoneMatch = eTag, ifModifiedSince = lastModified)
        if (metadata.supportsLatestChapters) {
            val latestResp = invokeMethod("getLatestChapters", listOf(JsonPrimitive(mangaUrl)), cacheCtx)
            if (cacheCtx.received304) {
                return ChapterListCacheResult(isNotModified = true)
            }
            val latest = parseChapterList(latestResp.result)
            if (latest.isNotEmpty()) {
                return ChapterListCacheResult(
                    isNotModified = false,
                    isPartial = true,
                    chapters = latest,
                    eTag = cacheCtx.extractedETag,
                    lastModified = cacheCtx.extractedLastModified
                )
            }
        }

        val fullResp = invokeMethod("getChapterList", listOf(JsonPrimitive(mangaUrl)), cacheCtx)
        if (cacheCtx.received304) {
            return ChapterListCacheResult(isNotModified = true)
        }
        return ChapterListCacheResult(
            isNotModified = false,
            isPartial = false,
            chapters = parseChapterList(fullResp.result),
            eTag = cacheCtx.extractedETag,
            lastModified = cacheCtx.extractedLastModified
        )
    }

    suspend fun getPageListWithStatus(chapterUrl: String): PageListResult {
        val resp = invokeMethod("getPageList", listOf(JsonPrimitive(chapterUrl)))
        val pages = parseStringList(resp.result).map { appendSourceUserAgent(it) }
        if (pages.isNotEmpty()) {
            return PageListResult(pages = pages, requiresVerification = resp.requiresVerification)
        }

        // Fallback to offscreen WebView extraction if extension provides pageWebViewExtractScript (e.g. voratoon.js)
        val extractScript = metadata.pageWebViewExtractScript
        if (!extractScript.isNullOrBlank() && webViewExtractor != null) {
            val absChapterUrl = if (chapterUrl.startsWith("http://", ignoreCase = true) ||
                chapterUrl.startsWith("https://", ignoreCase = true)
            ) {
                chapterUrl
            } else {
                baseUrl.trimEnd('/') + "/" + chapterUrl.trimStart('/')
            }
            val extracted = webViewExtractor.extractPages(
                chapterUrl = absChapterUrl,
                extractScript = extractScript,
                userAgent = effectiveSourceUserAgent(),
                referer = baseUrl.trimEnd('/') + "/"
            ).map { appendSourceUserAgent(it) }
            if (extracted.isNotEmpty()) {
                return PageListResult(pages = extracted, requiresVerification = false)
            }
        }

        return PageListResult(pages = emptyList(), requiresVerification = resp.requiresVerification)
    }

    private data class MethodInvocationResult(
        val ok: Boolean,
        val missing: Boolean = false,
        val result: JsonElement? = null,
        val requiresVerification: Boolean = false,
        val error: String? = null
    )

    private suspend fun invokeMethod(
        methodName: String,
        args: List<JsonElement>,
        cacheCtx: CacheContext? = null
    ): MethodInvocationResult = globalExecutionLimit.withPermit {
        runtimeMutex.withLock {
            closeRequested.set(false)
            val argsJson = json.encodeToString(JsonArray(args))
            activeCacheCtx = cacheCtx
            try {
                val (dispatcher, qjs) = getOrCreateWarmRuntime()
                withContext(dispatcher) {
                    withTimeout(EXECUTION_TIMEOUT_MS) {
                        val callExpr = "__callMethodJson(${json.encodeToString(methodName)}, ${json.encodeToString(argsJson)}, ${json.encodeToString(selectedLanguage)})"
                        val rawJson = qjs.evaluate<String?>(callExpr).orEmpty()
                        parseInvocationEnvelope(rawJson)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                safeLog("Timeout invoking $name.$methodName — cancelling HTTP call & resetting warm QuickJS runtime")
                runCatching { activeHttpCall?.cancel() }
                resetWarmRuntimeLocked()
                MethodInvocationResult(ok = false, error = "Timeout executing $name.$methodName")
            } catch (e: Exception) {
                safeLog("Error invoking $name.$methodName: ${e.message} — resetting warm QuickJS runtime")
                runCatching { activeHttpCall?.cancel() }
                resetWarmRuntimeLocked()
                MethodInvocationResult(ok = false, error = e.message)
            } finally {
                activeHttpCall = null
                activeCacheCtx = null
                domBridge.clear()
                if (closeRequested.getAndSet(false)) {
                    resetWarmRuntimeLocked()
                }
            }
        }
    }

    private suspend fun getOrCreateWarmRuntime(): Pair<ExecutorCoroutineDispatcher, QuickJs> {
        val existingDispatcher = warmDispatcher
        val existingQjs = warmQuickJs
        if (existingDispatcher != null && existingQjs != null) {
            return existingDispatcher to existingQjs
        }
        resetWarmRuntimeLocked()
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "YomicQjs-$pkg").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        return try {
            val qjs = withContext(dispatcher) {
                QuickJs.create(dispatcher).also { vm ->
                    bindHostFunctions(vm, domBridge)
                    vm.evaluate<Any?>(QuickJsBootstrap.PRELUDE)
                    vm.evaluate<Any?>(scriptCode)
                }
            }
            warmDispatcher = dispatcher
            warmQuickJs = qjs
            runtimeCreations.incrementAndGet()
            dispatcher to qjs
        } catch (e: Throwable) {
            runCatching { dispatcher.close() }
            throw e
        }
    }

    private suspend fun resetWarmRuntimeLocked() {
        val qjs = warmQuickJs
        val dispatcher = warmDispatcher
        warmQuickJs = null
        warmDispatcher = null
        if (qjs != null && dispatcher != null) {
            runCatching { withContext(dispatcher) { qjs.close() } }
        } else {
            runCatching { qjs?.close() }
        }
        runCatching { dispatcher?.close() }
    }

    /**
     * Thread-safe coroutine teardown: waits for any active JNI call to complete on its
     * affine thread before freeing QuickJS native memory and shutting down the executor.
     */
    suspend fun closeSafely() {
        closeRequested.set(true)
        runCatching { activeHttpCall?.cancel() }
        runtimeMutex.withLock {
            resetWarmRuntimeLocked()
            domBridge.clear()
            closeRequested.set(false)
        }
    }

    override fun close() {
        closeRequested.set(true)
        runCatching { activeHttpCall?.cancel() }
        if (runtimeMutex.tryLock()) {
            try {
                val qjs = warmQuickJs
                val dispatcher = warmDispatcher
                warmQuickJs = null
                warmDispatcher = null
                if (qjs != null && dispatcher != null) {
                    // Dispatch close onto the affine thread without blocking caller or racing JNI
                    dispatcher.executor.execute {
                        runCatching { qjs.close() }
                        runCatching { dispatcher.close() }
                    }
                } else {
                    runCatching { qjs?.close() }
                    runCatching { dispatcher?.close() }
                }
                domBridge.clear()
                closeRequested.set(false)
            } finally {
                runtimeMutex.unlock()
            }
        }
        // If mutex is currently held by an active invokeMethod(), closeRequested=true guarantees
        // invokeMethod()'s finally block will tear down the VM safely as soon as the call finishes.
    }

    private fun parseInvocationEnvelope(rawJson: String): MethodInvocationResult {
        if (rawJson.isBlank()) return MethodInvocationResult(ok = false, error = "Empty JS response")
        val obj = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return MethodInvocationResult(ok = false, error = "Malformed JS response")
        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull == true
        val missing = obj["missing"]?.jsonPrimitive?.booleanOrNull == true
        val requiresVer = obj["requiresVerification"]?.jsonPrimitive?.booleanOrNull == true
        val err = obj["error"]?.jsonPrimitive?.contentOrNull
        return MethodInvocationResult(
            ok = ok,
            missing = missing,
            result = obj["result"],
            requiresVerification = requiresVer,
            error = err
        )
    }

    private fun bindHostFunctions(qjs: QuickJs, dom: JsoupDomBridge) {
        qjs.function("__dom_parse") { args ->
            dom.parse(args.getOrNull(0)?.toString().orEmpty(), args.getOrNull(1)?.toString().orEmpty())
        }
        qjs.function("__dom_qs") { args ->
            dom.querySelector((args.getOrNull(0) as? Number)?.toInt() ?: -1, args.getOrNull(1)?.toString().orEmpty())
        }
        qjs.function("__dom_qsa") { args ->
            dom.querySelectorAllJson((args.getOrNull(0) as? Number)?.toInt() ?: -1, args.getOrNull(1)?.toString().orEmpty())
        }
        qjs.function("__dom_text") { args ->
            dom.text((args.getOrNull(0) as? Number)?.toInt() ?: -1)
        }
        qjs.function("__dom_attr") { args ->
            dom.attr((args.getOrNull(0) as? Number)?.toInt() ?: -1, args.getOrNull(1)?.toString().orEmpty())
        }
        qjs.function("__dom_absUrl") { args ->
            dom.absUrl((args.getOrNull(0) as? Number)?.toInt() ?: -1, args.getOrNull(1)?.toString().orEmpty())
        }
        qjs.function("__dom_html") { args ->
            dom.html((args.getOrNull(0) as? Number)?.toInt() ?: -1, args.getOrNull(1) as? Boolean ?: false)
        }
        qjs.function("__dom_tagName") { args ->
            dom.tagName((args.getOrNull(0) as? Number)?.toInt() ?: -1)
        }
        qjs.function("__dom_parent") { args ->
            dom.parentElement((args.getOrNull(0) as? Number)?.toInt() ?: -1)
        }
        qjs.function("__dom_nextSibling") { args ->
            dom.nextElementSibling((args.getOrNull(0) as? Number)?.toInt() ?: -1)
        }
        qjs.function("__b64_decode") { args ->
            decodeBase64Latin1(args.getOrNull(0)?.toString().orEmpty())
        }
        qjs.function("__b64_encode") { args ->
            encodeBase64Latin1(args.getOrNull(0)?.toString().orEmpty())
        }
        qjs.function("__yomic_log") { args ->
            safeLog("[$name] ${args.getOrNull(0)}")
        }
        qjs.function("__http_fetch") { args ->
            val url = args.getOrNull(0)?.toString().orEmpty()
            val optsJson = args.getOrNull(1)?.toString().orEmpty()
            performSyncFetch(url, optsJson, activeCacheCtx)
        }
    }

    private fun performSyncFetch(url: String, optsJson: String, cacheCtx: CacheContext?): String {
        if (url.isBlank()) {
            return json.encodeToString(FetchResponseDto(status = 400, body = "Empty URL"))
        }
        return try {
            var method = "GET"
            var postBody: String? = null
            var contentType = "application/json; charset=utf-8"
            val headers = LinkedHashMap<String, String>()

            if (optsJson.isNotBlank()) {
                val optsObj = runCatching { json.parseToJsonElement(optsJson).jsonObject }.getOrNull()
                if (optsObj != null) {
                    optsObj["method"]?.jsonPrimitive?.contentOrNull?.let { m ->
                        if (m.equals("POST", ignoreCase = true)) method = "POST"
                    }
                    optsObj["body"]?.jsonPrimitive?.contentOrNull?.let { b ->
                        postBody = b
                    }
                    (optsObj["headers"] as? JsonObject)?.forEach { (k, v) ->
                        val vStr = v.jsonPrimitive.contentOrNull.orEmpty()
                        headers[k] = vStr
                        if (k.equals("Content-Type", ignoreCase = true) && vStr.isNotBlank()) {
                            contentType = vStr
                        }
                    }
                }
            }

            if (cacheCtx != null) {
                if (!cacheCtx.ifNoneMatch.isNullOrBlank() && headers.keys.none { it.equals("If-None-Match", true) }) {
                    headers["If-None-Match"] = cacheCtx.ifNoneMatch
                }
                if (!cacheCtx.ifModifiedSince.isNullOrBlank() && headers.keys.none { it.equals("If-Modified-Since", true) }) {
                    headers["If-Modified-Since"] = cacheCtx.ifModifiedSince
                }
            }

            if (headers.keys.none { it.equals("User-Agent", true) }) {
                headers["User-Agent"] = effectiveSourceUserAgent()
            }

            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) ->
                if (k.isNotBlank()) reqBuilder.header(k, v)
            }
            if (method == "POST") {
                val mediaType = runCatching { contentType.toMediaType() }.getOrElse { "application/json; charset=utf-8".toMediaType() }
                reqBuilder.post((postBody ?: "").toRequestBody(mediaType))
            } else {
                reqBuilder.get()
            }

            val call = networkClient.httpClient.newCall(reqBuilder.build())
            activeHttpCall = call
            try {
                call.execute().use { resp ->
                    if (resp.code == 304) {
                        cacheCtx?.received304 = true
                        return json.encodeToString(FetchResponseDto(status = 304, body = ""))
                    }
                    if (resp.code == 200 && cacheCtx != null) {
                        resp.header("ETag")?.let { cacheCtx.extractedETag = it }
                        resp.header("Last-Modified")?.let { cacheCtx.extractedLastModified = it }
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    val mediaTypeStr = resp.body?.contentType()?.toString()?.lowercase().orEmpty()
                    val isBinary = mediaTypeStr.contains("protobuf") ||
                        mediaTypeStr.contains("octet-stream") ||
                        bytes.contains(0.toByte())
                    val bodyText = if (isBinary) {
                        String(bytes, Charsets.ISO_8859_1)
                    } else {
                        String(bytes, Charsets.UTF_8)
                    }
                    json.encodeToString(FetchResponseDto(status = resp.code, body = bodyText))
                }
            } finally {
                if (activeHttpCall === call) activeHttpCall = null
            }
        } catch (e: Exception) {
            safeLog("[$name] fetch($url) failed: ${e.message}")
            json.encodeToString(FetchResponseDto(status = 500, body = e.message.orEmpty()))
        }
    }

    private fun effectiveSourceUserAgent(): String =
        metadata.userAgent?.takeIf { it.isNotBlank() } ?: networkClient.effectiveUserAgent()

    private fun appendSourceUserAgent(rawUrl: String): String {
        val ua = metadata.userAgent
        if (rawUrl.isBlank() || ua.isNullOrBlank() || rawUrl.contains("|UserAgent=", ignoreCase = true)) {
            return rawUrl
        }
        return "$rawUrl|UserAgent=$ua"
    }

    private fun parsePagedMangaList(element: JsonElement?, currentPage: Int): PagedJsMangaResult {
        return when (element) {
            is JsonArray -> {
                val items = parseMangaArray(element)
                val total = if (items.isNotEmpty() && metadata.isHasMorePages) currentPage + 1 else currentPage
                PagedJsMangaResult(items = items, totalPages = total, hasNextPage = total > currentPage)
            }
            is JsonObject -> {
                val itemsArr = element["items"] as? JsonArray
                val items = if (itemsArr != null) parseMangaArray(itemsArr) else emptyList()
                val explicitTotal = element["totalPages"]?.jsonPrimitive?.intOrNull
                val hasMoreField = element["hasNextPage"]?.jsonPrimitive?.booleanOrNull
                val total = explicitTotal ?: if (items.isNotEmpty() && (hasMoreField != false) && metadata.isHasMorePages) {
                    currentPage + 1
                } else {
                    currentPage
                }
                val hasNext = hasMoreField ?: (total > currentPage && items.isNotEmpty())
                PagedJsMangaResult(items = items, totalPages = total, hasNextPage = hasNext)
            }
            else -> PagedJsMangaResult(emptyList(), currentPage, false)
        }
    }

    private fun parseMangaArray(arr: JsonArray): List<RawJsManga> =
        arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val title = obj.str("title")
            val url = obj.str("url")
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            RawJsManga(
                title = title,
                url = url,
                thumbnailUrl = appendSourceUserAgent(obj.str("thumbnailUrl")),
                status = obj.int("status", 0),
                author = obj.str("author"),
                description = obj.str("description"),
                genres = parseStringList(obj["genres"] ?: obj["genre"]),
                type = obj.str("type")
            )
        }

    private fun parseChapterList(element: JsonElement?): List<RawJsChapter> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val url = obj.str("url")
            if (url.isBlank()) return@mapNotNull null
            val name = obj.str("name").ifBlank { obj.str("title") }
            val numEl = obj["chapterNumber"] ?: obj["chapter"]
            val parsedNum = (numEl as? JsonPrimitive)?.doubleOrNull
                ?: (numEl as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: parseChapterNumber(name)
            RawJsChapter(
                name = name.ifBlank { "Chapter $parsedNum" },
                url = url,
                chapterNumber = parsedNum,
                dateUploadEpoch = (obj["dateUpload"] as? JsonPrimitive)?.longOrNull ?: 0L,
                scanlator = obj.str("scanlator")
            )
        }
    }

    private fun parseStringList(element: JsonElement?): List<String> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String, default: Int): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: default

    companion object {
        private const val EXECUTION_TIMEOUT_MS = 30_000L
        private val globalExecutionLimit = Semaphore(6)
        private val chapterNumberRegex = Regex("""\b(?:ch|chapter|chap|chp)\.?\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
        private val fallbackNumberRegex = Regex("""([0-9]+(?:\.[0-9]+)?)""")

        /**
         * Generates the exact same 64-bit source ID as Desktop's `JsMangaSource.GenerateStableId()`:
         * MD5("JS_" + Name + "_" + Language) -> BitConverter.ToInt64(hash, 0) (little-endian).
         */
        fun generateStableId(name: String, language: String): Long {
            val hashName = "JS_${name}_$language"
            val digest = MessageDigest.getInstance("MD5").digest(hashName.toByteArray(Charsets.UTF_8))
            return ByteBuffer.wrap(digest, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        }

        fun parseChapterNumber(name: String): Double {
            if (name.isBlank()) return -1.0
            val match = chapterNumberRegex.find(name)
            if (match != null) {
                match.groupValues[1].toDoubleOrNull()?.let { return it }
            }
            val fallback = fallbackNumberRegex.find(name)
            return fallback?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: -1.0
        }

        suspend fun loadFromCode(
            pkg: String,
            scriptFileName: String,
            scriptCode: String,
            networkClient: YomicNetworkClient,
            webViewExtractor: WebViewPageExtractor? = null
        ): JsExtensionSource = withContext(Dispatchers.IO) {
            val json = Json { ignoreUnknownKeys = true }
            val domBridge = JsoupDomBridge()
            val metaJson = try {
                QuickJs.create(Dispatchers.IO).use { qjs ->
                    qjs.function("__dom_parse") { -1 }
                    qjs.function("__dom_qs") { -1 }
                    qjs.function("__dom_qsa") { "[]" }
                    qjs.function("__dom_text") { "" }
                    qjs.function("__dom_attr") { "" }
                    qjs.function("__dom_absUrl") { "" }
                    qjs.function("__dom_html") { "" }
                    qjs.function("__dom_tagName") { "" }
                    qjs.function("__dom_parent") { -1 }
                    qjs.function("__dom_nextSibling") { -1 }
                    qjs.function("__b64_decode") { args -> decodeBase64Latin1(args.getOrNull(0)?.toString().orEmpty()) }
                    qjs.function("__b64_encode") { args -> encodeBase64Latin1(args.getOrNull(0)?.toString().orEmpty()) }
                    qjs.function("__yomic_log") { }
                    qjs.function("__http_fetch") { """{"status":503,"body":""}""" }

                    qjs.evaluate<Any?>(QuickJsBootstrap.PRELUDE)
                    qjs.evaluate<Any?>(scriptCode)
                    qjs.evaluate<String?>("__readMetadataJson()").orEmpty()
                }
            } finally {
                domBridge.clear()
            }

            val obj = json.parseToJsonElement(metaJson).jsonObject
            if (obj["valid"]?.jsonPrimitive?.booleanOrNull != true) {
                throw IllegalArgumentException("Script $scriptFileName does not define a global 'source' object.")
            }

            val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val baseUrl = obj["baseUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val language = obj["language"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "id" }
            val explicitId = obj["id"]?.jsonPrimitive?.longOrNull
            val stableId = explicitId ?: generateStableId(name, language)

            var userAgent = obj["userAgent"]?.jsonPrimitive?.contentOrNull
            if (userAgent.isNullOrBlank() &&
                (name.contains("MangaDex", ignoreCase = true) || baseUrl.contains("mangadex.org", ignoreCase = true))
            ) {
                userAgent = "Yomic/1.0.3"
            }

            val meta = JsSourceMetadata(
                id = stableId,
                name = name,
                baseUrl = baseUrl,
                apiUrl = obj["apiUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                language = language,
                version = obj["version"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "1.0.0" },
                description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                author = obj["author"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Unknown" },
                iconUrl = obj["iconUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                iconBackground = obj["iconBackground"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "#1e293b" },
                iconForeground = obj["iconForeground"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "#38bdf8" },
                isNsfw = obj["isNsfw"]?.jsonPrimitive?.booleanOrNull == true,
                isHasMorePages = obj["isHasMorePages"]?.jsonPrimitive?.booleanOrNull != false,
                requiresProxy = obj["requiresProxy"]?.jsonPrimitive?.booleanOrNull == true,
                isCloudflareProtected = obj["isCloudflareProtected"]?.jsonPrimitive?.booleanOrNull == true,
                chapterReadySelector = obj["chapterReadySelector"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                pageWebViewExtractScript = obj["pageWebViewExtractScript"]?.jsonPrimitive?.contentOrNull,
                userAgent = userAgent,
                genres = (obj["genres"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                formats = (obj["formats"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                supportsMangaList = obj["supportsMangaList"]?.jsonPrimitive?.booleanOrNull == true,
                supportsLatestChapters = obj["supportsLatestChapters"]?.jsonPrimitive?.booleanOrNull == true
            )

            JsExtensionSource(
                pkg = pkg,
                scriptFileName = scriptFileName,
                scriptCode = scriptCode,
                metadata = meta,
                networkClient = networkClient,
                webViewExtractor = webViewExtractor
            )
        }

        private const val B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        private fun decodeBase64Latin1(input: String): String {
            if (input.isBlank()) return ""
            return runCatching {
                val clean = buildString(input.length) {
                    for (ch in input) {
                        if (!ch.isWhitespace()) append(ch)
                    }
                }
                val out = ArrayList<Byte>((clean.length * 3) / 4)
                var buffer = 0
                var bitsCollected = 0
                for (ch in clean) {
                    if (ch == '=') break
                    val v = when (ch) {
                        in 'A'..'Z' -> ch - 'A'
                        in 'a'..'z' -> ch - 'a' + 26
                        in '0'..'9' -> ch - '0' + 52
                        '+', '-' -> 62
                        '/', '_' -> 63
                        else -> continue
                    }
                    buffer = (buffer shl 6) or v
                    bitsCollected += 6
                    if (bitsCollected >= 8) {
                        bitsCollected -= 8
                        out.add(((buffer shr bitsCollected) and 0xFF).toByte())
                    }
                }
                String(out.toByteArray(), Charsets.ISO_8859_1)
            }.getOrDefault("")
        }

        private fun encodeBase64Latin1(input: String): String {
            val bytes = input.toByteArray(Charsets.ISO_8859_1)
            if (bytes.isEmpty()) return ""
            val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
                val triple = (b0 shl 16) or (b1 shl 8) or b2
                sb.append(B64_CHARS[(triple shr 18) and 0x3F])
                sb.append(B64_CHARS[(triple shr 12) and 0x3F])
                sb.append(if (i + 1 < bytes.size) B64_CHARS[(triple shr 6) and 0x3F] else '=')
                sb.append(if (i + 2 < bytes.size) B64_CHARS[triple and 0x3F] else '=')
                i += 3
            }
            return sb.toString()
        }

        private fun safeLog(msg: String) {
            runCatching { Log.d("YomicJs", msg) }
        }
    }
}
