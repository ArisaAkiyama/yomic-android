package id.yomic.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import id.yomic.mobile.model.MangaItem
import id.yomic.mobile.ui.theme.YomicThemeTokens

/**
 * 2:3 Manga cover view backed by Coil 3 (`YomicImagePipeline` with DoH + Referer/Cookie headers).
 * Automatically falls back to Yomic's clean editorial Canvas monogram while loading or offline.
 */
@Composable
fun MangaCoverArtwork(
    manga: MangaItem,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    blurCover: Boolean = false,
    showBadges: Boolean = false,
    showUnreadBadge: Boolean = true,
    showInLibraryTag: Boolean = false,
    inLibraryLabel: String = "Di Pustaka",
    compactThumbnail: Boolean = false
) {
    val tokens = YomicThemeTokens.colors
    val shape = RoundedCornerShape(cornerRadius)
    val context = LocalContext.current

    Box(
        modifier = modifier
            .clip(shape)
            .border(0.6.dp, tokens.borderSubtle.copy(alpha = 0.65f), shape)
    ) {
        val artworkModifier = Modifier
            .fillMaxSize()
            .then(if (blurCover) Modifier.blur(12.dp) else Modifier)

        if (manga.thumbnailUrl.isNotBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(manga.thumbnailUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = artworkModifier,
                loading = {
                    EditorialMonogramCover(
                        manga = manga,
                        compactThumbnail = compactThumbnail,
                        modifier = Modifier.fillMaxSize()
                    )
                },
                error = {
                    EditorialMonogramCover(
                        manga = manga,
                        compactThumbnail = compactThumbnail,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        } else {
            EditorialMonogramCover(
                manga = manga,
                compactThumbnail = compactThumbnail,
                modifier = artworkModifier
            )
        }

        // Top-left clean monochrome unread badge (matching Yomic Desktop)
        if (showBadges) {
            val hasUnread = showUnreadBadge && manga.unreadCount > 0
            if (hasUnread) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xE618181B))
                        .border(0.75.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = manga.unreadCount.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Browse Catalog "In Library" Overlay Pill
        if (showInLibraryTag && manga.inLibrary) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xE618181B))
                    .border(0.75.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = inLibraryLabel,
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun EditorialMonogramCover(
    manga: MangaItem,
    compactThumbnail: Boolean,
    modifier: Modifier = Modifier
) {
    val c1 = Color(manga.coverPalette.first)
    val c2 = Color(manga.coverPalette.second)
    val initials = manga.title
        .trim()
        .split(Regex("\\s+"))
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "Y" }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        c2.copy(alpha = 0.72f),
                        c1.copy(alpha = 0.96f),
                        Color(0xFF0A0D12)
                    )
                )
            )
            if (!compactThumbnail) {
                val inset = size.width * 0.08f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.09f),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2f, size.height - inset * 2f),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }

        Text(
            text = initials,
            color = Color.White.copy(alpha = 0.88f),
            fontWeight = FontWeight.SemiBold,
            fontSize = if (compactThumbnail) 14.sp else 22.sp,
            letterSpacing = 1.2.sp,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
fun MangaGridCard(
    manga: MangaItem,
    showUnreadBadge: Boolean,
    blurExplicit: Boolean,
    subtitle: String? = null,
    showInLibraryTag: Boolean = false,
    inLibraryLabel: String = "Di Pustaka",
    modifier: Modifier = Modifier
) {
    val tokens = YomicThemeTokens.colors
    Column(modifier = modifier.fillMaxWidth()) {
        MangaCoverArtwork(
            manga = manga,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            cornerRadius = 8.dp,
            blurCover = blurExplicit && manga.isExplicit,
            showBadges = !showInLibraryTag,
            showUnreadBadge = showUnreadBadge,
            showInLibraryTag = showInLibraryTag,
            inLibraryLabel = inLibraryLabel
        )
        Text(
            text = manga.title,
            color = tokens.inkPrimary,
            style = MaterialTheme.typography.labelLarge,
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = tokens.inkMuted,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
