package build.terrynamic.opendisplay.input

import build.terrynamic.opendisplay.ui.VideoGeometry

object TouchMapper {
    fun normalized(
        px: Float,
        py: Float,
        viewWidth: Int,
        viewHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ): Pair<Double, Double>? {
        return VideoGeometry.normalizeTouch(px, py, viewWidth, viewHeight, videoWidth, videoHeight)
    }

    fun scrollDeltaVideoPx(
        viewDx: Float,
        viewDy: Float,
        viewWidth: Int,
        viewHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ): Pair<Double, Double>? {
        if (videoWidth <= 0) return null
        val scale = VideoGeometry.aspectFit(viewWidth, viewHeight, videoWidth, videoHeight).width / videoWidth
        if (scale <= 0f) return null
        return (viewDx / scale).toDouble() to (viewDy / scale).toDouble()
    }
}
