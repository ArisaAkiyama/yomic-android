package id.yomic.mobile.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import id.yomic.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

data class AttachedFeedbackScreenshot(
    val fileName: String,
    val mimeType: String,
    val base64: String
)

data class FeedbackSubmitResult(
    val success: Boolean,
    val message: String
)

@Serializable
internal data class FeedbackWebhookPayload(
    val targetOwnerEmail: String = "info.yomic@gmail.com",
    val spreadsheetName: String = "Yomic - Masukan Pengguna",
    val timestamp: String,
    val category: String,
    val categoryLabel: String,
    val message: String,
    val appVersion: String,
    val osVersion: String,
    val language: String,
    val hasScreenshot: Boolean,
    val screenshotName: String,
    val screenshotMimeType: String,
    val screenshotBase64: String
)

/**
 * 1:1 Android port of Yomic Desktop's HandleSubmitFeedbackAsync (BackendIpcController.cs):
 * Submits structured JSON feedback + optional resized JPEG Base64 screenshot to the Yomic
 * Google Apps Script webhook with `followRedirects(false)` and detects HTTP 2xx or
 * 301/302/303/307 redirects pointing to `script.googleusercontent.com`.
 */
class FeedbackRepository(
    private val httpClientProvider: () -> OkHttpClient,
    private val webhookUrl: String = DEFAULT_FEEDBACK_WEBHOOK_URL
) {
    constructor(
        baseHttpClient: OkHttpClient,
        webhookUrl: String = DEFAULT_FEEDBACK_WEBHOOK_URL
    ) : this(
        httpClientProvider = { baseHttpClient },
        webhookUrl = webhookUrl
    )

    @Volatile
    private var lastBaseClient: OkHttpClient? = null

    @Volatile
    private var cachedNoRedirectClient: OkHttpClient? = null

    @Synchronized
    private fun currentNoRedirectClient(): OkHttpClient {
        val base = httpClientProvider()
        val cached = cachedNoRedirectClient
        if (cached != null && base === lastBaseClient) return cached
        val rebuilt = base.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(35, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(35, TimeUnit.SECONDS)
            .build()
        lastBaseClient = base
        cachedNoRedirectClient = rebuilt
        return rebuilt
    }

    private val json = Json { encodeDefaults = true }

    suspend fun submitFeedback(
        category: String,
        categoryLabel: String,
        message: String,
        language: String = "id",
        appVersion: String = BuildConfig.VERSION_NAME,
        osVersion: String = defaultAndroidOsLabel(),
        screenshot: AttachedFeedbackScreenshot? = null
    ): FeedbackSubmitResult = withContext(Dispatchers.IO) {
        val cleanMsg = message.trim()
        val isEn = language.equals("en", ignoreCase = true)
        if (cleanMsg.isBlank()) {
            return@withContext FeedbackSubmitResult(
                success = false,
                message = if (isEn) "Please enter your feedback description first." else "Silakan tuliskan deskripsi masukan Anda terlebih dahulu."
            )
        }
        if (cleanMsg.length > 10_000) {
            return@withContext FeedbackSubmitResult(
                success = false,
                message = if (isEn) "Feedback description is too long (max 10,000 characters)." else "Deskripsi masukan terlalu panjang (maksimal 10.000 karakter)."
            )
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val payload = FeedbackWebhookPayload(
            timestamp = timestamp,
            category = category.ifBlank { "bug" },
            categoryLabel = categoryLabel.ifBlank { "Laporan Bug" },
            message = cleanMsg,
            appVersion = appVersion,
            osVersion = osVersion,
            language = language,
            hasScreenshot = screenshot != null && screenshot.base64.isNotBlank(),
            screenshotName = screenshot?.fileName.orEmpty(),
            screenshotMimeType = screenshot?.mimeType.orEmpty(),
            screenshotBase64 = screenshot?.base64.orEmpty()
        )

        try {
            val bodyJson = json.encodeToString(payload)
            val request = Request.Builder()
                .url(webhookUrl)
                .header("User-Agent", "Yomic-Android-App")
                .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            currentNoRedirectClient().newCall(request).execute().use { resp ->
                val code = resp.code
                val location = resp.header("Location").orEmpty()
                val ok = resp.isSuccessful || (
                    code in setOf(301, 302, 303, 307) &&
                        location.contains("script.googleusercontent.com", ignoreCase = true)
                    )
                if (ok) {
                    FeedbackSubmitResult(
                        success = true,
                        message = if (isEn) "Thank you! Your feedback has been sent." else "Terima kasih! Masukan Anda berhasil dikirim."
                    )
                } else {
                    FeedbackSubmitResult(
                        success = false,
                        message = if (isEn) "Failed to send feedback (HTTP $code). Please try again later." else "Gagal mengirim masukan (HTTP $code). Silakan coba lagi nanti."
                    )
                }
            }
        } catch (_: Exception) {
            FeedbackSubmitResult(
                success = false,
                message = if (isEn) "Could not reach the feedback server. Check your internet connection." else "Gagal terhubung ke server masukan. Periksa koneksi internet Anda."
            )
        }
    }

    companion object {
        const val DEFAULT_FEEDBACK_WEBHOOK_URL =
            "https://script.google.com/macros/s/AKfycbxRe38pavuutQibdL5bYSvR2xKXumHU6A_4cnrGh0zA_FxAsX6usetRSRhohHS9gZ-fZg/exec"

        fun mapCategoryKey(label: String): String {
            val l = label.lowercase(Locale.US)
            return when {
                l.contains("bug") -> "bug"
                l.contains("fitur") || l.contains("feature") -> "feature"
                l.contains("ekstensi") || l.contains("extension") -> "extension"
                l.contains("performa") || l.contains("performance") -> "performance"
                else -> "general"
            }
        }

        fun defaultAndroidOsLabel(): String {
            val release = runCatching { Build.VERSION.RELEASE }.getOrNull().orEmpty().ifBlank { "Android" }
            val sdk = runCatching { Build.VERSION.SDK_INT }.getOrDefault(0)
            val model = runCatching { Build.MODEL }.getOrNull().orEmpty()
            return buildString {
                append("Android ")
                append(release)
                if (sdk > 0) append(" (API $sdk)")
                if (model.isNotBlank()) append(" - $model")
            }
        }

        /**
         * Reads a gallery image URI via ContentResolver, downscales it to max 1280px,
         * encodes as JPEG (quality 82) and Base64 without line wraps.
         */
        suspend fun compressImageFromUri(
            context: Context,
            uri: Uri,
            maxDimension: Int = 1280
        ): AttachedFeedbackScreenshot? = withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val displayName = resolveDisplayName(context, uri)
                    .substringBeforeLast('.')
                    .ifBlank { "screenshot_yomic_mobile" } + ".jpg"

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

                var sampleSize = 1
                val maxSide = max(bounds.outWidth, bounds.outHeight)
                while (maxSide / sampleSize > maxDimension * 2) {
                    sampleSize *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                    ?: return@withContext null

                val scaled = if (max(decoded.width, decoded.height) > maxDimension) {
                    val ratio = maxDimension.toFloat() / max(decoded.width, decoded.height).toFloat()
                    val newW = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
                    val newH = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(decoded, newW, newH, true).also {
                        if (it !== decoded) decoded.recycle()
                    }
                } else {
                    decoded
                }

                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                scaled.recycle()

                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                AttachedFeedbackScreenshot(
                    fileName = displayName,
                    mimeType = "image/jpeg",
                    base64 = b64
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun resolveDisplayName(context: Context, uri: Uri): String {
            return runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull().orEmpty().ifBlank { "screenshot_yomic_mobile.jpg" }
        }
    }
}
