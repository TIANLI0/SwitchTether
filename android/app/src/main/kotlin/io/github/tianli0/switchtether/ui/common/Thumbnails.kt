package io.github.tianli0.switchtether.ui.common

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small previews of images held as bytes (pasted screenshots, `data:` URLs, images the agent read).
 *
 * A thumbnail used to be decoded at the picture's full size: a 4000×3000 photo is a 48 MB bitmap
 * for a 120 dp square, and a conversation with a few of them held hundreds of MB (it showed as
 * 350–450 MB RSS in the process exit records). Decoding with a power-of-two sample size keeps each
 * one near the size it is drawn at; the lightbox decodes its own, larger copy when opened.
 */
object Thumbnails {
    /**
     * The largest power-of-two sample size that keeps the long side at or above [maxPx] (so the
     * thumbnail is never blurrier than its slot), 1 for unknown or already small sizes.
     */
    fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        val longSide = maxOf(width, height)
        if (longSide <= 0 || maxPx <= 0) return 1
        var sample = 1
        while (longSide / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    /** [bytes] decoded with at most [maxPx] (rounded up to the next power-of-two step) on the long side; null if not an image. */
    fun decode(bytes: ByteArray, maxPx: Int): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
}

/** A thumbnail being decoded, decoded, or not an image at all. */
sealed interface Thumb {
    data object Decoding : Thumb
    data class Ready(val bitmap: ImageBitmap) : Thumb
    data object None : Thumb
}

/** [bytes] as a thumbnail for a slot [size] on its long side, decoded off the main thread. */
@Composable
fun rememberThumbnail(bytes: ByteArray?, size: Dp): State<Thumb> {
    val maxPx = with(LocalDensity.current) { size.roundToPx() }
    return produceState<Thumb>(if (bytes == null) Thumb.None else Thumb.Decoding, bytes, maxPx) {
        if (bytes == null) {
            value = Thumb.None
            return@produceState
        }
        value = Thumb.Decoding
        value = withContext(Dispatchers.Default) { Thumbnails.decode(bytes, maxPx) }?.let { Thumb.Ready(it) } ?: Thumb.None
    }
}
