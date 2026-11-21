package id.yomic.mobile.data.tracker

import id.yomic.mobile.data.local.LocalEntityMappers
import id.yomic.mobile.data.local.MangaTrackDao
import id.yomic.mobile.data.local.MangaTrackEntity
import id.yomic.mobile.data.preferences.MalTokenStorage
import id.yomic.mobile.model.MalSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale

data class MalLoginResult(
    val success: Boolean,
    val username: String = "",
    val error: String? = null
)

/**
 * 1:1 Android port of Yomic Desktop's MyAnimeListService.cs.
 * Supports OAuth2 PKCE (plain challenge, loopback 127.0.0.1:49152 -> yomic://mal-done),
 * automatic token refresh (5 minutes before expiry), manga search, status fetch/patch,
 * and automatic post-reading progress synchronization.
 */
class MalTrackerService(
    private val httpClientProvider: () -> OkHttpClient,
    private val tokenStore: MalTokenStorage,
    private val mangaTrackDao: MangaTrackDao? = null,
    private val tokenUrl: String = DEFAULT_TOKEN_URL,
    private val apiBaseUrl: String = DEFAULT_API_BASE,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    constructor(
        httpClient: OkHttpClient,
        tokenStore: MalTokenStorage,
        mangaTrackDao: MangaTrackDao? = null,
        tokenUrl: String = DEFAULT_TOKEN_URL,
        apiBaseUrl: String = DEFAULT_API_BASE,
        nowProvider: () -> Long = { System.currentTimeMillis() }
    ) : this(
        httpClientProvider = { httpClient },
        tokenStore = tokenStore,
        mangaTrackDao = mangaTrackDao,
        tokenUrl = tokenUrl,
        apiBaseUrl = apiBaseUrl,
        nowProvider = nowProvider
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    @Volatile
    private var activeServerSocket: ServerSocket? = null

    val isConnected: Boolean
        get() = tokenStore.getMalAuth().isConnected

    fun buildAuthorizationUrl(codeChallenge: String): String {
        val encodedRedirect = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        return "$OAUTH_AUTH_BASE?response_type=code" +
            "&client_id=$CLIENT_ID" +
            "&code_challenge=$codeChallenge" +
            "&code_challenge_method=plain" +
            "&redirect_uri=$encodedRedirect"
    }

    /**
     * Starts an ephemeral local loopback listener on 127.0.0.1:49152, emits the MAL OAuth URL
     * so the UI can open Chrome Custom Tabs, waits for the redirect containing `?code=...`,
     * responds with a redirect page to `yomic://mal-done`, and exchanges the code for tokens.
     */
    suspend fun startLoopbackOAuthFlow(
        timeoutMs: Int = 180_000,
        onAuthUrlReady: (String) -> Unit
    ): MalLoginResult = withContext(Dispatchers.IO) {
        cancelPendingLogin()
        val verifier = generateCodeVerifier()
        val authUrl = buildAuthorizationUrl(verifier)
        var server: ServerSocket? = null
        try {
            server = ServerSocket(LOOPBACK_PORT, 5, InetAddress.getByName("127.0.0.1")).apply {
                reuseAddress = true
                soTimeout = timeoutMs
            }
            activeServerSocket = server

            withContext(Dispatchers.Main.immediate) {
                onAuthUrlReady(authUrl)
            }

            var authCode: String? = null
            var oauthError: String? = null
            val deadline = nowProvider() + timeoutMs

            while (authCode == null && oauthError == null && nowProvider() < deadline) {
                val client = server.accept()
                client.use { socket ->
                    socket.soTimeout = 5_000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val requestLine = reader.readLine().orEmpty()
                    val path = requestLine.split(" ").getOrNull(1).orEmpty()

                    if (path.startsWith("/favicon.ico")) {
                        val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(notFound.toByteArray(Charsets.UTF_8))
                        socket.getOutputStream().flush()
                        return@use
                    }

                    val params = parseQueryParams(path)
                    authCode = params["code"]?.takeIf { it.isNotBlank() }
                    oauthError = params["error"]?.takeIf { it.isNotBlank() }

                    val html = buildCallbackHtml(success = authCode != null)
                    val htmlBytes = html.toByteArray(Charsets.UTF_8)
                    val responseHeader =
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${htmlBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    socket.getOutputStream().write(responseHeader.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().write(htmlBytes)
                    socket.getOutputStream().flush()
                }
            }

            if (oauthError != null) {
                return@withContext MalLoginResult(success = false, error = "OAuth ditolak: $oauthError")
            }
            val code = authCode ?: return@withContext MalLoginResult(
                success = false,
                error = "Waktu login MyAnimeList habis."
            )

            val authed = authenticate(code, verifier)
            if (!authed) {
                return@withContext MalLoginResult(success = false, error = "Gagal menukar token MyAnimeList.")
            }
            val username = fetchUserProfileName().ifBlank { "MAL User" }
            MalLoginResult(success = true, username = username)
        } catch (e: Exception) {
            MalLoginResult(success = false, error = e.message ?: "Login MyAnimeList dibatalkan.")
        } finally {
            runCatching { server?.close() }
            if (activeServerSocket === server) {
                activeServerSocket = null
            }
        }
    }

    fun cancelPendingLogin() {
        runCatching { activeServerSocket?.close() }
        activeServerSocket = null
    }

    suspend fun authenticate(authCode: String, codeVerifier: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "authorization_code")
                .add("code", authCode)
                .add("code_verifier", codeVerifier)
                .add("redirect_uri", REDIRECT_URI)
                .build()

            val request = Request.Builder()
                .url(tokenUrl)
                .post(form)
                .build()

            httpClientProvider().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body?.string().orEmpty()
                val obj = json.parseToJsonElement(body).jsonObject
                val access = obj["access_token"]?.jsonPrimitive?.content.orEmpty()
                val refresh = obj["refresh_token"]?.jsonPrimitive?.content.orEmpty()
                val expiresInSec = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
                if (access.isBlank()) return@withContext false

                tokenStore.saveMalAuth(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAtEpochMs = nowProvider() + (expiresInSec * 1000L),
                    username = tokenStore.getMalAuth().username
                )
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun disconnect() {
        cancelPendingLogin()
        tokenStore.clearMalAuth()
    }

    suspend fun ensureValidToken(): Boolean = withContext(Dispatchers.IO) {
        val current = tokenStore.getMalAuth()
        if (!current.isConnected) return@withContext false
        if (current.expiresAtEpochMs > 0L && nowProvider() + REFRESH_MARGIN_MS >= current.expiresAtEpochMs) {
            refreshMutex.withLock {
                val rechecked = tokenStore.getMalAuth()
                if (rechecked.expiresAtEpochMs > 0L && nowProvider() + REFRESH_MARGIN_MS >= rechecked.expiresAtEpochMs) {
                    return@withContext refreshTokenLocked(rechecked.refreshToken)
                }
            }
        }
        true
    }

    private fun refreshTokenLocked(refreshToken: String): Boolean {
        if (refreshToken.isBlank()) return false
        return try {
            val form = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url(tokenUrl)
                .post(form)
                .build()

            httpClientProvider().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body?.string().orEmpty()
                val obj = json.parseToJsonElement(body).jsonObject
                val access = obj["access_token"]?.jsonPrimitive?.content.orEmpty()
                val newRefresh = obj["refresh_token"]?.jsonPrimitive?.content.orEmpty().ifBlank { refreshToken }
                val expiresInSec = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
                if (access.isBlank()) return false

                tokenStore.saveMalAuth(
                    accessToken = access,
                    refreshToken = newRefresh,
                    expiresAtEpochMs = nowProvider() + (expiresInSec * 1000L),
                    username = tokenStore.getMalAuth().username
                )
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchUserProfileName(): String = withContext(Dispatchers.IO) {
        if (!ensureValidToken()) return@withContext ""
        try {
            val req = authenticatedRequest("$apiBaseUrl/users/@me").get().build()
            httpClientProvider().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext ""
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                if (name.isNotBlank()) {
                    tokenStore.updateMalUsername(name)
                }
                name
            }
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun searchManga(query: String): List<MalSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || !ensureValidToken()) return@withContext emptyList()
        try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = "$apiBaseUrl/manga?q=$encoded&limit=10&fields=main_picture,media_type,num_chapters,mean&nsfw=true"
            val req = authenticatedRequest(url).get().build()
            httpClientProvider().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val data = root["data"]?.jsonArray ?: return@withContext emptyList()
                data.mapNotNull { el ->
                    val node = el.jsonObject["node"]?.jsonObject ?: return@mapNotNull null
                    val id = node["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val title = node["title"]?.jsonPrimitive?.content.orEmpty()
                    if (title.isBlank()) return@mapNotNull null
                    val pic = node["main_picture"] as? JsonObject
                    val coverUrl = pic?.get("medium")?.jsonPrimitive?.content
                        ?: pic?.get("large")?.jsonPrimitive?.content
                        ?: ""
                    val rawMedia = node["media_type"]?.jsonPrimitive?.content.orEmpty()
                    val mediaType = formatMediaType(rawMedia)
                    val chapters = node["num_chapters"]?.jsonPrimitive?.intOrNull ?: 0
                    val score = node["mean"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val (_, accentHex, _) = LocalEntityMappers.deriveCoverPalette("mal_$id")
                    MalSearchResult(
                        id = id,
                        title = title,
                        mediaType = mediaType,
                        chapters = chapters,
                        score = score,
                        accentColorHex = accentHex,
                        coverUrl = coverUrl
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchMangaStatus(malId: Long, localMangaId: String): MangaTrackEntity? = withContext(Dispatchers.IO) {
        if (malId <= 0L || !ensureValidToken()) return@withContext null
        try {
            val url = "$apiBaseUrl/manga/$malId?fields=my_list_status,num_chapters"
            val req = authenticatedRequest(url).get().build()
            httpClientProvider().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val title = root["title"]?.jsonPrimitive?.content.orEmpty()
                val totalCh = root["num_chapters"]?.jsonPrimitive?.intOrNull ?: 0
                val myStatus = root["my_list_status"] as? JsonObject
                val apiStatus = myStatus?.get("status")?.jsonPrimitive?.content ?: "reading"
                val score = myStatus?.get("score")?.jsonPrimitive?.intOrNull ?: 0
                val readCh = myStatus?.get("num_chapters_read")?.jsonPrimitive?.intOrNull ?: 0
                MangaTrackEntity(
                    mangaId = localMangaId,
                    trackerName = "MyAnimeList",
                    remoteId = malId,
                    remoteTitle = title,
                    status = toDisplayStatus(apiStatus),
                    score = score,
                    chaptersRead = readCh,
                    totalChapters = totalCh
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun updateMangaStatus(
        malId: Long,
        status: String,
        numChaptersRead: Int,
        score: Int
    ): Boolean = withContext(Dispatchers.IO) {
        if (malId <= 0L || !ensureValidToken()) return@withContext false
        try {
            val url = "$apiBaseUrl/manga/$malId/my_list_status"
            val form = FormBody.Builder()
                .add("status", toMalApiStatus(status))
                .add("num_chapters_read", numChaptersRead.coerceAtLeast(0).toString())
                .add("score", score.coerceIn(0, 10).toString())
                .build()
            val req = authenticatedRequest(url).patch(form).build()
            httpClientProvider().newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun saveAndSyncTrack(
        mangaId: String,
        malId: Int,
        malTitle: String,
        status: String,
        chaptersRead: Int,
        score: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val existing = mangaTrackDao?.getByMangaId(mangaId)
        val resolvedMalId = if (malId > 0) malId.toLong() else (existing?.remoteId ?: 0L)
        val entity = MangaTrackEntity(
            mangaId = mangaId,
            trackerName = "MyAnimeList",
            remoteId = resolvedMalId,
            remoteTitle = malTitle.ifBlank { existing?.remoteTitle.orEmpty() },
            status = toDisplayStatus(status),
            score = score.coerceIn(0, 10),
            chaptersRead = chaptersRead.coerceAtLeast(0),
            totalChapters = existing?.totalChapters ?: 0
        )
        mangaTrackDao?.upsert(entity)
        if (resolvedMalId > 0L && isConnected) {
            updateMangaStatus(resolvedMalId, entity.status, entity.chaptersRead, entity.score)
        } else {
            true
        }
    }

    suspend fun unbindTrack(mangaId: String) = withContext(Dispatchers.IO) {
        mangaTrackDao?.deleteByMangaId(mangaId)
    }

    /**
     * Automatically pushes higher chapter progress to MyAnimeList when user finishes a chapter.
     */
    suspend fun autoSyncAfterReading(mangaId: String, chapterNumber: Double): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext false
        val track = mangaTrackDao?.getByMangaId(mangaId) ?: return@withContext false
        if (track.remoteId <= 0L) return@withContext false
        val nextCh = chapterNumber.toInt()
        if (nextCh <= track.chaptersRead) return@withContext false

        val nextStatus = if (track.totalChapters > 0 && nextCh >= track.totalChapters) {
            "Completed"
        } else if (toMalApiStatus(track.status) == "plan_to_read") {
            "Reading"
        } else {
            track.status
        }

        val ok = updateMangaStatus(track.remoteId, nextStatus, nextCh, track.score)
        if (ok) {
            mangaTrackDao?.upsert(track.copy(chaptersRead = nextCh, status = nextStatus))
        }
        ok
    }

    private fun authenticatedRequest(url: String): Request.Builder {
        val token = tokenStore.getMalAuth().accessToken
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Yomic-Android-App")
    }

    companion object {
        const val CLIENT_ID = "2178a6ea66e6d2c4356ab0fd4d298ce4"
        const val LOOPBACK_PORT = 49152
        const val REDIRECT_URI = "http://127.0.0.1:49152/"
        const val OAUTH_AUTH_BASE = "https://myanimelist.net/v1/oauth2/authorize"
        const val DEFAULT_TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"
        const val DEFAULT_API_BASE = "https://api.myanimelist.net/v2"
        const val REFRESH_MARGIN_MS = 300_000L // 5 minutes

        private const val PKCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        fun generateCodeVerifier(): String {
            val rng = SecureRandom()
            val bytes = ByteArray(128)
            rng.nextBytes(bytes)
            val sb = StringBuilder(128)
            for (b in bytes) {
                sb.append(PKCE_CHARS[(b.toInt() and 0xFF) % PKCE_CHARS.length])
            }
            return sb.toString()
        }

        fun toMalApiStatus(status: String): String {
            return when (status.trim().lowercase(Locale.US).replace("-", "_").replace(" ", "_")) {
                "completed" -> "completed"
                "on_hold", "onhold" -> "on_hold"
                "dropped" -> "dropped"
                "plan_to_read", "plantoread" -> "plan_to_read"
                else -> "reading"
            }
        }

        fun toDisplayStatus(apiStatus: String): String {
            return when (toMalApiStatus(apiStatus)) {
                "completed" -> "Completed"
                "on_hold" -> "On Hold"
                "dropped" -> "Dropped"
                "plan_to_read" -> "Plan to Read"
                else -> "Reading"
            }
        }

        internal fun formatMediaType(raw: String): String = when (raw.lowercase(Locale.US)) {
            "manhwa" -> "Manhwa"
            "manhua" -> "Manhua"
            "one_shot" -> "One-shot"
            "doujinshi" -> "Doujinshi"
            "light_novel" -> "Light Novel"
            "novel" -> "Novel"
            else -> "Manga"
        }

        internal fun parseQueryParams(path: String): Map<String, String> {
            val query = path.substringAfter('?', "")
            if (query.isBlank()) return emptyMap()
            return query.split('&').mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val k = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault(pair.substring(0, idx))
                val v = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault(pair.substring(idx + 1))
                k to v
            }.toMap()
        }

        private fun buildCallbackHtml(success: Boolean): String {
            val title = if (success) "Login MyAnimeList Berhasil" else "Login MyAnimeList Dibatalkan"
            val desc = if (success) "Mengalihkan kembali ke aplikasi Yomic..." else "Anda dapat menutup tab ini dan kembali ke Yomic."
            return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width,initial-scale=1" />
                  <meta http-equiv="refresh" content="0;url=yomic://mal-done" />
                  <title>$title</title>
                  <style>
                    body { font-family: system-ui, sans-serif; background:#09090B; color:#F4F4F5; display:flex; align-items:center; justify-content:center; min-height:100vh; margin:0; text-align:center; padding:24px; }
                    .card { background:#141418; border:1px solid #27272A; border-radius:16px; padding:28px; max-width:360px; }
                    a { display:inline-block; margin-top:16px; padding:10px 20px; border-radius:10px; background:#3B82F6; color:#fff; text-decoration:none; font-weight:600; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h2>$title</h2>
                    <p>$desc</p>
                    <a href="yomic://mal-done">Kembali ke Yomic</a>
                  </div>
                </body>
                </html>
            """.trimIndent()
        }
    }
}
