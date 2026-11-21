package id.yomic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import id.yomic.mobile.data.image.CbzPageData
import id.yomic.mobile.data.image.YomicImageProcessor
import id.yomic.mobile.model.ReaderFitMode
import id.yomic.mobile.model.ReaderPageItem
import id.yomic.mobile.ui.theme.ReaderAccent
import id.yomic.mobile.ui.theme.ReaderBorder
import id.yomic.mobile.ui.theme.ReaderInk
import id.yomic.mobile.ui.theme.ReaderInkMuted
import id.yomic.mobile.ui.theme.ReaderSurface
import id.yomic.mobile.ui.theme.YomicIcons
import java.io.File

/**
 * Renders a real comic/webtoon page via Coil 3 (`YomicImagePipeline`):
 * - Supports online URLs (with DoH + Referer/UA/Cookies + AES-256 decryption),
 *   local downloaded image files, and `.cbz` archive entries.
 * - Applies `YomicImageProcessor` (Auto-Crop Borders, Unsharp-Mask Sharpening, Max Resolution).
 * - Displays clean loading placeholder and per-page Retry button on network error.
 */
@Composable
fun RealComicPageView(
    page: ReaderPageItem,
    fitMode: ReaderFitMode,
    isWebtoon: Boolean,
    enableOptimization: Boolean,
    autoCropBorders: Boolean,
    sharpen: Boolean,
    maxDimension: Int,
    isEn: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var retryCounter by remember(page.urlOrPath) { mutableIntStateOf(0) }

    val contentScale = when {
        isWebtoon -> ContentScale.FillWidth
        fitMode == ReaderFitMode.FIT_WIDTH -> ContentScale.FillWidth
        fitMode == ReaderFitMode.FIT_HEIGHT -> ContentScale.FillHeight
        else -> ContentScale.Fit
    }

    val effectiveMaxDim = if (enableOptimization && maxDimension > 0) maxDimension else 0
    val needsProcessing = autoCropBorders || sharpen || effectiveMaxDim > 0

    val modelData: Any = remember(page, retryCounter) {
        when {
            page.isCbzEntry -> CbzPageData(page.cbzArchivePath, page.cbzEntryName)
            page.isLocalFile -> File(page.urlOrPath)
            else -> page.urlOrPath
        }
    }

    val request = remember(modelData, retryCounter, autoCropBorders, sharpen, effectiveMaxDim) {
        ImageRequest.Builder(context)
            .data(modelData)
            .memoryCacheKey("${page.urlOrPath}_r${retryCounter}_c${autoCropBorders}_s${sharpen}_m$effectiveMaxDim")
            .crossfade(120)
            .apply {
                if (needsProcessing) {
                    transformations(
                        YomicImageProcessor(
                            autoCropBorders = autoCropBorders,
                            sharpen = sharpen,
                            maxDimension = effectiveMaxDim
                        )
                    )
                }
            }
            .build()
    }

    val containerMod = if (isWebtoon) {
        modifier.fillMaxWidth()
    } else {
        modifier.fillMaxSize()
    }

    SubcomposeAsyncImage(
        model = request,
        contentDescription = "Page ${page.index}",
        contentScale = contentScale,
        modifier = containerMod,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (isWebtoon) 320.dp else 420.dp)
                    .background(Color(0xFF0E0F12)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        color = ReaderAccent,
                        strokeWidth = 2.2.dp,
                        modifier = Modifier.size(26.dp)
                    )
                    Text(
                        text = "${page.index}",
                        color = ReaderInkMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (isWebtoon) 260.dp else 360.dp)
                    .background(ReaderSurface)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = YomicIcons.Warning,
                        contentDescription = null,
                        tint = ReaderInkMuted,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = if (isEn) "Failed to load page ${page.index}" else "Gagal memuat halaman ${page.index}",
                        color = ReaderInkMuted,
                        fontSize = 12.5.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B))
                            .border(0.8.dp, ReaderBorder, RoundedCornerShape(8.dp))
                            .clickable { retryCounter++ }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = if (isEn) "Retry" else "Coba Lagi",
                            color = ReaderInk,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    )
}
