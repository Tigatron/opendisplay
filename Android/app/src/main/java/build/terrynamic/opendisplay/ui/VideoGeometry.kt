package build.terrynamic.opendisplay.ui

import kotlin.math.min
import kotlin.math.roundToInt

data class FloatRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

object VideoGeometry {
    fun aspectFit(
        containerWidth: Int,
        containerHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ): FloatRect {
        if (containerWidth <= 0 || containerHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return FloatRect(
                0f,
                0f,
                containerWidth.coerceAtLeast(0).toFloat(),
                containerHeight.coerceAtLeast(0).toFloat(),
            )
        }
        val scale = min(
            containerWidth.toFloat() / videoWidth.toFloat(),
            containerHeight.toFloat() / videoHeight.toFloat(),
        )
        val width = videoWidth * scale
        val height = videoHeight * scale
        val left = (containerWidth - width) / 2f
        val top = (containerHeight - height) / 2f
        return FloatRect(
            left.roundToInt().toFloat(),
            top.roundToInt().toFloat(),
            (left + width).roundToInt().toFloat(),
            (top + height).roundToInt().toFloat(),
        )
    }

    fun cursorRect(
        videoRect: FloatRect,
        x: Float,
        y: Float,
        normalizedWidth: Float,
        normalizedHeight: Float,
        anchorX: Float,
        anchorY: Float,
    ): FloatRect {
        val width = normalizedWidth * videoRect.width
        val height = normalizedHeight * videoRect.height
        val hotspotX = videoRect.left + x * videoRect.width
        val hotspotY = videoRect.top + y * videoRect.height
        val left = hotspotX - anchorX * width
        val top = hotspotY - anchorY * height
        return FloatRect(left, top, left + width, top + height)
    }

    fun normalizeTouch(
        px: Float,
        py: Float,
        containerWidth: Int,
        containerHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ): Pair<Double, Double>? {
        val rect = aspectFit(containerWidth, containerHeight, videoWidth, videoHeight)
        if (rect.width <= 0f || rect.height <= 0f) return null
        val x = ((px - rect.left) / rect.width).toDouble().coerceIn(0.0, 1.0)
        val y = ((py - rect.top) / rect.height).toDouble().coerceIn(0.0, 1.0)
        return x to y
    }
}
