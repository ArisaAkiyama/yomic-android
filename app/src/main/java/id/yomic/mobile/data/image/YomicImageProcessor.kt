package id.yomic.mobile.data.image

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Coil 3 Transformation providing Yomic Desktop parity for reader page processing:
 * - Smart Max Dimension downscaling (memory & GPU optimization)
 * - Auto-Crop uniform white/black margins around comic pages
 * - Mild 3x3 unsharp-mask sharpening for crisp lineart & speech bubble text
 */
class YomicImageProcessor(
    private val autoCropBorders: Boolean = false,
    private val sharpen: Boolean = false,
    private val maxDimension: Int = 2160
) : Transformation() {

    override val cacheKey: String =
        "yomic_proc_c${if (autoCropBorders) 1 else 0}_s${if (sharpen) 1 else 0}_m$maxDimension"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        var current = input

        val (targetW, targetH) = computeTargetDimensions(current.width, current.height, maxDimension)
        if (targetW != current.width || targetH != current.height) {
            val scaled = Bitmap.createScaledBitmap(current, targetW, targetH, true)
            if (scaled !== current && current !== input) {
                current.recycle()
            }
            current = scaled
        }

        if (autoCropBorders && current.width >= 64 && current.height >= 64) {
            val cropped = cropUniformBorders(current)
            if (cropped !== current && current !== input) {
                current.recycle()
            }
            current = cropped
        }

        if (sharpen && current.width >= 32 && current.height >= 32) {
            val sharpened = applyMildSharpen(current)
            if (sharpened !== current && current !== input) {
                current.recycle()
            }
            current = sharpened
        }

        return current
    }

    companion object {
        const val MAX_GPU_TEXTURE_DIM = 8192
        private const val LONG_STRIP_ASPECT_RATIO = 2.5f

        /**
         * Webtoon-aware downscaling calculator:
         * - For standard manga/comic pages: caps `max(width, height)` to `maxDim`.
         * - For tall webtoon long-strips (`height >= width * 2.5`): caps `width` to `maxDim`
         *   so vertical strips are NEVER crushed horizontally into blurry thumbnails, while still
         *   clamping height to `MAX_GPU_TEXTURE_DIM` (8192px) to avoid OpenGL texture crashes.
         */
        fun computeTargetDimensions(
            width: Int,
            height: Int,
            maxDim: Int,
            maxGpuTextureDim: Int = MAX_GPU_TEXTURE_DIM
        ): Pair<Int, Int> {
            if (width <= 0 || height <= 0) return width to height

            var scale = 1.0f
            val isLongStrip = height.toFloat() >= width.toFloat() * LONG_STRIP_ASPECT_RATIO

            if (maxDim > 0) {
                if (isLongStrip) {
                    if (width > maxDim) {
                        scale = minOf(scale, maxDim.toFloat() / width.toFloat())
                    }
                } else {
                    val largest = maxOf(width, height)
                    if (largest > maxDim) {
                        scale = minOf(scale, maxDim.toFloat() / largest.toFloat())
                    }
                }
            }

            if (maxGpuTextureDim > 0) {
                val largestAfter = maxOf(width * scale, height * scale)
                if (largestAfter > maxGpuTextureDim) {
                    scale = minOf(scale, maxGpuTextureDim.toFloat() / maxOf(width, height).toFloat())
                }
            }

            if (scale >= 0.99f) return width to height
            val newW = (width * scale).roundToInt().coerceAtLeast(1)
            val newH = (height * scale).roundToInt().coerceAtLeast(1)
            return newW to newH
        }

        private fun cropUniformBorders(src: Bitmap): Bitmap {
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)

            val cornerColor = pixels[0]
            val cR = (cornerColor shr 16) and 0xFF
            val cG = (cornerColor shr 8) and 0xFF
            val cB = cornerColor and 0xFF

            // Only crop if corner looks like near-white or near-black margin
            val isNearWhite = cR >= 235 && cG >= 235 && cB >= 235
            val isNearBlack = cR <= 24 && cG <= 24 && cB <= 24
            if (!isNearWhite && !isNearBlack) return src

            val tolerance = 22
            fun isBorderPixel(px: Int): Boolean {
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                return abs(r - cR) <= tolerance && abs(g - cG) <= tolerance && abs(b - cB) <= tolerance
            }

            fun isRowUniform(y: Int): Boolean {
                val rowOffset = y * w
                val step = (w / 48).coerceAtLeast(1)
                var x = 0
                while (x < w) {
                    if (!isBorderPixel(pixels[rowOffset + x])) return false
                    x += step
                }
                return true
            }

            fun isColUniform(x: Int, top: Int, bottom: Int): Boolean {
                val step = ((bottom - top) / 48).coerceAtLeast(1)
                var y = top
                while (y <= bottom) {
                    if (!isBorderPixel(pixels[y * w + x])) return false
                    y += step
                }
                return true
            }

            var top = 0
            while (top < h / 4 && isRowUniform(top)) top++
            var bottom = h - 1
            while (bottom > h - h / 4 && bottom > top && isRowUniform(bottom)) bottom--
            var left = 0
            while (left < w / 4 && isColUniform(left, top, bottom)) left++
            var right = w - 1
            while (right > w - w / 4 && right > left && isColUniform(right, top, bottom)) right--

            // Leave a tiny 2px safety pad and guard against over-cropping
            val safeTop = (top - 2).coerceAtLeast(0)
            val safeBottom = (bottom + 2).coerceAtMost(h - 1)
            val safeLeft = (left - 2).coerceAtLeast(0)
            val safeRight = (right + 2).coerceAtMost(w - 1)

            val cropW = safeRight - safeLeft + 1
            val cropH = safeBottom - safeTop + 1
            if (cropW < w * 0.55f || cropH < h * 0.55f || (cropW == w && cropH == h)) {
                return src
            }
            return Bitmap.createBitmap(src, safeLeft, safeTop, cropW, cropH)
        }

        private fun applyMildSharpen(src: Bitmap): Bitmap {
            val w = src.width
            val h = src.height
            val input = IntArray(w * h)
            val output = IntArray(w * h)
            src.getPixels(input, 0, w, 0, 0, w, h)

            // Mild unsharp kernel: center = 5, 4-neighbors = -1 (blended 35% to avoid haloing)
            System.arraycopy(input, 0, output, 0, input.size)
            val alpha = 0.35f
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val idx = row + x
                    val c = input[idx]
                    val up = input[idx - w]
                    val dn = input[idx + w]
                    val lt = input[idx - 1]
                    val rt = input[idx + 1]

                    val a = c ushr 24
                    val r0 = (c shr 16) and 0xFF
                    val g0 = (c shr 8) and 0xFF
                    val b0 = c and 0xFF

                    val lapR = 4 * r0 - ((up shr 16) and 0xFF) - ((dn shr 16) and 0xFF) - ((lt shr 16) and 0xFF) - ((rt shr 16) and 0xFF)
                    val lapG = 4 * g0 - ((up shr 8) and 0xFF) - ((dn shr 8) and 0xFF) - ((lt shr 8) and 0xFF) - ((rt shr 8) and 0xFF)
                    val lapB = 4 * b0 - (up and 0xFF) - (dn and 0xFF) - (lt and 0xFF) - (rt and 0xFF)

                    val r = (r0 + (lapR * alpha)).roundToInt().coerceIn(0, 255)
                    val g = (g0 + (lapG * alpha)).roundToInt().coerceIn(0, 255)
                    val b = (b0 + (lapB * alpha)).roundToInt().coerceIn(0, 255)

                    output[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            val result = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
            result.setPixels(output, 0, w, 0, 0, w, h)
            return result
        }
    }
}
