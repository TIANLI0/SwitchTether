package io.github.tianli0.switchtether.ui.files

import androidx.compose.ui.geometry.Offset
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableState

/**
 * Pinch limit of every zoomable picture and page, in the image's own pixels (telephoto's unit).
 * It was 5–8x; with a screenshot wider than the screen that let one gesture reach ~10x of what was
 * on screen. 4x of the original still resolves single pixels of a phone screenshot.
 */
internal val PictureZoomSpec = ZoomSpec(maxZoomFactor = 4f)

/**
 * Double-tap like a photo viewer (PhotoView's steps, Google Photos): from the fitted picture zoom
 * in 2.5x of what is on screen, around the tapped point; from any zoomed state, back to fitted.
 * telephoto's default jumps straight to [PictureZoomSpec]'s maximum, which measured against the
 * original pixels was ~8x of the screen in one tap.
 */
internal object PictureDoubleTap : DoubleClickToZoomListener {
    /** Relative to the fitted (unzoomed) size. */
    const val STEP = 2.5f

    override suspend fun onDoubleClick(state: ZoomableState, centroid: Offset) {
        val zoomed = (state.zoomFraction ?: return) > 0.02f
        if (zoomed) state.resetZoom() else state.zoomBy(STEP, centroid)
    }
}
