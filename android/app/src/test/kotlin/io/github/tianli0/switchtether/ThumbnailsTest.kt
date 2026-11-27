package io.github.tianli0.switchtether

import android.graphics.Bitmap
import io.github.tianli0.switchtether.ui.common.Thumbnails
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailsTest {
    @Test
    fun sampleSizeKeepsTheLongSideAtLeastTheSlot() {
        assertEquals(1, Thumbnails.sampleSize(300, 200, 420))
        assertEquals(1, Thumbnails.sampleSize(800, 600, 420))
        assertEquals(2, Thumbnails.sampleSize(840, 600, 420))
        // 4000×3000 photo for a 120 dp slot at 3.5x (420 px): 4000/8 = 500 ≥ 420, 4000/16 = 250 < 420.
        assertEquals(8, Thumbnails.sampleSize(4000, 3000, 420))
        assertEquals(8, Thumbnails.sampleSize(3000, 4000, 420))
    }

    @Test
    fun unknownSizesAreNotSampled() {
        assertEquals(1, Thumbnails.sampleSize(0, 0, 420))
        assertEquals(1, Thumbnails.sampleSize(-1, 500, 420))
        assertEquals(1, Thumbnails.sampleSize(4000, 3000, 0))
    }

    @Test
    fun largePictureIsDecodedNearTheSlotSize() {
        val bytes = png(2000, 1200)
        val bmp = Thumbnails.decode(bytes, 200)!!
        // Sample 8: 250×150 — never smaller than the slot on the long side, far below the original.
        assertEquals(250, bmp.width)
        assertEquals(150, bmp.height)
        assertTrue(bmp.width * bmp.height * 4 < 2000 * 1200 * 4 / 32)
    }

    @Test
    fun smallPictureKeepsItsSize() {
        val bmp = Thumbnails.decode(png(64, 48), 200)!!
        assertEquals(64, bmp.width)
        assertEquals(48, bmp.height)
    }

    @Test
    fun notAnImageIsNull() {
        assertNull(Thumbnails.decode("not an image".toByteArray(), 200))
        assertNull(Thumbnails.decode(ByteArray(0), 200))
    }

    private fun png(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFF3366CC.toInt())
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
