package build.terrynamic.opendisplay

import build.terrynamic.opendisplay.ui.VideoGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGeometryTest {
    @Test
    fun aspectFitAndCursorGeometry_shareTheSameVideoRect() {
        val video = VideoGeometry.aspectFit(1_000, 1_000, 2_000, 1_000)
        assertEquals(0f, video.left, 0.001f)
        assertEquals(250f, video.top, 0.001f)
        assertEquals(1_000f, video.right, 0.001f)
        assertEquals(750f, video.bottom, 0.001f)

        val cursor = VideoGeometry.cursorRect(
            videoRect = video,
            x = 0.5f,
            y = 0.5f,
            normalizedWidth = 0.1f,
            normalizedHeight = 0.2f,
            anchorX = 0.5f,
            anchorY = 0.5f,
        )
        assertEquals(450f, cursor.left, 0.001f)
        assertEquals(450f, cursor.top, 0.001f)
        assertEquals(550f, cursor.right, 0.001f)
        assertEquals(550f, cursor.bottom, 0.001f)
    }

    @Test
    fun aspectFit_isPixelAligned() {
        val video = VideoGeometry.aspectFit(1_001, 777, 1_920, 1_080)
        assertEquals(0f, video.left % 1f, 0f)
        assertEquals(0f, video.top % 1f, 0f)
        assertEquals(0f, video.right % 1f, 0f)
        assertEquals(0f, video.bottom % 1f, 0f)
    }

    @Test
    fun normalizeTouch_clampsToVideoRect() {
        val inside = VideoGeometry.normalizeTouch(500f, 500f, 1_000, 1_000, 2_000, 1_000)!!
        assertEquals(0.5, inside.first, 0.001)
        assertEquals(0.5, inside.second, 0.001)
        val letterbox = VideoGeometry.normalizeTouch(10f, 10f, 1_000, 1_000, 2_000, 1_000)!!
        assertEquals(0.0, letterbox.second, 0.001)
    }
}
